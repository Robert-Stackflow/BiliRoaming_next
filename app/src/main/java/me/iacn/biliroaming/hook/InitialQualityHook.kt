package me.iacn.biliroaming.hook

import de.robv.android.xposed.XposedHelpers
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*

/** Select quality before playback; the server still enforces account entitlements. */
class InitialQualityHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val policy = InitialQualityPolicy()
    private val autoVideo get() = sPrefs.getBoolean("prefer_highest_quality", true)
    private val creatingStream = ThreadLocal<Int>()

    override fun startHook() {
        hookVideoCodec()
        if (!autoVideo) {
            if (sPrefs.getBoolean("auto_audio_quality", true)) hookInitialMediaItem()
            return
        }
        Log.d("startHook: InitialQuality")
        hookResourceConversion()
        hookInitialMediaItem()
        hookNativeStartup()
        hookActivePlayer()
        hookViewPlayerArgs()
        instance.playerPreloadHolderClass?.replaceAllMethods(instance.getPreload()) { null }
        instance.playerQualityServices().forEach { (clazz, method) ->
            clazz?.replaceAllMethods(method) { MAX_QN }
        }
        instance.playerSettingHelperClass?.replaceAllMethods(instance.getDefaultQn()) { requestedQn() }
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
                    val qn = (vod.callMethodOrNull("getQn") as? Number)?.toInt()
                    val download = (vod.callMethodOrNull("getDownload") as? Number)?.toInt()
                    val initialOrDefault = download == 0 && (initial || qn == 0 || qn == requestedQn())
                    param.setObjectExtra(INITIAL_REQUEST, initialOrDefault)
                    val handler = param.args.getOrNull(1)
                    if (handler != null && instance.mossResponseHandlerClass?.isInstance(handler) == true) {
                        param.args[1] = handler.mossResponseHandlerProxy { reply ->
                            reply?.let { XposedHelpers.setAdditionalInstanceField(it, INITIAL_REQUEST, initialOrDefault) }
                        }
                    }
                }
                moss.hookAfterAllMethods(method) { param ->
                    val initial = param.getObjectExtra(INITIAL_REQUEST) as? Boolean
                    if (initial != null) {
                        param.result?.let { XposedHelpers.setAdditionalInstanceField(it, INITIAL_REQUEST, initial) }
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
            (reply.callMethodOrNull("getPlayArc")?.callMethodOrNull("getCid") as? Number)?.toLong()?.let(policy::markPlayback)
            // A disk/preload reply has no RPC marker. It is still a new playback
            // resource, rather than an explicit manual-quality response.
            val initial = XposedHelpers.removeAdditionalInstanceField(reply, INITIAL_REQUEST) != false
            XposedHelpers.setAdditionalInstanceField(resource, INITIAL_REQUEST, initial)
            val selected = resource.callMethodOrNull("getPlayIndex")?.getIntFieldOrNull("mQuality")
            Log.d("InitialQuality converted=$selected initial=$initial cover rules removed")
            if (!initial) return@hookAfterAllMethods
            // The host helper checks playable URLs, respecting account availability.
            val (index, quality) = selectResourceQuality(resource) ?: return@hookAfterAllMethods
            resource.callMethod("setResolvedIndex", index)
            "tv.danmaku.videoplayer.coreV2.ABRManagerKt".findClassOrNull(mClassLoader)
                ?.callStaticMethodOrNull("setUserQn", quality)
            Log.d("InitialQuality resolved playable quality=$quality")
        }
    }

    private fun hookViewPlayerArgs() {
        instance.viewUniteMossClass?.let { moss ->
            listOf("executeView", "view").forEach { method ->
                moss.hookBeforeAllMethods(method) { param ->
                    val request = param.args.firstOrNull() ?: return@hookBeforeAllMethods
                    val args = request.callMethodOrNull("getPlayerArgs") ?: return@hookBeforeAllMethods
                    val builder = args.callMethodOrNull("toBuilder") ?: return@hookBeforeAllMethods
                    val flags = (args.callMethodOrNull("getFnval") as? Number)?.toInt() ?: 0
                    builder.callMethod("setQn", requestedQn().toLong())
                    builder.callMethod("setFnval", capabilityFlags(flags).toLong())
                    builder.callMethod("setQnPolicyValue", 0)
                    request.callMethod("setPlayerArgs", builder.callMethod("build"))
                    Log.d("InitialQuality detail preload request=$MAX_QN fnval=${BangumiPlayUrlHook.MAX_FNVAL} previous=$flags")
                }
            }
        }
    }

    private fun hookInitialMediaItem() {
        val paramsClass = "tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams".findClassOrNull(mClassLoader)
        listOf("isHdr", "getHdrType", "isDolbyVision").forEach { method ->
            paramsClass?.hookBeforeAllMethods(method) { param ->
                val selected = XposedHelpers.getAdditionalInstanceField(param.thisObject, SELECTED_FORMAT) as? Int
                    ?: return@hookBeforeAllMethods
                param.result = when (method) {
                    "isHdr" -> selected == 125 || selected == 129
                    "isDolbyVision" -> selected == 126
                    else -> if (selected == 125 || selected == 129) selected else 0
                }
            }
        }
        paramsClass?.hookAfterAllMethods("getDolbyConfigPath") { param ->
            if (XposedHelpers.getAdditionalInstanceField(param.thisObject, SELECTED_FORMAT) != 126 ||
                !(param.result as? String).isNullOrEmpty()) return@hookAfterAllMethods
            val manager = "com.bilibili.lib.media.dolby.DolbyVisionConfigManager".findClassOrNull(mClassLoader)
                ?: return@hookAfterAllMethods
            val path = XposedHelpers.getStaticObjectField(manager, "INSTANCE").callMethodOrNull("getConfigPath")
            if (path is String) param.result = path
        }
        "tv.danmaku.videoplayer.coreV2.transformer.IjkMediaItemTransformer"
            .findClassOrNull(mClassLoader)?.hookBeforeAllMethods("createMediaItem") { param ->
                val resource = param.args.firstOrNull() ?: return@hookBeforeAllMethods
                val itemParams = param.args.getOrNull(1) ?: return@hookBeforeAllMethods
                val source = resource.callMethodOrNull("getPlayIndex")?.getObjectFieldOrNull("mFrom")
                if (source == "downloaded" || source == "live") return@hookBeforeAllMethods
                bindAudio(resource, itemParams)
                if (!autoVideo) return@hookBeforeAllMethods
                if (XposedHelpers.getAdditionalInstanceField(resource, INITIAL_REQUEST) == false ||
                    XposedHelpers.getAdditionalInstanceField(resource, INITIAL_BOUND) == true)
                    return@hookBeforeAllMethods
                val (index, quality) = selectResourceQuality(resource) ?: return@hookBeforeAllMethods
                val previous = itemParams.callMethodOrNull("getVideoStreamId")
                val range = itemParams.callMethodOrNull("getRecommendStartQualityRange")
                resource.callMethod("setResolvedIndex", index)
                itemParams.callMethod("setVideoStreamId", quality)
                XposedHelpers.setAdditionalInstanceField(itemParams, SELECTED_FORMAT, quality)
                // This native startup hint can choose a cheaper stream before
                // setDashAuto(false) runs, despite the visible quality being 116.
                val pair = "kotlin.Pair".findClassOrNull(mClassLoader)?.new(quality, quality)
                itemParams.callMethod("setRecommendStartQualityRange", pair)
                XposedHelpers.setAdditionalInstanceField(resource, INITIAL_BOUND, true)
                creatingStream.set(quality)
                Log.d("InitialQuality bind first stream=$quality previous=$previous startupRange=$range")
            }
        "tv.danmaku.videoplayer.coreV2.transformer.IjkMediaItemTransformer"
            .findClassOrNull(mClassLoader)?.hookAfterAllMethods("createMediaItem") {
                creatingStream.remove()
            }
    }

    private fun hookNativeStartup() {
        val item = "tv.danmaku.ijk.media.player.IjkMediaPlayerItem".findClassOrNull(mClassLoader) ?: return
        item.hookBeforeAllMethods("init") { param ->
            val quality = creatingStream.get() ?: return@hookBeforeAllMethods
            val config = param.args.getOrNull(1) ?: return@hookBeforeAllMethods
            XposedHelpers.setAdditionalInstanceField(param.thisObject, NATIVE_STREAM, quality)
            bindNativeRange(config, quality)
        }
        item.hookBeforeAllMethods("setDataSourceToNative") { param ->
            val quality = XposedHelpers.getAdditionalInstanceField(param.thisObject, NATIVE_STREAM) as? Int
                ?: return@hookBeforeAllMethods
            val config = param.thisObject.getObjectFieldOrNull("mIjkMediaConfigParams") ?: return@hookBeforeAllMethods
            bindNativeRange(config, quality)
            Log.d("InitialQuality native startup range=$quality..$quality")
        }
    }

    private fun bindNativeRange(config: Any, quality: Int) {
        config.setObjectField("mUseRecommendedQn", true)
        config.setObjectField("mMinRecommendedQn", quality)
        config.setObjectField("mMaxRecommendedQn", quality)
    }

    private fun hookActivePlayer() {
        val adapter = "tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaPlayAdapter"
            .findClassOrNull(mClassLoader) ?: return
        adapter.hookAfterAllMethods("setMediaItem") { param ->
            val item = param.args.firstOrNull()?.callMethodOrNull("realMediaItem") ?: return@hookAfterAllMethods
            val quality = XposedHelpers.getAdditionalInstanceField(item, NATIVE_STREAM) as? Int ?: return@hookAfterAllMethods
            XposedHelpers.setAdditionalInstanceField(param.thisObject, NATIVE_STREAM, quality)
            // The player core is reusable and may still have its previous/default
            // stream selected. Binding the MediaItem alone does not switch it.
            param.thisObject.callMethod("setVideoStream", quality, quality, quality)
            val player = param.thisObject.getObjectFieldOrNull("a") ?: return@hookAfterAllMethods
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (player.callMethodOrNull("getIjkMediaPlayerItem") !== item) return@postDelayed
                Log.d("InitialQuality active current=${player.callMethodOrNull("getCurrentVideoId")} expected=$quality " +
                    "decodeFps=${player.callMethodOrNull("getVideoDecodeFramesPerSecond")} " +
                    "outputFps=${player.callMethodOrNull("getVideoOutputFramesPerSecond")} sourceFps=${player.callMethodOrNull("getVideoFps")} " +
                    "audio=${player.callMethodOrNull("getCurrentAudioId")}")
            }, 5000)
        }
        adapter.hookBeforeAllMethods("setVideoStream") { param ->
            if (param.args.firstOrNull() != 0) return@hookBeforeAllMethods
            val quality = XposedHelpers.getAdditionalInstanceField(param.thisObject, NATIVE_STREAM) as? Int
                ?: return@hookBeforeAllMethods
            param.args[0] = quality
            Log.d("InitialQuality active automatic stream bound=$quality")
        }
    }

    private fun requestedQn() = sPrefs.getString("preferred_video_quality", "highest")?.toIntOrNull() ?: MAX_QN

    private fun capabilityFlags(base: Int = 0): Int {
        var flags = base or BangumiPlayUrlHook.MAX_FNVAL
        if (!sPrefs.getBoolean("enable_8k_quality", true)) flags = flags and 1024.inv()
        if (!sPrefs.getBoolean("enable_hdr_quality", false)) flags = flags and (64 or 16384).inv()
        if (!sPrefs.getBoolean("enable_dolby_vision", false)) flags = flags and 512.inv()
        return flags
    }

    private fun selectResourceQuality(resource: Any): Pair<Int, Int>? {
        val dash = resource.callMethodOrNull("getDashResource")
        val rows = dash?.getObjectFieldOrNull("a") as? List<*>
        val indices = resource.getObjectFieldOrNull("mVodIndex")?.getObjectFieldOrNull("mVodList") as? List<*>
        if (rows != null && indices != null) {
            val available = rows.filterNotNull().filter {
                !((it.getObjectFieldOrNull("c") as? String).isNullOrEmpty())
            }.mapNotNull {
                val qn = it.getIntFieldOrNull("a") ?: return@mapNotNull null
                AutoQualityPolicy.Video(qn, it.getIntFieldOrNull("k") ?: 0, it.getIntFieldOrNull("l") ?: 0,
                    AutoQualityPolicy.frameRate(it.getObjectFieldOrNull("j") as? String))
            }
            val qn = AutoQualityPolicy.video(available,
                sPrefs.getString("preferred_video_quality", "highest") ?: "highest",
                sPrefs.getString("fallback_video_quality", "highest") ?: "highest",
                sPrefs.getBoolean("prefer_highest_frame_rate", true),
                allowHdr = sPrefs.getBoolean("enable_hdr_quality", false),
                allowDolby = sPrefs.getBoolean("enable_dolby_vision", false),
                allow8k = sPrefs.getBoolean("enable_8k_quality", true))
            val index = indices.indexOfFirst { it?.getIntFieldOrNull("mQuality") == qn }
            if (index >= 0 && qn != null) return index to qn
        }
        val best = resource.callMethodOrNull("findMaxAvailableQuality", MAX_QN) ?: return null
        val index = best.callMethodOrNull("getFirst") as? Int ?: return null
        val quality = best.callMethodOrNull("getSecond") as? Int ?: return null
        return index to quality
    }

    private fun bindAudio(resource: Any, params: Any) {
        if (!sPrefs.getBoolean("auto_audio_quality", true) ||
            XposedHelpers.getAdditionalInstanceField(resource, AUDIO_BOUND) == true ||
            XposedHelpers.getAdditionalInstanceField(resource, INITIAL_REQUEST) == false) return
        val rows = resource.callMethodOrNull("getIjkAudioStreams") as? List<*> ?: return
        val candidates = rows.filterNotNull().mapNotNull {
            val id = (it.callMethodOrNull("getQualityId") as? Number)?.toInt() ?: return@mapNotNull null
            val type = it.callMethodOrNull("getAudioQualityType")?.toString() ?: "NORMAL"
            val bandwidth = (it.callMethodOrNull("getBandWidth") as? Number)?.toInt() ?: 0
            AutoQualityPolicy.Audio(id, type, bandwidth)
        }
        val audio = AutoQualityPolicy.audio(candidates, sPrefs.getBoolean("prefer_lossless_audio", true),
            sPrefs.getBoolean("prefer_dolby_audio", true)) ?: return
        params.callMethod("setAudioStreamId", audio.id)
        XposedHelpers.setAdditionalInstanceField(resource, AUDIO_BOUND, true)
        Log.d("AudioQuality initial=${audio.id} type=${audio.type} available=" + candidates.joinToString { "${it.id}:${it.type}" })
    }

    private fun applyQuality(vod: Any): Boolean {
        val download = (vod.callMethodOrNull("getDownload") as? Number)?.toInt() ?: return false
        val cid = (vod.callMethodOrNull("getCid") as? Number)?.toLong() ?: return false
        if (cid <= 0) return false
        val qn = (vod.callMethodOrNull("getQn") as? Number)?.toLong() ?: return false
        // Do not replace a subsequent explicit quality choice.
        if (!policy.shouldOverride(cid, qn, download)) return false
        vod.callMethod("setFnval", capabilityFlags((vod.callMethodOrNull("getFnval") as? Number)?.toInt() ?: 0))
        vod.callMethod("setFourk", true)
        preferredCodec()?.let { codec ->
            val codeType = "com.bapis.bilibili.playershared.CodeType".findClassOrNull(mClassLoader)
            if (codeType != null) vod.callMethodOrNull("setPreferCodecType", XposedHelpers.getStaticObjectField(codeType,
                when (codec) { "AV1" -> "CODEAV1"; "H265" -> "CODE265"; else -> "CODE264" }))
        }
        vod.callMethod("setQn", requestedQn())
        Log.d("InitialQuality request=$MAX_QN previous=$qn")
        return true
    }

    private fun preferredCodec(): String? = when (sPrefs.getString("preferred_video_codec", "auto")) {
        "av1" -> "AV1"; "hevc" -> "H265"; "avc" -> "H264"; else -> null
    }

    private fun hookVideoCodec() {
        val preferred = preferredCodec()
        if (!autoVideo && preferred == null) return
        // Keep every quality tier. Only prefer an encoding when that tier actually
        // contains it; unavailable encodings retain the host's playable fallback.
        "com.bilibili.lib.media.resource.MediaResource".findClassOrNull(mClassLoader)
            ?.hookAfterAllMethods("getIjkVideoMediaStreams") { param ->
                var streams = (param.result as? List<*>)?.filterNotNull() ?: return@hookAfterAllMethods
                // The native decoder may fall back to another entry after a
                // decode/network error. Its candidate list must obey the same
                // initial format policy as the selected qn, or it can restore DV.
                if (autoVideo && XposedHelpers.getAdditionalInstanceField(param.thisObject, INITIAL_REQUEST) == true) {
                    streams = streams.filter {
                        AutoQualityPolicy.allowedVideo((it.callMethodOrNull("getQualityId") as? Number)?.toInt() ?: 0,
                            sPrefs.getBoolean("enable_hdr_quality", false), sPrefs.getBoolean("enable_dolby_vision", false),
                            sPrefs.getBoolean("enable_8k_quality", true))
                    }
                    param.result = ArrayList(streams)
                }
                if (preferred == null) return@hookAfterAllMethods
                val preferredQualities = streams.filter {
                    it.callMethodOrNull("getVideoCodecType")?.toString() == preferred
                }.map { it.callMethodOrNull("getQualityId") }.toSet()
                if (preferredQualities.isEmpty()) return@hookAfterAllMethods
                param.result = ArrayList(streams.filter {
                    it.callMethodOrNull("getQualityId") !in preferredQualities ||
                        it.callMethodOrNull("getVideoCodecType")?.toString() == preferred
                })
            }
    }

    companion object {
        private const val MAX_QN = 127
        private const val INITIAL_REQUEST = "biliroaming.initial_quality"
        private const val INITIAL_BOUND = "biliroaming.initial_stream_bound"
        private const val SELECTED_FORMAT = "biliroaming.selected_video_format"
        private const val AUDIO_BOUND = "biliroaming.initial_audio_bound"
        private const val NATIVE_STREAM = "biliroaming.native_initial_stream"
    }
}
