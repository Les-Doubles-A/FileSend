package app.filesend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Construction de la liste d'envoi (partage et selecteur Android) : chemins relatifs acceptes par le receveur. */
class SendNamesTest {
    private fun file(name: String, size: Int = 3) = Entry(name, size = size.toLong(), open = { ByteArrayInputStream(ByteArray(size)) })
    private fun dir(name: String, vararg kids: Entry) = Entry(name, children = kids.toList())

    private fun paths(roots: List<Entry>) = SendNames.flatten(roots).map { it.relativePath }

    @Test fun treeKeepsStructure() {
        val got = paths(listOf(dir("photos", file("a.jpg"), dir("v", file("b.jpg"))), file("notes.txt")))
        assertEquals(listOf("photos/a.jpg", "photos/v/b.jpg", "notes.txt"), got)
    }

    @Test fun emptyDirectoriesAreSkipped() {
        assertEquals(listOf("d/a"), paths(listOf(dir("d", file("a"), dir("vide")))))
    }

    @Test fun sizesAndContentArePreserved() {
        val s = SendNames.flatten(listOf(file("x.bin", 10))).single()
        assertEquals(10L, s.size)
        assertEquals(10, s.open().readBytes().size)
    }

    // Deux elements partages portant le meme nom : le receveur refuserait la liste (doublon).
    @Test fun duplicateNamesAreRenamedCaseInsensitively() {
        val got = paths(listOf(file("IMG.jpg"), file("img.JPG"), file("IMG.jpg")))
        assertEquals(listOf("IMG.jpg", "img (1).JPG", "IMG (2).jpg"), got)
        assertEquals(got.size, got.map { it.lowercase() }.toSet().size)
    }

    @Test fun duplicatesInsideAFolderAreRenamedToo() {
        assertEquals(listOf("d/a.txt", "d/A (1).txt"), paths(listOf(dir("d", file("a.txt"), file("A.txt")))))
    }

    @Test fun unsafeNamesBecomeAcceptableNames() {
        val cases = mapOf(
            "a:b.txt" to "a_b.txt", "a<b>c" to "a_b_c", "q?*|\"x" to "q____x", "slash/name" to "slash_name",
            "back\\slash" to "back_slash", "fin." to "fin", "espace " to "espace", "." to "fichier", ".." to "fichier",
            "" to "fichier", "   " to "fichier", "CON" to "_CON", "nul.txt" to "_nul.txt", "ctl\u0007x" to "ctl_x",
            "Résumé été.pdf" to "Résumé été.pdf", ".hidden" to ".hidden",
        )
        for ((raw, want) in cases) assertEquals(raw, want, SendNames.safe(raw))
    }

    @Test fun longNamesAreTruncated() {
        assertEquals(255, SendNames.safe("a".repeat(400)).length)
    }

    // Tout ce que produit la liste passe la validation du receveur (memes regles que les vecteurs).
    @Test fun everyProducedPathPassesReceiverValidation() {
        val nasty = listOf("a:b", "CON", "x.", "..", "", "COM1.txt", "a\\b", "é\u0000", "aux", "PRN.log", " ", "ok")
        val roots = listOf(dir("dossier:1", *nasty.map { file(it) }.toTypedArray()), *nasty.map { file(it) }.toTypedArray())
        val all = SendNames.flatten(roots)
        assertTrue(all.isNotEmpty())
        for (s in all) sanitizeRelativePath(s.relativePath)
        assertEquals(all.size, all.map { it.relativePath.lowercase() }.toSet().size)
    }
}
