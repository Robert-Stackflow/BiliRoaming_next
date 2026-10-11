package me.iacn.biliroaming.hook

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import me.iacn.biliroaming.BiliBiliPackage
import me.iacn.biliroaming.utils.*
import java.lang.reflect.Modifier


class CopyCommentHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (!sPrefs.getBoolean("copy_comment", false)) return
        hookLegacyMenuHolder()
        // 新菜单分发方法的查找涉及类枚举，耗时较大，移到后台线程避免阻塞启动
        Thread({
            val start = System.currentTimeMillis()
            hookNewMenuDispatch()
            Log.d("new comment menu dispatch hook took ${System.currentTimeMillis() - start}ms")
        }, "BiliRoaming-CopyComment").start()
    }

    /**
     * 旧版（<= 8.x）: CommentMoreMenuItemHolder.y3(Function1, MenuItem, View)
     * 菜单行点击时执行复制后读取剪贴板弹出“自由复制内容”对话框。
     */
    private fun hookLegacyMenuHolder() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder",
                mClassLoader,
                "y3",
                "kotlin.jvm.functions.Function1",
                "com.bilibili.app.comment3.data.model.CommentItem\$MenuItem",
                "android.view.View",  // ConstraintLayout
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        super.afterHookedMethod(param)
                        showCopyDialog()
                    }
                })
        }.onFailure { Log.d("legacy comment menu holder not found, skipping") }
    }

    /**
     * 新版（9.8.0+）: 评论更多菜单改为 Compose 实现，
     * 菜单项点击通过 CommentContainerImpl$attachRepository$xx$2$1
     * 的静态分发方法 (Intent, CommentItem.MenuItem, MutableStateFlow) 转发。
     * 该方法执行完同步的复制逻辑后才返回，因此 after 钩子里读剪贴板即可拿到复制内容。
     */
    private fun hookNewMenuDispatch() {
        val menuItemClass =
            "com.bilibili.app.comment3.data.model.CommentItem\$MenuItem".findClassOrNull(mClassLoader)
                ?: return
        val stateFlowName = "kotlinx.coroutines.flow.MutableStateFlow"
        val primary = runCatchingOrNull {
            "com.bilibili.app.comment3.ui.CommentContainerImpl".findClassOrNull(mClassLoader)
                ?.declaredClasses
                .orEmpty()
                .flatMap { it.declaredMethods.toList() }
                .filter { m ->
                    Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 3 &&
                            m.parameterTypes[1] == menuItemClass &&
                            m.parameterTypes[2].name == stateFlowName
                }
        }
        val dispatchMethods = if (!primary.isNullOrEmpty()) primary else runCatchingOrNull {
            Log.d("new comment menu dispatch: primary scan failed, fallback to allClassesList")
            mClassLoader
                .allClassesList(BiliBiliPackage::findRealClassloader)
                .asSequence()
                .filter {
                    it.startsWith("com.bilibili.app.comment3.ui.CommentContainerImpl\$attachRepository\$")
                }
                .mapNotNull { it.findClassOrNull(mClassLoader) }
                .flatMap { c ->
                    c.declaredMethods.asSequence().filter { m ->
                        Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 3 &&
                                m.parameterTypes[1] == menuItemClass &&
                                m.parameterTypes[2].name == stateFlowName
                    }
                }
                .toList()
        }
        dispatchMethods?.forEach { method ->
            method.hookAfterMethod { param ->
                val menu = param.args.getOrNull(1) ?: return@hookAfterMethod
                Log.d("CopyCommentHook: menu dispatch ${method.declaringClass?.name} menu=$menu")
                if (!menu.toString().contains("COPY")) return@hookAfterMethod
                showCopyDialog()
            }
        }
        if (dispatchMethods.isNullOrEmpty()) {
            Log.d("new comment menu dispatch not found, skipping")
        }
    }

    private fun showCopyDialog() {
        val clipboard =
            currentContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // 实在是找不到了。那你就说有没有获取到吧
        val txt = runCatchingOrNull {
            clipboard.primaryClip?.getItemAt(0)?.text
        } ?: return
        AlertDialog.Builder(currentContext, getResId("AppTheme.Dialog.Alert", "style"))
            .run {
                setTitle("自由复制内容")
                setMessage(txt)
                setPositiveButton("完成") { _, _ -> }
                setNegativeButton("复制全部") { _, _ -> }
                show()
            }.apply {
                findViewById<TextView>(android.R.id.message).setTextIsSelectable(true)
            }
    }
}
