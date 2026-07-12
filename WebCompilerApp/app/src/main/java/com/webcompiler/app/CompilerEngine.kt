package com.webcompiler.app

import android.content.Context
import com.webcompiler.app.signing.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
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
        "STORAGE" to "android.permission.READ_EXTERNAL_STORAGE"
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

            onLog("[2/5] Patching AndroidManifest.xml...")
            val manifestFile = File(extractDir, "AndroidManifest.xml")
            val patchedManifest = patchManifest(manifestFile.readBytes(), config.permissions, onLog)
            manifestFile.writeBytes(patchedManifest)

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
        onLog("  Extracted $count files")
    }

    private fun patchManifest(data: ByteArray, permissions: Set<String>, onLog: (String) -> Unit): ByteArray {
        try {
            return patchManifestInternal(data, permissions, onLog)
        } catch (e: Exception) {
            onLog("  WARNING: Manifest patch failed (${e.message ?: "unknown"}), returning original")
            return data
        }
    }

    private fun patchManifestInternal(data: ByteArray, permissions: Set<String>, onLog: (String) -> Unit): ByteArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        if ((buf.getShort().toInt() and 0xFFFF) != 0x0003) {
            onLog("  WARNING: Not AXML format, skipping manifest patch")
            return data
        }
        buf.getShort()
        buf.getInt()

        val stringPoolStart = buf.position()
        val spType = buf.getShort().toInt() and 0xFFFF
        if (spType != 0x0001) return data
        val spHeaderSize = buf.getShort().toInt() and 0xFFFF
        val spChunkSize = buf.getInt()
        val spCount = buf.getInt()
        val styleCount = buf.getInt()
        val spFlags = buf.getInt()
        val spStringsStart = buf.getInt()
        buf.getInt()

        val isUtf8 = (spFlags and 0x0100) != 0

        val offsets = IntArray(spCount) { buf.getInt() }
        repeat(styleCount) { buf.getInt() }

        val poolData = ByteArray(spChunkSize - (buf.position() - stringPoolStart))
        try {
            buf.get(poolData)
        } catch (_: Exception) {
            onLog("  WARNING: String pool data truncated, skipping manifest patch")
            return data
        }
        val poolBuf = ByteBuffer.wrap(poolData).order(ByteOrder.LITTLE_ENDIAN)

        val strings = mutableListOf<String>()
        for (off in offsets) {
            if (off < 0 || off >= poolData.size) { strings.add(""); continue }
            poolBuf.position(off)
            if (isUtf8) {
                try {
                    val b1 = poolBuf.get().toInt() and 0xFF
                    val charLen = if (b1 and 0x80 != 0)
                        ((b1 and 0x7F) shl 8) or (poolBuf.get().toInt() and 0xFF)
                    else b1
                    val b2 = poolBuf.get().toInt() and 0xFF
                    val byteLen = if (b2 and 0x80 != 0)
                        ((b2 and 0x7F) shl 8) or (poolBuf.get().toInt() and 0xFF)
                    else b2
                    if (byteLen < 0 || poolBuf.remaining() < byteLen + 1) {
                        strings.add(""); continue
                    }
                    val arr = ByteArray(byteLen)
                    poolBuf.get(arr)
                    poolBuf.get()
                    strings.add(String(arr, StandardCharsets.UTF_8))
                } catch (_: Exception) {
                    strings.add("")
                }
            } else {
                try {
                    val charLen = poolBuf.getShort().toInt() and 0xFFFF
                    if (charLen < 0 || poolBuf.remaining() < charLen * 2 + 2) {
                        strings.add(""); continue
                    }
                    val arr = ByteArray(charLen * 2)
                    poolBuf.get(arr)
                    poolBuf.getShort()
                    strings.add(String(arr, StandardCharsets.UTF_16LE))
                } catch (_: Exception) {
                    strings.add("")
                }
            }
        }

        val permNames = permissions.mapNotNull { permissionMap[it] }
        val usesPermTagIdx = strings.indexOf("uses-permission")
        val nameAttrIdx = strings.indexOf("name")
        val maxSdkAttrIdx = strings.indexOf("maxSdkVersion")
        val androidNsUri = "http://schemas.android.com/apk/res/android"

        if (permNames.isEmpty() || usesPermTagIdx < 0 || nameAttrIdx < 0 || maxSdkAttrIdx < 0) {
            onLog("  No permissions to enable")
            return data
        }

        val androidNsIdx = strings.indexOf(androidNsUri)
        val permStringIndices = permNames.map { strings.indexOf(it) }.filter { it >= 0 }

        val result = data.toMutableList()
        var pos = stringPoolStart + spChunkSize

        while (pos + 8 < data.size) {
            val chunkType = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            if (chunkType != 0x0102) {
                val cSize = ((data[pos + 4].toInt() and 0xFF) or
                        ((data[pos + 5].toInt() and 0xFF) shl 8) or
                        ((data[pos + 6].toInt() and 0xFF) shl 16) or
                        ((data[pos + 7].toInt() and 0xFF) shl 24))
                pos += cSize
                continue
            }

            val headerSize = (data[pos + 2].toInt() and 0xFF) or ((data[pos + 3].toInt() and 0xFF) shl 8)
            val chunkSize = (data[pos + 4].toInt() and 0xFF) or
                    ((data[pos + 5].toInt() and 0xFF) shl 8) or
                    ((data[pos + 6].toInt() and 0xFF) shl 16) or
                    ((data[pos + 7].toInt() and 0xFF) shl 24)

            val tagNsIdx = (data[pos + 16].toInt() and 0xFF) or
                    ((data[pos + 17].toInt() and 0xFF) shl 8) or
                    ((data[pos + 18].toInt() and 0xFF) shl 16) or
                    ((data[pos + 19].toInt() and 0xFF) shl 24)
            val tagNameIdx = (data[pos + 20].toInt() and 0xFF) or
                    ((data[pos + 21].toInt() and 0xFF) shl 8) or
                    ((data[pos + 22].toInt() and 0xFF) shl 16) or
                    ((data[pos + 23].toInt() and 0xFF) shl 24)

            if (tagNameIdx in strings.indices && strings[tagNameIdx] == "uses-permission" &&
                tagNameIdx == usesPermTagIdx) {

                val attrCount = (data[pos + 28].toInt() and 0xFF) or ((data[pos + 29].toInt() and 0xFF) shl 8)
                val attrStart = 24
                val attrSize = 20

                var foundPermName: String? = null
                var maxSdkAttrOffset = -1
                val attributesBefore = mutableListOf<Int>()

                for (i in 0 until attrCount) {
                    val aPos = pos + 16 + attrStart + i * attrSize
                    val aNs = (data[aPos].toInt() and 0xFF) or ((data[aPos + 1].toInt() and 0xFF) shl 8) or
                            ((data[aPos + 2].toInt() and 0xFF) shl 16) or ((data[aPos + 3].toInt() and 0xFF) shl 24)
                    val aName = (data[aPos + 4].toInt() and 0xFF) or ((data[aPos + 5].toInt() and 0xFF) shl 8) or
                            ((data[aPos + 6].toInt() and 0xFF) shl 16) or ((data[aPos + 7].toInt() and 0xFF) shl 24)

                    if (aName in strings.indices && strings[aName] == "name") {
                        val rawVal = (data[aPos + 8].toInt() and 0xFF) or ((data[aPos + 9].toInt() and 0xFF) shl 8) or
                                ((data[aPos + 10].toInt() and 0xFF) shl 16) or ((data[aPos + 11].toInt() and 0xFF) shl 24)
                        if (rawVal in strings.indices) foundPermName = strings[rawVal]
                    }

                    if (androidNsIdx >= 0 && aNs == androidNsIdx &&
                        aName in strings.indices && strings[aName] == "maxSdkVersion") {
                        maxSdkAttrOffset = aPos
                    }
                    attributesBefore.add(aPos)
                }

                if (foundPermName in permNames && maxSdkAttrOffset >= 0) {
                    val newAttrCount = attrCount - 1
                    val removeStart = maxSdkAttrOffset - (pos + 16)
                    val newChunkSize = chunkSize - attrSize

                    val before = result.subList(0, pos)
                    val attrData = result.subList(pos + 16 + attrStart, pos + 16 + attrStart + attrCount * attrSize)
                    val after = result.subList(pos + chunkSize, result.size)

                    val newAttrData = mutableListOf<Byte>()
                    for (i in 0 until attrCount) {
                        val aPos2 = pos + 16 + attrStart + i * attrSize
                        if (aPos2 != maxSdkAttrOffset) {
                            for (j in 0 until attrSize) {
                                newAttrData.add(data[aPos2 + j])
                            }
                        }
                    }

                    val newChunk = mutableListOf<Byte>()
                    for (i in 0 until pos) newChunk.add(result[i].toByte())
                    for (i in pos + 16 + attrStart until pos + 16 + attrStart + attrCount * attrSize) {
                        if (i in maxSdkAttrOffset until maxSdkAttrOffset + attrSize) continue
                        newChunk.add(result[i].toByte())
                    }
                    for (i in pos + chunkSize until result.size) newChunk.add(result[i].toByte())

                    val newChunkBytes = newChunk.toByteArray()

                    newChunkBytes[pos + 4] = (newChunkSize and 0xFF).toByte()
                    newChunkBytes[pos + 5] = ((newChunkSize shr 8) and 0xFF).toByte()
                    newChunkBytes[pos + 6] = ((newChunkSize shr 16) and 0xFF).toByte()
                    newChunkBytes[pos + 7] = ((newChunkSize shr 24) and 0xFF).toByte()
                    newChunkBytes[pos + 28] = (newAttrCount and 0xFF).toByte()
                    newChunkBytes[pos + 29] = ((newAttrCount shr 8) and 0xFF).toByte()

                    val shortPermName = permissionMap.entries.find { it.value == foundPermName }?.key ?: foundPermName
                    onLog("  Enabled: $shortPermName")
                    return newChunkBytes
                }
            }
            pos += chunkSize
        }

        return data
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
