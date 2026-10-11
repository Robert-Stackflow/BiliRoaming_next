@file:Suppress("unused")

package me.iacn.biliroaming.utils

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import me.iacn.biliroaming.BiliBiliPackage
import me.iacn.biliroaming.Constant.TAG
import android.util.Log as ALog
import java.io.FileOutputStream

object Log {

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var toast: Toast? = null

    @Volatile
    var fileLoggingEnabled = false

    private val fileLock = Any()
    private var fileWriter: FileOutputStream? = null

    fun toast(msg: String, force: Boolean = false, duration: Int = Toast.LENGTH_SHORT, alsoLog: Boolean = true) {
        if (!force && !sPrefs.getBoolean("show_info", true)) return
        handler.post {
            val inst = runCatchingOrNull { BiliBiliPackage.instance } ?: return@post
            inst.toastHelperClass?.runCatchingOrNull {
                callStaticMethod(inst.cancelShowToast())
                callStaticMethod(
                    inst.showToast(),
                    currentContext,
                    "哔哩漫游：$msg",
                    duration
                )
                Unit
            } ?: run {
                toast?.cancel()
                toast = Toast.makeText(currentContext, "", duration).apply {
                    setText("哔哩漫游：$msg")
                    show()
                }
            }
        }
        if (alsoLog) w(msg)
    }

    private fun writeToFile(level: String, str: String) {
        if (!fileLoggingEnabled && !shouldSaveLog) return
        runCatchingOrNull {
            synchronized(fileLock) {
                val file = logFile
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
                if (file.length() > MAX_LOG_FILE_SIZE) {
                    val old = oldLogFile
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                    file.createNewFile()
                    runCatching { fileWriter?.close() }
                    fileWriter = null
                }
                val out = fileWriter ?: FileOutputStream(file, true).also { fileWriter = it }
                out.write("[$level] ${System.currentTimeMillis()}: $str\n".toByteArray())
                out.flush()
            }
        }
    }

    @JvmStatic
    private fun doLog(
        f: (String, String) -> Int,
        level: String,
        obj: Any?,
        toXposed: Boolean = false
    ) {
        val str = if (obj is Throwable) ALog.getStackTraceString(obj) else obj.toString()

        if (str.length > maxLength) {
            val chunkCount: Int = str.length / maxLength
            for (i in 0..chunkCount) {
                val max: Int = maxLength * (i + 1)
                if (max >= str.length) {
                    doLog(f, level, str.substring(maxLength * i))
                } else {
                    doLog(f, level, str.substring(maxLength * i, max))
                }
            }
        } else {
            f(TAG, str)
            if (toXposed)
                XposedBridge.log("$TAG : $str")
            writeToFile(level, str)
        }
    }

    @JvmStatic
    fun d(obj: Any?) {
        doLog(ALog::d, "D", obj)
    }

    @JvmStatic
    fun i(obj: Any?) {
        doLog(ALog::i, "I", obj)
    }

    @JvmStatic
    fun e(obj: Any?) {
        doLog(ALog::e, "E", obj, true)
    }

    @JvmStatic
    fun v(obj: Any?) {
        doLog(ALog::v, "V", obj)
    }

    @JvmStatic
    fun w(obj: Any?) {
        doLog(ALog::w, "W", obj)
    }

    private const val maxLength = 3000
    private const val MAX_LOG_FILE_SIZE = 4L * 1024 * 1024
}
