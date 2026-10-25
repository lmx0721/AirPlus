/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

/**
 * 段分类（三处共用：模板检索 / 运行时逐 tick 路由 / train.py 训练 pair 标签 ——
 * Python 侧镜像同一规则与阈值，改这里必须同步 train.py）。
 *
 * v5 起类别完全由场景真值决定（[AimScene] 录制端逐 tick 观测：
 * - TRACK 追踪：准星正压在实体 hitbox 上 —— 在目标身上持续滑动/跟随（含静止目标
 *   的亚 gcd 微修正，这是漂移判据永远抓不到的分布）。
 * - FLICK 初次定位：不在目标上、剩余差 >= 12° 的大角度甩头。
 * - FINE 微调：不在目标上、剩余差 < 12° 的贴身小角度修正。
 *
 * scene==0（无目标上下文，如空地乱转）在三端都不产生类别：
 * 运行时逐 tick 路由 / 模板检索返回 null → 调用方回退 Default 管线；训练侧跳过该 pair。
 * （开发版本无旧格式兼容要求，不做启发式回退。）
 */
enum class AimSegmentClass {
    FINE, TRACK, FLICK;

    companion object {
        /** 初次定位的幅值下限（度），与 [AimScene.LOCATE_MIN_DIFF_DEG] 同值同源 */
        const val FLICK_MIN_DIFF_DEG = 12f

        /**
         * 漂移序列的间断保护阈值（度/tick）：|d| 超过它视为瞄准点重获取/失效
         * tick 混入（如录制 invalidate 零样本），不是真实漂移，按 0 处理。
         */
        const val DRIFT_DISCONTINUITY_DEG = 25f

        /**
         * 场景标签（[AimScene] 录制真值）→ 运行类别。三处共用映射：
         * 定位(1) → FLICK；调整(2) → FINE；追踪(3) → TRACK；0 → null
         * （调用方放弃 AI 生成、回退 Default 管线，不再做启发式兜底）。
         */
        fun fromScene(scene: Int): AimSegmentClass? = when (scene) {
            AimScene.LOCATE -> FLICK
            AimScene.ADJUST -> FINE
            AimScene.TRACK -> TRACK
            else -> null
        }
    }
}
