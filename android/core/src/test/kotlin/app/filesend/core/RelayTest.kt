package app.filesend.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RelayTest {
    @Test
    fun e2eRoundTripMultiChunk() {
        val plain = ByteArray(1024 * 1024 + 7777) { (it % 251).toByte() }
        val enc = ByteArrayOutputStream()
        val key = Relay.encryptE2e(ByteArrayInputStream(plain), enc)
        assertEquals(32, key.size)

        val dec = ByteArrayOutputStream()
        Relay.decryptE2e(ByteArrayInputStream(enc.toByteArray()), dec, key)
        assertArrayEquals(plain, dec.toByteArray())
    }

    @Test
    fun uriRoundTrip() {
        val key = ByteArray(32) { 7 }
        val uri = Relay.buildUri("https://litter.catbox.moe/test.fse", key, "photo vacances.jpg", 42_000, false)
        val parsed = Relay.parseUri(uri)
        assertEquals("https://litter.catbox.moe/test.fse", parsed.url)
        assertArrayEquals(key, parsed.key)
        assertEquals("photo vacances.jpg", parsed.name)
        assertEquals(42_000L, parsed.size)
        assertFalse(parsed.isZip)

        val zipUri = Relay.buildUri("https://example.com/a.fse", key, "pack.zip", 100, true)
        assertTrue(Relay.parseUri(zipUri).isZip)
    }
}
