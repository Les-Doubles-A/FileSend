package app.filesend.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.filesend.core.Entry
import java.io.File
import java.io.IOException

/**
 * Construit les elements a envoyer depuis des URI : partage (ACTION_SEND), selecteur de
 * fichiers, selecteur de dossier. La liste finale (noms surs, doublons) est faite par
 * [app.filesend.core.SendNames.flatten], testee sur la JVM.
 */
class UriSources(private val cr: ContentResolver, private val cacheDir: File) {

    /** Fichiers partages ou choisis. */
    fun files(uris: List<Uri>): List<Entry> = uris.map { file(it) }

    private fun file(uri: Uri): Entry {
        var name: String? = null
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        val label = name ?: uri.lastPathSegment ?: "fichier"
        return fileEntry(label, uri, size)
    }

    private fun fileEntry(label: String, uri: Uri, declared: Long): Entry {
        var size = declared
        if (size < 0) size = runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
        if (size < 0) {
            // Taille inconnue du fournisseur : copie temporaire pour la connaitre (Content-Length obligatoire).
            val tmp = File.createTempFile("send", ".tmp", cacheDir).apply { deleteOnExit() }
            (cr.openInputStream(uri) ?: throw IOException("illisible")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            return Entry(label, size = tmp.length(), open = { tmp.inputStream() })
        }
        return Entry(label, size = size, open = { cr.openInputStream(uri) ?: throw IOException("illisible") })
    }

    /** Dossier choisi (arbre SAF), avec ses sous-dossiers. */
    fun tree(treeUri: Uri): Entry {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        var rootName = "dossier"
        cr.query(rootDoc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) rootName = it.getString(0) ?: rootName
        }
        return Entry(rootName, children = children(treeUri, rootId, 0))
    }

    private fun children(tree: Uri, parentId: String, depth: Int): List<Entry> {
        if (depth > 32) return emptyList()
        val out = ArrayList<Entry>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        data class Row(val id: String, val name: String, val dir: Boolean, val size: Long)
        val rows = ArrayList<Row>()
        cr.query(uri, cols, null, null, null)?.use {
            while (it.moveToNext()) {
                rows += Row(
                    it.getString(0), it.getString(1) ?: "fichier",
                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    if (it.isNull(3)) -1L else it.getLong(3),
                )
            }
        }
        for (r in rows) {
            if (r.dir) out += Entry(r.name, children = children(tree, r.id, depth + 1))
            else out += fileEntry(r.name, DocumentsContract.buildDocumentUriUsingTree(tree, r.id), r.size)
        }
        return out
    }
}
