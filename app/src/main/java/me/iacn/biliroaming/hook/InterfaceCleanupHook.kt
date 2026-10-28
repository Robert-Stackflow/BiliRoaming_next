package me.iacn.biliroaming.hook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import me.iacn.biliroaming.utils.*

/** Resolve host IDs by name: numeric IDs move between official client versions. */
class InterfaceCleanupHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val listen = sPrefs.getBoolean("hide_player_listen", true)
        val portrait = sPrefs.getBoolean("hide_player_portrait", true)
        val game = sPrefs.getBoolean("purify_game", true)
        val ids = HashSet<Int>()
        if (portrait) {
            listOf("gemini_halfscreen_story", "bbplayer_halfscreen_story")
                .map(::getId).filterTo(ids) { it != 0 }
            "com.bapis.bilibili.app.viewunite.v1.StoryEntrance".findClassOrNull(mClassLoader)?.let { model ->
                listOf("getPlayStory", "getArcPlayStory", "getArcLandscapeStory").forEach { method ->
                    model.replaceAllMethods(method) { false }
                }
            }
        }
        if (game) hookHomeGameMenu()
        val reported = HashSet<Int>()
        val baseVisibility = View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType!!)
        fun hide(view: View) {
            if (view.id !in ids) return
            // Inflation can finish before Gemini initializes its controller.
            // Its override accesses lateinit fields; use the base View operation.
            XposedBridge.invokeOriginalMethod(baseVisibility, view, arrayOf(View.GONE))
            if (reported.add(view.id)) Log.d("InterfaceCleanup hidden ${view.resources.getResourceEntryName(view.id)}")
        }
        if (ids.isNotEmpty()) {
            View::class.java.hookBeforeMethod("setVisibility", Int::class.javaPrimitiveType!!) {
                if ((it.thisObject as View).id in ids) it.args[0] = View.GONE
            }
            fun traverse(view: View) {
                hide(view)
                if (view is ViewGroup) for (i in 0 until view.childCount) traverse(view.getChildAt(i))
            }
            LayoutInflater::class.java.hookAfterAllMethods("inflate") {
                (it.result as? View)?.let(::traverse)
            }
        }
        if (listen) {
            "com.bilibili.ship.theseus.united.page.toolbar.ToolbarRepository"
                .findClassOrNull(mClassLoader)?.hookBeforeAllMethods("f") { param ->
                    val list = param.args.firstOrNull() as? List<*> ?: return@hookBeforeAllMethods
                    val filtered = list.filterNot { it?.javaClass?.name?.contains(".toolbar.actionview.listen.") == true }
                    if (filtered.size != list.size) {
                        param.args[0] = ArrayList(filtered)
                        Log.d("InterfaceCleanup removed listen toolbar action")
                    }
                }
        }
        if (sPrefs.getBoolean("block_view_page_ads", true)) {
            // getUpperAdView explicitly accepts an empty list and returns null.
            // Clear both sources so delayed/refreshed cards cannot repopulate it.
            // Same provider contract, different R8 names in 9.12 and 9.13.
            listOf("r10.n\$e", "k10.p\$e").forEach { provider ->
                provider.findClassOrNull(mClassLoader)?.declaredMethods?.filter {
                    it.name == "getUpperAdView" && it.parameterTypes.size == 7 &&
                        List::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                        List::class.java.isAssignableFrom(it.parameterTypes[4])
                }?.forEach { method ->
                    method.hookBeforeMethod {
                        it.args[1] = ArrayList<Any>()
                        it.args[4] = ArrayList<Any>()
                        Log.d("InterfaceCleanup removed under-player ad sources")
                    }
                }
            }
        }
    }

    private fun hookHomeGameMenu() {
        // This provider merges server tabs, cached tabs and the built-in fallback.
        // The new game entry is an action route with no dedicated View ID.
        val provider = "tv.danmaku.bili.ui.main2.HomeFragmentV2\$HomeMenuDataProvider"
            .findClassOrNull(mClassLoader) ?: return
        provider.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java)) &&
                List::class.java.isAssignableFrom(it.returnType)
        }.forEach { method ->
            method.hookAfterMethod { param ->
                val items = param.result as? List<*> ?: return@hookAfterMethod
                val filtered = items.filterNot { item ->
                    val route = item?.getObjectFieldOrNull("d")?.getObjectFieldOrNullAs<String>("b")
                    HomeMenuPolicy.isGameCenter(route)
                }
                if (filtered.size != items.size) {
                    param.result = ArrayList(filtered)
                    Log.d("InterfaceCleanup removed home game menu (${items.size} -> ${filtered.size})")
                }
            }
        }
    }
}
