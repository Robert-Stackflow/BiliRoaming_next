package me.iacn.biliroaming.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import me.iacn.biliroaming.utils.*
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Optional platform navigation; the default leaves the client unchanged. */
class DetailTransitionHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private var resumed = WeakReference<Activity>(null)
    private var source = WeakReference<Activity>(null)
    private var launchTime = 0L

    override fun startHook() {
        // Removed/unknown values deliberately behave like "original".
        if (sPrefs.getString("video_detail_transition", "original") != "system") return
        Log.d("DetailTransition system navigation enabled")
        Instrumentation::class.java.hookAfterAllMethods("callActivityOnResume") {
            resumed = WeakReference(it.args.firstOrNull() as? Activity)
        }
        Instrumentation::class.java.hookBeforeAllMethods("execStartActivity") { param ->
            val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return@hookBeforeAllMethods
            val context = param.args.firstOrNull() as? Context ?: return@hookBeforeAllMethods
            if (!isTarget(intent, context)) return@hookBeforeAllMethods
            source = WeakReference((param.args.getOrNull(3) as? Activity) ?: resumed.get())
            launchTime = SystemClock.uptimeMillis()
            removeHostCapture(intent)
            val index = (param.method as Method).parameterTypes.indexOf(Bundle::class.java)
            if (index < 0) return@hookBeforeAllMethods
            val original = param.args[index] as? Bundle
            val cleaned = Bundle(original ?: Bundle()).apply {
                keySet().filter { it.startsWith("android:activity.anim") || it.startsWith("android:activity.remote") ||
                    it.startsWith("android:activity.sharedElement") || it.startsWith("android:activity.transition") }.forEach(::remove)
            }
            if (original != null) { original.clear(); original.putAll(cleaned) } else param.args[index] = cleaned
        }
        Instrumentation::class.java.hookBeforeAllMethods("callActivityOnCreate") {
            val owner = it.args.firstOrNull() as? Activity ?: return@hookBeforeAllMethods
            if (owner.javaClass.name !in DETAILS) return@hookBeforeAllMethods
            removeHostCapture(owner.intent)
            restoreWindow(owner)
        }
        Instrumentation::class.java.hookAfterAllMethods("callActivityOnCreate") {
            val owner = it.args.firstOrNull() as? Activity ?: return@hookAfterAllMethods
            if (owner.javaClass.name in DETAILS) restoreWindow(owner)
        }
        listOf("overridePendingTransition", "overrideActivityTransition").forEach { method ->
            Activity::class.java.hookBeforeAllMethods(method) {
                val owner = it.thisObject as Activity
                if (owner.javaClass.name in DETAILS || source.get() === owner &&
                    SystemClock.uptimeMillis() - launchTime < 1500) it.result = null
            }
        }
    }

    private fun restoreWindow(owner: Activity) {
        owner.window.attributes = owner.window.attributes.apply { windowAnimations = android.R.style.Animation_Activity }
        if (Build.VERSION.SDK_INT >= 34) {
            owner.clearOverrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN)
            owner.clearOverrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE)
        }
    }

    private fun isTarget(intent: Intent, context: Context): Boolean {
        if (intent.component?.className in DETAILS) return true
        val info = runCatching { intent.resolveActivityInfo(context.packageManager, 0) }.getOrNull() ?: return false
        return (info.targetActivity ?: info.name) in DETAILS
    }

    private fun removeHostCapture(intent: Intent) {
        val key = intent.getStringExtra("capture_bitmap_key")
        intent.removeExtra("capture_bitmap_key")
        val uri = intent.data; val query = uri?.encodedQuery
        if (query != null && query.contains("capture_bitmap_key=")) intent.data = uri.buildUpon()
            .encodedQuery(query.split('&').filterNot { it.substringBefore('=') == "capture_bitmap_key" }.joinToString("&")).build()
        if (key != null) {
            val cache = "tt0.h".findClassOrNull(mClassLoader)
            if (cache != null) (XposedHelpers.getStaticObjectField(cache, "a") as? MutableMap<*, *>)?.remove(key)
        }
    }

    companion object {
        private val DETAILS = setOf("com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity", "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity")
    }
}
