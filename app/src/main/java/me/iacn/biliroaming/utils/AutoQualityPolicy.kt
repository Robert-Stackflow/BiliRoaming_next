package me.iacn.biliroaming.utils

/** Operates on returned, playable tracks; display names and VIP badges are not selectors. */
object AutoQualityPolicy {
    data class Video(val qn: Int, val width: Int, val height: Int, val fps: Double)
    data class Audio(val id: Int, val type: String, val bandwidth: Int)

    fun allowedVideo(qn: Int, hdr: Boolean, dolby: Boolean, eightk: Boolean) =
        (hdr || qn !in listOf(125, 129)) && (dolby || qn != 126) && (eightk || qn != 127)

    private fun nominalRate(fps: Double): Double {
        val standard = listOf(24.0, 25.0, 30.0, 48.0, 50.0, 60.0, 90.0, 120.0, 144.0, 165.0, 240.0)
            .minByOrNull { kotlin.math.abs(it - fps) } ?: return fps
        // DASH can report 58.824/59.94 for the same nominal 60-fps tier.
        return if (kotlin.math.abs(standard - fps) / standard < .1) standard else fps
    }

    fun frameRate(value: String?): Double {
        val parts = value?.split('/') ?: return 0.0
        val numerator = parts.firstOrNull()?.toDoubleOrNull() ?: return 0.0
        val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (denominator > 0 && numerator.isFinite()) numerator / denominator else 0.0
    }

    fun video(returned: List<Video>, preferred: String, fallback: String, preferFps: Boolean,
              allowHdr: Boolean = true, allowDolby: Boolean = true, allow8k: Boolean = true): Int? {
        val tracks = returned.filter {
            allowedVideo(it.qn, allowHdr, allowDolby, allow8k)
        }
        if (tracks.isEmpty()) return null
        val highest = if (preferFps) tracks.maxWithOrNull(
            compareBy<Video> { it.width.toLong() * it.height }.thenBy { nominalRate(it.fps) }.thenBy { it.qn }
        ) else tracks.maxByOrNull { it.qn }
        fun choose(setting: String): Int? = if (setting == "highest") highest?.qn
            else setting.toIntOrNull()?.takeIf { qn -> tracks.any { it.qn == qn } }
        return choose(preferred) ?: choose(fallback) ?: highest?.qn
    }

    fun audio(tracks: List<Audio>, lossless: Boolean, dolby: Boolean): Audio? = tracks
        .filter { it.type == "NORMAL" || (it.type == "HIRES" && lossless) || (it.type == "DOLBY" && dolby) }
        .maxWithOrNull(compareBy<Audio> {
            when (it.type) { "DOLBY" -> 3; "HIRES" -> 2; else -> 1 }
        }.thenBy { it.bandwidth })
}
