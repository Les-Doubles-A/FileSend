package app.filesend.core

/** Assainissement des chemins recus (AC-F2-4). Memes regles que windows/core/src/paths.rs. */
class PathRefused(why: String) : Exception("chemin refuse: $why")

private const val MAX_COMPONENT = 255
private const val MAX_TOTAL = 1024
private val RESERVED = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)
private const val FORBIDDEN = "<>:\"|?*\\/"

/** Valide un chemin relatif et le decoupe en composants surs, ou leve [PathRefused]. */
fun sanitizeRelativePath(raw: String): List<String> {
    val bytes = raw.toByteArray(Charsets.UTF_8).size
    if (raw.isEmpty() || bytes > MAX_TOTAL) throw PathRefused("longueur")
    if (raw.contains('\\')) throw PathRefused("separateur")
    if (raw.startsWith('/')) throw PathRefused("absolu")
    return raw.split('/').onEach(::checkComponent)
}

private fun checkComponent(c: String) {
    if (c.isEmpty()) throw PathRefused("composant vide")
    if (c == "." || c == "..") throw PathRefused("point")
    if (c.codePointCount(0, c.length) > MAX_COMPONENT) throw PathRefused("composant trop long")
    var i = 0
    while (i < c.length) {
        val cp = c.codePointAt(i)
        if (Character.getType(cp) == Character.CONTROL.toInt() || (cp < 0x80 && FORBIDDEN.indexOf(cp.toChar()) >= 0)) {
            throw PathRefused("caractere interdit")
        }
        i += Character.charCount(cp)
    }
    if (c.endsWith('.') || c.endsWith(' ')) throw PathRefused("fin interdite")
    val stem = c.substringBefore('.').uppercase(java.util.Locale.ROOT)
    if (stem in RESERVED) throw PathRefused("nom reserve")
}

/** Premier nom disponible : `a.txt` -> `a (1).txt`, `photos` -> `photos (1)`. */
fun uniqueName(name: String, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = name.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var n = 1
    while (true) {
        val candidate = "$stem ($n)$ext"
        if (!exists(candidate)) return candidate
        n++
    }
}
