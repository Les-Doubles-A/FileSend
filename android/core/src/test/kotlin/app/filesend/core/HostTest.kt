package app.filesend.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HostTest {
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

    private fun wait(secs: Long = 10, f: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs)
        while (System.nanoTime() < end) { if (f()) return true; Thread.sleep(30) }
        return f()
    }

    private fun host(rec: Notifier, decisionMs: Long = 60_000): Pair<Host, File> {
        val root = tmp.newFolder()
        val id = Identity.generate()
        val h = Host(
            HostConfig(
                DeviceInfo("Tel", id.fingerprint, "mobile"), id, DirSink(root), httpsPort = 0, decisionTimeoutMs = decisionMs,
                discovery = { DiscoveryConfig(it.info, it.httpsPort, multicast = false, scan = ScanTargets.Off) },
            ),
            rec,
        )
        hosts += h
        return h to root
    }

    private fun target(h: Host) = InetSocketAddress("127.0.0.1", h.port)

    private fun source(name: String, size: Int): SendSource {
        val f = File(tmp.newFolder(), name).apply { writeBytes(ByteArray(size) { it.toByte() }) }
        return enumerateFiles(listOf(f)).single()
    }

    private val sender = SendOptions(DeviceInfo("Pc", "p", "desktop"))

    // AC-F3-1 / AC-F3-4 / AC-F4-2 : demande avec expediteur, nombre et taille ; acceptation ; "Recu de".
    @Test fun requestAcceptedThenReceivedNotification() {
        val rec = Rec()
        val (h, root) = host(rec)
        assertTrue(h.start())
        val t = Thread { send(target(h), listOf(source("a.bin", 3_000_000)), sender) }.apply { start() }
        assertTrue(wait { h.current.incoming != null })
        assertEquals("Pc veut envoyer 1 élément", h.current.incoming!!.title)
        assertEquals("2,9 Mo", h.current.incoming!!.detail)
        assertTrue(rec.log.any { it == "request|Pc veut envoyer 1 élément|2,9 Mo" })
        assertTrue("rien ecrit avant accord", root.walkTopDown().none { it.isFile })
        h.answer(h.current.incoming!!.id, true)
        t.join(15_000)
        assertTrue(wait { rec.log.any { it.startsWith("done|") } })
        assertTrue(rec.log.contains("done|Reçu de Pc|a.bin"))
        assertTrue(rec.log.any { it.startsWith("progress|Réception de Pc|") })
        assertEquals("Reçu de Pc", h.current.status)
        assertNull(h.current.incoming)
        assertTrue(File(root, "a.bin").isFile)
    }

    // AC-F3-2
    @Test fun refusedAndNothingSaved() {
        val rec = Rec()
        val (h, root) = host(rec)
        h.start()
        val result = arrayOfNulls<Throwable>(1)
        val t = Thread { try { send(target(h), listOf(source("a.txt", 100)), sender) } catch (e: Throwable) { result[0] = e } }.apply { start() }
        assertTrue(wait { h.current.incoming != null })
        h.answer(h.current.incoming!!.id, false)
        t.join(10_000)
        assertEquals(FailReason.Refused, (result[0] as SendException).reason)
        assertTrue(root.walkTopDown().none { it.isFile })
    }

    // AC-F3-3 (delai ramene a 400 ms) : sans reponse = refus, la demande disparait de l'ecran
    @Test fun noAnswerCountsAsRefusalAndClearsRequest() {
        val rec = Rec()
        val (h, _) = host(rec, decisionMs = 400)
        h.start()
        try {
            send(target(h), listOf(source("a.txt", 100)), sender)
            fail("devait etre refuse")
        } catch (e: SendException) {
            assertEquals(FailReason.Refused, e.reason)
        }
        assertTrue(wait { h.current.incoming == null && rec.log.contains("clear") })
    }

    // AC-F4-4 : notifications refusees (Notifier muet) -> le transfert aboutit, l'etat est visible dans E1
    @Test fun worksWithoutAnyNotification() {
        val silent = object : Notifier {
            override fun request(id: String, title: String, detail: String) {}
            override fun clearRequest(id: String) {}
            override fun progress(id: String, title: String, percent: Int?) {}
            override fun done(id: String, title: String, tops: List<String>) {}
            override fun fail(id: String, title: String) {}
            override fun dismiss(id: String) {}
        }
        val (h, root) = host(silent)
        h.start()
        val t = Thread { send(target(h), listOf(source("b.txt", 500)), sender) }.apply { start() }
        assertTrue(wait { h.current.incoming != null })
        h.answer(h.current.incoming!!.id, true) // bouton de l'ecran principal
        t.join(10_000)
        assertTrue(wait { h.current.status == "Reçu de Pc" })
        assertTrue(File(root, "b.txt").isFile)
    }

    // Echec d'envoi cote receveur : "Échec : Interrompu" dans la notification et l'etat
    @Test fun failureIsReported() {
        val rec = Rec()
        val (h, _) = host(rec)
        h.start()
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val big = source("big.bin", 20 * 1024 * 1024)
        val t = Thread {
            try {
                send(target(h), listOf(big), SendOptions(sender.info, hashUpTo = 0), { d, _ -> if (d > 1_000_000) cancel.set(true) }, cancel)
            } catch (_: SendCancelled) {
            }
        }.apply { start() }
        assertTrue(wait { h.current.incoming != null })
        h.answer(h.current.incoming!!.id, true)
        t.join(10_000)
        assertTrue(wait { rec.log.contains("fail|Échec : Interrompu") })
        assertEquals("Échec : Interrompu", h.current.status)
    }

    // Reception uniquement app ouverte (H3) : arret differe jusqu'a la fin du transfert
    @Test fun stopWhenIdleStopsImmediatelyWhenIdle() {
        val (h, _) = host(Rec())
        h.start()
        assertTrue(h.current.running)
        h.stopWhenIdle()
        assertTrue(wait { !h.current.running })
        assertNotNull(h.current)
    }

    // Port occupe : erreur lisible, pas d'exception
    @Test fun busyPortReportsError() {
        val (h1, _) = host(Rec())
        h1.start()
        val port = h1.port
        val id = Identity.generate()
        val h2 = Host(HostConfig(DeviceInfo("X", id.fingerprint, "mobile"), id, DirSink(tmp.newFolder()), httpsPort = port), Rec())
        hosts += h2
        assertEquals(false, h2.start())
        assertEquals("Port occupé", h2.current.error)
    }
}
