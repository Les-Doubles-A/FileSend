package app.filesend.core

import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Fichier en cours d'ecriture. `commit` le rend visible, `abort` le supprime. */
interface PartialFile {
    val out: OutputStream
    fun commit()
    fun abort()
}

/**
 * Destination des fichiers recus. Sur Android : MediaStore (Telechargements/FileSend).
 * Sur la JVM (tests) : un dossier.
 */
interface ReceiveSink {
    /** Nom de premier niveau disponible (jamais d'ecrasement, AC-F2-3). */
    fun uniqueTopName(name: String): String

    /** `components` : chemin deja assaini, dont le premier element vient de [uniqueTopName]. */
    fun create(components: List<String>): PartialFile
}

/** Ecrit dans un dossier : fichier `.part` puis renommage, jamais d'ecrasement. */
class DirSink(private val root: File) : ReceiveSink {
    init {
        root.mkdirs()
    }

    override fun uniqueTopName(name: String): String = uniqueName(name) { File(root, it).exists() }

    override fun create(components: List<String>): PartialFile {
        var dest = root
        for (c in components) dest = File(dest, c)
        require(dest.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "hors du dossier de reception" }
        dest.parentFile.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val stream = part.outputStream().buffered(64 * 1024)
        return object : PartialFile {
            override val out: OutputStream = stream
            override fun commit() {
                stream.close()
                if (dest.exists()) {
                    part.delete()
                    throw IOException("existe deja")
                }
                if (!part.renameTo(dest)) throw IOException("renommage impossible")
            }

            override fun abort() {
                runCatching { stream.close() }
                part.delete()
            }
        }
    }
}
