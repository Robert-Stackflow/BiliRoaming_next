package me.iacn.biliroaming.hook.kotlinx

import me.iacn.biliroaming.utils.sPrefs

class KotlinxSplashListProcessor : KotlinxProcessor {
    override val targetSerialName =
        "kntr.srcs.app.splash.model.SplashListResponse"

    override fun shouldEnable() =
        sPrefs.getBoolean("remove_splash_ad", true) ||
                (sPrefs.getBoolean("hidden", false) && sPrefs.getBoolean("purify_splash", false))

    override fun process(result: Any) {
        try {
            result.javaClass.declaredFields
                .filter { MutableList::class.java.isAssignableFrom(it.type) }
                .forEach { field ->
                    field.isAccessible = true
                    field.set(result, ArrayList<Any>())
                }
        } catch (_: Throwable) {}
    }
}
