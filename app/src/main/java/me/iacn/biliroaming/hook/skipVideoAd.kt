package me.iacn.biliroaming.hook

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.BilibiliSponsorBlock
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.av2bv
import me.iacn.biliroaming.utils.callMethod
import me.iacn.biliroaming.utils.callMethodAs
import me.iacn.biliroaming.utils.currentContext
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.mossResponseHandlerReplaceProxy
import me.iacn.biliroaming.utils.sPrefs
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * SponsorBlock 空降助手（BiliRoaming 播放器侧移植）。
 *
 * 数据来自 [BilibiliSponsorBlock]，在播放时跳过/静音命中的片段。
 * 支持：自动跳过、跳过倒计时、片段静音、最小片段时长、按分类开关、跳过提示与统计。
 */
class SkipVideoAd(classLoader: ClassLoader) : BaseHook(classLoader) {

    companion object {
        private const val LOOKAHEAD_MS = 250L
    }

    private var lastSeekTime = 0L
    private var playerRef: WeakReference<Any>? = null
    private val player get() = playerRef?.get()
    private var duration = -1L

    @Volatile
    private var segments: List<BilibiliSponsorBlock.Segment> = emptyList()
    private var bvid = ""
    private var cid = ""

    private val skipped = HashSet<String>()
    private var muted = false

    // 设置快照：进入新视频时刷新，避免每帧读 MODE_MULTI_PROCESS 的 SharedPreferences。
    @Volatile private var server = BilibiliSponsorBlock.DEFAULT_SERVER
    @Volatile private var autoSkip = true
    @Volatile private var muteEnabled = false
    @Volatile private var showToast = true
    @Volatile private var statsEnabled = true
    @Volatile private var minDurationMs = 0L
    @Volatile private var countdownMs = 0L
    @Volatile private var enabledCategories: Set<String> = BilibiliSponsorBlock.defaultCategories

    private val handler = Handler(Looper.getMainLooper())
    private var countdownSegment: BilibiliSponsorBlock.Segment? = null
    private var countdownRunnable: Runnable? = null

