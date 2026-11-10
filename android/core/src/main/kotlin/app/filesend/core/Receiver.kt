package app.filesend.core

import kotlinx.serialization.encodeToString
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverConfig(
    val info: DeviceInfo,
    val sink: ReceiveSink,
    val decisionTimeoutMs: Long = 60_000,
    val idleTimeoutMs: Int = 10_000,
    val sessionExpiryMs: Long = 120_000,
    val maxFiles: Int = 10_000,
    val maxTotalBytes: Long = 1L shl 42,
)

/**
 * Receveur : meme comportement que windows/core/src/server.rs (docs/protocol.md).
 * `decider` est appele sans qu'aucun octet n'ait ete ecrit ; il retourne true pour accepter.
 * Il est execute avec un delai de `decisionTimeoutMs` (depasse = refus, AC-F3-3).
 */
class Receiver(
    private val cfg: ReceiverConfig,
    identity: Identity,
    private val decider: (IncomingRequest) -> Boolean,
    private val onEvent: (Event) -> Unit = {},
) : HttpHandler {

    private enum class State { Pending, Receiving, Done }

    private class SessionFile(val entry: FileEntry, val token: String, val components: List<String>) {
        var state = State.Pending
    }

    private class Session(val id: String, val from: DeviceInfo, val total: Long) {
        var accepted = false
        var files: Map<String, SessionFile> = emptyMap()
        var tops: List<String> = emptyList()
        var doneBytes = 0L
        var doneFiles = 0
        var lastActivity = System.nanoTime()
        val cancelled = AtomicBoolean(false)
    }

    private val lock = Any()
    private var session: Session? = null
    private val deciders = Executors.newCachedThreadPool { r -> Thread(r, "filesend-decide").apply { isDaemon = true } }
    private val server = HttpsServer(identity.serverSslContext(), this, cfg.idleTimeoutMs)

    fun start(port: Int = DEFAULT_PORT): Int = server.start(port)

    fun stop() {
        server.stop()
        deciders.shutdownNow()
    }

    override fun handle(req: HttpRequest): HttpResponse {
        val route = req.path.removePrefix(API_PREFIX)
        return when {
            !req.path.startsWith(API_PREFIX) -> HttpResponse(404)
            req.method == "GET" && route == "/info" ->
                HttpResponse(200, ProtocolJson.encodeToString(cfg.info).toByteArray(), "application/json")
            req.method == "POST" && route == "/prepare-upload" -> prepareUpload(req)
            req.method == "POST" && route == "/upload" -> upload(req)
            req.method == "POST" && route == "/cancel" -> {
                req.query["sessionId"]?.let { failSession(it, FailReason.Interrupted) }
                HttpResponse(200)
            }
            else -> HttpResponse(404)
        }
    }

    private fun elapsedMs(since: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since)

    // ---------- prepare-upload ----------

    private fun prepareUpload(req: HttpRequest): HttpResponse {
        if (req.contentLength > 16 * 1024 * 1024) return HttpResponse(413)
        val parsed: PrepareUploadRequest = try {
            ProtocolJson.decodeFromString(String(req.body.readBytes(), Charsets.UTF_8))
        } catch (_: EOFException) {
            return HttpResponse(400)
        } catch (_: Exception) {
            return HttpResponse(400)
        }
        if (parsed.files.isEmpty() || parsed.files.size > cfg.maxFiles) return HttpResponse(400)
        var total = 0L
        val seen = HashSet<String>()
        val comps = LinkedHashMap<String, List<String>>()
        for ((key, f) in parsed.files) {
            if (key != f.id || f.id.isEmpty() || f.size < 0) return HttpResponse(400)
            val c = try {
                sanitizeRelativePath(f.relativePath)
            } catch (_: PathRefused) {
                return HttpResponse(400)
            }
            if (!seen.add(c.joinToString("/").lowercase())) return HttpResponse(400)
            total = try {
                Math.addExact(total, f.size)
            } catch (_: ArithmeticException) {
                return HttpResponse(400)
            }
            comps[key] = c
        }
        if (total > cfg.maxTotalBytes) return HttpResponse(400)

        val mine = Session(UUID.randomUUID().toString(), parsed.info, total)
        synchronized(lock) {
            session?.let {
                if (elapsedMs(it.lastActivity) < cfg.sessionExpiryMs) return HttpResponse(409)
                it.cancelled.set(true)
            }
            session = mine // reserve la place pendant que l'utilisateur decide
        }
        var committed = false
        try {
            val incoming = IncomingRequest(parsed.info, parsed.files.size, total)
            val answer = deciders.submit<Boolean> { decider(incoming) }
            val accepted = try {
                answer.get(cfg.decisionTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                answer.cancel(true)
                false
            } catch (_: Exception) {
                false
            }
            if (!accepted) return HttpResponse(403)

            // Noms de premier niveau uniques (AC-F2-3), calcules apres l'accord.
            val topMap = LinkedHashMap<String, String>()
            val files = LinkedHashMap<String, SessionFile>()
            val tokens = LinkedHashMap<String, String>()
            for ((id, c) in comps) {
                val top = topMap.getOrPut(c[0]) { cfg.sink.uniqueTopName(c[0]) }
                val token = UUID.randomUUID().toString()
                tokens[id] = token
                files[id] = SessionFile(parsed.files.getValue(id), token, listOf(top) + c.drop(1))
            }
            synchronized(lock) {
                if (session !== mine || mine.cancelled.get()) return HttpResponse(409)
                mine.accepted = true
                mine.files = files
                mine.tops = topMap.values.toList()
                mine.lastActivity = System.nanoTime()
                committed = true
            }
            onEvent(Event.Started(mine.id, parsed.info, parsed.files.size, total))
            val body = ProtocolJson.encodeToString(PrepareUploadResponse(mine.id, tokens))
            return HttpResponse(200, body.toByteArray(), "application/json")
        } finally {
            if (!committed) synchronized(lock) { if (session === mine) session = null }
        }
    }

    // ---------- upload ----------

    private fun upload(req: HttpRequest): HttpResponse {
        val sid = req.query["sessionId"]
        val fid = req.query["fileId"]
        val token = req.query["token"]
        if (sid == null || fid == null || token == null) return HttpResponse(400)

        val s: Session
        val file: SessionFile
        synchronized(lock) {
            val cur = session
            if (cur == null || cur.id != sid || !cur.accepted) return HttpResponse(403)
            val f = cur.files[fid]
            if (f == null || f.token != token || f.state != State.Pending) return HttpResponse(403)
            f.state = State.Receiving
            cur.lastActivity = System.nanoTime()
            s = cur
            file = f
        }

        val partial = try {
            cfg.sink.create(file.components)
        } catch (e: IOException) {
            return fail(sid, e)
        }
        try {
            receive(s, file.entry, partial, req)
        } catch (e: Failure) {
            partial.abort()
            failSession(sid, e.reason)
            return HttpResponse(e.status)
        } catch (e: IOException) {
            partial.abort()
            return fail(sid, e)
        }

        // La seance a-t-elle ete annulee entre-temps ?
        var finished: Session? = null
        var alive = false
        synchronized(lock) {
            if (session === s && !s.cancelled.get()) {
                try {
                    partial.commit()
                } catch (e: IOException) {
                    partial.abort()
                    return fail(sid, e)
                }
                alive = true
                file.state = State.Done
                s.doneFiles++
                s.doneBytes += file.entry.size
                s.lastActivity = System.nanoTime()
                if (s.doneFiles == s.files.size) {
                    finished = s
                    session = null
                }
            }
        }
        if (!alive) {
            partial.abort()
            return HttpResponse(403)
        }
        finished?.let { onEvent(Event.Completed(it.id, it.from, it.tops)) }
        return HttpResponse(200)
    }

    private class Failure(val status: Int, val reason: FailReason) : Exception()

    private fun isDiskFull(e: IOException): Boolean {
        val m = e.message ?: return false
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) ||
            m.contains("not enough space", ignoreCase = true)
    }

    private fun fail(sid: String, e: IOException): HttpResponse {
        val full = isDiskFull(e)
        failSession(sid, if (full) FailReason.DiskFull else FailReason.Interrupted)
        return HttpResponse(if (full) 507 else 500)
    }

    private fun receive(s: Session, entry: FileEntry, partial: PartialFile, req: HttpRequest) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var received = 0L
        var lastEmit = 0L
        val step = maxOf(entry.size / 100, 256L * 1024)
        while (true) {
            if (s.cancelled.get()) throw Failure(403, FailReason.Interrupted)
            val n = try {
                req.body.read(buf)
            } catch (_: SocketTimeoutException) {
                throw Failure(400, FailReason.Interrupted)
            } catch (_: EOFException) {
                throw Failure(400, FailReason.Interrupted)
            }
            if (n < 0) break
            received += n
            if (received > entry.size) throw Failure(422, FailReason.Interrupted)
            digest.update(buf, 0, n)
            partial.out.write(buf, 0, n)
            if (received - lastEmit >= step) {
                lastEmit = received
                val done = synchronized(lock) {
                    if (session !== s) throw Failure(403, FailReason.Interrupted)
                    s.lastActivity = System.nanoTime()
                    s.doneBytes + received
                }
                onEvent(Event.Progress(s.id, done, s.total))
            }
        }
        if (received != entry.size) throw Failure(422, FailReason.Interrupted)
        entry.sha256?.let {
            if (!it.equals(Identity.hex(digest.digest()), ignoreCase = true)) throw Failure(422, FailReason.Interrupted)
        }
        partial.out.flush()
    }

    private fun failSession(id: String, reason: FailReason) {
        val taken = synchronized(lock) {
            val cur = session
            if (cur != null && cur.id == id) {
                cur.cancelled.set(true)
                session = null
                cur
            } else null
        }
        if (taken != null) onEvent(Event.Failed(id, reason))
    }
}
