package me.iacn.biliroaming.utils

/** Preloads for another video must not turn a manual change into a first request. */
class InitialQualityPolicy {
    private val seen = object : LinkedHashMap<Long, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?) = size > 64
    }

    @Synchronized
    fun shouldOverride(cid: Long, qn: Long, download: Int): Boolean {
        if (cid <= 0 || download != 0) return false
        val first = seen.put(cid, Unit) == null
        return first || qn == 0L
    }
}
