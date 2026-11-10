package app.filesend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun certificateIsValidSelfSignedEcdsa() {
        val id = Identity.generate()
        val cert = id.certificate // parse par le JDK : le DER fait a la main est valide
        cert.verify(cert.publicKey) // signature auto-verifiee
        cert.checkValidity()
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertEquals(64, id.fingerprint.length)
        assertTrue(id.fingerprint.all { it in "0123456789abcdef" })
    }

    @Test fun persistsAndReloadsSameFingerprint() {
        val dir = tmp.newFolder("identity")
        val a = Identity.loadOrCreate(dir)
        val b = Identity.loadOrCreate(dir)
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.fingerprint, Identity.generate().fingerprint)
    }

    @Test fun corruptedFilesAreRegenerated() {
        val dir = tmp.newFolder("identity2")
        val a = Identity.loadOrCreate(dir)
        java.io.File(dir, "cert.der").writeBytes(byteArrayOf(1, 2, 3))
        val b = Identity.loadOrCreate(dir)
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test fun serverContextBuilds() {
        Identity.generate().serverSslContext()
    }
}
