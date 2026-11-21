package app.filesend.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Partage par QR code hors reseau local (relais chiffre E2E AES-256-GCM format FSE1,
 * identique bit-a-bit a windows/core/src/relay.rs) + lien navigateur + serveur Wi-Fi local ephemere.
 */
object Relay {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val CHUNK_PLAIN = 1024 * 1024 // 1 Mio
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16

    data class QrShare(
        val uri: String,
        val mode: String,
        val name: String,
        val size: Long,
        val expires: String,
    )

    data class ParsedUri(
        val url: String,
        val key: ByteArray?,
        val name: String,
        val size: Long,
        val isZip: Boolean,
    )

    private fun chunkNonce(base: ByteArray, index: Long): ByteArray {
        val n = base.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            n[4 + i] = (n[4 + i].toInt() xor ((index ushr shift) and 0xFFL).toInt()).toByte()
        }
        return n
    }

    /** Chiffre `src` dans `dst` au format FSE1 (AES-256-GCM en blocs de 1 Mio). Retourne la cle 32 octets. */
    fun encryptE2e(src: InputStream, dst: OutputStream): ByteArray {
        val rng = SecureRandom()
        val key = ByteArray(32).also { rng.nextBytes(it) }
        val baseNonce = ByteArray(12).also { rng.nextBytes(it) }
        val keySpec = SecretKeySpec(key, "AES")

        val out = DataOutputStream(BufferedOutputStream(dst, 256 * 1024))
        out.write(MAGIC)
        out.write(baseNonce)

        val buf = ByteArray(CHUNK_PLAIN)
        var idx = 0L
        while (true) {
            var readTotal = 0
            while (readTotal < CHUNK_PLAIN) {
                val n = src.read(buf, readTotal, CHUNK_PLAIN - readTotal)
                if (n < 0) break
                readTotal += n
            }
            if (readTotal == 0 && idx > 0) break
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, chunkNonce(baseNonce, idx)))
            val sealed = cipher.doFinal(buf, 0, readTotal)
            out.writeInt(sealed.size)
            out.write(sealed)
            idx++
            if (readTotal < CHUNK_PLAIN) break
        }
        out.flush()
        return key
    }

    /** Dechiffre un flux FSE1 (AES-256-GCM) dans `dst`. */
    fun decryptE2e(src: InputStream, dst: OutputStream, key: ByteArray) {
        val keySpec = SecretKeySpec(key, "AES")
        val ins = DataInputStream(BufferedInputStream(src, 256 * 1024))
        val magic = ByteArray(4)
        ins.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Format chiffré non reconnu")
        val baseNonce = ByteArray(12)
        ins.readFully(baseNonce)

        val out = BufferedOutputStream(dst, 256 * 1024)
        var idx = 0L
        while (true) {
            val clen = try {
                ins.readInt()
            } catch (_: EOFException) {
                break
            }
            if (clen < TAG_BYTES || clen > CHUNK_PLAIN + TAG_BYTES + 64) {
                throw IOException("Bloc chiffré corrompu")
            }
            val chunk = ByteArray(clen)
            ins.readFully(chunk)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, chunkNonce(baseNonce, idx)))
            val plain = cipher.doFinal(chunk)
            out.write(plain)
            idx++
        }
        out.flush()
    }

    fun buildUri(url: String, key: ByteArray?, name: String, size: Long, isZip: Boolean): String {
        val sb = StringBuilder("filesend://r?u=").append(URLEncoder.encode(url, "UTF-8"))
        if (key != null) {
            val k = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            sb.append("&k=").append(k)
        }
        sb.append("&n=").append(URLEncoder.encode(name, "UTF-8"))
        sb.append("&s=").append(size)
        if (isZip) sb.append("&z=1")
        return sb.toString()
    }

    fun parseUri(raw: String): ParsedUri {
        val s = raw.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val u = URI(s)
            val name = u.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "fichier_recu"
            return ParsedUri(s, null, name, 0L, false)
        }
        if (!s.startsWith("filesend://")) throw IllegalArgumentException("Lien FileSend invalide")
        val qIdx = s.indexOf('?')
        if (qIdx < 0) throw IllegalArgumentException("Paramètres manquants")
        val params = LinkedHashMap<String, String>()
        for (pair in s.substring(qIdx + 1).split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                params[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }
        val url = params["u"] ?: throw IllegalArgumentException("URL manquante")
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw IllegalArgumentException("URL invalide")
        }
        val key = params["k"]?.let {
            val b = Base64.getUrlDecoder().decode(it)
            if (b.size != 32) throw IllegalArgumentException("Clé invalide")
            b
        }
        val name = params["n"]?.takeIf { it.isNotBlank() } ?: "fichier_recu"
        val size = params["s"]?.toLongOrNull() ?: 0L
        val isZip = params["z"] == "1" || params["z"] == "true"
        return ParsedUri(url, key, name, size, isZip)
    }

    private fun packZip(sources: List<SendSource>, dst: File): Long {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(dst), 256 * 1024)).use { zos ->
            val buf = ByteArray(128 * 1024)
            for (s in sources) {
                zos.putNextEntry(ZipEntry(s.relativePath))
                s.open().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                    }
                }
                zos.closeEntry()
            }
        }
        return dst.length()
    }

    private fun uploadMultipart(endpoint: String, fields: Map<String, String>, fileField: String, fileName: String, file: File): String {
        val boundary = "----FileSend${UUID.randomUUID().toString().replace("-", "")}"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(64 * 1024)
        }
        try {
            BufferedOutputStream(conn.outputStream, 64 * 1024).use { out ->
                for ((k, v) in fields) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
                }
                val safeName = fileName.replace("\"", "_")
                out.write(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()
                )
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.write("\r\n--$boundary--\r\n".toByteArray())
                out.flush()
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes()?.toString(Charsets.UTF_8)?.trim().orEmpty()
            if (code !in 200..299) throw IOException("Erreur relais HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Partage des fichiers par QR code hors reseau local (chiffre E2E ou lien navigateur). */
    fun shareViaRelay(
        sources: List<SendSource>,
        cacheDir: File,
        encrypted: Boolean,
        expiry: String = "1h",
        customRelay: String? = null,
    ): QrShare {
        if (sources.isEmpty()) throw IOException("Aucun fichier sélectionné")
        val tmpDir = File(cacheDir, "qr-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val isZip = sources.size > 1
            val origName = if (!isZip) sources[0].relativePath.substringAfterLast('/') else "FileSend_${sources.size}_elements.zip"
            val payloadFile = File(tmpDir, "payload.bin")
            val origSize = if (!isZip) {
                FileOutputStream(payloadFile).use { out -> sources[0].open().use { it.copyTo(out, 128 * 1024) } }
                payloadFile.length()
            } else {
                packZip(sources, payloadFile)
            }

            val uploadFile: File
            val uploadName: String
            val key: ByteArray?
            if (encrypted) {
                uploadFile = File(tmpDir, "filesend.fse")
                uploadName = "filesend.fse"
                key = FileInputStream(payloadFile).use { ins ->
                    FileOutputStream(uploadFile).use { out -> encryptE2e(ins, out) }
                }
            } else {
                uploadFile = payloadFile
                uploadName = origName
                key = null
            }

            val exp = if (expiry == "24h" || expiry == "72h") expiry else "1h"
            val remoteUrl = if (!customRelay.isNullOrBlank()) {
                uploadMultipart(customRelay.trim(), emptyMap(), "file", uploadName, uploadFile)
            } else {
                try {
                    val u = uploadMultipart(
                        "https://litterbox.catbox.moe/resources/internals/api.php",
                        mapOf("reqtype" to "fileupload", "time" to exp),
                        "fileToUpload",
                        uploadName,
                        uploadFile,
                    )
                    if (!u.startsWith("https://")) throw IOException("Réponse relais invalide")
                    u
                } catch (_: Exception) {
                    val json = uploadMultipart("https://tmpfiles.org/api/v1/upload", emptyMap(), "file", uploadName, uploadFile)
                    val m = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)
                        ?: throw IOException("Relais temporairement indisponible")
                    m.groupValues[1].replace("\\/", "/").replace("http://", "https://").replaceFirst("tmpfiles.org/", "tmpfiles.org/dl/")
                }
            }

            val uri = if (key != null) buildUri(remoteUrl, key, origName, origSize, isZip) else remoteUrl
            val mode = if (key != null) "relay-e2e" else "relay-direct"
            val expLabel = when (exp) {
                "24h" -> "24 h"
                "72h" -> "72 h"
                else -> "1 h"
            }
            return QrShare(uri, mode, origName, origSize, expLabel)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Telecharge, dechiffre (si E2E) et enregistre un lien `filesend://r?...` ou `https://...` dans `sink`. */
    fun downloadFromUri(rawUri: String, sink: ReceiveSink, cacheDir: File): List<String> {
        val parsed = parseUri(rawUri)
        val tmpDir = File(cacheDir, "dl-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val rawFile = File(tmpDir, "raw.bin")
            val conn = (URL(parsed.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 300_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) {
                    throw IOException("Lien expiré ou indisponible (HTTP ${conn.responseCode})")
                }
                BufferedOutputStream(FileOutputStream(rawFile), 256 * 1024).use { out ->
                    conn.inputStream.use { ins -> ins.copyTo(out, 128 * 1024) }
                }
            } finally {
                conn.disconnect()
            }

            val plainFile = if (parsed.key != null) {
                val dec = File(tmpDir, "plain.bin")
                FileInputStream(rawFile).use { ins ->
                    FileOutputStream(dec).use { out -> decryptE2e(ins, out, parsed.key) }
                }
                dec
            } else {
                rawFile
            }

            if (parsed.isZip) {
                val topMap = LinkedHashMap<String, String>()
                val buf = ByteArray(128 * 1024)
                ZipInputStream(BufferedInputStream(FileInputStream(plainFile), 256 * 1024)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val comps = sanitizeRelativePath(entry.name)
                        val top = topMap.getOrPut(comps[0]) { sink.uniqueTopName(comps[0]) }
                        val mapped = listOf(top) + comps.drop(1)
                        val partial = sink.create(mapped)
                        try {
                            BufferedOutputStream(partial.out, 128 * 1024).use { out ->
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            partial.commit()
                        } catch (e: Exception) {
                            partial.abort()
                            throw e
                        }
                    }
                }
                return topMap.values.toList()
            } else {
                val safe = runCatching { sanitizeRelativePath(parsed.name).last() }.getOrDefault("fichier_recu")
                val top = sink.uniqueTopName(safe)
                val partial = sink.create(listOf(top))
                try {
                    FileInputStream(plainFile).use { ins ->
                        BufferedOutputStream(partial.out, 256 * 1024).use { out ->
                            ins.copyTo(out, 128 * 1024)
                        }
                    }
                    partial.commit()
                } catch (e: Exception) {
                    partial.abort()
                    throw e
                }
                return listOf(top)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** Serveur HTTP local ephemere sur le Wi-Fi pour partager par QR sans Internet. */
    class LocalQrServer private constructor(
        private val server: ServerSocket,
        private val tmpDir: File,
        val share: QrShare,
    ) {
        fun stop() {
            runCatching { server.close() }
            runCatching { tmpDir.deleteRecursively() }
        }

        companion object {
            fun start(sources: List<SendSource>, cacheDir: File): LocalQrServer {
                val ip = primaryAddress()?.hostAddress ?: throw IOException("Aucun Wi-Fi local détecté")
                val tmpDir = File(cacheDir, "lanqr-${UUID.randomUUID()}").apply { mkdirs() }
                val isZip = sources.size > 1
                val name = if (!isZip) sources[0].relativePath.substringAfterLast('/') else "FileSend_${sources.size}_elements.zip"
                val file = File(tmpDir, "payload.bin")
                val size = if (!isZip) {
                    FileOutputStream(file).use { out -> sources[0].open().use { it.copyTo(out, 128 * 1024) } }
                    file.length()
                } else {
                    packZip(sources, file)
                }
                val token = UUID.randomUUID().toString().replace("-", "")
                val ss = ServerSocket(0)
                val port = ss.localPort
                val uri = "http://$ip:$port/dl/$token"

                Thread({
                    while (!ss.isClosed) {
                        val sock = try { ss.accept() } catch (_: IOException) { break }
                        Thread({
                            sock.use { s ->
                                runCatching {
                                    s.soTimeout = 15_000
                                    val firstLine = s.getInputStream().bufferedReader().readLine() ?: return@runCatching
                                    val out = BufferedOutputStream(s.getOutputStream(), 128 * 1024)
                                    if (!firstLine.startsWith("GET /dl/$token")) {
                                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                        out.flush()
                                        return@runCatching
                                    }
                                    val safeName = name.replace("\"", "_")
                                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $size\r\nContent-Disposition: attachment; filename=\"$safeName\"\r\nConnection: close\r\n\r\n"
                                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                                    FileInputStream(file).use { fis -> fis.copyTo(out, 128 * 1024) }
                                    out.flush()
                                }
                            }
                        }, "filesend-lanqr-tx").apply { isDaemon = true }.start()
                    }
                }, "filesend-lanqr").apply { isDaemon = true }.start()

                return LocalQrServer(ss, tmpDir, QrShare(uri, "lan", name, size, "Wi-Fi local"))
            }
        }
    }
}
