package app.filesend.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import java.net.Socket
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Identite de l'appareil : certificat TLS auto-signe (ECDSA P-256) et empreinte
 * (SHA-256 du certificat DER, comme cote Windows). Aucune bibliotheque tierce :
 * le certificat est encode a la main (DER) pour fonctionner a l'identique sur
 * Android et sur la JVM des tests.
 */
class Identity(val certDer: ByteArray, private val keyPkcs8: ByteArray) {
    val fingerprint: String = hex(MessageDigest.getInstance("SHA-256").digest(certDer))

    val certificate: X509Certificate
        get() = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

    private val privateKey: PrivateKey
        get() = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyPkcs8))

    /** Contexte TLS serveur presentant ce certificat. */
    fun serverSslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("filesend", privateKey, CharArray(0), arrayOf(certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    /** Ecrit le certificat et la cle dans `dir` (dossier prive de l'application). */
    fun save(dir: File) {
        dir.mkdirs()
        File(dir, CERT_FILE).writeBytes(certDer)
        File(dir, KEY_FILE).writeBytes(keyPkcs8)
    }

    companion object {
        private const val CERT_FILE = "cert.der"
        private const val KEY_FILE = "key.pk8"

        fun generate(): Identity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0C, "FileSend".toByteArray()))))
            val sigAlg = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)))
            val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F or 0x01).toByte() }
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val notBefore = utcTime(now)
            val end = (now.clone() as Calendar).apply { add(Calendar.YEAR, 10) }
            val tbs = der(
                0x30,
                der(0xA0, der(0x02, byteArrayOf(2))) + // version v3
                    der(0x02, serial) +
                    sigAlg + name +
                    der(0x30, notBefore + utcTime(end)) +
                    name + kp.public.encoded,
            )
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(kp.private)
                update(tbs)
                sign()
            }
            val cert = der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0) + sig))
            return Identity(cert, kp.private.encoded)
        }

        /** Charge l'identite stockee dans `dir`, ou la cree au premier lancement. */
        fun loadOrCreate(dir: File): Identity {
            val c = File(dir, CERT_FILE)
            val k = File(dir, KEY_FILE)
            if (c.isFile && k.isFile) {
                runCatching { Identity(c.readBytes(), k.readBytes()).also { it.certificate; it.privateKey } }
                    .getOrNull()?.let { return it }
            }
            return generate().also { it.save(dir) }
        }

        internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        private fun len(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }

        private fun der(tag: Int, body: ByteArray): ByteArray =
            ByteArrayOutputStream().apply { write(tag); write(len(body.size)); write(body) }.toByteArray()

        private fun utcTime(c: Calendar): ByteArray {
            val s = "%02d%02d%02d%02d%02d%02dZ".format(
                c.get(Calendar.YEAR) % 100, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
            )
            return der(0x17, s.toByteArray())
        }
    }
}

/**
 * Contexte TLS client acceptant les certificats auto-signes des autres appareils.
 * Le chiffrement est assure, l'identite non (voir TECHNICAL_ARCHITECTURE section 7).
 */
fun trustAllSslContext(): SSLContext {
    // X509ExtendedTrustManager : evite les controles de nom ajoutes par le JDK aux gestionnaires simples.
    val tm = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
}