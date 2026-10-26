package me.iacn.biliroaming.hook

import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*

class VideoQualityHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (sPrefs.getBoolean("prefer_highest_quality", true)) return
        if (!sPrefs.getBoolean("main_func", false)) return

        val halfScreenQuality = sPrefs.getString("half_screen_quality", "0")?.toInt() ?: 0
        val fullScreenQuality = sPrefs.getString("full_screen_quality", "0")?.toInt() ?: 0
        Log.d("startHook: VideoQuality half=$halfScreenQuality full=$fullScreenQuality")
        if (halfScreenQuality != 0) {
            instance.playerPreloadHolderClass?.replaceAllMethods(instance.getPreload()) { null }
            instance.playerQualityServices().forEach { (clazz, getDefaultQnThumb) ->
                clazz?.replaceAllMethods(getDefaultQnThumb) { halfScreenQuality }
            }
        }
        if (fullScreenQuality != 0) {
            instance.playerSettingHelperClass?.replaceMethod(instance.getDefaultQn()) { fullScreenQuality }
        }

        if (halfScreenQuality != 0 || fullScreenQuality != 0) {
            val desiredQn = if (fullScreenQuality != 0) fullScreenQuality else halfScreenQuality
            // 9.8.0+: 播放器画质为“自动”时会调用 setUserQn(0)，这里强制成设定值；
            // 同时关闭自动画质切换，避免随后又被 ABR 改回
            val abrClass = "tv.danmaku.videoplayer.coreV2.ABRManagerKt"
                .findClassOrNull(mClassLoader)
            Log.d("VideoQuality ABRManagerKt=${abrClass != null}")
            abrClass?.hookBeforeMethod("setUserQn", Int::class.java) { param ->
                if (param.args[0] == 0) param.args[0] = desiredQn
            }
            val settingCompanion = "tv.danmaku.biliplayerv2.service.setting.IPlayerSettingService\$Companion"
                .findClassOrNull(mClassLoader)
            Log.d("VideoQuality settingCompanion=${settingCompanion != null}")
            settingCompanion?.hookBeforeMethod(
                "putBoolean", String::class.java, Boolean::class.java
            ) { param ->
                if (param.args[0] == "pref_player_mediaSource_quality_auto_switch") {
                    param.args[1] = false
                }
            }
        }

        if (halfScreenQuality != 0 || fullScreenQuality != 0) {
            instance.autoSupremumQualityClass?.hookBeforeConstructor(
                *Array(6) { Int::class.javaPrimitiveType }
            ) { param ->
                if (halfScreenQuality != 0) {
                    param.args[0] = halfScreenQuality       // loginHalf

                    param.args[3] = halfScreenQuality       // unloginHalf
                    param.args[4] = halfScreenQuality       // unloginFull
                    param.args[5] = halfScreenQuality       // unloginMobileFull
                }
                if (fullScreenQuality != 0) {
                    param.args[1] = fullScreenQuality       // loginFull
                    param.args[2] = fullScreenQuality       // loginMobileFull
                }
            }
            instance.qualityStrategyProviderClass?.hookBeforeMethod(
                instance.selectQuality(),
                instance.autoSupremumQualityClass,
                Boolean::class.javaPrimitiveType,           // isFullscreen
                Boolean::class.javaPrimitiveType            // isVideoPortrait
            ) { param ->
                // videoQuality = when {
                //     isVideoPortrait && isFullscreen -> loginFull
                //     isVideoPortrait && !isFullscreen -> unloginFull
                //     isFullscreen -> loginHalf
                //     else -> unloginHalf
                // }
                param.args[2] = true
            }
        }
    }
}
