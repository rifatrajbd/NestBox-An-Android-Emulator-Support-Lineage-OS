package app.nestbox.adb

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Nestbox's ADB client identity, used to talk to this phone's own adbd over Wireless debugging.
 * The RSA key and self-signed certificate live in filesDir/adb and are reused after pairing.
 */
class NestboxAdb private constructor(context: Context) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "private.key")
        val certFile = File(dir, "cert.pem")
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            val pair = gen.generateKeyPair()
            privateKey = pair.private
            certificate = selfSigned(pair.public, pair.private)
            keyFile.writeBytes(privateKey.encoded)
            certFile.outputStream().use { os ->
                os.write(X509Factory.BEGIN_CERT.toByteArray())
                os.write('\n'.code)
                BASE64Encoder().encode(certificate.encoded, os)
                os.write('\n'.code)
                os.write(X509Factory.END_CERT.toByteArray())
            }
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey
    override fun getCertificate(): Certificate = certificate
    override fun getDeviceName(): String = "Nestbox"

    private fun selfSigned(publicKey: java.security.PublicKey, key: PrivateKey): Certificate {
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 3600 * 1000)
        val name = X500Name("CN=Nestbox")
        val ext = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random.nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(name))
            set("extensions", ext)
        }
        return X509CertImpl(info).apply { sign(key, algorithm) }
    }

    companion object {
        @Volatile private var instance: NestboxAdb? = null

        fun get(context: Context): NestboxAdb =
            instance ?: synchronized(this) { instance ?: NestboxAdb(context.applicationContext).also { instance = it } }
    }
}
