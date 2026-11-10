package app.filesend.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Envoi depuis Android (Host.sendTo) : notifications, etat, refus, introuvable, annulation, memoire. */
class HostSendTest {
    @get:Rule val tmp = TemporaryFolder()
    private val hosts = ArrayList<Host>()

    @After fun stopAll() = hosts.forEach { runCatching { it.stop() } }

    private class Rec : Notifier {
        val log = CopyOnWriteArrayList<String>()
        override fun request(id: String, title: String, detail: String) { log += "request|$title|$detail" }
        override fun clearRequest(id: String) { log += "clear" }
        override fun progress(id: String, title: String, percent: Int?) { log += "progress|$title|$percent" }
        override fun done(id: String, title: String, tops: List<String>) { log += "done|$title|${tops.joinToString(",")}" }
        override fun fail(id: String, title: String) { log += "fail|$title" }
        override fun dismiss(id: String) { log += "dismiss" }
    }

    private fun wait(secs: Long = 15, f: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs)
        while (System.nanoTime() < end) { if (f()) return true; Thread.sleep(30) }
        return f()
    }

    private fun host(alias: String, rec: Notifier, sink: ReceiveSink, decisionMs: Long = 60_000): Host {
        val id = Identity.generate()
        val h = Host(
            HostConfig(
                DeviceInfo(alias, id.fingerprint, "mobile"), id, sink, httpsPort = 0, decisionTimeoutMs = decisionMs,
                discovery = { DiscoveryConfig(it.info, it.httpsPort, multicast = false, scan = ScanTargets.Off) },
            ),
            rec,
        )
        hosts += h
        h.start()
        return h
    }

    private fun deviceOf(h: Host, alias: String) =
        Device(DeviceInfo(alias, "fp-$alias", "mobile"), InetSocketAddress("127.0.0.1", h.port), Via.Multicast, System.nanoTime())

    private fun sha(f: File) = Identity.hex(MessageDigest.getInstance("SHA-256").digest(f.readBytes()))

    private fun sources(vararg e: Entry) = { SendNames.flatten(e.toList()) }

    private fun fileEntry(f: File) = Entry(f.name, size = f.length(), open = { f.inputStream() })

    // AC-F2-1, AC-F2-2, AC-F4-1, AC-F4-2 : envoi complet, notifications "Envoi a" puis "Envoye a", progression
    @Test fun sendFolderAndFileEndToEnd() {
        val recvRoot = tmp.newFolder()
        val receiver = host("Pc", Rec(), DirSink(recvRoot))
        val rec = Rec()
        val sender = host("Tel", rec, DirSink(tmp.newFolder()))
        val src = tmp.newFolder()
        val a = File(src, "photos/a.jpg").apply { parentFile.mkdirs(); writeBytes(ByteArray(3_000_000) { (it * 7).toByte() }) }
        val b = File(src, "photos/sous/b.txt").apply { parentFile.mkdirs(); writeText("bonjour é") }
        val n = File(src, "Résumé été.txt").apply { writeText("accents") }
        val tree = Entry("photos", children = listOf(fileEntry(a), Entry("sous", children = listOf(fileEntry(b)))))
        assertTrue(sender.sendTo(deviceOf(receiver, "Pc"), sources(tree, fileEntry(n))))
        assertTrue(wait { receiver.current.incoming != null })
        receiver.answer(receiver.current.incoming!!.id, true)
        assertTrue(wait { rec.log.any { it.startsWith("done|") } })
        assertEquals("Envoyé à Pc", sender.current.status)
        assertTrue(rec.log.first().startsWith("progress|Envoi à Pc|null")) // en attente de la reponse
        assertTrue(rec.log.contains("done|Envoyé à Pc|"))
        val pct = rec.log.filter { it.startsWith("progress|Envoi à Pc|") }.map { it.substringAfterLast('|') }.filter { it != "null" }.map { it.toInt() }
        assertTrue(pct.toString(), pct.isNotEmpty() && pct == pct.sorted() && pct.last() == 100)
        assertEquals(sha(a), sha(File(recvRoot, "photos/a.jpg")))
        assertEquals("bonjour é", File(recvRoot, "photos/sous/b.txt").readText())
        assertEquals("accents", File(recvRoot, "Résumé été.txt").readText())
        assertFalse(sender.current.sending)
    }

    // AC-F4-3 : refus -> "Échec : Refusé"
    @Test fun refusedShowsFailureMessage() {
        val receiver = host("Pc", Rec(), DirSink(tmp.newFolder()))
        val rec = Rec()
        val sender = host("Tel", rec, DirSink(tmp.newFolder()))
        val f = File(tmp.newFolder(), "a.txt").apply { writeText("x") }
        sender.sendTo(deviceOf(receiver, "Pc"), sources(fileEntry(f)))
        assertTrue(wait { receiver.current.incoming != null })
        receiver.answer(receiver.current.incoming!!.id, false)
        assertTrue(wait { rec.log.contains("fail|Échec : Refusé") })
        assertEquals("Échec : Refusé", sender.current.status)
        assertTrue(wait { !sender.current.sending })
    }

    // AC-F4-3 : appareil injoignable -> "Échec : Introuvable"
    @Test fun unreachableShowsNotFound() {
        val rec = Rec()
        val sender = host("Tel", rec, DirSink(tmp.newFolder()))
        val f = File(tmp.newFolder(), "a.txt").apply { writeText("x") }
        sender.sendTo(Device(DeviceInfo("Ghost", "g", "desktop"), InetSocketAddress("127.0.0.1", 9), Via.Scan, 0), sources(fileEntry(f)))
        assertTrue(wait { rec.log.contains("fail|Échec : Introuvable") })
    }

    // Echec pendant la preparation de la liste (fichier illisible) : message lisible, pas de blocage
    @Test fun unreadableSourceReportsFailure() {
        val receiver = host("Pc", Rec(), DirSink(tmp.newFolder()))
        val rec = Rec()
        val sender = host("Tel", rec, DirSink(tmp.newFolder()))
        sender.sendTo(deviceOf(receiver, "Pc")) { throw java.io.IOException("illisible") }
        assertTrue(wait { rec.log.contains("fail|Échec : Interrompu") })
        assertTrue(wait { !sender.current.sending })
    }

    // Un seul envoi a la fois
    @Test fun secondSendWhileSendingIsRejected() {
        val receiver = host("Pc", Rec(), DirSink(tmp.newFolder()))
        val sender = host("Tel", Rec(), DirSink(tmp.newFolder()))
        val f = File(tmp.newFolder(), "a.txt").apply { writeText("x") }
        assertTrue(sender.sendTo(deviceOf(receiver, "Pc"), sources(fileEntry(f))))
        assertFalse(sender.sendTo(deviceOf(receiver, "Pc"), sources(fileEntry(f))))
        assertTrue(wait { receiver.current.incoming != null })
        receiver.answer(receiver.current.incoming!!.id, false)
        assertTrue(wait { !sender.current.sending })
    }

    // Annulation : plus de notification de progression, rien d'enregistre
    @Test fun cancelDismissesAndSavesNothing() {
        val recvRoot = tmp.newFolder()
        val receiver = host("Pc", Rec(), DirSink(recvRoot))
        val rec = Rec()
        val sender = host("Tel", rec, DirSink(tmp.newFolder()))
        val big = Entry("big.bin", size = 200L * 1024 * 1024, open = { zeros(200L * 1024 * 1024) })
        sender.sendTo(deviceOf(receiver, "Pc"), sources(big))
        assertTrue(wait { receiver.current.incoming != null })
        receiver.answer(receiver.current.incoming!!.id, true)
        assertTrue(wait { rec.log.any { it.startsWith("progress|Envoi à Pc|") && !it.endsWith("|null") } })
        sender.cancelSend()
        assertTrue(wait { rec.log.contains("dismiss") })
        assertTrue(wait { !sender.current.sending })
        Thread.sleep(300)
        assertTrue(recvRoot.walkTopDown().none { it.isFile })
    }

    private fun zeros(size: Long): InputStream = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            java.util.Arrays.fill(b, off, off + n, 0)
            left -= n
            return n
        }
    }

    /** Destination qui compte les octets sans rien stocker. */
    private class CountingSink : ReceiveSink {
        @Volatile var bytes = 0L
        override fun uniqueTopName(name: String) = name
        override fun create(components: List<String>) = object : PartialFile {
            override val out = object : OutputStream() {
                override fun write(b: Int) { bytes++ }
                override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
            }
            override fun commit() {}
            override fun abort() {}
        }
    }

    // AC-F2-6 : 2 Go sous 128 Mo de memoire (tas limite par Gradle). Lourd : gradlew test -PbigTests
    @Test fun twoGigabytesStayWithinMemoryBudget() {
        assumeTrue("test lourd ignore (-PbigTests)", System.getProperty("bigTests") != null)
        assertTrue("tas de test attendu <= 128 Mo", Runtime.getRuntime().maxMemory() <= 140L * 1024 * 1024)
        val sink = CountingSink()
        val receiver = host("Pc", Rec(), sink)
        val rec = Rec()
        val sender = host("Tel", rec, DirSink(tmp.newFolder()))
        val size = 2L * 1024 * 1024 * 1024
        sender.sendTo(deviceOf(receiver, "Pc"), sources(Entry("big2g.bin", size = size, open = { zeros(size) })))
        assertTrue(wait { receiver.current.incoming != null })
        receiver.answer(receiver.current.incoming!!.id, true)
        assertTrue("fin de l'envoi", wait(300) { rec.log.any { it.startsWith("done|") || it.startsWith("fail|") } })
        assertTrue(rec.log.toString().takeLast(200), rec.log.any { it.startsWith("done|") })
        assertEquals(size, sink.bytes)
    }
}
