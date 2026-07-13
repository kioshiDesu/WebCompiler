package com.webcompiler.app.signing

import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
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
        writeSignedApk(inputApk, outputApk, manifest, sigFile, sigBlock)
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

                for ((name, data) in newMetaInf) {
                    val ze = ZipEntry(name)
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
        }
    }
}
