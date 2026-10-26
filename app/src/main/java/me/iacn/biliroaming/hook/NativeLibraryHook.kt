package me.iacn.biliroaming.hook

import me.iacn.biliroaming.utils.*
import java.io.File

/** OEM dex preloading can retain the old library search directory after an update. */
class NativeLibraryHook(classLoader: ClassLoader, private val libraryDir: String) : BaseHook(classLoader) {
    private val loading = object : ThreadLocal<Boolean>() {
        override fun initialValue() = false
    }

    override fun startHook() {
        listOf("load0", "loadLibrary0").forEach { method ->
            Runtime::class.java.hookBeforeAllMethods(method) { param ->
                if (loading.get()) return@hookBeforeAllMethods
                val caller = param.args.firstOrNull() as? Class<*> ?: return@hookBeforeAllMethods
                if (caller.classLoader == null) return@hookBeforeAllMethods
                val path = param.args.getOrNull(1) as? String ?: return@hookBeforeAllMethods
                val name = if (method == "load0") File(path).name else System.mapLibraryName(path)
                if (name !in dependencies || !File(libraryDir, name).isFile) return@hookBeforeAllMethods
                loading.set(true)
                try {
                    val visited = HashSet<String>()
                    fun loadDependencies(parent: String) {
                        dependencies[parent].orEmpty().forEach { child ->
                            if (visited.add(child)) {
                                loadDependencies(child)
                                val file = File(libraryDir, child)
                                // The caller class is essential: System.load would use
                                // the module namespace instead of the host namespace.
                                if (file.isFile) Runtime.getRuntime().callMethod("load0", caller, file.absolutePath)
                            }
                        }
                    }
                    loadDependencies(name)
                    android.util.Log.d("BiliRoaming", "Loaded current-installation dependencies for $name")
                } catch (error: Throwable) {
                    android.util.Log.w("BiliRoaming", "Dependency preload failed for $name", error)
                } finally {
                    loading.set(false)
                }
            }
        }
    }

    // DT_NEEDED edges between bundled arm64 libraries in official 9.13.0 (9130500).
    // System libraries are deliberately excluded. Only dependencies of the library
    // being loaded are touched, and only when present in the current installation.
    private val dependencies = mapOf(
        "libMNN_CL.so" to listOf("libMNN.so"),
        "libMNN_Express.so" to listOf("libMNN.so"),
        "libPerfgeniusApi.so" to listOf("libc++_shared.so"),
        "libYTLiveness.so" to listOf("libYTCommonLiveness.so"),
        "libacckitjni-lib.so" to listOf("libPerfgeniusApi.so"),
        "libavif.so" to listOf("libijkffmpeg.so", "libcrypto.so", "libssl.so"),
        "libbili-upload.so" to listOf("libcrypto.so", "libssl.so", "libignet.so", "libbvc-xcode-tools.so", "libijkffmpeg.so"),
        "libbili_core.so" to listOf("libbytehook.so"),
        "libblive_pandora.so" to listOf("libblog.so"),
        "libbvc-xcode-tools.so" to listOf("libijkffmpeg.so"),
        "libbytehook.so" to listOf("libshadowhook.so"),
        "libdvvcd.so" to listOf("libc++_shared.so"),
        "libffmpeg_cc_dummy_app_for_so.so" to listOf("libijkffmpeg.so", "libcrypto.so", "libssl.so"),
        "libfinauthlivenessv5Ex.so" to listOf("libcsgface.so"),
        "libhardcoder.so" to listOf("libc++_shared.so"),
        "libignet.so" to listOf("libcrypto.so", "libssl.so"),
        "libijk.so" to listOf("libijksdl.so", "libijkffmpeg.so", "libijkffext.so", "libdvvcd.so", "libignet.so", "libcrypto.so", "libssl.so"),
        "libijkffext.so" to listOf("libijksdl.so", "libijkffmpeg.so", "libignet.so", "libcrypto.so", "libssl.so", "libdvvcd.so"),
        "libijkffmpeg.so" to listOf("libcrypto.so", "libssl.so"),
        "libijkplayer.so" to listOf("libijksdl.so", "libijkffmpeg.so", "libijkffext.so", "libdvvcd.so", "libignet.so", "libcrypto.so", "libssl.so", "libijkDolbyVision.so"),
        "libijksdl.so" to listOf("libignet.so", "libcrypto.so", "libssl.so", "libijkffmpeg.so"),
        "liblynx.so" to listOf("liblynxbase.so", "libquick.so"),
        "liblynxtrace.so" to listOf("liblynxbase.so"),
        "libmnn_predictor.so" to listOf("libMNN.so", "libMNN_CL.so", "libMNN_Express.so"),
        "libnapi.so" to listOf("libquick.so"),
        "libnirvana.so" to listOf("libc++_shared.so"),
        "libquickjs-jni.so" to listOf("libquickjs.so"),
        "libssl.so" to listOf("libcrypto.so"),
        "libtf.so" to listOf("libignet.so", "libcrypto.so", "libssl.so"),
        "libwasm.so" to listOf("libquick.so")
    )
}
