package app.filesend.core

import java.io.InputStream
import java.util.Locale

/**
 * Element a envoyer (fichier ou dossier), independant d'Android : l'application le construit
 * depuis des URI (partage, selecteur), les tests depuis des objets en memoire.
 * `children != null` signifie dossier.
 */
class Entry(
    val name: String,
    val children: List<Entry>? = null,
    val size: Long = 0,
    val open: (() -> InputStream)? = null,
)

private val RESERVED = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

object SendNames {
    /** Nom accepte par le receveur : caracteres interdits remplaces, fins interdites retirees, nom reserve prefixe. */
    fun safe(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val bad = Character.isISOControl(cp) || (cp < 0x80 && "<>:\"|?*\\/".indexOf(cp.toChar()) >= 0)
            sb.appendCodePoint(if (bad) '_'.code else cp)
            i += Character.charCount(cp)
        }
        var s = sb.toString()
        if (s.codePointCount(0, s.length) > 255) s = s.substring(0, s.offsetByCodePoints(0, 255))
        s = s.trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") s = "fichier"
        if (s.substringBefore('.').uppercase(Locale.ROOT) in RESERVED) s = "_$s"
        return s
    }

    /** Aplatit les elements en liste de fichiers a chemins relatifs sans doublon (casse ignoree, comme le receveur). */
    fun flatten(roots: List<Entry>): List<SendSource> {
        val out = ArrayList<SendSource>()
        fun level(entries: List<Entry>, prefix: String) {
            val used = HashSet<String>()
            for (e in entries) {
                val name = uniqueName(safe(e.name)) { it.lowercase(Locale.ROOT) in used }
                used += name.lowercase(Locale.ROOT)
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                val kids = e.children
                if (kids != null) level(kids, rel)
                else e.open?.let { out += SendSource(rel, e.size, it) }
            }
        }
        level(roots, "")
        return out
    }
}
