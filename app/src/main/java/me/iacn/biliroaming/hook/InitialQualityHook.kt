package me.iacn.biliroaming.hook

import de.robv.android.xposed.XposedHelpers
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*

/** Select quality before playback; the server still enforces account entitlements. */
class InitialQualityHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val policy = InitialQualityPolicy()

    override fun startHook() {
        if (!sPrefs.getBoolean("prefer_highest_quality", true)) return
        Log.d("startHook: InitialQuality")
        hookResourceConversion()
        instance.playerPreloadHolderClass?.replaceAllMethods(instance.getPreload()) { null }
        instance.playerQualityServices().forEach { (clazz, method) ->
            clazz?.replaceAllMethods(method) { MAX_QN }
        }
        instance.playerSettingHelperClass?.replaceAllMethods(instance.getDefaultQn()) { MAX_QN }
        // The player applies a separate half-screen ceiling after the RPC returns.
        // Raising only the requested qn leaves the old 480P playback selection intact.
        instance.autoSupremumQualityClass?.hookBeforeAllConstructors { param ->
            if (param.args.size == 6 && param.args.all { it is Int }) {
                param.args.indices.forEach { param.args[it] = MAX_QN }
            }
        }
        instance.qualityStrategyProviderClass?.replaceAllMethods(instance.selectQuality()) { MAX_QN }
        "tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaPlayAdapter"
            .findClassOrNull(mClassLoader)?.hookBeforeAllMethods("setDashAuto") {
                if (it.args.firstOrNull() == true) {
                    it.args[0] = false
                    Log.d("InitialQuality disabled automatic DASH downgrade")
                }
            }
        "tv.danmaku.videoplayer.coreV2.ABRManagerKt".findClassOrNull(mClassLoader)
            ?.hookBeforeAllMethods("setUserQn") {
                if (it.args.firstOrNull() == 0) it.args[0] = MAX_QN
            }
        // Override reads, preserving the user's saved auto-quality preference.
        "tv.danmaku.biliplayerv2.service.setting.IPlayerSettingService\$Companion"
            .findClassOrNull(mClassLoader)?.hookBeforeAllMethods("getBoolean") {
                if (it.args.firstOrNull() == "pref_player_mediaSource_quality_auto_switch")
                    it.result = false
            }
        instance.playerMossClass?.let { moss ->
            listOf("executePlayViewUnite", "playViewUnite").forEach { method ->
                moss.hookBeforeAllMethods(method) { param ->
                    val request = param.args.firstOrNull() ?: return@hookBeforeAllMethods
                    val vod = request.callMethodOrNull("getVod") ?: return@hookBeforeAllMethods
                    val initial = applyQuality(vod)
                    param.setObjectExtra(INITIAL_REQUEST, initial)
                    val handler = param.args.getOrNull(1)
                    if (initial && handler != null && instance.mossResponseHandlerClass?.isInstance(handler) == true) {
                        param.args[1] = handler.mossResponseHandlerProxy { reply ->
                            reply?.let { XposedHelpers.setAdditionalInstanceField(it, INITIAL_REQUEST, true) }
                        }
                    }
                }
                moss.hookAfterAllMethods(method) { param ->
                    if (param.getObjectExtra(INITIAL_REQUEST) == true) {
                        param.result?.let { XposedHelpers.setAdditionalInstanceField(it, INITIAL_REQUEST, true) }
                    }
                }
            }
        }
        instance.playURLMossClass?.let { moss ->
            listOf("executePlayView", "playView").forEach { method ->
                moss.hookBeforeAllMethods(method) { param ->
                    param.args.firstOrNull()?.let(::applyQuality)
                }
            }
        }
    }

    private fun hookResourceConversion() {
        // Theseus 9.13 consumes an already-fetched reply through this companion;
        // GeminiCommonResolver.resolveMediaResource is bypassed on initial play.
        val converter = "com.bilibili.app.gemini.base.resolver.GeminiCommonResolver\$a"
            .findClassOrNull(mClassLoader) ?: return
        converter.hookAfterAllMethods("c") { param ->
            if (param.args.getOrNull(1) != "vod_common") return@hookAfterAllMethods
            val resource = param.result ?: return@hookAfterAllMethods
            // Server cover rules substitute a lower-resolution stream in half screen.
            // Disable that substitution while retaining ordinary explicit quality changes.
            resource.callMethod("setDynamicQuality", null)
            val reply = param.args.firstOrNull() ?: return@hookAfterAllMethods
            val initial = XposedHelpers.getAdditionalInstanceField(reply, INITIAL_REQUEST) == true
            val selected = resource.callMethodOrNull("getPlayIndex")?.getIntFieldOrNull("mQuality")
            Log.d("InitialQuality converted=$selected initial=$initial cover rules removed")
            if (!initial) return@hookAfterAllMethods
            // The host helper checks playable URLs, respecting account availability.
            val best = resource.callMethodOrNull("findMaxAvailableQuality", MAX_QN) ?: return@hookAfterAllMethods
            val index = best.callMethodOrNull("getFirst") as? Int ?: return@hookAfterAllMethods
            val quality = best.callMethodOrNull("getSecond") as? Int ?: return@hookAfterAllMethods
            resource.callMethod("setResolvedIndex", index)
            "tv.danmaku.videoplayer.coreV2.ABRManagerKt".findClassOrNull(mClassLoader)
                ?.callStaticMethodOrNull("setUserQn", quality)
            Log.d("InitialQuality resolved playable quality=$quality")
        }
    }

    private fun applyQuality(vod: Any): Boolean {
        val download = (vod.callMethodOrNull("getDownload") as? Number)?.toInt() ?: return false
        val cid = (vod.callMethodOrNull("getCid") as? Number)?.toLong() ?: return false
        if (cid <= 0) return false
        val qn = (vod.callMethodOrNull("getQn") as? Number)?.toLong() ?: return false
        // Do not replace a subsequent explicit quality choice.
        if (!policy.shouldOverride(cid, qn, download)) return false
        vod.callMethod("setFnval", BangumiPlayUrlHook.MAX_FNVAL)
        vod.callMethod("setFourk", true)
        vod.callMethod("setQn", MAX_QN)
        Log.d("InitialQuality request=$MAX_QN previous=$qn")
        return true
    }

    companion object {
        private const val MAX_QN = 127
        private const val INITIAL_REQUEST = "biliroaming.initial_quality"
    }
}
