package net.airplus.utils.render

import net.airplus.utils.client.ClientUtils
import org.lwjgl.opengl.ARBDebugOutput
import org.lwjgl.opengl.ARBDebugOutputCallback
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GLContext
import java.nio.IntBuffer

/**
 * [临时诊断工具] GL 错误探针：
 * 1. drain(tag)：在渲染管线边界排水 glGetError，节流打 WARN 定位错误首次出现的区间；
 * 2. install()：安装 GL_ARB_debug_output 同步回调 —— 驱动在产生 GL 错误的那次 GL 调用上
 *    同步回调（glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS_ARB)），回调栈即确切调用点，
 *    NVIDIA 的 message 还会说明具体是哪个 pname/枚举不被接受。定位完成后整体移除。
 */
object GLErrorProbe {
    private val lastLog = HashMap<String, Long>()
    private val lastMsgLog = HashMap<String, Long>()
    private val sb = StringBuilder(64)

    /**
     * 生产环境默认关闭（每次 drain 都是同步 glGetError 循环 + 潜在 debug 回调，
     * 每帧累积 0.5-2ms 开销）。需要诊断 GL 错误时以 JVM 参数
     * `-Dairplus.glProbe=true` 显式开启。
     */
    @JvmStatic
    private val enabled: Boolean =
        try {
            System.getProperty("airplus.glProbe", "false").toBoolean()
        } catch (t: Throwable) {
            false
        }

    @Volatile
    private var installed = false

    @JvmStatic
    fun drain(tag: String) {
        if (!enabled) return
        install()
        sb.setLength(0)
        var n = 0
        while (n < 16) {
            val e = GL11.glGetError()
            if (e == GL11.GL_NO_ERROR) break
            if (n > 0) sb.append(',')
            sb.append(e)
            n++
        }
        if (n == 0) return
        val now = System.currentTimeMillis()
        val last = lastLog[tag] ?: 0L
        if (now - last >= 1000L) {
            lastLog[tag] = now
            ClientUtils.LOGGER.warn("[GLErrProbe] $tag errors=[$sb]")
        }
    }

    @Synchronized
    private fun install() {
        if (installed) return
        installed = true
        try {
            val caps = GLContext.getCapabilities()
            if (!caps.GL_ARB_debug_output) {
                ClientUtils.LOGGER.warn("[GLErrProbe] GL_ARB_debug_output 不可用，无法启用调用点回调")
                return
            }
            // 同步模式：回调在出错的 GL 调用上直接发生，栈即调用点（spec 0x8242 = 33346）
            GL11.glEnable(33346)
            val handler = ARBDebugOutputCallback.Handler { _, type, _, _, message ->
                if (type == 33356) { // GL_DEBUG_TYPE_ERROR_ARB
                    val now = System.currentTimeMillis()
                    val last = lastMsgLog[message]
                    if (last == null || now - last >= 1000L) {
                        lastMsgLog[message] = now
                        ClientUtils.LOGGER.warn("[GLDBG] $message", RuntimeException("GL error call site"))
                    }
                }
            }
            ARBDebugOutput.glDebugMessageCallbackARB(ARBDebugOutputCallback(handler))
            val none: IntBuffer? = null
            ARBDebugOutput.glDebugMessageControlARB(0x1100, 0x1100, 0x1100, none, true)
            ClientUtils.LOGGER.warn("[GLErrProbe] GL_ARB_debug_output 同步回调已安装")
            runPnameSelfTest()
        } catch (t: Throwable) {
            ClientUtils.LOGGER.warn("[GLErrProbe] debug output 安装失败: $t")
        }
    }

    /** 逐 pname 自检：快照段每个查询单独调用，GL 错误归零后测，命中即报 */
    private fun runPnameSelfTest() {
        val i16 = org.lwjgl.BufferUtils.createIntBuffer(16)
        val f16 = org.lwjgl.BufferUtils.createFloatBuffer(16)
        val names = linkedMapOf(
            "GL_SCISSOR_BOX(0x0C10)" to { GL11.glGetInteger(0x0C10) },
            "GL_STENCIL_FUNC(0x0B92)" to { GL11.glGetInteger(0x0B92) },
            "GL_STENCIL_REF(0x0B97)" to { GL11.glGetInteger(0x0B97) },
            "GL_STENCIL_VALUE_MASK(0x0B93)" to { GL11.glGetInteger(0x0B93) },
            "GL_STENCIL_WRITEMASK(0x0B98)" to { GL11.glGetInteger(0x0B98) },
            "GL_STENCIL_FAIL(0x0B94)" to { GL11.glGetInteger(0x0B94) },
            "GL_STENCIL_PASS_DEPTH_FAIL(0x0B95)" to { GL11.glGetInteger(0x0B95) },
            "GL_STENCIL_PASS_DEPTH_PASS(0x0B96)" to { GL11.glGetInteger(0x0B96) },
            "GL_BLEND_SRC(0x0BE1)" to { GL11.glGetInteger(0x0BE1) },
            "GL_BLEND_DST(0x0BE0)" to { GL11.glGetInteger(0x0BE0) },
            "GL_BLEND_SRC_ALPHA(0x80CA)" to { GL11.glGetInteger(0x80CA) },
            "GL_BLEND_DST_ALPHA(0x80CB)" to { GL11.glGetInteger(0x80CB) },
            "GL_COLOR_WRITEMASK_buf(0x0C04)" to { GL11.glGetInteger(0x0C04, i16) },
            "GL_CURRENT_COLOR_buf(0x0B01)" to { GL11.glGetFloat(0x0B01, f16) },
            "GL_ACTIVE_TEXTURE(0x84E0)" to { GL11.glGetInteger(0x84E0) },
            "GL_TEXTURE_BINDING_2D(0x8069)" to { GL11.glGetInteger(0x8069) },
            "GL_COLOR_WRITEMASK_single(0x0C04)" to { GL11.glGetInteger(0x0C04) },
            "GL_CURRENT_COLOR_single(0x0B01)" to { GL11.glGetFloat(0x0B01) },
            "GL_COLOR_WRITEMASK_boolean(0x0C04)" to { GL11.glGetBoolean(0x0C04, org.lwjgl.BufferUtils.createByteBuffer(16)) }
        )
        for ((name, call) in names) {
            while (GL11.glGetError() != 0) {}
            try {
                call()
            } catch (t: Throwable) {
                ClientUtils.LOGGER.warn("[GLErrProbe] selftest $name -> JAVA 异常: $t")
                continue
            }
            val e = GL11.glGetError()
            if (e != 0) ClientUtils.LOGGER.warn("[GLErrProbe] selftest $name -> GL error $e")
            else ClientUtils.LOGGER.warn("[GLErrProbe] selftest $name -> OK")
        }
    }

    /** 保留：可能需要清空节流表时使用 */
    @JvmStatic
    fun resetThrottle() {
        lastLog.clear()
        lastMsgLog.clear()
    }
}
