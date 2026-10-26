package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.*

/** Hide the whole 9.13 Compose section, including its heading and reserved space. */
class VideoMentionsHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("remove_video_mentions", true)) return
        // VideoMentionedModuleFactory (obfuscated as p in 9.13.0) already creates
        // a zero-height component for an empty list. Reuse that normal path.
        val factory = "com.bilibili.ship.theseus.ugc.intro.videomentioned.module.p"
            .findClassOrNull(mClassLoader) ?: return
        val method = factory.declaredMethods.firstOrNull {
            it.parameterTypes.size == 2 &&
                List::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                it.returnType.simpleName == "RunningUIComponent"
        } ?: return
        var reported = false
        method.hookBeforeMethod { param ->
            param.args[1] = ArrayList<Any>()
            if (!reported) Log.d("removed video mentions section before composition")
            reported = true
        }
        Log.d("startHook: VideoMentions section")
    }
}
