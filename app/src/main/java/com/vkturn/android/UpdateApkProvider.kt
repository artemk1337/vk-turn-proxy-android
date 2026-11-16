package com.vkturn.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

class UpdateApkProvider : ContentProvider() {
    override fun onCreate() = true

    private fun validate(uri: Uri) {
        require(uri == Uri.parse("content://${context!!.packageName}.updates/update.apk"))
    }

    override fun getType(uri: Uri): String {
        validate(uri)
        return "application/vnd.android.package-archive"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        validate(uri)
        require(mode == "r")
        return ParcelFileDescriptor.open(AppUpdates.apk(context!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        validate(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "update.apk"
                    OpenableColumns.SIZE -> AppUpdates.apk(context!!).length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
