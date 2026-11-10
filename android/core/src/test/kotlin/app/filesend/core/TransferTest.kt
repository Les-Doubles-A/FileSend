package app.filesend.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/** Miroir Kotlin de windows/core/tests/transfer.rs : meme protocole, memes criteres. */
class TransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private val info = DeviceInfo("sender", "x", "mobile")
    private val receivers = ArrayList<Receiver>()

    @After fun stopAll() = receivers.forEach { it.stop() }

    private class Rx(val receiver: Receiver, val port: Int, val root: File, val events: LinkedBlockingQueue<Event>) {
        val addr = InetSocketAddress("127.0.0.1", port)
    }

    private fun start(
        decisionMs: Long = 60_000,
        idleMs: Int = 10_000,
        decider: (IncomingRequest) -> Boolean = { true },
    ): Rx {
        val root = tmp.newFolder()
        val events = LinkedBlockingQueue<Event>()
        val cfg = ReceiverConfig(DeviceInfo("recv", "y", "desktop"), DirSink(root), decisionMs, idleMs)
        val r = Receiver(cfg, Identity.generate(), decider) { events.add(it) }
        receivers += r
        return Rx(r, r.start(0), root, events)
    }

    private fun data(size: Int, seed: Int = 7) = ByteArray(size) { (it * 31 + seed).toByte() }
    private fun sha(b: ByteArray) = Identity.hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun sha(f: File) = sha(f.readBytes())

    private fun write(f: File, size: Int, seed: Int = 7): File {
        f.parentFile.mkdirs()
        f.writeBytes(data(size, seed))
        return f
    }

    private fun opts() = SendOptions(info)

    private fun leftovers(root: File) = root.walkTopDown().filter { it.isFile }.toList()

    private inline fun <reified T : Event> await(rx: Rx, secs: Long = 10): T {
        val end = System.nanoTime() + secs * 1_000_000_000
        while (true) {
            val left = end - System.nanoTime()
            val e = rx.events.poll(maxOf(left, 0), TimeUnit.NANOSECONDS) ?: fail("evenement attendu non recu")
            if (e is T) return e
        }
    }

    private fun raw(rx: Rx, path: String, query: Map<String, String>, body: ByteArray): Int {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { "${it.key}=${it.value}" }
        val c = URL("https://127.0.0.1:${rx.port}$API_PREFIX$path$q").openConnection() as HttpsURLConnection
        c.sslSocketFactory = trustAllSslContext().socketFactory
        c.setHostnameVerifier { _, _ -> true }
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        return c.responseCode
    }

    private fun prepareBody(vararg paths: Pair<String, Long>): ByteArray {
        val files = paths.mapIndexed { i, p -> "\"f$i\":{\"id\":\"f$i\",\"relativePath\":${quote(p.first)},\"size\":${p.second}}" }
        return """{"info":{"alias":"evil","fingerprint":"e","deviceType":"mobile"},"files":{${files.joinToString(",")}}}""".toByteArray()
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // AC-F2-1, AC-F4-2 (evenement Completed)
    @Test fun file10mbSameNameAndSha() {
        val rx = start()
        val f = write(File(tmp.newFolder(), "rapport.bin"), 10 * 1024 * 1024)
        send(rx.addr, enumerateFiles(listOf(f)), opts())
        assertEquals(sha(f), sha(File(rx.root, "rapport.bin")))
        val done = await<Event.Completed>(rx)
        assertEquals("sender", done.from.alias)
        assertEquals(listOf("rapport.bin"), done.tops)
        assertTrue(leftovers(rx.root).none { it.name.endsWith(".part") })
    }

    // AC-F2-2, plus keep-alive : 60 petits fichiers sur une meme connexion
    @Test fun folderTreePreservedAndManySmallFiles() {
        val rx = start()
        val src = tmp.newFolder()
        write(File(src, "photos/a.jpg"), 5000, 1)
        write(File(src, "photos/v/b.jpg"), 70_000, 2)
        for (i in 0 until 60) write(File(src, "photos/lot/f$i.txt"), 10 + i, i)
        send(rx.addr, enumerateFiles(listOf(File(src, "photos"))), opts())
        for (rel in listOf("photos/a.jpg", "photos/v/b.jpg", "photos/lot/f59.txt")) {
            assertEquals(rel, sha(File(src, rel)), sha(File(rx.root, rel)))
        }
        assertEquals(62, leftovers(rx.root).size)
    }

    // AC-F2-3
    @Test fun collisionsNeverOverwrite() {
        val rx = start()
        File(rx.root, "a.txt").writeText("original")
        File(rx.root, "photos").mkdirs()
        File(rx.root, "photos/old.txt").writeText("old")
        val src = tmp.newFolder()
        write(File(src, "a.txt"), 100, 3)
        write(File(src, "photos/a.jpg"), 100, 4)
        send(rx.addr, enumerateFiles(listOf(File(src, "a.txt"), File(src, "photos"))), opts())
        assertEquals("original", File(rx.root, "a.txt").readText())
        assertEquals(sha(File(src, "a.txt")), sha(File(rx.root, "a (1).txt")))
        assertTrue(File(rx.root, "photos/old.txt").exists())
        assertEquals(sha(File(src, "photos/a.jpg")), sha(File(rx.root, "photos (1)/a.jpg")))
        assertFalse(File(rx.root, "photos/a.jpg").exists())
    }

    // AC-F2-4 : refuses avant toute question a l'utilisateur
    @Test fun trapPathsRejectedWithoutPromptOrWrite() {
        val asked = AtomicInteger()
        val rx = start(decider = { asked.incrementAndGet(); true })
        for (bad in listOf("../evil.txt", "a/../../evil.txt", "/abs.txt", "C:/evil.txt", "a\\b.txt", "CON", "nul.txt", "ads:stream", "dir/", "x?y")) {
            assertEquals(bad, 400, raw(rx, "/prepare-upload", emptyMap(), prepareBody(bad to 1)))
        }
        assertEquals(400, raw(rx, "/prepare-upload", emptyMap(), prepareBody("A.txt" to 1, "a.TXT" to 1)))
        assertEquals(400, raw(rx, "/prepare-upload", emptyMap(), "pas du json".toByteArray()))
        assertEquals(400, raw(rx, "/prepare-upload", emptyMap(), prepareBody("neg.txt" to -5)))
        assertEquals(0, asked.get())
        assertTrue(leftovers(rx.root).isEmpty())
    }

    // AC-F3-1
    @Test fun requestShowsSenderCountSizeAndNothingWrittenBeforeAccept() {
        var seen: IncomingRequest? = null
        var onDisk = -1
        lateinit var rx: Rx
        rx = start(decider = { seen = it; onDisk = leftovers(rx.root).size; true })
        val src = tmp.newFolder()
        write(File(src, "d/a"), 1000, 1)
        write(File(src, "d/b"), 2000, 2)
        send(rx.addr, enumerateFiles(listOf(File(src, "d"))), opts())
        assertEquals("sender", seen!!.from.alias)
        assertEquals(2, seen!!.count)
        assertEquals(3000L, seen!!.total)
        assertEquals(0, onDisk)
    }

    // AC-F3-2
    @Test fun refusalSavesNothingAndSenderSeesRefused() {
        val rx = start(decider = { false })
        val f = write(File(tmp.newFolder(), "a.txt"), 100)
        try {
            send(rx.addr, enumerateFiles(listOf(f)), opts())
            fail("devait etre refuse")
        } catch (e: SendException) {
            assertEquals(FailReason.Refused, e.reason)
            assertEquals("Échec : Refusé", e.message)
        }
        assertTrue(leftovers(rx.root).isEmpty())
    }

    // AC-F3-3 (delai ramene a 300 ms)
    @Test fun noAnswerCountsAsRefusal() {
        val rx = start(decisionMs = 300, decider = { Thread.sleep(60_000); true })
        val f = write(File(tmp.newFolder(), "a.txt"), 100)
        val t0 = System.nanoTime()
        try {
            send(rx.addr, enumerateFiles(listOf(f)), opts())
            fail("devait etre refuse")
        } catch (e: SendException) {
            assertEquals(FailReason.Refused, e.reason)
        }
        assertTrue(System.nanoTime() - t0 < 5_000_000_000)
        assertTrue(leftovers(rx.root).isEmpty())
    }

    // Une seule seance a la fois : 409 pendant qu'une decision est en attente.
    @Test fun secondRequestWhilePendingGetsConflict() {
        val rx = start(decisionMs = 2_000, decider = { Thread.sleep(10_000); true })
        val first = Thread { raw(rx, "/prepare-upload", emptyMap(), prepareBody("a.txt" to 1)) }.apply { start() }
        Thread.sleep(400)
        assertEquals(409, raw(rx, "/prepare-upload", emptyMap(), prepareBody("b.txt" to 1)))
        first.join()
    }

    // AC-F3-5
    @Test fun plainHttpGetsNoApplicationResponse() {
        val rx = start()
        Socket("127.0.0.1", rx.port).use { s ->
            s.soTimeout = 3_000
            try {
                s.getOutputStream().write("POST $API_PREFIX/prepare-upload HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                s.getOutputStream().flush()
            } catch (_: IOException) {
            }
            val buf = ByteArray(1024)
            val n = try { s.getInputStream().read(buf) } catch (_: IOException) { -1 }
            val text = if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
            assertFalse("reponse HTTP en clair recue: $text", text.contains("HTTP/1"))
        }
    }

    // AC-F2-5 : annulation par l'expediteur en plein transfert
    @Test fun senderCancelRemovesPartialAndReportsInterrupted() {
        val rx = start()
        val f = write(File(tmp.newFolder(), "big.bin"), 60 * 1024 * 1024)
        val cancel = AtomicBoolean(false)
        val o = SendOptions(info, hashUpTo = 0)
        try {
            send(rx.addr, enumerateFiles(listOf(f)), o, { done, _ -> if (done > 2 * 1024 * 1024) cancel.set(true) }, cancel)
            fail("devait etre annule")
        } catch (_: SendCancelled) {
        }
        val ev = await<Event.Failed>(rx)
        assertEquals(FailReason.Interrupted, ev.reason)
        Thread.sleep(300)
        assertTrue(leftovers(rx.root).toString(), leftovers(rx.root).isEmpty())
    }

    // AC-F2-5 : flux fige (reseau coupe) -> inactivite detectee, partiel supprime
    @Test fun stalledUploadTimesOutAndCleansPartial() {
        val rx = start(idleMs = 1_000)
        val prep = prepare(rx, "s.bin", 1_000_000)
        val c = URL("https://127.0.0.1:${rx.port}$API_PREFIX/upload?sessionId=${prep.sessionId}&fileId=f0&token=${prep.files.getValue("f0")}")
            .openConnection() as HttpsURLConnection
        c.sslSocketFactory = trustAllSslContext().socketFactory
        c.setHostnameVerifier { _, _ -> true }
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(1_000_000L)
        c.connectTimeout = 3000
        val out = c.outputStream
        out.write(ByteArray(1000))
        out.flush()
        val t0 = System.nanoTime()
        val ev = await<Event.Failed>(rx)
        assertTrue(System.nanoTime() - t0 < 10_000_000_000)
        assertEquals(FailReason.Interrupted, ev.reason)
        assertTrue(leftovers(rx.root).isEmpty())
        c.disconnect()
    }

    private fun prepare(rx: Rx, path: String, size: Long): PrepareUploadResponse {
        val c = URL("https://127.0.0.1:${rx.port}$API_PREFIX/prepare-upload").openConnection() as HttpsURLConnection
        c.sslSocketFactory = trustAllSslContext().socketFactory
        c.setHostnameVerifier { _, _ -> true }
        c.requestMethod = "POST"
        c.doOutput = true
        val body = prepareBody(path to size)
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        assertEquals(200, c.responseCode)
        return ProtocolJson.decodeFromString(String(c.inputStream.readBytes()))
    }

    // Jeton invalide (403) et taille differente (422).
    @Test fun badTokenAndSizeMismatch() {
        val rx = start()
        val prep = prepare(rx, "t.bin", 10)
        val q = { t: String -> mapOf("sessionId" to prep.sessionId, "fileId" to "f0", "token" to t) }
        assertEquals(403, raw(rx, "/upload", q("mauvais"), ByteArray(10)))
        assertEquals(422, raw(rx, "/upload", q(prep.files.getValue("f0")), ByteArray(5)))
        assertEquals(400, raw(rx, "/upload", mapOf("sessionId" to prep.sessionId), ByteArray(1)))
        assertTrue(leftovers(rx.root).isEmpty())
    }

    // Destinataire injoignable -> Introuvable
    @Test fun unreachableTargetIsNotFound() {
        val f = write(File(tmp.newFolder(), "a.txt"), 10)
        try {
            send(InetSocketAddress("127.0.0.1", 9), enumerateFiles(listOf(f)), opts())
            fail("devait echouer")
        } catch (e: SendException) {
            assertEquals(FailReason.NotFound, e.reason)
        }
    }

    // GET /info
    @Test fun infoEndpoint() {
        val rx = start()
        val c = URL("https://127.0.0.1:${rx.port}$API_PREFIX/info").openConnection() as HttpsURLConnection
        c.sslSocketFactory = trustAllSslContext().socketFactory
        c.setHostnameVerifier { _, _ -> true }
        assertEquals(200, c.responseCode)
        val got = ProtocolJson.decodeFromString<DeviceInfo>(String(c.inputStream.readBytes()))
        assertEquals("recv", got.alias)
        assertNotNull(got.fingerprint)
    }
}
