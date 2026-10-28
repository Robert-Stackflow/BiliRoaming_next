package me.iacn.biliroaming.utils

object HomeMenuPolicy {
    fun isGameCenter(uri: String?): Boolean {
        val path = uri?.substringBefore('?')?.substringBefore('#')?.trimEnd('/') ?: return false
        return path == "action://game_center/home/menu" ||
            path == "bilibili://game_center/home" || path.startsWith("bilibili://game_center/home/")
    }
}
