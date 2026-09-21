package eu.kanade.tachiyomi.ui.reader.filter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File

/** Release-build diagnostics, accessible only to the app, root and an authorized ADB shell. */
class ReaderImageRulesProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ReaderImageRules.initialize(requireNotNull(context))
        return true
    }

    private fun authorize() {
        if (Binder.getCallingUid() !in setOf(Process.myUid(), 0, 2000)) {
            throw SecurityException(
                "ADB shell access required",
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        authorize()
        require(uri.path == "/rules")
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(ReaderImageRules.export())) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        if (uri.path == "/rules" && mode == "r") {
            val snapshot = File.createTempFile("image-rules-", ".json", requireNotNull(context).cacheDir)
            try {
                snapshot.writeText(ReaderImageRules.export())
                return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                snapshot.delete()
            }
        }
        require(mode == "r" && uri.pathSegments.size == 2 && uri.pathSegments[0] == "original")
        return ParcelFileDescriptor.open(
            ReaderImageRules.original(uri.pathSegments[1]),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        authorize()
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "rules")
        return if (ReaderImageRules.remove(uri.pathSegments[1])) 1 else 0
    }

    override fun getType(uri: Uri) = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
