package com.webcompiler.app.signing

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

class ApkSigner {

    private val digestAlgorithm = "SHA-256"
    private val signatureAlgorithm = "SHA256WithRSA"

    fun sign(inputApk: File, outputApk: File, keystoreFile: File? = null) {
        val ks = keystoreFile ?: File(inputApk.parentFile, "debug.keystore")

        val keyStore: KeyStore
        val privateKey: java.security.PrivateKey
        val certificate: X509Certificate

        if (ks.exists()) {
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(ks.inputStream(), "android".toCharArray())
            }
            val alias = if (keyStore.aliases().hasMoreElements()) keyStore.aliases().nextElement() else "debug"
            privateKey = keyStore.getKey(alias, "android".toCharArray()) as java.security.PrivateKey
            certificate = keyStore.getCertificate(alias) as X509Certificate
        } else {
            val keyPair = generateKeyPair()
            privateKey = keyPair.private
            certificate = generateSelfSignedCertificate(keyPair)

            keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("debug", privateKey, "android".toCharArray(), arrayOf(certificate))
            }
            ks.parentFile?.mkdirs()
            keyStore.store(ks.outputStream(), "android".toCharArray())
        }

        val manifest = createManifest(inputApk)
        val signatureFile = createSignatureFile(manifest, privateKey, certificate)
        val signatureBlock = createSignatureBlock(signatureFile, privateKey, certificate)

        writeSignedApk(inputApk, outputApk, manifest, signatureFile, signatureBlock)
    }

    private fun generateKeyPair(): java.security.KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    private fun generateSelfSignedCertificate(keyPair: java.security.KeyPair): X509Certificate {
        val dn = X500Principal("CN=Debug, OU=Dev, O=WebCompiler, L=City, ST=State, C=US")
        val notBefore = Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 10000L * 24 * 60 * 60 * 1000)

        val builder = JcaX509v3CertificateBuilder(
            dn, BigInteger.ONE, notBefore, notAfter, dn, keyPair.public
        )
        val sigGen = JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(sigGen))
    }

    private fun createManifest(apkFile: File): ByteArray {
        val lines = mutableListOf<String>()
        lines.add("Manifest-Version: 1.0")
        lines.add("Created-By: WebCompiler")
        lines.add("")

        val md = MessageDigest.getInstance(digestAlgorithm)

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

    private fun createSignatureFile(
        manifest: ByteArray,
        privateKey: java.security.PrivateKey,
        certificate: X509Certificate
    ): ByteArray {
        val md = MessageDigest.getInstance(digestAlgorithm)
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
        signatureFile: ByteArray,
        privateKey: java.security.PrivateKey,
        certificate: X509Certificate
    ): ByteArray {
        val sigGen = CMSSignedDataGenerator()

        val signerBuilder = JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().build()
        )
        val contentSigner = JcaContentSignerBuilder(signatureAlgorithm).build(privateKey)
        sigGen.addSignerInfoGenerator(signerBuilder.build(contentSigner, certificate))

        val certList = mutableListOf<org.bouncycastle.cert.X509CertificateHolder>()
        certList.add(org.bouncycastle.cert.X509CertificateHolder(certificate.encoded))
        sigGen.addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(certList))

        val cmsData = object : CMSTypedData {
            override fun getContentType() = PKCSObjectIdentifiers.data
            override fun write(outputStream: java.io.OutputStream) {
                outputStream.write(signatureFile)
            }
            override fun getContent() = signatureFile
        }

        return sigGen.generate(cmsData, false).encoded
    }

    private fun writeSignedApk(
        input: File, output: File,
        manifest: ByteArray, sf: ByteArray, signatureBlock: ByteArray
    ) {
        val newMetaInf = mapOf(
            "META-INF/MANIFEST.MF" to manifest,
            "META-INF/CERT.SF" to sf,
            "META-INF/CERT.RSA" to signatureBlock
        )

        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream()).use { zos ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .sortedBy { it.name }
                    .toList()

                val written = mutableSetOf<String>()

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
                    ze.size = data.size.toLong()
                    CRC32().also { it.update(data) }.let { ze.crc = it.value }
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                    written.add(name)
                }

                for ((name, data) in newMetaInf) {
                    if (name !in written) {
                        val ze = ZipEntry(name)
                        ze.size = data.size.toLong()
                        CRC32().also { it.update(data) }.let { ze.crc = it.value }
                        zos.putNextEntry(ze)
                        zos.write(data)
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}
