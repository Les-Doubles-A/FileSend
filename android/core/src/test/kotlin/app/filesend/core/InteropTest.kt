package app.filesend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Interoperabilite reelle avec l'implementation Rust : le binaire `filesend-dev` de windows/core.
 * Ignore (assume) si le binaire n'a pas ete compile : `cargo build --release` dans windows/core.
 */
class InteropTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun exe(): File {
        val w = File(System.getProperty("windowsDir"))
        val found = listOf("core/target/release/filesend-dev.exe", "target/release/filesend-dev.exe", "target/release/filesend-dev")
            .map { File(w, it) }.firstOrNull { it.isFile }
        assumeTrue("filesend-dev (Rust) non compile : test d'interoperabilite ignore", found != null)
        return found!!
    }

    private fun sha(f: File) = Identity.hex(MessageDigest.getInstance("SHA-256").digest(f.readBytes()))

    private fun tree(): File {
        val src = tmp.newFolder("src")
        File(src, "dossier/sous").mkdirs()
        File(src, "dossier/a.bin").writeBytes(ByteArray(3_000_000) { (it * 7).toByte() })
        File(src, "dossier/sous/b.txt").writeText("bonjour é à ü")
        File(src, "dossier/Résumé été.txt").writeText("accents")
        return File(src, "dossier")
    }

    // Rust (reqwest + rustls) -> Kotlin (serveur HTTP maison + certificat DER fait a la main)
    @Test fun rustSendsToKotlin() {
        val bin = exe()
        val dest = tmp.newFolder("recv")
        val events = java.util.concurrent.LinkedBlockingQueue<Event>()
        val r = Receiver(ReceiverConfig(DeviceInfo("kotlin", "k", "mobile"), DirSink(dest)), Identity.generate(), { true }) { events.add(it) }
        try {
            val port = r.start(0)
            val src = tree()
            val p = ProcessBuilder(bin.path, "send", "127.0.0.1:$port", src.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            assertEquals(out, 0, p.exitValue())
            for (rel in listOf("a.bin", "sous/b.txt", "Résumé été.txt")) {
                assertEquals(rel, sha(File(src, rel)), sha(File(dest, "dossier/$rel")))
            }
            val done = generateSequence { events.poll(5, TimeUnit.SECONDS) }.first { it is Event.Completed } as Event.Completed
            assertEquals("dev-sender", done.from.alias)
            assertEquals(listOf("dossier"), done.tops)
        } finally {
            r.stop()
        }
    }

    // Kotlin (HttpsURLConnection) -> Rust (axum + rustls)
    @Test fun kotlinSendsToRust() {
        val bin = exe()
        val dest = tmp.newFolder("rustrecv")
        val port = ServerSocket(0).use { it.localPort }
        val p = ProcessBuilder(bin.path, "receive", dest.path, port.toString()).redirectErrorStream(true).start()
        try {
            val reader = p.inputStream.bufferedReader()
            val ready = Thread { reader.readLine() }.apply { isDaemon = true; start() }
            ready.join(15_000)
            Thread.sleep(300)
            val src = tree()
            send(InetSocketAddress("127.0.0.1", port), enumerateFiles(listOf(src)), SendOptions(DeviceInfo("kotlin", "k", "mobile")))
            for (rel in listOf("a.bin", "sous/b.txt", "Résumé été.txt")) {
                assertEquals(rel, sha(File(src, rel)), sha(File(dest, "dossier/$rel")))
            }
        } finally {
            p.destroyForcibly()
        }
    }
}
