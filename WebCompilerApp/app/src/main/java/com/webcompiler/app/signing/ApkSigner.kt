package com.webcompiler.app.signing

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

class ApkSigner(
    private val privateKey: PrivateKey,
    private val certificate: X509Certificate
) {

    fun sign(inputApk: File, outputApk: File) {
        val manifest = createManifest(inputApk)
        val sigFile = createSignatureFile(manifest)
        val sigBlock = createSignatureBlock(sigFile, privateKey, certificate)
        val v1Apk = File(outputApk.absolutePath + ".v1temp")
        try {
            writeSignedApk(inputApk, v1Apk, manifest, sigFile, sigBlock)
            addV2SigningBlock(v1Apk, outputApk)
        } finally {
            v1Apk.delete()
        }
    }

    private fun createManifest(apkFile: File): ByteArray {
        val lines = mutableListOf<String>()
        lines.add("Manifest-Version: 1.0")
        lines.add("Created-By: WebCompiler")
        lines.add("")

        val md = MessageDigest.getInstance("SHA-256")

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                .sortedBy { it.name }
                .toList()

            for (entry in entries) {
                val data = zip.getInputStream(entry).readBytes()
                val hash = md.digest(data)
                val b64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
                lines.add("Name: ${entry.name}")
                lines.add("SHA-256-Digest: $b64")
                lines.add("")
            }
        }

        return (lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }

    private fun createSignatureFile(manifest: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(manifest)
        val b64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)

        val lines = mutableListOf<String>()
        lines.add("Signature-Version: 1.0")
        lines.add("Created-By: WebCompiler")
        lines.add("SHA-256-Digest-Manifest: $b64")
        lines.add("")

        return (lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.US_ASCII)
    }

    private fun createSignatureBlock(
        sigFile: ByteArray,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): ByteArray {
        val certHolder = org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(certificate)

        val signerBuilder = JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().build()
        ).build(
            JcaContentSignerBuilder("SHA256withRSA").build(privateKey),
            certHolder
        )

        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(signerBuilder)
        gen.addCertificate(certHolder)

        val content = object : CMSTypedData {
            override fun getContentType() = org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.7.1")
            override fun getContent() = sigFile
            override fun write(out: java.io.OutputStream) = out.write(sigFile)
        }
        val signedData = gen.generate(content, true)
        return signedData.encoded
    }

    private fun writeSignedApk(
        input: File, output: File,
        manifest: ByteArray, sf: ByteArray, sigBlock: ByteArray
    ) {
        val newMetaInf = mapOf(
            "META-INF/MANIFEST.MF" to manifest,
            "META-INF/CERT.SF" to sf,
            "META-INF/CERT.RSA" to sigBlock
        )

        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream()).use { zos ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .sortedBy { it.name }
                    .toList()

                for (entry in entries) {
                    val name = entry.name
                    if (name.startsWith("META-INF/")) {
                        val fn = name.substring("META-INF/".length)
                        if (fn == "MANIFEST.MF" || fn.endsWith(".SF") || fn.endsWith(".RSA") || fn.endsWith(".DSA") || fn.endsWith(".EC")) {
                            continue
                        }
                    }
                    val ze = ZipEntry(name)
                    val data = zip.getInputStream(entry).readBytes()
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                }

                val crc32 = CRC32()
                for ((name, data) in newMetaInf) {
                    val ze = ZipEntry(name)
                    ze.method = ZipEntry.STORED
                    ze.size = data.size.toLong()
                    crc32.reset()
                    crc32.update(data)
                    ze.crc = crc32.value
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
        }
    }

    // ── v2 APK Signature Scheme ──────────────────────────────────────────────────

    private fun addV2SigningBlock(input: File, output: File) {
        val raf = RandomAccessFile(input, "r")
        val (eocdOffset, eocdBytes) = try {
            findEocd(raf)
        } finally {
            raf.close()
        }

        val cdSize = readUint32LE(eocdBytes, 12)
        val cdOffset = readUint32LE(eocdBytes, 16)

        RandomAccessFile(input, "r").use { reader ->
            val beforeCd = ByteArray(cdOffset.toInt())
            reader.seek(0)
            reader.readFully(beforeCd)

            val centralDir = ByteArray(cdSize.toInt())
            reader.seek(cdOffset)
            reader.readFully(centralDir)

            val contentDigest = computeContentDigest(beforeCd, centralDir, eocdBytes)
            val signedData = buildV2SignedData(contentDigest, certificate)
            val signature = signV2SignedData(signedData)
            val signerBlock = buildV2SignerBlock(signedData, signature, certificate)
            val signingBlock = buildApkSigningBlock(signerBlock)

            val newCdOffset = cdOffset + signingBlock.size.toLong()
            writeUint32LE(eocdBytes, 16, newCdOffset)

            FileOutputStream(output).use { out ->
                out.write(beforeCd)
                out.write(signingBlock)
                out.write(centralDir)
                out.write(eocdBytes)
            }
        }
    }

    private fun findEocd(raf: RandomAccessFile): Pair<Long, ByteArray> {
        val fileLen = raf.length()
        val searchStart = maxOf(0L, fileLen - 22 - 65535)
        for (pos in fileLen - 22 downTo searchStart) {
            raf.seek(pos)
            if (Integer.reverseBytes(raf.readInt()) == 0x06054b50) {
                raf.seek(pos + 20)
                val commentLen = raf.readUnsignedShort()
                if (pos + 22 + commentLen == fileLen) {
                    val eocd = ByteArray(22 + commentLen)
                    raf.seek(pos)
                    raf.readFully(eocd)
                    return Pair(pos, eocd)
                }
            }
        }
        throw IllegalArgumentException("EOCD record not found in APK")
    }

    private fun computeContentDigest(
        beforeCd: ByteArray,
        centralDir: ByteArray,
        eocdBytes: ByteArray
    ): ByteArray {
        val eocdCopy = eocdBytes.copyOf()
        writeUint32LE(eocdCopy, 16, 0L)

        val md = MessageDigest.getInstance("SHA-256")

        fun writeChunk(data: ByteArray) {
            md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            md.update(data)
        }

        writeChunk(beforeCd)
        writeChunk(centralDir)
        writeChunk(eocdCopy)

        return md.digest()
    }

    private fun buildV2SignedData(digest: ByteArray, cert: X509Certificate): ByteArray {
        val certDer = cert.encoded
        val bb = ByteBuffer.allocate(4 + 4 + 32 + 4 + 4 + certDer.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(1)                      // digest count
        bb.putInt(0x0201)                 // SHA-256 content digest algorithm
        bb.put(digest)                    // 32 bytes
        bb.putInt(1)                      // certificate count
        bb.putInt(certDer.size)
        bb.put(certDer)
        bb.putInt(0)                      // attribute count
        return bb.array()
    }

    private fun signV2SignedData(signedData: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signedData)
        return sig.sign()
    }

    private fun buildV2SignerBlock(
        signedData: ByteArray,
        signature: ByteArray,
        cert: X509Certificate
    ): ByteArray {
        val pubKey = cert.publicKey.encoded
        val bb = ByteBuffer.allocate(
            4 + signedData.size +
            4 +
            4 + 4 + signature.size +
            4 + pubKey.size
        ).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(signedData.size)
        bb.put(signedData)
        bb.putInt(1)                      // signature count
        bb.putInt(0x0101)                 // RSA PKCS1v1.5 with SHA-256
        bb.putInt(signature.size)
        bb.put(signature)
        bb.putInt(pubKey.size)
        bb.put(pubKey)
        return bb.array()
    }

    private fun buildApkSigningBlock(signerBlock: ByteArray): ByteArray {
        val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
        val pairOverhead = 4L + 8L
        val pairSize = pairOverhead + signerBlock.size.toLong()
        val pairsSize = pairSize
        val trailingSize = 8L + 16L
        val totalSize = 8L + pairsSize + trailingSize
        val blockSizeField = totalSize - 8L

        val bb = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(blockSizeField)
        bb.putInt(0x7109871a)              // v2 signer ID
        bb.putLong(signerBlock.size.toLong())
        bb.put(signerBlock)
        bb.putLong(blockSizeField)
        bb.put(magic)
        return bb.array()
    }

    private fun readUint32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset + 3].toLong() and 0xff) shl 24) or
               ((data[offset + 2].toLong() and 0xff) shl 16) or
               ((data[offset + 1].toLong() and 0xff) shl 8) or
               (data[offset].toLong() and 0xff)
    }

    private fun writeUint32LE(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value shr 8) and 0xff).toByte()
        data[offset + 2] = ((value shr 16) and 0xff).toByte()
        data[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
}
