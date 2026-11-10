package app.filesend.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** Corps limite a Content-Length ; leve EOFException si le client coupe avant la fin. */
    val body: InputStream,
    val contentLength: Long,
)

class HttpResponse(val status: Int, val body: ByteArray = ByteArray(0), val contentType: String? = null)

fun interface HttpHandler {
    fun handle(req: HttpRequest): HttpResponse
}

/**
 * Serveur HTTP/1.1 minimal au-dessus de TLS : ce dont le protocole a besoin et rien de plus.
 * Content-Length obligatoire pour un corps (pas de Transfer-Encoding), connexions persistantes,
 * delais d'inactivite, limites de taille des en-tetes et du nombre de connexions.
 * En-clair : la poignee de main TLS echoue et la connexion est fermee sans reponse (AC-F3-5).
 */
class HttpsServer(
    private val sslContext: SSLContext,
    private val handler: HttpHandler,
    private val idleTimeoutMs: Int,
    maxConnections: Int = 32,
) {
    private var server: SSLServerSocket? = null
    private val permits = Semaphore(maxConnections)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "filesend-http").apply { isDaemon = true } }

    /** Demarre l'ecoute ; retourne le port reel (utile avec le port 0). */
    fun start(port: Int, bind: InetAddress? = null): Int {
        val s = sslContext.serverSocketFactory.createServerSocket(port, 50, bind) as SSLServerSocket
        server = s
        Thread({
            while (!s.isClosed) {
                val sock = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                if (!permits.tryAcquire()) {
                    runCatching { sock.close() }
                    continue
                }
                pool.execute {
                    try {
                        serve(sock)
                    } finally {
                        permits.release()
                    }
                }
            }
        }, "filesend-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun serve(sock: java.net.Socket) {
        try {
            sock.soTimeout = idleTimeoutMs
            val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
            val output = sock.getOutputStream()
            while (true) {
                val req = try {
                    readRequest(input)
                } catch (e: HttpError) {
                    write(output, HttpResponse(e.status), keepAlive = false)
                    return
                } ?: return
                val resp = try {
                    handler.handle(req)
                } catch (_: SocketTimeoutException) {
                    return
                } catch (_: IOException) {
                    return
                } catch (_: Exception) {
                    HttpResponse(500)
                }
                val keep = (req.headers["connection"]?.lowercase() != "close") && drained(req.body)
                write(output, resp, keep)
                if (!keep) return
            }
        } catch (_: IOException) {
            // client parti, delai ou handshake TLS refuse : rien a repondre
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Vrai si le corps a ete lu en entier (sinon la connexion ne peut pas etre reutilisee). */
    private fun drained(body: InputStream): Boolean = (body as? LimitedBody)?.remaining == 0L

    private class HttpError(val status: Int) : Exception()

    private fun readLine(input: InputStream, limit: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else throw HttpError(400)
            if (b == '\n'.code) {
                if (sb.endsWith("\r")) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > limit) throw HttpError(431)
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val line = readLine(input, 8192) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400)
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val h = readLine(input, 8192) ?: throw HttpError(400)
            if (h.isEmpty()) break
            total += h.length
            if (total > 32 * 1024 || headers.size >= 100) throw HttpError(431)
            val i = h.indexOf(':')
            if (i <= 0) throw HttpError(400)
            headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(411)
        val len = headers["content-length"]?.let { it.toLongOrNull() ?: throw HttpError(400) } ?: 0L
        if (len < 0) throw HttpError(400)
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = HashMap<String, String>()
        if (q >= 0) {
            for (kv in target.substring(q + 1).split('&')) {
                if (kv.isEmpty()) continue
                val e = kv.indexOf('=')
                try {
                    val k = URLDecoder.decode(if (e >= 0) kv.substring(0, e) else kv, "UTF-8")
                    val v = if (e >= 0) URLDecoder.decode(kv.substring(e + 1), "UTF-8") else ""
                    query[k] = v
                } catch (_: IllegalArgumentException) {
                    throw HttpError(400)
                }
            }
        }
        return HttpRequest(parts[0], path, query, headers, LimitedBody(input, len), len)
    }

    private fun write(out: java.io.OutputStream, r: HttpResponse, keepAlive: Boolean) {
        val reason = when (r.status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"
            431 -> "Request Header Fields Too Large"; 507 -> "Insufficient Storage"; else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ${r.status} $reason\r\n")
            append("Content-Length: ${r.body.size}\r\n")
            r.contentType?.let { append("Content-Type: $it\r\n") }
            append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }
}

/** Lit au plus `limit` octets ; une coupure avant la fin leve EOFException. */
private class LimitedBody(private val src: InputStream, limit: Long) : InputStream() {
    private var left = limit
    val remaining: Long get() = left

    override fun read(): Int {
        if (left <= 0) return -1
        val b = src.read()
        if (b < 0) throw EOFException("corps incomplet")
        left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        if (len == 0) return 0
        val n = src.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw EOFException("corps incomplet")
        left -= n
        return n
    }

    override fun available(): Int = if (left <= 0) 0 else minOf(src.available().toLong(), left).toInt()
}
