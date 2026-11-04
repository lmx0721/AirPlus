package net.airplus.utils.render

import net.airplus.event.Listenable
import net.airplus.event.Render2DEvent
import net.airplus.event.handler
import net.airplus.features.module.modules.client.Client
import net.airplus.utils.client.ClientUtils
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.RenderUtils.RoundedCorners
import net.airplus.utils.render.shader.HudGlowShader
import net.airplus.utils.render.shader.KawaseBlur
import net.airplus.utils.render.shader.ShaderSupport
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL11.*

/**
 * 统一的客户端模糊入口（全屏单次模糊 + 组件遮罩裁剪架构）。
 *
 * 逻辑：
 * 1. 同一帧内第一次调用 [blur] 时，按全局设置（[Client.blurMode] / [Client.blurStrength]）
 *    对整个屏幕（主 framebuffer）做一次全屏模糊，结果保存在离屏纹理中；同帧后续调用直接复用，
 *    因此每帧最多一次全屏模糊管线（N 个 HUD 组件零额外模糊 pass）。
 * 2. 每个需要模糊背景的组件按自己的 AABB/遮罩形状（EmbeddedStencil）从预模糊纹理上裁剪贴回，
 *    视觉效果与旧的逐区域模糊一致，但性能从「每个组件一次多 pass 模糊」降为「每帧一次」。
 *
 * [blur] 的 x1/y1/x2/y2 为缩放 GUI 坐标系（ScaledResolution 空间）下的屏幕绝对坐标；
 * [mask] 在调用方当前 GL 矩阵下绘制模糊遮罩形状（可传局部坐标的圆角矩形等自定义形状），
 * 为 null 时按绝对坐标绘制圆角矩形（要求调用方当前矩阵为单位阵）；mask 是最后一个参数，
 * 便于调用方直接使用尾随 lambda 传遮罩。
 *
 * 模糊范围始终由 x1..y2（AABB）决定，形状由 [mask]（配合 stencil）决定。
 */
object HudBlur : MinecraftInstance, Listenable {

    // ===== 每帧会话：只在会话（帧）内第一次调用时做全屏模糊 =====

    /** 帧计数（Render2DEvent 驱动，每个真实渲染帧 +1） */
    private var session = 0L

    /** 上一事件对应的帧 key（timer 累计值构造，严格递增） */
    private var lastFrameKey = -1.0

    /** 在当前平台不可用（着色器编译失败等）的模糊模式 → 标记时的会话号；约 10s 后允许重试 */
    private val unavailableModes = HashMap<String, Long>()

    /** 已缓存模糊结果对应的会话号 */
    private var blurredSession = -1L

    /** 离屏全屏模糊结果纹理（GL 坐标，尺寸 = 主 framebuffer 像素尺寸） */
    private var resultTexture = -1
    private var resultWidth = 0
    private var resultHeight = 0

    /** 缓存命中校验：必须与缓存 result 生成时使用的模式/强度一致，否则丢弃重算 */
    private var cachedMode: String? = null
    private var cachedStrength = -1f

    /** KawaseBlur 链代数快照：链重建（resize/参数变化）会让 resultTexture 作废 */
    private var cachedKawaseGeneration = -1

    // ===== 基准快照：本帧「全局标准强度」的全屏模糊 =====
    // strengthScale ≠ 1 的调用（Target2 淡出、OpaiScreen 的 alpha 动画等）不再直接对
    // framebuffer 重拍：framebuffer 在 HUD 中途已经被前序组件贴入模糊与半透明背景，
    // 途中重拍会把它们烙进快照（此后走标准强度的组件再重拍 → 模糊里出现别组件画面）。
    // 现在统一先保证「基准快照」（在帧早期从干净 framebuffer 拍出），带 strengthScale
    // 的模糊一律以基准快照为源再模糊派生，源的像素内容与标准强度完全一致。
    private var baseSession = -1L
    private var baseMode: String? = null
    private var baseStrength = -1f
    private var baseKawaseGeneration = -1
    private var baseTexture = -1

    init {
        // priority = Byte.MAX_VALUE：EventManager 按 priority 降序执行，本处理器必须
        // 在帧内【所有】blur 调用之前把 session 递增到位。否则（曾用 MIN_VALUE，帧内
        // 最后执行）：HUD 元素先用旧 session 复用上一帧快照 → handler 递增 → 自定义
        // 聊天栏在 Render2DEvent 之后（同帧晚段）调用 blur 时快照已"过期" → 在聊天
        // 阶段重拍基准快照，把当帧已渲染的全部 HUD 内容 + 聊天背景烙进快照 → 下一帧
        // 所有 HUD 元素复用被污染的快照 → 模糊区域发黑/出现其他组件画面。
        handler<Render2DEvent>(always = true, priority = Byte.MAX_VALUE) {
            // 帧会话判定：用 timer 累计值构造严格递增的帧 key（elapsedTicks + elapsedPartialTicks）。
            // 同一渲染帧内可能触发多次 Render2DEvent（GuiIngame / GuiSpectator / Profiler 等
            // 多处注入），此前用「1ms 墙钟去重」判定同帧——但 GUI 渲染耗时远超 1ms，帧内
            // 第二次事件必然误判为新帧 → 会话中途拆分 → 基准快照重拍，此时 framebuffer
            // 已混入前序组件贴入的模糊/半透明背景与聊天内容，重拍会把它们烙进快照
            // （模糊里出现别的组件画面）。timer key 在同一渲染帧内恒定，跨帧严格递增。
            val timer = mc.timer
            val frameKey = timer.elapsedTicks + timer.elapsedPartialTicks.toDouble()
            if (frameKey != lastFrameKey) {
                session++
                lastFrameKey = frameKey
            }
        }
    }

