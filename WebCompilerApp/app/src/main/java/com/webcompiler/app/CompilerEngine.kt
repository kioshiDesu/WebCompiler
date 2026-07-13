package com.webcompiler.app

import android.content.Context
import com.webcompiler.app.signing.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CompilerEngine(private val context: Context) {

    data class Config(
        val appName: String,
        val packageName: String,
        val permissions: Set<String>,
        val htmlCode: String,
        val templateApk: File,
        val iconPng: ByteArray? = null,
        val zipEntries: Map<String, ByteArray>? = null
    )

    private val workDir: File get() = File(context.cacheDir, "compiler")
    private val outputDir: File get() = File(context.getExternalFilesDir(null), "WebCompiler")

    private val permissionMap = mapOf(
        "CAMERA" to "android.permission.CAMERA",
        "LOCATION" to "android.permission.ACCESS_FINE_LOCATION",
        "NOTIFICATIONS" to "android.permission.POST_NOTIFICATIONS",
        "STORAGE" to "android.permission.READ_EXTERNAL_STORAGE",
        "MICROPHONE" to "android.permission.RECORD_AUDIO",
        "CONTACTS" to "android.permission.READ_CONTACTS",
        "PHONE" to "android.permission.READ_PHONE_STATE"
    )

    fun extractBundledAssets(destDir: File = context.filesDir): File? {
        val templateApk = File(destDir, "template.apk")
        if (!templateApk.exists()) {
            try {
                context.assets.open("template.apk").use { input ->
                    FileOutputStream(templateApk).use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return templateApk
    }

    fun build(config: Config, onLog: (String) -> Unit): Result<File> {
        return try {
            outputDir.mkdirs()
            workDir.mkdirs()
            workDir.deleteRecursively()
            workDir.mkdirs()

            val extractDir = File(workDir, "extracted")
            val finalApk = File(outputDir, "${config.appName.replace(" ", "")}.apk")

            onLog("[1/5] Extracting template APK...")
            extractApk(config.templateApk, extractDir, onLog)

            onLog("[2/5] Preparing AndroidManifest.xml...")
            // All permissions are pre-declared in the template; no patching needed

            onLog("[3/5] Injecting assets...")
            injectAssets(extractDir, config, onLog)

            onLog("[4/5] Repackaging APK...")
            repackageApk(extractDir, File(workDir, "unsigned.apk"), onLog)

            onLog("[5/5] Signing APK...")
            val signer = ApkSigner()
            signer.sign(File(workDir, "unsigned.apk"), finalApk)
            File(workDir, "unsigned.apk").delete()

            extractDir.deleteRecursively()
            onLog("Done! APK saved to: ${finalApk.absolutePath}")
            Result.success(finalApk)
        } catch (e: Exception) {
            onLog("ERROR: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun extractApk(apkFile: File, destDir: File, onLog: (String) -> Unit) {
        destDir.mkdirs()
        var count = 0
        ZipFile(apkFile).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) file.mkdirs()
                else {
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                    count++
                }
            }
        }
        onLog("  Extracted template")
    }



    private fun injectAssets(extractDir: File, config: Config, onLog: (String) -> Unit) {
        val assetsDir = File(extractDir, "assets")
        assetsDir.mkdirs()

        if (config.zipEntries != null && config.zipEntries.isNotEmpty()) {
            for ((path, data) in config.zipEntries) {
                val targetFile = File(assetsDir, path)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(data)
            }
            onLog("  Extracted ${config.zipEntries.size} files from zip into assets/")
        } else {
            File(assetsDir, "index.html").writeText(config.htmlCode)
            onLog("  Injected index.html")
        }

        if (config.iconPng != null) {
            val iconDirs = listOf("res/mipmap-hdpi", "res/mipmap-mdpi", "res/mipmap-xhdpi",
                "res/mipmap-xxhdpi", "res/mipmap-xxxhdpi")
            val scaled = scaleIcon(config.iconPng!!, onLog)
            if (scaled != null) {
                for ((i, dir) in iconDirs.withIndex()) {
                    val f = File(extractDir, "$dir/ic_launcher.png")
                    f.parentFile?.mkdirs()
                    f.writeBytes(scaled[i])
                }
                onLog("  Applied custom icon")
            }
        }
    }

    private fun scaleIcon(originalPng: ByteArray, onLog: (String) -> Unit): List<ByteArray>? {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(originalPng, 0, originalPng.size) ?: return null
            listOf(36, 48, 72, 96, 144).map { size ->
                val s = android.graphics.Bitmap.createScaledBitmap(bmp, size, size, true)
                val os = ByteArrayOutputStream()
                s.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                s.recycle()
                os.toByteArray()
            }.also { bmp.recycle() }
        } catch (e: Exception) {
            onLog("  WARNING: Icon scaling failed: ${e.message}")
            null
        }
    }

    private fun repackageApk(inputDir: File, outputApk: File, onLog: (String) -> Unit) {
        val files = inputDir.walkTopDown().filter { it.isFile }.toList()
        ZipOutputStream(outputApk.outputStream()).use { zos ->
            for (file in files) {
                val entryName = file.relativeTo(inputDir).path
                val entry = ZipEntry(entryName)
                entry.method = ZipEntry.DEFLATED
                val data = file.readBytes()
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        onLog("  Repackaged ${files.size} entries")
    }
}
