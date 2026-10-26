package me.iacn.biliroaming.hook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.iacn.biliroaming.utils.*

/** View IDs and toolbar item types from the official 9.13.0 resources. */
class InterfaceCleanupHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        val listen = sPrefs.getBoolean("hide_player_listen", true)
        val portrait = sPrefs.getBoolean("hide_player_portrait", true)
        val game = sPrefs.getBoolean("purify_game", true)
        val ids = HashSet<Int>()
        if (portrait) {
            ids.addAll(listOf(0x7f09191a, 0x7f0904ba)) // gemini/bbplayer_halfscreen_story
            "com.bapis.bilibili.app.viewunite.v1.StoryEntrance".findClassOrNull(mClassLoader)?.let { model ->
                listOf("getPlayStory", "getArcPlayStory", "getArcLandscapeStory").forEach { method ->
                    model.replaceAllMethods(method) { false }
                }
            }
        }
        if (game) ids.addAll(listOf(0x7f093338, 0x7f09487d, 0x7f095be1)) // home game containers
        val reported = HashSet<Int>()
        fun hide(view: View) {
            if (view.id !in ids) return
            view.visibility = View.GONE
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
            "k10.p\$e".findClassOrNull(mClassLoader)?.hookBeforeAllMethods("getUpperAdView") {
                it.args[1] = ArrayList<Any>()
                it.args[4] = ArrayList<Any>()
                Log.d("InterfaceCleanup removed under-player ad sources")
            }
        }
    }
}