    fun blur(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float = 0F,
        corners: RoundedCorners = RoundedCorners.ALL,
        strengthScale: Float = 1F,
        mode: String? = null,
        mask: (() -> Unit)? = null
    ) {
        if (!ShaderSupport.isSupported) return

        val mode = mode ?: Client.blurMode
        if (mode == "None") return
        // 不可用模式跳过；约 600 帧（≈10s）后允许重试，避免一次瞬时失败
        // （驱动/上下文未就绪等）把该模式永久打死
        val markedSession = unavailableModes[mode]
        if (markedSession != null && session - markedSession < 600L) {
            logSkipDiag("模式处于不可用重试期")
            return
        }

        // 强度乘以 strengthScale 后过低时直接退避（Target2 淡出等动画场景）
        if (Client.blurStrength * strengthScale < 0.05F) {
            logSkipDiag("强度过低退避")
            return
        }

        val left = minOf(x1, x2)
        val right = maxOf(x1, x2)
        val top = minOf(y1, y2)
        val bottom = maxOf(y1, y2)
        val width = right - left
        val height = bottom - top

        if (width <= 0F || height <= 0F) return

        // 全屏模糊（基准快照每帧只算一次；带 strengthScale 时以基准快照为源再模糊派生，
        // 源内容与标准强度完全一致，不会拍到前序组件已绘制/已贴入的内容）
        val effectiveStrength = (Client.blurStrength * strengthScale).coerceAtLeast(0.01F)
        if (!ensureFullScreenBlur(mode, effectiveStrength)) return

        // 全屏模糊管线可能停在离屏 FBO（Kawase 链尾），切回主 framebuffer，
        // 保证后续 EmbeddedStencil 形状与裁剪贴回都作用在主 framebuffer 上
        mc.framebuffer.bindFramebuffer(true)

        // 关键修复：外层 stencil 作用域保护。调用方（Island / 歌词气泡等 EmbeddedStencil
        // 使用者）常在自己的 erase(true)（GL_EQUAL）作用域内调用 blur —— 此前无条件走
        // write() 会 glClear 整个 stencil 缓冲并重写遮罩（外层形状被抹掉），dispose() 又
        // 会关闭外层还需要的 stencil 测试 → 只有管线真正执行的模式（Kawase）下外层
        // 元素的背景/内容消失。检测到活动的外层 GL_EQUAL 作用域时直接复用其遮罩贴回：
        // 不清缓冲、不改 func/op、不关测试（Island 的模糊区域与外层岛体形状一致，
        // 复用语义完全正确；自定义 mask 在该路径下被外层形状取代）。
        val outerStencilActive = glIsEnabled(GL_STENCIL_TEST)
        val outerStencilFunc = glGetInteger(GL11.GL_STENCIL_FUNC)
        val reuseOuterMask = outerStencilActive && outerStencilFunc == GL11.GL_EQUAL

        // 入口 scissor 硬化：stencil 清屏（EmbeddedStencil.write 内 glClear）与遮罩绘制
        // 定义了模糊区域本身，绝不能被调用方（或上游泄漏的）scissor 裁剪——清屏不完整
        // 会在区域外残留上一帧/上一组件的 stencil=1，后续任何一次贴回（GL_EQUAL）都会
        // 把模糊贴进错误的形状：组件自身区域没有模糊（只剩深色背景 + glow 阴影），
        // 别处却出现错位的模糊内容。TabGUI 等组件本身就在 scissor 开启状态下调用 blur，
        // 任何上游 scissor 泄漏都会放大成全局性模糊损坏。这里保存并强制关闭，退出恢复。
        val entryScissorEnabled = glIsEnabled(GL_SCISSOR_TEST)
        scissorBox.clear()
        glGetInteger(GL_SCISSOR_BOX, scissorBox)
        if (entryScissorEnabled) {
            glDisable(GL_SCISSOR_TEST)
            logScissorLeakOnce()
        }

        // mask 绘制异常时也要恢复 colorMask/depthMask（erase 内部会还原），
        // push/pop 保护当前矩阵：自定义遮罩可能修改矩阵（如 Target2 的动画变换），
        // 若不恢复会导致后续渲染（如 drawTarget）落在错误的变换上
        // 默认遮罩永远按屏幕绝对坐标绘制（同 paste 的自身正交矩阵）：
        // 不依赖调用方矩阵正好是单位阵，避免调用方处于平移/缩放矩阵时
        // 遮罩形状被变换到错误位置 → 遮罩与 AABB 错位 → 局部不模糊
        val drawMask: () -> Unit = {
            glPushMatrix()
            try {
                mask?.invoke() ?: run {
                    glLoadIdentity()
                    drawDefaultMask(left, top, right, bottom, radius, corners)
                }
            } finally {
                glPopMatrix()
            }
        }

        // 遮罩绘制与贴回阶段关闭深度测试：主 framebuffer 的深度缓冲在 2D 阶段之前
        // 已被 3D 世界几何写入（近处墙体/半透明方块的深度值小于 GUI 平面深度），而
        // EmbeddedStencil 遮罩的 GL_KEEP/KEEP/REPLACE 依赖深度测试通过 —— 深度不通过的
        // 像素 stencil 写入被丢弃 → 遮罩出现空洞 → 那块区域不贴模糊（「只模糊了一部分区域」）。
        // 2D HUD 内容不需要深度，统一关闭；退出时按进入前的开关两步法还原（推开缓存守卫）。
        val prevDepthTest = glIsEnabled(GL_DEPTH_TEST)
        if (!reuseOuterMask) EmbeddedStencil.write(false)
        try {
            forceColorMaskOn()
            glDisable(GL_DEPTH_TEST)
            GlStateManager.disableDepth()
            if (!reuseOuterMask) {
                try {
                    drawMask()
                } finally {
                    EmbeddedStencil.erase(true)
                }
            }
            drawClippedResult(left, top, right, bottom)

            // Glow 叠加 pass（Client.blurGlow 开启时）：以本帧预模糊快照为源做一次
            // 加法混合绘制——遮罩内亮部辉光、遮罩外 SDF 高斯衰减的边缘光晕。
            // 复用预模糊纹理，无额外模糊 pass；强度随 strengthScale 同步淡出。
            // reuseOuterMask（Island 等外层 stencil 作用域）下同样绘制：光晕本质是
            // 画出形状之外的边缘光，被外层 GL_EQUAL 裁掉就没有意义，因此临时关闭
            // stencil 测试画完再精确还原外层作用域（func/ref/mask/op 全量保存恢复）。
            // Island 的岛体形状就是其 AABB 的圆角矩形，SDF 光晕与之对齐，不会溢形。
            if (Client.blurGlow) {
                val glowIntensity = (Client.blurGlowStrength / 100F) * strengthScale
                if (glowIntensity > 0.01F) {
                    if (reuseOuterMask) {
                        // 保存外层作用域的完整 stencil 状态（贴回正运行在 GL_EQUAL 之下）
                        val stFunc = glGetInteger(GL11.GL_STENCIL_FUNC)
                        val stRef = glGetInteger(GL11.GL_STENCIL_REF)
                        val stValueMask = glGetInteger(GL11.GL_STENCIL_VALUE_MASK)
                        val stWriteMask = glGetInteger(GL11.GL_STENCIL_WRITEMASK)
                        val stFail = glGetInteger(GL11.GL_STENCIL_FAIL)
                        val stZFail = glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL)
                        val stZPass = glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS)
                        GL11.glDisable(GL11.GL_STENCIL_TEST)
                        try {
                            drawGlowHalo(left, top, right, bottom, radius, resultTexture, glowIntensity)
                        } finally {
                            // 精确还原外层作用域：先 op/func/writemask 再重新启用测试
                            glStencilOp(stFail, stZFail, stZPass)
                            glStencilFunc(stFunc, stRef, stValueMask)
                            glStencilMask(stWriteMask)
                            GL11.glEnable(GL11.GL_STENCIL_TEST)
                        }
                    } else {
                        // glow 不参与 stencil（贴回已结束），直接关闭测试画扩展区域
                        GL11.glDisable(GL11.GL_STENCIL_TEST)
                        drawGlowHalo(left, top, right, bottom, radius, resultTexture, glowIntensity)
                    }
                }
            }
        } finally {
            restoreColorMask()
            // 异常路径同样还原 stencil 状态，避免残留 GL_EQUAL 导致后续渲染错乱
            if (!reuseOuterMask) EmbeddedStencil.dispose()
            if (prevDepthTest) {
                GlStateManager.disableDepth()
                GlStateManager.enableDepth()
            } else {
                GlStateManager.enableDepth()
                GlStateManager.disableDepth()
            }
            // 恢复入口 scissor 状态（硬化期间全程关闭）
            glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            if (entryScissorEnabled) glEnable(GL_SCISSOR_TEST)
        }
    }

    /** 释放 Dual 模式的离屏 GPU 资源（应在 GL 上下文销毁前调用）。 */
    fun cleanup() {
        // Dual 独立管线已并入 InternalBlurShader（rounds=2），这里统一转发释放
        InternalBlurShader.cleanup()
        HudGlowShader.cleanup()
    }

    private fun drawDefaultMask(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        corners: RoundedCorners
    ) {
        RenderUtils.drawRoundedRect(x1, y1, x2, y2, -1, radius, corners)
    }

    // ===== colorMask 真实状态加固 =====
    // 贴回与辉光写颜色，必须保证真实 colorMask 开启。上游某处用裸 glColorMask(false)
    // 写入后若只靠守卫式 GlStateManager.colorMask(true) 恢复，缓存仍为 true 时写入被
    // 跳过，真实 colorMask 泄漏为 false —— 此后所有离屏模糊 pass 被静默丢弃（输出纹理
    // 全黑、驱动跳过片元着色器 → 帧率假性飙升），贴回也全部不可见（组件只剩深色底，
    // 表现为「模糊变黑 / blur 消失」）。这里快照真实值、用裸 GL 强制开启，退出时两步法
    // 同步回快照值；发现毒化时把调用链打进日志一次，便于定位泄漏源。
    private val colorMaskBuf = org.lwjgl.BufferUtils.createIntBuffer(16)
    private val savedColorMask = BooleanArray(4)
    private var colorMaskForced = false
    private var colorMaskPoisonLogged = false

    private fun forceColorMaskOn() {
        colorMaskBuf.clear()
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, colorMaskBuf)
        for (i in 0..3) savedColorMask[i] = colorMaskBuf[i] == GL11.GL_TRUE
        colorMaskForced = !(savedColorMask[0] && savedColorMask[1] && savedColorMask[2] && savedColorMask[3])
        if (colorMaskForced) {
            GL11.glColorMask(true, true, true, true)
            if (!colorMaskPoisonLogged) {
                colorMaskPoisonLogged = true
                val chain = Throwable().stackTrace.drop(1).take(6).joinToString(" <- ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
                ClientUtils.LOGGER.warn(
                    "[HudBlur] 模糊管线入口发现真实 colorMask 为 ({}, {}, {}, {})（与缓存可能失步），" +
                        "已强制开启并在退出时恢复；该状态会导致模糊输出全黑、贴回不可见、帧率假性升高。" +
                        "若反复出现请把此日志上报（调用链即泄漏源头）: {}", 
                    savedColorMask[0], savedColorMask[1], savedColorMask[2], savedColorMask[3], chain
                )
            }
        }
    }

    private fun restoreColorMask() {
        if (!colorMaskForced) return
        colorMaskForced = false
        // 两步法：先反向写推开可能失步的缓存守卫，再写快照值，保证 真实 == 缓存 == 进入前
        GlStateManager.colorMask(!savedColorMask[0], !savedColorMask[1], !savedColorMask[2], !savedColorMask[3])
        GlStateManager.colorMask(savedColorMask[0], savedColorMask[1], savedColorMask[2], savedColorMask[3])
    }

    /** blur 被静默跳过时的节流诊断日志（10s 一次），用于定位「模糊整体消失」类问题 */
    private var lastSkipDiagMs = 0L
    private fun logSkipDiag(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastSkipDiagMs < 10_000L) return
        lastSkipDiagMs = now
        ClientUtils.LOGGER.warn(
            "[HudBlur] blur 调用被跳过（{}）: mode={}, blurStrength={}, 不可用模式={}",
            reason, Client.blurMode, Client.blurStrength, unavailableModes.keys
        )
    }

    // ===== 全屏模糊管线 =====

    /**
     * 确保当前帧的离屏全屏模糊纹理已生成。
     */
    private fun ensureFullScreenBlur(mode: String, strength: Float): Boolean {
        val isBase = strength == Client.blurStrength

        if (isBase) {
            // 标准强度调用：会话内所有 scale=1 的组件共享本帧基准快照
            if (isSnapshotValid(mode, Client.blurStrength, useBase = true)) {
                // 同步 result*/cached* 指针到基准快照：drawClippedResult 永远贴
                // 「当前调用」对应的那张纹理（此前命中时可能残留别强度的派生纹理）
                resultTexture = baseTexture
                resultWidth = mc.displayWidth
                resultHeight = mc.displayHeight
                cachedMode = mode
                cachedStrength = Client.blurStrength
                cachedKawaseGeneration = baseKawaseGeneration
                blurredSession = session
                return true
            }
            return captureFullScreen(mode, Client.blurStrength, source = null, isBase = true)
        }

        // scale≠1 的派生调用：以本帧基准快照为源再模糊（此时 framebuffer 已被前序
        // 组件贴入模糊与半透明背景，直接重拍会把它们烙进快照 → 重影/残影）
        ensureBaseSnapshot(mode)
        if (isSnapshotValid(mode, strength, useBase = false)) return true
        // 基准快照不可用时退回 framebuffer 直拍（保持旧行为，至少不缺效果）
        return captureFullScreen(mode, strength, source = if (baseTexture != -1) baseTexture else null, isBase = false)
    }

    /** 执行一次全屏模糊管线并写入缓存字段 */
    private fun captureFullScreen(mode: String, strength: Float, source: Int?, isBase: Boolean): Boolean {
        val mcW = mc.displayWidth
        val mcH = mc.displayHeight
        if (mcW <= 0 || mcH <= 0) return false

        val texture = when (mode) {
            "Kawase" -> {
                val iterations = (strength / 3F).toInt().coerceIn(1, 5)
                val offset = (strength / iterations).toInt().coerceIn(1, 6)
                KawaseBlur.blurFullScreen(iterations, offset, source)
            }

            // Dual = 两轮 H/V 分离高斯（per-round σ = radius/2，两轮净 σ ≈ radius/√2）。
            // 传 2×strength 使净模糊幅度与旧版 Dual（两轮 r/1.5 配 1.5× 采样偏移，净 σ=r）
            // 一致 —— 此前直接把 strength 当 radius，同样强度下 Dual 明显弱于 Kawase，
            // 在本来就柔和的半透明材质上看起来像「没有模糊」
            "Dual" -> InternalBlurShader.blurFullScreen(strength * 2F, rounds = 2, sourceTexture = source)

            "Better" -> InternalBlurShader.blurFullScreen(strength, sourceTexture = source)

            else -> return unavailable(mode)
        }

        if (texture <= 0) return unavailable(mode)

        if (isBase) {
            baseSession = session
            baseMode = mode
            baseStrength = strength
            baseKawaseGeneration = KawaseBlur.chainGeneration
        }
        resultTexture = texture
        resultWidth = mcW
        resultHeight = mcH
        cachedMode = mode
        cachedStrength = strength
        cachedKawaseGeneration = KawaseBlur.chainGeneration
        blurredSession = session
        return true
    }

    /** 标记当前模式不可用并失败返回（同一模式约 600 帧后允许重试） */
    private fun unavailable(mode: String): Boolean {
        resultTexture = -1
        blurredSession = session
        val prev = unavailableModes[mode]
        if (prev == null || session - prev >= 600L) {
            ClientUtils.LOGGER.warn(
                "[HudBlur] 模糊模式 {} 的全屏管线失败（着色器编译/链接失败或上下文未就绪），" +
                    "本会话内该模式降级为不模糊；稍后将自动重试，也可切换其他 BlurMode 继续使用模糊。", mode
            )
        }
        unavailableModes[mode] = session
        return false
    }

    /** scissor 泄漏诊断：入口发现 scissor 开启时记录一次调用链（避免刷屏只报首次） */
    private var scissorLeakLogged = false

    private fun logScissorLeakOnce() {
        if (scissorLeakLogged) return
        scissorLeakLogged = true
        val chain = Throwable().stackTrace
            .drop(1)
            .take(6)
            .joinToString(" <- ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
        ClientUtils.LOGGER.warn(
            "[HudBlur] blur() 入口检测到 scissor 处于开启状态（上游泄漏或组件在自身裁剪内调用）。" +
                "已在本调用内强制关闭并恢复。调用链: {}", chain
        )
    }

    /**
     * 保证本帧「全局标准强度 + 当前模式」的基准快照存在（从主 framebuffer 干净内容拍出）。
     * 基准快照与派生快照分开缓存：strengthScale 动画期间（强度逐帧变化）每帧都要
     * 重新派生，但基准快照始终复用 —— 既省性能，也保证派生源不混入前序画面。
     */
    private fun ensureBaseSnapshot(mode: String) {
        if (isSnapshotValid(mode, Client.blurStrength, useBase = true)) return

        // 暂存派生缓存字段，拍摄基准后归还（isSnapshotValid/captureFullScreen 都用这些字段）
        val savedMode = cachedMode
        val savedStrength = cachedStrength
        val savedTexture = resultTexture
        val savedW = resultWidth
        val savedH = resultHeight
        val savedGen = cachedKawaseGeneration
        val savedSession = blurredSession

        resultTexture = -1
        resultWidth = 0
        resultHeight = 0
        cachedStrength = -1f
        val ok = captureFullScreen(mode, Client.blurStrength, source = null, isBase = false)

        if (ok) {
            baseTexture = resultTexture
            baseSession = session
            baseMode = mode
            baseStrength = Client.blurStrength
            baseKawaseGeneration = KawaseBlur.chainGeneration
        } else {
            // 基准拍摄失败：本帧不再重试（baseSession=session 使 isSnapshotValid 失败短路）
            baseTexture = -1
            baseSession = session
            baseMode = mode
            baseStrength = Client.blurStrength
        }

        cachedMode = savedMode
        cachedStrength = savedStrength
        resultTexture = savedTexture
        resultWidth = savedW
        resultHeight = savedH
        cachedKawaseGeneration = savedGen
        blurredSession = savedSession
    }

    /**
     * 缓存命中校验，任一不满足就判为无效重算：
     * 1) 同帧会话；2) 模糊参数（模式/强度）一致（strengthScale 动画时此前会沿用
     *    旧强度结果）；3) Kawase 链代数一致；4) 纹理仍存活（glIsTexture 兜底，
     *    覆盖 GL 上下文重建/链重建/id 复用边界等任何删纹理路径）。
     */
    private fun isSnapshotValid(mode: String, strength: Float, useBase: Boolean): Boolean {
        val tex = if (useBase) baseTexture else resultTexture
        val sessionNo = if (useBase) baseSession else blurredSession
        val cachedM = if (useBase) baseMode else cachedMode
        val cachedS = if (useBase) baseStrength else cachedStrength
        val cachedGen = if (useBase) baseKawaseGeneration else cachedKawaseGeneration

        if (sessionNo != session || tex == -1 || cachedM != mode || cachedS != strength ||
            (mode == "Kawase" && cachedGen != KawaseBlur.chainGeneration)
        ) return false
        return GL11.glIsTexture(tex)
    }

    // ===== 组件裁剪贴回 =====

    /**
     * 把预模糊纹理按 [x1..y2]（缩放 GUI 绝对坐标）裁剪并贴回主 framebuffer。
     * 此刻正处于 EmbeddedStencil.erase(true)（GL_EQUAL）之后的 stencil 测试中，
     * 只有遮罩形状内的像素会写入。
     */
    private fun drawClippedResult(x1: Float, y1: Float, x2: Float, y2: Float) {
        val texture = resultTexture
        if (texture <= 0) return

        val sr = ScaledResolution(mc)
        val factor = sr.scaleFactor

        // GL 像素坐标（GL 原点在左下角）
        val rx = (x1 * factor).toInt()
        val ry = (mc.displayHeight - y2 * factor).toInt()
        val rw = ((x2 - x1) * factor).toInt()
        val rh = ((y2 - y1) * factor).toInt()
        if (rw <= 0 || rh <= 0) return

        // 保存 scissor 开关 + box（调用方可能在 withClipping/scissor 中）
        val prevScissorEnabled = glIsEnabled(GL_SCISSOR_TEST)
        scissorBox.clear()
        glGetInteger(GL_SCISSOR_BOX, scissorBox)

        // 保存矩阵（glGetFloatv 方式，不占用矩阵栈，见 KawaseBlur 同款修复）：
        // 贴回必须使用自身正交矩阵，而不是调用方可能处于平移/缩放后的模型视图矩阵
        val prevMatrixMode = glGetInteger(GL11.GL_MATRIX_MODE)
        projMatrix.clear()
        glGetFloat(GL11.GL_PROJECTION_MATRIX, projMatrix)
        modelMatrix.clear()
        glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrix)
        glMatrixMode(GL11.GL_PROJECTION)
        glLoadIdentity()
        glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        glTranslated(0.0, 0.0, -2000.0)
        glMatrixMode(GL11.GL_MODELVIEW)
        glLoadIdentity()

        // 保存 blend / alpha（含因数与纹理单元、绑定）——裸 GL 贴回模糊纹理后
        // 这些状态必须「缓存 == 真实」地还原，否则守卫式渲染（字体等）会错乱
        val prevBlend = glIsEnabled(GL_BLEND)
        val prevAlpha = glIsEnabled(GL_ALPHA_TEST)
        // GL_BLEND_SRC/GL_BLEND_DST：贴回把因数改成了 (SRC_ALPHA, 1-SRC_ALPHA)，退出时按调用方原值还原
        // 0x80CA/0x80CB = GL_BLEND_SRC_ALPHA / GL_BLEND_DST_ALPHA（字面量规避 LWJGL2 常量归属差异）
        val prevBlendSrc = glGetInteger(GL_BLEND_SRC)
        val prevBlendDst = glGetInteger(GL_BLEND_DST)
        val prevBlendSrcAlpha = glGetInteger(0x80CA)
        val prevBlendDstAlpha = glGetInteger(0x80CB)
        val prevActiveTexture = glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        // GL_TEXTURE_BINDING_2D = 0x8069（GL 1.1 核心常量，用字面量避免 LWJGL2 常量归属不一致）
        val prevTextureBinding = glGetInteger(0x8069)

        try {
            mc.framebuffer.bindFramebuffer(true)

            glEnable(GL_SCISSOR_TEST)
            glScissor(rx, ry, rw, rh)

            // 与旧的逐区域模糊写回一致：SS blend 保留采样透明度，关 alpha test
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDisable(GL_ALPHA_TEST)
            glColor4f(1f, 1f, 1f, 1f)

            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP)

            // 纹理逐像素对齐主 framebuffer：GUI (x,y) 对应纹素
            // u = x*factor/W，v = 1 - y*factor/H（GL 左下角原点，GUI 左上角原点）
            val tw = resultWidth.toFloat()
            val th = resultHeight.toFloat()
            val uL = rx / tw
            val uR = (rx + rw) / tw
            val vTop = 1f - y1 * factor / th
            val vBottom = 1f - y2 * factor / th

            glBegin(GL_QUADS)
            glTexCoord2f(uL, vTop); glVertex2f(x1, y1)
            glTexCoord2f(uL, vBottom); glVertex2f(x1, y2)
            glTexCoord2f(uR, vBottom); glVertex2f(x2, y2)
            glTexCoord2f(uR, vTop); glVertex2f(x2, y1)
            glEnd()
        } finally {
            // 恢复进入时的 GL 状态（矩阵在最后恢复，blend/alpha/纹理先还原）
            //
            // 纹理绑定还原（关键修复）：try 里用裸 glBindTexture 绑定了模糊结果纹理，
            // 若此处单步走 GlStateManager.bindTexture(prevTextureBinding)，而缓存里
            // 记录的恰好也是这个值（前一次文字绘制后缓存就停在字形贴图上，两者经常撞值），
            // 守卫会判定「已绑定」静默跳过 → 真实绑定残留为模糊结果纹理。紧接着的
            // 字体渲染再用守卫式 bindTexture(字形贴图) 时同样撞值被跳过，字形 quad 直接
            // 采样整屏模糊纹理：深色场景下个别字变纯黑方块、彩色 UI 区域则闪现模糊后的
            // 主题色；中文动态字形裸绑定后回绑同样被跳过 → 后续字符采样单个字形贴图。
            // 先把 unit0 必须恢复：先切回 unit0 再执行，避免 prevActiveTexture≠unit0 时绑错单元
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            // 两步法：先绑 0 真实推开缓存，再真实绑回原纹理，保证 真实绑定 == 缓存
            GlStateManager.bindTexture(0)
            GlStateManager.bindTexture(prevTextureBinding)
            GL13.glActiveTexture(prevActiveTexture)

            // 当前颜色两步法：try 里是裸 glColor4f(1,1,1,1)，异常提前退出时真实颜色是脏的，
            // 单步缓存守卫可能不写 → 先写脏值推开缓存再写白，保证真实恢复
            GlStateManager.color(0f, 0f, 0f, 0f)
            GlStateManager.color(1f, 1f, 1f, 1f)

            // blend/alpha 还原也走 GlStateManager 两步法：foo 先写「相反状态」真实推开缓存，
            // 再写目标状态，无论进入时缓存与真实是否已经失同步，退出时二者必然一致。
            // （纯裸 GL 恢复不同步缓存：缓存若误记"已启用"，后续文本渲染的守卫式
            //  GlStateManager.enableBlend() 会被跳过 → 字形 quad 无混合，透明像素以 (0,0,0)
            //  写入画面 → 文字背后整排黑方块。）
            if (prevBlend) {
                GlStateManager.disableBlend()
                GlStateManager.enableBlend()
            } else {
                GlStateManager.enableBlend()
                GlStateManager.disableBlend()
            }
            // blend 因数同样两步法（try 里裸 glBlendFunc 改过真实因数，缓存可能不同步）
            GlStateManager.tryBlendFuncSeparate(GL_ZERO, GL_ONE, GL_ZERO, GL_ONE)
            GlStateManager.tryBlendFuncSeparate(
                prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha
            )
            if (prevAlpha) {
                GlStateManager.disableAlpha()
                GlStateManager.enableAlpha()
            } else {
                GlStateManager.enableAlpha()
                GlStateManager.disableAlpha()
            }

            glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            if (prevScissorEnabled) glEnable(GL_SCISSOR_TEST) else glDisable(GL_SCISSOR_TEST)

            glMatrixMode(GL11.GL_PROJECTION)
            glLoadMatrix(projMatrix)
            glMatrixMode(GL11.GL_MODELVIEW)
            glLoadMatrix(modelMatrix)
            glMatrixMode(prevMatrixMode)
        }
    }

    // ===== Glow 叠加 =====

    /**
     * 辉光叠加 pass：把预模糊纹理按 [intensity] 调制后以加法混合画在扩展 AABB 上。
     * 遮罩圆角矩形（SDF）内部为亮部辉光（暗部按亮度权重压低），向外按高斯衰减为
     * 边缘光晕（C1 连续，无硬边圈）。复用预模糊快照，无额外模糊 pass、无新 FBO；
     * 每个组件只多一个 shader 四边形（填充范围 = 模糊区域 + 3σ 衰减距离）。
     * 状态保存/恢复与 [drawClippedResult] 同款纪律（两步法推开缓存守卫）。
     */
    private fun drawGlowHalo(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        radius: Float,
        texture: Int,
        intensity: Float
    ) {
        if (texture <= 0) return
        val sr = ScaledResolution(mc)
        val factor = sr.scaleFactor

        // 遮罩矩形几何（GL 窗口像素坐标，原点左下）
        val centerXPx = (x1 + x2) * 0.5F * factor
        val centerYPx = mc.displayHeight - (y1 + y2) * 0.5F * factor
        val halfXPx = (x2 - x1) * 0.5F * factor
        val halfYPx = (y2 - y1) * 0.5F * factor
        val radiusPx = (radius * factor).coerceAtLeast(1F)
        // 高斯 σ：比旧指数衰减的 falloff 稍大（×1.4），补偿高斯尾巴衰减更快导致的
        // 光晕视觉尺寸收窄；falloffPx 现在直接作为 HudGlowShader 的高斯 σ 使用
        val falloffPx = (11F + 22F * intensity.coerceIn(0.1F, 2F)) * factor
        // exp(-4.5) ≈ 1%，超过 3σ 的高斯光晕肉眼不可见，作为扩展范围截断
        val expandGui = falloffPx * 3F / factor
        val gx1 = x1 - expandGui
        val gx2 = x2 + expandGui
        val gy1 = y1 - expandGui
        val gy2 = y2 + expandGui

        val grx = (gx1 * factor).toInt()
        val gry = (mc.displayHeight - gy2 * factor).toInt()
        val grw = ((gx2 - gx1) * factor).toInt()
        val grh = ((gy2 - gy1) * factor).toInt()
        if (grw <= 0 || grh <= 0) return

        // 保存 scissor + 矩阵 + blend/alpha/颜色/纹理绑定
        val prevScissorEnabled = glIsEnabled(GL_SCISSOR_TEST)
        scissorBox.clear()
        glGetInteger(GL_SCISSOR_BOX, scissorBox)

        val prevMatrixMode = glGetInteger(GL11.GL_MATRIX_MODE)
        projMatrix.clear()
        glGetFloat(GL11.GL_PROJECTION_MATRIX, projMatrix)
        modelMatrix.clear()
        glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrix)
        glMatrixMode(GL11.GL_PROJECTION)
        glLoadIdentity()
        glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        glTranslated(0.0, 0.0, -2000.0)
        glMatrixMode(GL11.GL_MODELVIEW)
        glLoadIdentity()

        val prevBlend = glIsEnabled(GL_BLEND)
        val prevAlpha = glIsEnabled(GL_ALPHA_TEST)
        // GL_BLEND_SRC/GL_BLEND_DST 与 0x80CA/0x80CB（SRC/DST_ALPHA）含义同 drawClippedResult
        val prevBlendSrc = glGetInteger(GL_BLEND_SRC)
        val prevBlendDst = glGetInteger(GL_BLEND_DST)
        val prevBlendSrcAlpha = glGetInteger(0x80CA)
        val prevBlendDstAlpha = glGetInteger(0x80CB)
        val prevActiveTexture = glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val prevTextureBinding = glGetInteger(0x8069)

        try {
            glEnable(GL_SCISSOR_TEST)
            glScissor(grx, gry, grw, grh)

            // 加法混合：RGB 相加，alpha 通道保持原样（GL_ZERO, GL_ONE）
            glEnable(GL_BLEND)
            GL14.glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE)
            glDisable(GL_ALPHA_TEST)
            glColor4f(1f, 1f, 1f, 1f)

            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP)

            if (HudGlowShader.use(
                    intensity, centerXPx, centerYPx, halfXPx, halfYPx, radiusPx, falloffPx,
                    texelScreenX = 1F / resultWidth.coerceAtLeast(1),
                    texelScreenY = 1F / resultHeight.coerceAtLeast(1),
                    spreadPx = Client.blurGlowSoft / 100F * falloffPx * 0.5F,
                    tintR = Client.blurGlowColor.red / 255F,
                    tintG = Client.blurGlowColor.green / 255F,
                    tintB = Client.blurGlowColor.blue / 255F,
                    tintMix = Client.blurGlowColor.alpha / 255F
                )
            ) {
                // 纹理逐像素对齐主 framebuffer（同 drawClippedResult 的映射公式）
                val tw = resultWidth.toFloat()
                val th = resultHeight.toFloat()
                val uL = grx / tw
                val uR = (grx + grw) / tw
                val vTop = 1f - gy1 * factor / th
                val vBottom = 1f - gy2 * factor / th

                glBegin(GL_QUADS)
                glTexCoord2f(uL, vTop); glVertex2f(gx1, gy1)
                glTexCoord2f(uL, vBottom); glVertex2f(gx1, gy2)
                glTexCoord2f(uR, vBottom); glVertex2f(gx2, gy2)
                glTexCoord2f(uR, vTop); glVertex2f(gx2, gy1)
                glEnd()
            }
            HudGlowShader.unbind()
        } finally {
            HudGlowShader.unbind()

            // 纹理绑定两步法（同 drawClippedResult：先绑 0 推开缓存守卫再真实绑回）
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GlStateManager.bindTexture(0)
            GlStateManager.bindTexture(prevTextureBinding)
            GL13.glActiveTexture(prevActiveTexture)

            // 当前颜色两步法
            GlStateManager.color(0f, 0f, 0f, 0f)
            GlStateManager.color(1f, 1f, 1f, 1f)

            // blend 开关与因数两步法还原
            if (prevBlend) {
                GlStateManager.disableBlend()
                GlStateManager.enableBlend()
            } else {
                GlStateManager.enableBlend()
                GlStateManager.disableBlend()
            }
            GlStateManager.tryBlendFuncSeparate(GL_ZERO, GL_ONE, GL_ZERO, GL_ONE)
            GlStateManager.tryBlendFuncSeparate(
                prevBlendSrc, prevBlendDst, prevBlendSrcAlpha, prevBlendDstAlpha
            )
            if (prevAlpha) {
                GlStateManager.disableAlpha()
                GlStateManager.enableAlpha()
            } else {
                GlStateManager.enableAlpha()
                GlStateManager.disableAlpha()
            }

            glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
            if (prevScissorEnabled) glEnable(GL_SCISSOR_TEST) else glDisable(GL_SCISSOR_TEST)

            glMatrixMode(GL11.GL_PROJECTION)
            glLoadMatrix(projMatrix)
            glMatrixMode(GL11.GL_MODELVIEW)
            glLoadMatrix(modelMatrix)
            glMatrixMode(prevMatrixMode)
        }
    }

    // ===== 状态保存 buffer（渲染线程单线程使用，复用避免每帧分配） =====

    private val projMatrix = BufferUtils.createFloatBuffer(16)
    private val modelMatrix = BufferUtils.createFloatBuffer(16)
    private val scissorBox = BufferUtils.createIntBuffer(16)
}
