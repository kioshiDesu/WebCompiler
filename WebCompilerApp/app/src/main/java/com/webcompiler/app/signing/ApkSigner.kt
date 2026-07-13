package com.webcompiler.app.signing

import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERUTCTime
import org.bouncycastle.asn1.DLSequence
import org.bouncycastle.asn1.DLSet
import org.bouncycastle.asn1.DERTaggedObject

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

        return lines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)
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

        return lines.joinToString("\r\n").toByteArray(Charsets.US_ASCII)
    }

    private fun createSignatureBlock(
        sigFile: ByteArray,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): ByteArray {
        val sigFileDigest = MessageDigest.getInstance("SHA-256").digest(sigFile)

        val attrContentType = DLSequence(
            ASN1ObjectIdentifier("1.2.840.113549.1.9.3"),
            DLSet(ASN1ObjectIdentifier("1.2.840.113549.1.7.1"))
        )
        val attrMessageDigest = DLSequence(
            ASN1ObjectIdentifier("1.2.840.113549.1.9.4"),
            DLSet(DEROctetString(sigFileDigest))
        )
        val attrSigningTime = DLSequence(
            ASN1ObjectIdentifier("1.2.840.113549.1.9.5"),
            DLSet(DERUTCTime(Date()))
        )
        val signedAttrs = DLSet(attrContentType, attrMessageDigest, attrSigningTime)

        val signedAttrsDer = signedAttrs.getEncoded("DER")
        val sig = Signature.getInstance("SHA256WithRSA").apply {
            initSign(privateKey)
            update(signedAttrsDer)
        }.sign()

        val issuerAndSn = DLSequence(
            DLSequence(certificate.issuerX500Principal.encoded),
            ASN1Integer(certificate.serialNumber)
        )

        val signerInfo = DLSequence(
            ASN1Integer(1),
            issuerAndSn,
            DLSequence(
                ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"),
                DERNull.INSTANCE
            ),
            DERTaggedObject(false, 0, signedAttrs),
            DLSequence(
                ASN1ObjectIdentifier("1.2.840.113549.1.1.11"),
                DERNull.INSTANCE
            ),
            DEROctetString(sig)
        )

        val certSeq = ASN1Primitive.fromByteArray(certificate.encoded) as ASN1Sequence
        val certSet = DLSet(certSeq)

        val innerContentInfo = DLSequence(
            ASN1ObjectIdentifier("1.2.840.113549.1.7.1")
        )

        val signedData = DLSequence(
            ASN1Integer(1),
            DLSet(
                DLSequence(
                    ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"),
                    DERNull.INSTANCE
                )
            ),
            innerContentInfo,
            DERTaggedObject(false, 0, certSet),
            DLSet(signerInfo)
        )

        val contentInfo = DLSequence(
            ASN1ObjectIdentifier("1.2.840.113549.1.7.2"),
            DERTaggedObject(true, 0, signedData)
        )

        return contentInfo.getEncoded("DER")
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