    override fun startHook() {
        if (!sPrefs.getBoolean("skip_video_ad", false)) return

        Log.d("startHook: SkipVideoAd")

        instance.playerMossClass?.apply {
            hookBeforeMethod(
                "executePlayViewUnite",
                instance.playViewUniteReqClass
            ) { param ->
                val req = param.args[0]
                bvid = req.callMethodAs("getBvid")
                val vod = req.callMethod("getVod") ?: return@hookBeforeMethod
                if (bvid.isEmpty()) {
                    val aid = vod.callMethodAs<Long>("getAid")
                    if (aid == -1L) {
                        return@hookBeforeMethod
                    }
                    bvid = av2bv(aid)
                }
                cid = vod.callMethodAs<Long>("getCid").toString()
            }

            hookBeforeMethod(
                "playViewUnite",
                instance.playViewUniteReqClass,
                instance.mossResponseHandlerClass
            ) { param ->
                param.args[1] = param.args[1].mossResponseHandlerReplaceProxy { reply ->
                    reply ?: return@mossResponseHandlerReplaceProxy null
                    val playArc = reply.callMethod("getPlayArc")
                        ?: return@mossResponseHandlerReplaceProxy null
                    cid = playArc.callMethodAs<Long>("getCid").toString()
                    val aid = playArc.callMethodAs<Long>("getAid") ?: -1L
                    if (aid == -1L) {
                        return@mossResponseHandlerReplaceProxy null
                    }
                    bvid = av2bv(aid)
                    null
                }
            }
        }

        instance.playerCoreServiceV2Class?.apply {
            val stateMethod = declaredMethods.firstOrNull {
                (it.name == "G1" || it.name == "r") && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.name
            stateMethod?.let { name ->
                hookAfterMethod(name, Int::class.java) { param ->
                    playerRef = WeakReference(param.thisObject)
                    val state = param.args[0] as Int
                    if (state in 3..5 && duration <= 0) {
                        duration = (player?.callMethodAs<Int>("getDuration") ?: -1).toLong()
                    }
                    if (state == 2) {
                        onVideoStart()
                    }
                }
            }

            hookAfterMethod("getCurrentPosition") { param ->
                val position = (param.result as? Number)?.toLong() ?: return@hookAfterMethod
                onProgress(position)
            }
        }
    }

    private fun onVideoStart() {
        duration = -1
        segments = emptyList()
        skipped.clear()
        setMuted(false)
        cancelCountdown()
        reloadSettings()
        val currentBvid = bvid
        val currentCid = cid
        if (currentBvid.isEmpty() || currentCid.isEmpty()) return
        if (BilibiliSponsorBlock.hasCached(currentBvid, currentCid)) {
            segments = BilibiliSponsorBlock.cached(currentBvid, currentCid).orEmpty()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            val maxRetries = 3
            while (retryCount < maxRetries) {
                val result = BilibiliSponsorBlock.fetch(
                    currentBvid, currentCid, server, enabledCategories
                )
                // 只有真的拿到片段才算成功；空结果也重试，避免服务端偶发抖动导致整集不跳。
                segments = result
                if (result.isNotEmpty()) break
                retryCount++
                if (retryCount < maxRetries) delay(1000)
            }
        }
    }

    private fun reloadSettings() {
        server = sPrefs.getString("skip_video_ad_server", null)
            ?.takeIf { it.isNotBlank() } ?: BilibiliSponsorBlock.DEFAULT_SERVER
        autoSkip = sPrefs.getBoolean("skip_video_ad_auto", true)
        muteEnabled = sPrefs.getBoolean("skip_video_ad_mute", false)
        showToast = sPrefs.getBoolean("skip_video_ad_toast", true)
        statsEnabled = sPrefs.getBoolean("skip_video_ad_stats", true)
        enabledCategories = sPrefs.getStringSet("skip_video_ad_categories", null)
            ?: BilibiliSponsorBlock.defaultCategories
        minDurationMs = (sPrefs.getString("skip_video_ad_min_duration", "0")
            ?.toFloatOrNull()?.times(1000f) ?: 0f).toLong()
        countdownMs = (sPrefs.getString("skip_video_ad_countdown", "0")
            ?.toFloatOrNull()?.times(1000f) ?: 0f).toLong()
    }

    private fun onProgress(positionMs: Long) {
        if (duration <= 0L) return
        val position = positionMs.coerceIn(0L, duration)

        // 倒计时进行中：离开片段则取消，否则等待回调触发跳过。
        countdownSegment?.let { seg ->
            if (position < seg.startMs - LOOKAHEAD_MS || position >= seg.endMs) {
                cancelCountdown()
            } else {
                return
            }
        }

        if (muteEnabled) {
            val muteSegment = segments.firstOrNull {
                it.actionType == BilibiliSponsorBlock.ACTION_MUTE &&
                    it.durationMs >= minDurationMs &&
                    position >= it.startMs && position < it.endMs
            }
            setMuted(muteSegment != null)
        } else if (muted) {
            setMuted(false)
        }

        if (!autoSkip) return

        val segment = segments.firstOrNull {
            it.actionType == BilibiliSponsorBlock.ACTION_SKIP &&
                it.category != BilibiliSponsorBlock.CATEGORY_HIGHLIGHT &&
                it.durationMs >= minDurationMs &&
                it.uuid !in skipped &&
                position >= it.startMs - LOOKAHEAD_MS &&
                position < it.endMs
        } ?: return

        if (countdownMs > 0L) {
            startCountdown(segment)
        } else {
            skipped.add(segment.uuid)
            if (performSkip(segment)) {
                onSkipped(segment)
            } else {
                skipped.remove(segment.uuid)
            }
        }
    }

    private fun startCountdown(segment: BilibiliSponsorBlock.Segment) {
        skipped.add(segment.uuid)
        countdownSegment = segment
        val totalMs = minOf(countdownMs, segment.durationMs / 2).coerceAtLeast(1L)
        if (showToast) {
            Log.toast("${BilibiliSponsorBlock.displayName(segment.category)} ${totalMs / 1000}秒后跳过")
        }
        val runnable = Runnable {
            countdownSegment = null
            countdownRunnable = null
            val current = player?.callMethodAs<Int>("getCurrentPosition")?.toLong() ?: -1L
            if (current in 0 until segment.endMs && performSkip(segment)) {
                onSkipped(segment)
            }
        }
        countdownRunnable = runnable
        handler.postDelayed(runnable, totalMs)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownSegment?.let { skipped.remove(it.uuid) }
        countdownSegment = null
    }

    private fun performSkip(segment: BilibiliSponsorBlock.Segment): Boolean {
        val position = player?.callMethodAs<Int>("getCurrentPosition")?.toLong() ?: return false
        // 已经越过片段尾：seek(endMs) 是往回跳且没有省下时长，放弃。
        if (position >= segment.endMs) return false
        val target = if (duration in 1 until segment.endMs) duration else segment.endMs
        if (System.currentTimeMillis() - lastSeekTime < 200) return false
        lastSeekTime = System.currentTimeMillis()
        player?.callMethod("seekTo", target.toInt())
        return true
    }

    private fun onSkipped(segment: BilibiliSponsorBlock.Segment) {
        if (statsEnabled) {
            val count = sPrefs.getLong("skip_video_ad_count", 0L) + 1
            val saved = sPrefs.getLong("skip_video_ad_saved_ms", 0L) + segment.durationMs
            sPrefs.edit()
                .putLong("skip_video_ad_count", count)
                .putLong("skip_video_ad_saved_ms", saved)
                .apply()
        }
        if (showToast) {
            val name = BilibiliSponsorBlock.displayName(segment.category)
            val seconds = String.format(Locale.US, "%.1f", segment.durationMs / 1000.0)
            Log.toast("已跳过 $name (${seconds}秒)")
        }
    }

    private fun setMuted(mute: Boolean) {
        if (mute == muted) return
        val audioManager = try {
            currentContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (mute) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            muted = mute
        } catch (e: Exception) {
            Log.e(e)
        }
    }
}
