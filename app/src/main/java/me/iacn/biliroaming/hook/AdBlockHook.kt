package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.*

class AdBlockHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("remove_pause_ad", true)) return
        Log.d("startHook: AdBlock")

        // 9.13 的入口在请求广告和创建倒计时之前检查此开关。
        var reported = false
        "com.bilibili.adcommon.config.DDConfig".findClassOrNull(mClassLoader)
            ?.replaceAllMethods("getPausePageEnable") {
                if (!reported) {
                    Log.d("pause advertising disabled before countdown")
                    reported = true
                }
                false
            }

        // 保留用户主动打开的其他广告面板，只拦截暂停页类型（5）。
        "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository".findClassOrNull(mClassLoader)
            ?.hookBeforeAllMethods("showPanel") { param ->
                if (param.args.firstOrNull() == 5) {
                    Log.d("blocked pause ad panel")
                    param.result = null
                }
            }
    }
}
