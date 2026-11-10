package app.filesend.core

import java.util.Locale

/** Textes des notifications (F4). Courts, en francais ; memes resultats que windows/core/src/messages.rs. */
object Messages {
    /** Taille lisible, unites en octets (o, Ko, Mo, Go, To), base 1024. */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val units = arrayOf("Ko", "Mo", "Go", "To")
        var v = bytes / 1024.0
        var u = 0
        while (v >= 1024.0 && u < units.size - 1) {
            v /= 1024.0
            u++
        }
        return if (v < 10.0) String.format(Locale.ROOT, "%.1f %s", v, units[u]).replace('.', ',')
        else String.format(Locale.ROOT, "%.0f %s", v, units[u])
    }

    fun items(n: Int) = if (n <= 1) "$n élément" else "$n éléments"

    /** (titre, detail) de la demande entrante (AC-F3-1). */
    fun request(alias: String, count: Int, total: Long) = "$alias veut envoyer ${items(count)}" to formatSize(total)

    fun sending(alias: String) = "Envoi à $alias"
    fun sent(alias: String) = "Envoyé à $alias"
    fun receiving(alias: String) = "Réception de $alias"
    fun received(alias: String) = "Reçu de $alias"

    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 100 else minOf(done.toBigInteger().multiply(100.toBigInteger()).divide(total.toBigInteger()), 100.toBigInteger()).toInt()
}
