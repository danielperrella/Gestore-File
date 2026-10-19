package it.liberofiles.app

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import it.liberofiles.core.Node
import java.util.UUID

/** Read-only, token-based provider. Android grants are limited to the selected URI. */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true
    private fun path(uri: Uri): java.io.File {
        require(uri.authority == AUTHORITY && uri.pathSegments.size == 1) { "URI non valido" }
        val ctx = context!!; val prefs = ctx.getSharedPreferences("share-tokens", Context.MODE_PRIVATE)
        val token = uri.lastPathSegment!!
        val expires = prefs.getLong("$token.expires", 0)
        require(expires >= System.currentTimeMillis()) { "Accesso scaduto; apri nuovamente il file" }
        val path = prefs.getString(token, null) ?: error("Accesso non autorizzato")
        val cachedKey = prefs.getString("$token.cachedKey",null)
        if(cachedKey != null) {
            val graph = (ctx.applicationContext as FilesApplication).graph
            graph.drive.account(cachedKey) // Revoked positions invalidate already granted cached URIs.
            val file = java.io.File(path).canonicalFile
            require(file.toPath().startsWith(java.io.File(ctx.cacheDir,"drive/$cachedKey").canonicalFile.toPath()))
            return file
        }
        val local = (ctx.applicationContext as FilesApplication).graph.local
        return local.path(Node(local.key, path)).toFile()
    }
    override fun getType(uri: Uri): String {
        val file=path(uri)
        return context!!.getSharedPreferences("share-tokens",Context.MODE_PRIVATE).getString("${uri.lastPathSegment}.mime",null) ?: mime(file.name)
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Accesso in sola lettura" }
        return ParcelFileDescriptor.open(path(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = path(uri); val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(columns.size)
        val displayName=context!!.getSharedPreferences("share-tokens",Context.MODE_PRIVATE).getString("${uri.lastPathSegment}.name",file.name)
        columns.forEachIndexed { i, column -> row[i] = when (column) { OpenableColumns.DISPLAY_NAME -> displayName; OpenableColumns.SIZE -> file.length(); else -> null } }
        return MatrixCursor(columns).apply { addRow(row) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    companion object {
        const val AUTHORITY = "it.liberofiles.app.files"
        fun uri(context: Context, node: Node): Uri {
            val graph = (context.applicationContext as FilesApplication).graph
            val store = graph.stores()[node]
            if (store is DocumentStorage) return store.uri(node)
            require(node.store == graph.local.key && java.nio.file.Paths.get(node.id).toAbsolutePath().normalize().startsWith(graph.local.root))
            val prefs = context.getSharedPreferences("share-tokens", Context.MODE_PRIVATE)
            val edit = prefs.edit(); val now = System.currentTimeMillis()
            for ((key, value) in prefs.all) if (key.endsWith(".expires") && value is Long && value < now) {
                edit.remove(key).remove(key.removeSuffix(".expires")).remove(key.removeSuffix(".expires")+".cachedKey")
            }
            val token = UUID.randomUUID().toString()
            edit.putString(token, node.id).putLong("$token.expires", now + 24 * 60 * 60 * 1000L).apply()
            return Uri.parse("content://$AUTHORITY/$token")
        }
        fun cachedUri(context: Context, key: String, file: java.io.File, name: String = file.name, mimeType: String? = null): Uri {
            (context.applicationContext as FilesApplication).graph.drive.account(key)
            require(file.canonicalFile.toPath().startsWith(java.io.File(context.cacheDir,"drive/$key").canonicalFile.toPath()))
            val token = UUID.randomUUID().toString()
            context.getSharedPreferences("share-tokens",Context.MODE_PRIVATE).edit()
                .putString(token,file.canonicalPath).putString("$token.cachedKey",key)
                .putString("$token.name",name).putString("$token.mime",mimeType ?: mime(name))
                .putLong("$token.expires",System.currentTimeMillis()+24*60*60*1000L).apply()
            return Uri.parse("content://$AUTHORITY/$token")
        }
        fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
