package it.liberofiles.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Exposes only the verified update in private cache, through a temporary Android grant. */
class UpdateProvider : ContentProvider() {
    override fun onCreate() = true
    private fun apk(uri: Uri): File {
        require(uri.authority == "${context!!.packageName}.updates" && uri.path == "/gestorefile.apk")
        return File(context!!.cacheDir, "updates/gestorefile.apk")
    }
    override fun getType(uri: Uri): String { apk(uri); return "application/vnd.android.package-archive" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(apk(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val file = apk(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when(it) {
            OpenableColumns.DISPLAY_NAME -> file.name
            OpenableColumns.SIZE -> file.length()
            else -> null
        } }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
