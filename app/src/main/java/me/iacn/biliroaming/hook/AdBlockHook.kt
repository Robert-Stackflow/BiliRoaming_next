package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.*

class AdBlockHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("remove_pause_ad", true)) return
        Log.d("startHook: AdBlock")

        // 9.8.0+: 暂停数秒后弹出的广告面板由 AdPanelHelper.showPanel 展示，
        // 跳过该方法即可去掉暂停广告，同时保留暂停页的推荐内容
        "com.bilibili.ship.theseus.united.page.ad.AdPanelHelper".findClassOrNull(mClassLoader)
            ?.hookBeforeMethod("showPanel") { param ->
                Log.d("blocked pause ad panel showPanel")
                param.result = null
            }
    }
}
