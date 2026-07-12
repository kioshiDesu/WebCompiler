package com.webcompiler.app.signing

import com.android.apksig.ApkSigner
import com.android.apksig.SignerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

class ApkSigner {

    fun sign(inputApk: File, outputApk: File, keystoreFile: File? = null) {
        val ks = keystoreFile ?: File(inputApk.parentFile, "debug.keystore")

        val keyStore: KeyStore
        val privateKey: PrivateKey
        val certificate: X509Certificate

        if (ks.exists()) {
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(ks.inputStream(), "android".toCharArray())
            }
            val alias = keyStore.aliases().asSequence().firstOrNull()
                ?: throw IllegalStateException("Keystore has no entries")
            privateKey = keyStore.getKey(alias, "android".toCharArray()) as PrivateKey
            certificate = keyStore.getCertificate(alias) as X509Certificate
        } else {
            val keyPair = generateKeyPair()
            privateKey = keyPair.private
            certificate = generateCertificate(keyPair)

            keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("debug", privateKey, "android".toCharArray(), arrayOf(certificate))
            }
            ks.parentFile?.mkdirs()
            ks.outputStream().use { keyStore.store(it, "android".toCharArray()) }
        }

        val signerConfig = SignerConfig.Builder("CERT", privateKey, listOf(certificate)).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(false)
            .setV3SigningEnabled(false)
            .build()
            .sign()
    }

    private fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        val dn = X500Principal("CN=Debug, OU=Dev, O=WebCompiler, L=City, ST=State, C=US")
        val serial = BigInteger.ONE
        val notBefore = Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)
        val notAfter = Date(System.currentTimeMillis() + 10000L * 24 * 60 * 60 * 1000)

        val rsaPub = keyPair.public as RSAPublicKey

        val pubKeyInfo = DerWriter().apply {
            writeSequence {
                writeSequence {
                    writeOid("1.2.840.113549.1.1.1")
                    writeNull()
                }
                writeBitString(
                    DerWriter().apply {
                        writeSequence {
                            writeInteger(rsaPub.modulus)
                            writeInteger(rsaPub.publicExponent)
                        }
                    }.toByteArray()
                )
            }
        }.toByteArray()

        val tbsCert = DerWriter().apply {
            writeSequence {
                writeContextSpecificExplicit(0) { writeInteger(BigInteger.valueOf(2)) }
                writeInteger(serial)
                writeSequence {
                    writeOid("1.2.840.113549.1.1.11")
                    writeNull()
                }
                writeRaw(dn.encoded)
                writeSequence {
                    writeUtcTime(notBefore)
                    writeUtcTime(notAfter)
                }
                writeRaw(dn.encoded)
                writeRaw(pubKeyInfo)
            }
        }.toByteArray()

        val sig = Signature.getInstance("SHA256WithRSA").apply {
            initSign(keyPair.private)
            update(tbsCert)
        }.sign()

        val certDer = DerWriter().apply {
            writeSequence {
                writeRaw(tbsCert)
                writeSequence {
                    writeOid("1.2.840.113549.1.1.11")
                    writeNull()
                }
                writeBitString(sig)
            }
        }.toByteArray()

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certDer.inputStream()) as X509Certificate
    }

    private class DerWriter {
        private val out = ByteArrayOutputStream()

        fun write(tag: Int, bytes: ByteArray) {
            out.write(tag)
            writeLength(bytes.size)
            out.write(bytes)
        }

        fun writeSequence(block: DerWriter.() -> Unit) {
            val nested = DerWriter()
            nested.block()
            write(0x30, nested.toByteArray())
        }

        fun writeSequence(data: ByteArray) {
            write(0x30, data)
        }

        fun writeSet(block: DerWriter.() -> Unit) {
            val nested = DerWriter()
            nested.block()
            write(0x31, nested.toByteArray())
        }

        fun writeInteger(value: BigInteger) {
            write(0x02, value.toByteArray())
        }

        fun writeOctetString(bytes: ByteArray) {
            write(0x04, bytes)
        }

        fun writeNull() {
            out.write(0x05)
            out.write(0x00)
        }

        fun writeOid(oid: String) {
            val parts = oid.split(".").map { it.toInt() }
            val buf = ByteArrayOutputStream()
            buf.write(40 * parts[0] + parts[1])
            for (i in 2 until parts.size) {
                writeBase128(buf, parts[i])
            }
            write(0x06, buf.toByteArray())
        }

        fun writeBitString(bytes: ByteArray) {
            val buf = ByteArrayOutputStream()
            buf.write(0x00)
            buf.write(bytes)
            write(0x03, buf.toByteArray())
        }

        fun writeUtcTime(date: Date) {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            write(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
        }

        fun writeRaw(bytes: ByteArray) {
            out.write(bytes)
        }

        fun writeContextSpecificExplicit(tag: Int, block: DerWriter.() -> Unit) {
            val nested = DerWriter()
            nested.block()
            write(0xA0 + tag, nested.toByteArray())
        }

        private fun writeLength(length: Int) {
            if (length < 128) {
                out.write(length)
            } else {
                val bytes = ByteArrayOutputStream()
                var len = length
                while (len > 0) {
                    bytes.write(len and 0xFF)
                    len = len shr 8
                }
                val lenBytes = bytes.toByteArray().reversedArray()
                out.write(0x80 or lenBytes.size)
                out.write(lenBytes)
            }
        }

        private fun writeBase128(out: ByteArrayOutputStream, value: Int) {
            val parts = mutableListOf<Int>()
            var v = value
            parts.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                parts.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            for (b in parts.reversed()) {
                out.write(b)
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
