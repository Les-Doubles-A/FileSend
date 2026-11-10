package app.filesend.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/** Miroir Kotlin de windows/core/tests/discovery.rs. */
class DiscoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val stoppers = ArrayList<() -> Unit>()

    @After fun cleanup() = stoppers.forEach { runCatching { it() } }

    private class Node(val info: DeviceInfo, val receiver: Receiver, val port: Int) {
        val local = InetSocketAddress("127.0.0.1", port)
    }

    private fun node(alias: String): Node {
        val id = Identity.generate()
        val info = DeviceInfo(alias, id.fingerprint, "mobile")
        val r = Receiver(ReceiverConfig(info, DirSink(tmp.newFolder())), id, { true })
        stoppers += { r.stop() }
        return Node(info, r, r.start(0))
    }

    private fun discovery(n: Node, udp: Int, tweak: (DiscoveryConfigBuilder) -> Unit = {}): Discovery {
        val b = DiscoveryConfigBuilder(n.info, n.port, udp).also(tweak)
        val d = Discovery(b.build())
        d.start()
        stoppers += { d.stop() }
        return d
    }

    private class DiscoveryConfigBuilder(val info: DeviceInfo, val port: Int, val udp: Int) {
        var multicast = true
        var scan: ScanTargets = ScanTargets.Off
        var ttlMs = DEFAULT_TTL_MS
        var announceMs = 3_000L
        var initialDelayMs = 100L
        var keepaliveMs = 500L
        fun build() = DiscoveryConfig(
            info, port, multicast = multicast, udpPort = udp, announceMs = announceMs, ttlMs = ttlMs,
            scan = scan, scanInitialDelayMs = initialDelayMs, scanIntervalMs = 1_000, keepaliveMs = keepaliveMs,
        )
    }

    private fun waitUntil(d: Discovery, secs: Long, f: (List<Device>) -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs)
        while (System.nanoTime() < end) {
            if (f(d.devices())) return true
            Thread.sleep(50)
        }
        return f(d.devices())
    }

    // AC-F1-1 : visibles l'un pour l'autre en moins de 5 s ; jamais soi-meme.
    @Test fun twoNodesSeeEachOtherViaMulticastButNotThemselves() {
        val (a, b) = node("PC") to node("Tel")
        val t0 = System.nanoTime()
        val da = discovery(a, 53421)
        val db = discovery(b, 53421)
        assumeTrue("multicast indisponible : ${da.multicastError ?: db.multicastError}", da.multicastError == null && db.multicastError == null)
        assertTrue("A ne voit pas B", waitUntil(da, 5) { it.size == 1 })
        assertTrue("B ne voit pas A", waitUntil(db, 5) { it.size == 1 })
        assertTrue(System.nanoTime() - t0 < 5_000_000_000)
        val seen = da.devices().single()
        assertEquals("Tel", seen.info.alias)
        assertEquals(b.info.fingerprint, seen.info.fingerprint)
        assertEquals(b.port, seen.addr.port)
        assertEquals(Via.Multicast, seen.via)
        assertEquals("PC", db.devices().single().info.alias)
    }

    // AC-F1-2
    @Test fun closedDeviceDisappearsWithinTtl() {
        assertTrue(DEFAULT_TTL_MS + 1_000 < 15_000)
        val (a, b) = node("PC") to node("Tel")
        val da = discovery(a, 53422) { it.ttlMs = 2_000; it.announceMs = 500 }
        val db = discovery(b, 53422) { it.announceMs = 500 }
        assumeTrue("multicast indisponible", da.multicastError == null && db.multicastError == null)
        assertTrue(waitUntil(da, 5) { it.size == 1 })
        db.stop()
        assertTrue("B devrait avoir disparu", waitUntil(da, 6) { it.isEmpty() })
    }

    // AC-F1-3 : multicast coupe, repli par sondage ; soi-meme filtre ; reverification ; retrait a l'extinction.
    @Test fun scanFallbackFindsDeviceWhenMulticastIsOff() {
        val (a, b) = node("PC") to node("Tel")
        val mk = { me: Node, other: Node ->
            discovery(me, 53423) {
                it.multicast = false
                it.scan = ScanTargets.Explicit(listOf(me.local, other.local))
                it.ttlMs = 2_000
            }
        }
        val t0 = System.nanoTime()
        val da = mk(a, b)
        val db = mk(b, a)
        assertTrue("repli : A ne voit pas B", waitUntil(da, 20) { it.size == 1 })
        assertTrue("repli : B ne voit pas A", waitUntil(db, 20) { it.size == 1 })
        assertTrue(System.nanoTime() - t0 < 20_000_000_000)
        assertEquals("Tel", da.devices().single().info.alias)
        assertEquals(Via.Scan, da.devices().single().via)
        Thread.sleep(4_000) // plus long que le ttl : la reverification le maintient
        assertEquals(1, da.devices().size)
        b.receiver.stop()
        assertTrue("B eteint devrait disparaitre", waitUntil(da, 10) { it.isEmpty() })
    }

    // Paquets mal formes, trop gros ou hors protocole : ignores ; alias assaini.
    @Test fun junkIgnoredAndAliasSanitized() {
        val a = node("PC")
        val da = discovery(a, 53424)
        assumeTrue("multicast indisponible", da.multicastError == null)
        val s = DatagramSocket()
        val dest = InetSocketAddress(InetAddress.getByName(MULTICAST_GROUP), 53424)
        fun send(b: ByteArray) = s.send(DatagramPacket(b, b.size, dest))
        fun pkt(alias: String, port: Int, announce: Boolean, fp: String) =
            """{"info":{"alias":${kotlinx.serialization.json.JsonPrimitive(alias)},"fingerprint":"$fp","deviceType":"mobile"},"port":$port,"announce":$announce}""".toByteArray()
        send("pas du json".toByteArray())
        send(ByteArray(5000) { 'x'.code.toByte() })
        send(pkt("Zero", 0, true, "f1"))
        send(pkt("Depart", 5000, false, "f2"))
        send(pkt("   ", 5000, true, "f3"))
        Thread.sleep(1_500)
        assertTrue(da.devices().toString(), da.devices().isEmpty())
        send(pkt("\u0007\u0000" + "x".repeat(100) + "\n", 6000, true, "zz"))
        assertTrue(waitUntil(da, 5) { it.size == 1 })
        val alias = da.devices().single().info.alias
        assertEquals(32, alias.length)
        assertTrue(alias.all { it == 'x' })
        s.close()
    }

    // Aucun appareil : liste vide (base de AC-F1-4, cote interface).
    @Test fun aloneMeansEmptyList() {
        val a = node("PC")
        val da = discovery(a, 53425)
        Thread.sleep(1_000)
        assertTrue(da.devices().isEmpty())
    }

    // Pair reel : l'application Windows (Rust) est vue par la decouverte Kotlin (meme format de paquet).
    @Test fun seesTheRustAppOnTheSameMachine() {
        val w = java.io.File(System.getProperty("windowsDir"))
        val exe = listOf("target/release/filesend-app.exe").map { java.io.File(w, it) }.firstOrNull { it.isFile }
        assumeTrue("filesend-app (Rust) non compile", exe != null)
        assumeTrue("port 53318 occupe (une instance tourne deja)", runCatching { java.net.ServerSocket(53318).close() }.isSuccess)
        val pb = ProcessBuilder(exe!!.path, "--minimized").also { it.environment()["FILESEND_NO_SENDTO"] = "1" }
        val p = pb.start()
        stoppers += { p.destroyForcibly() }
        val a = node("KotlinTest")
        val da = discovery(a, DEFAULT_PORT)
        assumeTrue("multicast indisponible", da.multicastError == null)
        assertTrue("l'app Windows n'est pas vue", waitUntil(da, 15) { it.any { d -> d.info.deviceType == "desktop" } })
        val rust = da.devices().first { it.info.deviceType == "desktop" }
        assertEquals(Via.Multicast, rust.via)
        assertEquals(53318, rust.addr.port)
    }
}
