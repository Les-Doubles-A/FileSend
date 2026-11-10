package app.filesend.android

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.filesend.core.PartialFile
import app.filesend.core.ReceiveSink
import app.filesend.core.uniqueName
import java.io.IOException
import java.io.OutputStream

/**
 * Ecrit dans Telechargements/FileSend via MediaStore (Android 10+, sans permission de stockage).
 * Le fichier reste "en attente" (IS_PENDING) jusqu'a `commit`, invisible des autres applications ;
 * `abort` le supprime. Verifie sur appareil en T7/T8 (non testable hors Android).
 */
class MediaStoreSink(private val resolver: ContentResolver) : ReceiveSink {
    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    override fun uniqueTopName(name: String): String = uniqueName(name) { topExists(it) }

    /** Un element de premier niveau existe : fichier `name`, ou dossier `name/...`. */
    private fun topExists(name: String): Boolean {
        val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?) OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        val args = arrayOf("$BASE/", name, "$BASE/${escapeLike(name)}/%")
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { return it.moveToFirst() }
        return false
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    override fun create(components: List<String>): PartialFile {
        val name = components.last()
        val dir = (listOf(BASE) + components.dropLast(1)).joinToString("/") + "/"
        val ext = name.substringAfterLast('.', "").lowercase()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("insertion MediaStore impossible")
        val stream = try {
            resolver.openOutputStream(uri)?.buffered(64 * 1024) ?: throw IOException("ouverture impossible")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return object : PartialFile {
            override val out: OutputStream = stream
            override fun commit() {
                stream.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }

            override fun abort() {
                runCatching { stream.close() }
                resolver.delete(uri, null, null)
            }
        }
    }

    private companion object {
        const val BASE = "Download/FileSend"
    }
}
