package app.filesend.core

import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

/** Un fichier a envoyer. `open` est appele une fois pour le hachage eventuel et une fois pour l'envoi. */
class SendSource(val relativePath: String, val size: Long, val open: () -> InputStream)

class SendOptions(
    val info: DeviceInfo,
    /** Les fichiers jusqu'a cette taille ont leur sha256 envoye dans la liste. */
    val hashUpTo: Long = 64L * 1024 * 1024,
    val idleTimeoutMs: Long = 10_000,
    val decisionTimeoutMs: Long = 60_000,
)

class SendException(val reason: FailReason, cause: Throwable? = null) : Exception(reason.message, cause)
class SendCancelled : Exception("Annulé")

typealias Progress = (done: Long, total: Long) -> Unit

/** Liste recursivement fichiers et dossiers (liens symboliques ignores), chemins relatifs avec `/`. */
fun enumerateFiles(roots: List<File>): List<SendSource> {
    val out = ArrayList<SendSource>()
    fun walk(dir: File, rel: String) {
        for (c in (dir.listFiles() ?: throw IOException("illisible")).sortedBy { it.name }) {
            if (Files.isSymbolicLink(c.toPath())) continue
            val r = "$rel/${c.name}"
            if (c.isDirectory) walk(c, r) else if (c.isFile) out += SendSource(r, c.length()) { c.inputStream() }
        }
    }
    for (r in roots) {
        if (r.isDirectory) walk(r, r.name) else out += SendSource(r.name, r.length()) { r.inputStream() }
    }
    return out
}

/**
 * Envoi vers un appareil (meme comportement que windows/core/src/client.rs).
 * Bloquant : a appeler hors du fil principal. `cancelled` interrompt l'envoi.
 */
fun send(
    target: InetSocketAddress,
    sources: List<SendSource>,
    opts: SendOptions,
    progress: Progress? = null,
    cancelled: AtomicBoolean = AtomicBoolean(false),
) {
    if (sources.isEmpty()) throw SendException(FailReason.Interrupted)
    val ssl = trustAllSslContext().socketFactory
    val base = "https://${target.hostString}:${target.port}$API_PREFIX"

    fun open(path: String, query: Map<String, String> = emptyMap()): HttpsURLConnection {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        return (URL(base + path + q).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl
            setHostnameVerifier { _, _ -> true }
            connectTimeout = 5_000
            useCaches = false
            requestMethod = "POST"
        }
    }

    fun cancelRemote(sid: String) {
        runCatching {
            open("/cancel", mapOf("sessionId" to sid)).apply { connectTimeout = 2_000; readTimeout = 2_000 }.responseCode
        }
    }

    // Surveille l'annulation et l'inactivite : ferme la connexion bloquee.
    class Watch(val conn: HttpsURLConnection, val counter: AtomicLong?, val expected: Long, val idleMs: Long) {
        @Volatile var stalled = false
        @Volatile var stop = false
        val thread = Thread({
            var last = counter?.get() ?: 0L
            var since = System.nanoTime()
            while (!stop) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { return@Thread }
                if (cancelled.get()) { conn.disconnect(); return@Thread }
                val c = counter ?: continue
                val now = c.get()
                if (now != last) { last = now; since = System.nanoTime() }
                else {
                    val limit = if (now < expected) idleMs else idleMs * 3
                    if ((System.nanoTime() - since) / 1_000_000 >= limit) { stalled = true; conn.disconnect(); return@Thread }
                }
            }
        }, "filesend-watch").apply { isDaemon = true; start() }
        fun close() { stop = true; thread.interrupt() }
    }

    fun reason(e: IOException) = when (e) {
        is ConnectException, is UnknownHostException, is NoRouteToHostException -> FailReason.NotFound
        is SocketTimeoutException -> if (e.message?.contains("connect", true) == true) FailReason.NotFound else FailReason.Interrupted
        else -> FailReason.Interrupted
    }

    // Liste des fichiers, sha256 des petits fichiers.
    val total = sources.sumOf { it.size }
    val entries = LinkedHashMap<String, FileEntry>()
    sources.forEachIndexed { i, s ->
        val sha = if (s.size <= opts.hashUpTo) {
            val d = MessageDigest.getInstance("SHA-256")
            s.open().use { ins ->
                val b = ByteArray(64 * 1024)
                while (true) { val n = ins.read(b); if (n < 0) break; d.update(b, 0, n) }
            }
            Identity.hex(d.digest())
        } else null
        entries["f$i"] = FileEntry("f$i", s.relativePath, s.size, sha)
    }

    // prepare-upload : attend la decision de l'utilisateur.
    val payload = ProtocolJson.encodeToString(PrepareUploadRequest(opts.info, entries)).toByteArray()
    val prep: PrepareUploadResponse
    val conn = open("/prepare-upload").apply {
        doOutput = true
        setFixedLengthStreamingMode(payload.size)
        setRequestProperty("Content-Type", "application/json")
        readTimeout = (opts.decisionTimeoutMs + 10_000).toInt()
    }
    val w = Watch(conn, null, 0, 0)
    try {
        conn.outputStream.use { it.write(payload) }
        when (conn.responseCode) {
            200 -> prep = ProtocolJson.decodeFromString(String(conn.inputStream.readBytes(), Charsets.UTF_8))
            403 -> throw SendException(FailReason.Refused)
            else -> throw SendException(FailReason.Interrupted)
        }
    } catch (e: IOException) {
        if (cancelled.get()) throw SendCancelled()
        throw SendException(reason(e), e)
    } finally {
        w.close()
        conn.disconnect()
    }

    // upload : un fichier a la fois, en flux.
    var baseDone = 0L
    for ((i, src) in sources.withIndex()) {
        if (cancelled.get()) { cancelRemote(prep.sessionId); throw SendCancelled() }
        val id = "f$i"
        val sent = AtomicLong(0)
        val c = open("/upload", mapOf("sessionId" to prep.sessionId, "fileId" to id, "token" to (prep.files[id] ?: throw SendException(FailReason.Interrupted)))).apply {
            doOutput = true
            setFixedLengthStreamingMode(src.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            readTimeout = 30_000
        }
        val watch = Watch(c, sent, src.size, opts.idleTimeoutMs)
        try {
            src.open().use { ins ->
                c.outputStream.use { out ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) throw SendCancelled()
                        val n = ins.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        val s = sent.addAndGet(n.toLong())
                        progress?.invoke(baseDone + s, total)
                    }
                }
            }
            val code = c.responseCode
            if (code != 200) throw SendException(if (code == 507) FailReason.DiskFull else FailReason.Interrupted)
        } catch (e: SendCancelled) {
            cancelRemote(prep.sessionId)
            throw e
        } catch (e: SendException) {
            cancelRemote(prep.sessionId)
            throw e
        } catch (e: IOException) {
            cancelRemote(prep.sessionId)
            if (cancelled.get()) throw SendCancelled()
            throw SendException(if (watch.stalled) FailReason.Interrupted else reason(e), e)
        } finally {
            watch.close()
            c.disconnect()
        }
        baseDone += src.size
    }
}
