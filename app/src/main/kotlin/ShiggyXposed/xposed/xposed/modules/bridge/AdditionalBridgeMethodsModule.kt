package FireXposed.xposed.modules.bridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import FireXposed.xposed.Module
import FireXposed.xposed.Utils
import java.io.File

object AdditionalBridgeMethodsModule : Module() {
    override fun onContext(context: Context) = with(context) {
        BridgeModule.registerMethod("Fire.fs.getConstants") {
            mapOf(
                "data" to dataDir.absolutePath,
                "files" to filesDir.absolutePath,
                "cache" to cacheDir.absolutePath,
            )
        }

        BridgeModule.registerMethod("Fire.fs.delete") {
            val (path) = it
            File(path as String).run {
                if (this.isDirectory) this.deleteRecursively()
                else this.delete()
            }
        }

        BridgeModule.registerMethod("Fire.fs.exists") {
            val (path) = it
            File(path as String).exists()
        }

        BridgeModule.registerMethod("Fire.fs.read") { it ->
            val (path) = it
            val file = File(path as String).apply { openFileGuarded() }

            file.bufferedReader().use { it.readText() }
        }

        BridgeModule.registerMethod("Fire.fs.write") {
            val (path, contents) = it
            val file = File(path as String).apply { openFileGuarded() }

            file.writeText(contents as String)
        }
    }

    override fun onActivity(activity: Activity) = with(activity) {
        BridgeModule.registerMethod("Fire.alertError") {
            val (error, version) = it
            val app = getAppInfo()
            val errorString = "$error"

            val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Stack Trace", errorString)

            AlertDialog.Builder(this)
                .setTitle("FireCord Error")
                .setMessage(
                    """
                    FireCord: $version
                    ${app.name}: ${app.version} (${app.versionCode})
                    Device: ${Build.MANUFACTURER} ${Build.MODEL}


                """.trimIndent() + errorString
                )
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton("Copy") { dialog, _ ->
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext, "Copied stack trace", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .show()

            null
        }

        BridgeModule.registerMethod("Fire.showRecoveryAlert") {
            Utils.showRecoveryAlert(this)
        }
    }

    private fun File.openFileGuarded() {
        if (!this.exists()) throw Error("Path does not exist: $path")
        if (!this.isFile) throw Error("Path is not a file: $path")
    }
}
