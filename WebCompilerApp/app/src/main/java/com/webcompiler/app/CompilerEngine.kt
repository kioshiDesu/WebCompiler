package com.webcompiler.app

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class CompilerEngine(private val context: Context) {

    data class Config(
        val appName: String,
        val packageName: String,
        val permissions: Set<String>,
        val htmlCode: String,
        val templateApk: File,
        val apktoolJar: File
    )

    private val workDir: File get() = File(context.cacheDir, "compiler")
    private val outputDir: File get() = File(context.getExternalFilesDir(null), "WebCompiler")

    /**
     * Extracts bundled assets (template.apk, apktool.jar) from the APK's
     * built-in assets into [destDir] so the pipeline can use them.
     * Files are only extracted if they don't already exist at the destination.
     *
     * @return Pair(apktoolJar, templateApk) — the File handles after extraction
     */
    fun extractBundledAssets(destDir: File = context.filesDir): Pair<File, File> {
        val apktoolJar = File(destDir, "apktool.jar")
        val templateApk = File(destDir, "template.apk")
        val assets = context.assets

        if (!apktoolJar.exists()) {
            try {
                assets.open("apktool.jar").use { input ->
                    FileOutputStream(apktoolJar).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // Not bundled — development build, user must provide manually
            }
        }

        if (!templateApk.exists()) {
            try {
                assets.open("template.apk").use { input ->
                    FileOutputStream(templateApk).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // Not bundled
            }
        }

        return Pair(apktoolJar, templateApk)
    }

    fun build(config: Config, onLog: (String) -> Unit): Result<File> {
        return try {
            outputDir.mkdirs()
            workDir.mkdirs()
            workDir.deleteRecursively()
            workDir.mkdirs()

            val decodedDir = File(workDir, "decoded")

            onLog("[1/5] Decompiling template APK...")
            runApktool(config.apktoolJar, listOf(
                "d", config.templateApk.absolutePath,
                "-o", decodedDir.absolutePath,
                "-f"
            ), onLog)

            onLog("[2/5] Injecting HTML code...")
            val assetsDir = File(decodedDir, "assets")
            assetsDir.mkdirs()
            File(assetsDir, "index.html").writeText(config.htmlCode)

            onLog("[3/5] Applying permissions...")
            applyPermissions(decodedDir, config.permissions, onLog)

            onLog("[4/5] Updating app metadata...")
            updateAppMetadata(decodedDir, config, onLog)

            onLog("[5/5] Rebuilding APK...")
            val unsignedApk = File(workDir, "unsigned.apk")
            runApktool(config.apktoolJar, listOf(
                "b", decodedDir.absolutePath,
                "-o", unsignedApk.absolutePath
            ), onLog)

            onLog("Signing APK...")
            val finalApk = File(outputDir, "${config.appName.replace(" ", "")}.apk")
            signApk(unsignedApk, finalApk, onLog)

            onLog("Done! APK saved to: ${finalApk.absolutePath}")
            Result.success(finalApk)
        } catch (e: Exception) {
            onLog("ERROR: ${e.message}")
            Result.failure(e)
        }
    }

    private fun runApktool(apktoolJar: File, args: List<String>, onLog: (String) -> Unit) {
        val cmd = listOf(
            "java", "-jar", apktoolJar.absolutePath
        ) + args

        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        reader.use { r ->
            r.lines().forEach { line ->
                onLog("  $line")
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("apktool exited with code $exitCode")
        }
    }

    private fun applyPermissions(decodedDir: File, permissions: Set<String>, onLog: (String) -> Unit) {
        val manifestFile = File(decodedDir, "AndroidManifest.xml")
        if (!manifestFile.exists()) {
            onLog("  WARNING: AndroidManifest.xml not found")
            return
        }

        var content = manifestFile.readText()

        val permissionMap = mapOf(
            "CAMERA" to "android.permission.CAMERA",
            "LOCATION" to "android.permission.ACCESS_FINE_LOCATION",
            "NOTIFICATIONS" to "android.permission.POST_NOTIFICATIONS",
            "STORAGE" to "android.permission.READ_EXTERNAL_STORAGE"
        )

        for (perm in permissions) {
            val androidPerm = permissionMap[perm] ?: continue
            val permTag = "<uses-permission android:name=\"$androidPerm\" />"
            if (permTag !in content) {
                content = content.replace(
                    "</manifest>",
                    "    $permTag\n</manifest>"
                )
                onLog("  Added: $perm")
            }
        }

        manifestFile.writeText(content)
    }

    private fun updateAppMetadata(decodedDir: File, config: Config, onLog: (String) -> Unit) {
        val manifestFile = File(decodedDir, "AndroidManifest.xml")
        if (!manifestFile.exists()) return

        var content = manifestFile.readText()

        // Update app label in manifest if there's an android:label
        content = content.replace(
            """android:label="[^"]*"""".toRegex(),
            "android:label=\"${config.appName}\""
        )

        // Update package name in the root <manifest> tag
        content = content.replace(
            """package="[^"]*"""".toRegex(),
            "package=\"${config.packageName}\""
        )

        manifestFile.writeText(content)
        onLog("  App name: ${config.appName}, Package: ${config.packageName}")
    }

    private fun signApk(unsigned: File, signed: File, onLog: (String) -> Unit) {
        // Generate a debug keystore if none exists
        val keystoreFile = File(workDir, "debug.keystore")
        if (!keystoreFile.exists()) {
            val ksCmd = listOf(
                "keytool", "-genkey", "-v",
                "-keystore", keystoreFile.absolutePath,
                "-alias", "debug",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "android",
                "-keypass", "android",
                "-dname", "CN=Debug, OU=Dev, O=WebCompiler, L=City, ST=State, C=US"
            )
            val ksProcess = ProcessBuilder(ksCmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            ksProcess.waitFor()
        }

        // Sign with apksigner
        val apksignerCmd = if (Build.VERSION.SDK_INT >= 30) {
            // Use the bundled apksigner from the system
            listOf(
                "apksigner", "sign",
                "--ks", keystoreFile.absolutePath,
                "--ks-pass", "pass:android",
                "--key-pass", "pass:android",
                "--out", signed.absolutePath,
                unsigned.absolutePath
            )
        } else {
            // Fallback: just copy unsigned
            unsigned.copyTo(signed, overwrite = true)
            onLog("  WARNING: apksigner not available, APK is unsigned")
            return
        }

        val process = ProcessBuilder(apksignerCmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            // If apksigner fails, copy unsigned
            unsigned.copyTo(signed, overwrite = true)
            onLog("  WARNING: Signing failed, APK is unsigned")
        } else {
            onLog("  Signed with debug key")
        }
    }
}
