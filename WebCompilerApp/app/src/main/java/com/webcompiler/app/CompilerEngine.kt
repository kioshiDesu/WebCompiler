package com.webcompiler.app

import android.content.Context
import android.util.Log
import com.webcompiler.app.signing.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CompilerEngine(private val context: Context) {

    private val signer: ApkSigner by lazy {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = context.resources.openRawResource(R.raw.cert).use { input ->
            cf.generateCertificate(input) as java.security.cert.X509Certificate
        }
        val keyBytes = context.resources.openRawResource(R.raw.key).use { it.readBytes() }
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("RSA")
        val privateKey = kf.generatePrivate(keySpec)
        ApkSigner(privateKey, cert)
    }

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
        "PHONE" to "android.permission.READ_PHONE_STATE",
        "INTERNET" to "android.permission.INTERNET",
        "NETWORK" to "android.permission.ACCESS_NETWORK_STATE",
        "WIFI" to "android.permission.ACCESS_WIFI_STATE",
        "BLUETOOTH" to "android.permission.BLUETOOTH",
        "NFC" to "android.permission.NFC",
        "VIBRATE" to "android.permission.VIBRATE"
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
        val startMs = System.currentTimeMillis()

        fun stepLog(step: String, detail: String = "") {
            val elapsed = System.currentTimeMillis() - startMs
            val ts = String.format("[%02d:%02d]", (elapsed / 60000) % 60, (elapsed / 1000) % 60)
            onLog("$ts $step${if (detail.isNotEmpty()) " — $detail" else ""}")
        }

        return try {
            workDir.deleteRecursively()
            workDir.mkdirs()
            outputDir.mkdirs()
            outputDir.listFiles { f -> f.extension == "apk" }?.forEach { it.delete() }

            val extractDir = File(workDir, "extracted")
            val templateApk = config.templateApk

            if (!templateApk.exists()) {
                return Result.failure(IllegalStateException("Template APK not found: ${templateApk.absolutePath}"))
            }
            val templateSize = templateApk.length()
            stepLog("[1/5] Extracting template APK", "${templateSize / 1024} KB")

            val extracted = extractApk(templateApk, extractDir)
            if (extracted.isEmpty()) {
                return Result.failure(IllegalStateException("Template APK extraction produced no files"))
            }
            stepLog("[1/5] Extracted ${extracted.size} entries")

            stepLog("[2/5] Preparing AndroidManifest.xml")
            patchPackageName(extractDir, config.packageName, onLog)

            stepLog("[3/5] Injecting assets")
            injectAssets(extractDir, config, onLog)

            stepLog("[4/5] Repackaging APK")
            val unsignedApk = File(workDir, "unsigned.apk")
            val repackageCount = repackageApk(extractDir, unsignedApk)
            if (!unsignedApk.exists() || unsignedApk.length() == 0L) {
                return Result.failure(IllegalStateException("Repackaged APK is empty or missing"))
            }
            stepLog("[4/5] Repackaged $repackageCount entries (${unsignedApk.length() / 1024} KB)")

            stepLog("[5/5] Signing APK", "SHA-256 + RSA / BouncyCastle ASN.1")
            val finalApk = File(outputDir, "${config.appName.replace(" ", "")}.apk")
            try {
                signer.sign(unsignedApk, finalApk)
            } catch (e: Exception) {
                Log.e("WebCompiler", "Signing step failed", e)
                return Result.failure(Exception("Signing failed: ${e.message}"))
            }
            unsignedApk.delete()

            if (!finalApk.exists() || finalApk.length() == 0L) {
                return Result.failure(IllegalStateException("Signed APK is missing or empty"))
            }

            extractDir.deleteRecursively()

            val elapsed = System.currentTimeMillis() - startMs
            stepLog("Done! ${finalApk.length() / 1024} KB signed APK → ${finalApk.name}", "took ${elapsed / 1000}s")
            onLog("")

            Result.success(finalApk)
        } catch (e: java.util.zip.ZipException) {
            Log.e("WebCompiler", "ZIP error during build", e)
            onLog("ERROR: Corrupt or invalid ZIP file: ${e.message}")
            Result.failure(e)
        } catch (e: java.security.GeneralSecurityException) {
            Log.e("WebCompiler", "Security error during build", e)
            onLog("ERROR: Cryptographic operation failed: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("WebCompiler", "Build failed unexpectedly", e)
            onLog("ERROR: ${e.message ?: e.javaClass.simpleName}")
            outputDir.listFiles { f -> f.extension == "apk" }?.forEach { it.delete() }
            Result.failure(e)
        }
    }

    private fun extractApk(apkFile: File, destDir: File): List<String> {
        destDir.mkdirs()
        val names = mutableListOf<String>()
        ZipFile(apkFile).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) file.mkdirs()
                else {
                    try {
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                        names.add(entry.name)
                    } catch (e: Exception) {
                        Log.w("WebCompiler", "Failed to extract ${entry.name}: ${e.message}")
                    }
                }
            }
        }
        return names
    }

    private fun injectAssets(extractDir: File, config: Config, onLog: (String) -> Unit) {
        val assetsDir = File(extractDir, "assets")
        assetsDir.mkdirs()

        if (config.zipEntries != null && config.zipEntries.isNotEmpty()) {
            var injected = 0
            for ((path, data) in config.zipEntries) {
                if (data.size > 10 * 1024 * 1024) {
                    Log.w("WebCompiler", "Skipping oversized asset: $path (${data.size / 1024 / 1024} MB)")
                    onLog("  WARNING: Skipping oversized file: $path")
                    continue
                }
                val targetFile = File(assetsDir, path)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(data)
                injected++
            }
            onLog("  Injected $injected files from zip into assets/")
        } else {
            val html = config.htmlCode
            if (html.length > 5 * 1024 * 1024) {
                Log.w("WebCompiler", "HTML code unusually large: ${html.length / 1024} KB")
                onLog("  WARNING: HTML code is ${html.length / 1024} KB")
            }
            File(assetsDir, "index.html").writeText(html)
            onLog("  Injected index.html (${html.length / 1024} KB)")
        }

        if (config.iconPng != null) {
            val iconDirs = listOf("res/mipmap-hdpi", "res/mipmap-mdpi", "res/mipmap-xhdpi",
                "res/mipmap-xxhdpi", "res/mipmap-xxxhdpi")
            val scaled = scaleIcon(config.iconPng, onLog)
            if (scaled != null) {
                for ((i, dir) in iconDirs.withIndex()) {
                    val f = File(extractDir, "$dir/ic_launcher.png")
                    f.parentFile?.mkdirs()
                    f.writeBytes(scaled[i])
                }
                onLog("  Applied custom icon across 5 densities")
            }
        }
    }

    private fun scaleIcon(originalPng: ByteArray, onLog: (String) -> Unit): List<ByteArray>? {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(originalPng, 0, originalPng.size) ?: return null
            val result = listOf(36, 48, 72, 96, 144).map { size ->
                val s = android.graphics.Bitmap.createScaledBitmap(bmp, size, size, true)
                val os = ByteArrayOutputStream()
                s.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                s.recycle()
                os.toByteArray()
            }
            bmp.recycle()
            result
        } catch (e: Exception) {
            onLog("  WARNING: Icon scaling failed: ${e.message}")
            null
        }
    }

    private fun patchPackageName(extractDir: File, newPackage: String, onLog: (String) -> Unit) {
        if (newPackage.isBlank()) {
            onLog("  Package name is empty, keeping default")
            return
        }
        val manifestFile = File(extractDir, "AndroidManifest.xml")
        if (!manifestFile.exists()) {
            onLog("  WARNING: AndroidManifest.xml not found, skipping")
            return
        }

        val data = manifestFile.readBytes()
        val templatePkg = "com.webview.blank"
        val templateUtf16 = templatePkg.toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
        val newUtf16 = newPackage.toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)

        val idx = data.indexOf(templateUtf16)
        if (idx < 0) {
            onLog("  WARNING: Could not find package '$templatePkg' in binary manifest")
            return
        }

        val padded = if (newUtf16.size <= templateUtf16.size) {
            newUtf16 + ByteArray(templateUtf16.size - newUtf16.size)
        } else {
            onLog("  WARNING: Package name too long, truncated to ${templatePkg.length} chars")
            newUtf16.copyOf(templateUtf16.size)
        }

        System.arraycopy(padded, 0, data, idx, padded.size)
        manifestFile.writeBytes(data)
        onLog("  Changed package: $templatePkg → $newPackage")
    }

    private fun repackageApk(inputDir: File, outputApk: File): Int {
        val files = inputDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            throw IllegalStateException("No files to repackage")
        }
        ZipOutputStream(outputApk.outputStream()).use { zos ->
            val crc32 = CRC32()
            for (file in files) {
                val entryName = file.relativeTo(inputDir).path
                val data = file.readBytes()
                if (data.isEmpty()) {
                    Log.w("WebCompiler", "Skipping empty entry: $entryName")
                    continue
                }
                val entry = ZipEntry(entryName)
                if (entryName.startsWith("META-INF/")) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    crc32.reset()
                    crc32.update(data)
                    entry.crc = crc32.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return files.size
    }
}
