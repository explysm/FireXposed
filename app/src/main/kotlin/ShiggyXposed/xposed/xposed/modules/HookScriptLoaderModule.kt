package FireXposed.xposed.modules

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import FireXposed.xposed.Constants
import FireXposed.xposed.HookStateHolder
import FireXposed.xposed.Module
import FireXposed.xposed.BuildConfig
import FireXposed.xposed.Utils.Companion.JSON
import FireXposed.xposed.Utils.Log
import FireXposed.xposed.modules.HookScriptLoaderModule.PRELOADS_DIR
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.reflect.Method

/**
 * Hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * Preload scripts should be placed in the [PRELOADS_DIR] directory inside the module's files directory.
 *
 * The main bundle should be placed in the [Constants.CACHE_DIR] directory named [Constants.MAIN_SCRIPT_FILE].
 * If the bundle file does not exist, it will attempt to load `assets://Fire.bundle` from the module's assets.
 */
object HookScriptLoaderModule : Module() {
    private lateinit var preloadsDir: File
    private lateinit var mainScript: File

    private lateinit var modulePath: String
    private lateinit var resources: XModuleResources
    
    private var modules: List<Module>? = null

    const val PRELOADS_DIR = "preloads"
    const val GLOBAL_NAME = "__PYON_LOADER__"

    fun setModules(modules: List<Module>) {
        this.modules = modules
    }

    private fun getPayloadString(): String = JSON.encodeToString(
        buildJsonObject {
            put("loaderName", Constants.LOADER_NAME)
            put("loaderVersion", BuildConfig.VERSION_NAME)
            modules?.forEach { 
                @Suppress("DEPRECATION")
                it.buildPayload(this) 
            }
        })

    override fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {
        this@HookScriptLoaderModule.modulePath = startupParam.modulePath
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { asDir() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { asDir() }

        preloadsDir = File(filesDir, PRELOADS_DIR).apply { asDir() }
        mainScript = File(cacheDir, Constants.MAIN_SCRIPT_FILE).apply { asFile() }

        listOf(
            "com.facebook.react.runtime.ReactInstance\$loadJSBundle$1",
            "com.facebook.react.runtime.ReactInstance$1",
            // TODO: Remove once Discord fully switches to Bridgeless
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }.forEach { hook(it) }
    }

    private fun hook(instance: Class<*>) = runCatching {
        val loadScriptFromAssets = instance.method(
            "loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )

        val loadScriptFromFile = instance.method(
            "loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )

        loadScriptFromAssets.hook {
            before {
                Log.i("Received call to loadScriptFromAssets: ${args[1]} (sync: ${args[2]})")
                runCustomScripts(loadScriptFromFile, loadScriptFromAssets)
            }
        }

        loadScriptFromFile.hook {
            before {
                Log.i("Received call to loadScriptFromFile: ${args[0]} (sync: ${args[2]})")
                runCustomScripts(loadScriptFromFile, loadScriptFromAssets)
            }
        }
    }.onFailure {
        Log.e("Failed to hook script loading methods in ${instance.name}:", it)
    }

    private fun HookScope.runCustomScripts(loadScriptFromFile: Method, loadScriptFromAssets: Method) {
        Log.i("Running custom scripts...")

        runBlocking {
            val downloadJob = UpdaterModule.downloadScript()
            if (!mainScript.exists()) {
                Log.i("Main script not found, waiting for download...")
                downloadJob.join()
            }
            HookStateHolder.readyDeferred.join()
        }

        val loadSynchronously = args[2]
        
        // Inject global payload
        try {
            val payloadFile = File(preloadsDir, "rv_globals_$GLOBAL_NAME.js")
            payloadFile.writeText("this[${JSON.encodeToString(GLOBAL_NAME)}]=${getPayloadString()}")
            Log.i("Injected global payload to ${payloadFile.absolutePath}")
        } catch (e: Throwable) {
            Log.e("Failed to inject global payload", e)
        }

        val runScriptFile = { file: File ->
            Log.i("Loading script: ${file.absolutePath}")

            XposedBridge.invokeOriginalMethod(
                loadScriptFromFile, thisObject, arrayOf(file.absolutePath, file.absolutePath, loadSynchronously)
            )

            Unit
        }

        try {
            // If a disabled marker exists next to the cached bundle, treat this as a global
            // disable for the entire script-loading step. In that case, we skip preloads,
            // cached main script and the assets fallback so nothing is injected.
            val disabledMarker = File(mainScript.parentFile, "${Constants.MAIN_SCRIPT_FILE}.disabled")
            if (disabledMarker.exists()) {
                Log.i("Script loading disabled by marker; skipping preloads, cached bundle and fallback")
            } else {
                // Normal behaviour: run preloads then cached main script or fallback to bundled asset
                preloadsDir.walk().filter { it.isFile }.forEach(runScriptFile)
                
                // Cleanup temporary globals
                preloadsDir.listFiles { _, name -> name.startsWith("rv_globals_") }?.forEach { it.delete() }

                if (mainScript.exists()) {
                    runScriptFile(mainScript)
                } else {
                    Log.i("Main script does not exist, falling back")

                    if (!::resources.isInitialized) resources = XModuleResources.createInstance(modulePath, null)

                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromAssets,
                        thisObject,
                        arrayOf(resources.assets, "assets://Fire.bundle", loadSynchronously)
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e("Unable to run scripts:", e)
        }
    }
}
