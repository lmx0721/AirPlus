package net.airplus.injection.forge

/**
 * 启动加载屏（MixinSplashProgress）与主菜单之间的状态桥：
 * splash 线程把 Flux 加载动画播完后置位，主菜单据此跳过自带的启动动画，
 * 避免进主菜单后又重播一遍 "Loading AirPlus..."。
 * （splash 被 config/splash.properties 禁用时不置位，主菜单动画作为兜底照常播放。）
 */
object StartupSplash {
    @JvmField var animationPlayed = false
}
