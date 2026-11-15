package net.airplus.utils.render

/**
 * LWJGL 2.9.4-nightly-20150209（MC 1.8.9 Forge 锁定版本）的 GL11 常量表存在错值，
 * 此处按 OpenGL 规范值硬编码修正，供 glGet 查询使用。
 *
 * 已核实的错误常量（javap -constants 实测）：
 * - GL_COLOR_WRITEMASK：LWJGL 定义为 3107（0x0C23，规范里是 GL_INDEX_CLEAR），
 *   规范值应为 0x0C04。用错值查询返回的是 index 清除值等垃圾数据，
 *   导致 colorMask 状态快照永远读到全关、恢复时反而把 colorMask 写成全关
 *   ——模糊管线的状态中毒恢复整个失效（blur 贴回不可见 / 后续渲染隐形）。
 * - GL_CURRENT_COLOR：LWJGL 定义为 2816（0x0B00，规范里是 GL_CURRENT_INDEX），
 *   规范值应为 0x0B01。用错值查询读到的是当前颜色索引而非 RGBA。
 */
object LwjglCompat {
    /** GL_COLOR_WRITEMASK（规范 0x0C04）—— 禁用 GL11.GL_COLOR_WRITEMASK，LWJGL 该值为 3107，错误 */
    const val GL_COLOR_WRITEMASK = 0x0C04

    /** GL_CURRENT_COLOR（规范 0x0B01）—— 禁用 GL11.GL_CURRENT_COLOR，LWJGL 该值为 2816，错误 */
    const val GL_CURRENT_COLOR = 0x0B01

    private val cmByteBuf = org.lwjgl.BufferUtils.createByteBuffer(16)

    /**
     * colorMask 快照（驱动兼容版）。
     * 实测部分驱动（本机即如此）对 glGetIntegerv(GL_COLOR_WRITEMASK) 返回 GL_INVALID_ENUM
     * （规范里该 pname 对 GetIntegerv 合法，属于驱动实现缺陷），改走 glGetBooleanv。
     * @return true=快照已写入 buf[0..3]；false=驱动连 glGetBooleanv 也拒绝（调用方按全开恢复）
     */
    @JvmStatic
    fun snapshotColorMask(buf: java.nio.IntBuffer): Boolean {
        for (i in 0 until 16) cmByteBuf.put(i, 0.toByte())
        org.lwjgl.opengl.GL11.glGetBoolean(GL_COLOR_WRITEMASK, cmByteBuf)
        val err = org.lwjgl.opengl.GL11.glGetError()
        if (err != 0) return false
        for (i in 0 until 4) {
            buf.put(i, if (cmByteBuf.get(i).toInt() != 0) org.lwjgl.opengl.GL11.GL_TRUE else org.lwjgl.opengl.GL11.GL_FALSE)
        }
        return true
    }

    /** colorMask 全开回退写入（驱动拒绝查询时按 HUD 常态处理） */
    @JvmStatic
    fun fallbackColorMaskAllTrue(buf: java.nio.IntBuffer) {
        for (i in 0 until 4) buf.put(i, org.lwjgl.opengl.GL11.GL_TRUE)
    }
}
