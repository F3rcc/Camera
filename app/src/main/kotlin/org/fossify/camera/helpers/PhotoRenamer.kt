package org.fossify.camera.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.fossify.commons.extensions.deleteFromMediaStore
import org.fossify.commons.extensions.rescanPath
import java.io.File

/**
 * Renames an already-saved photo across every storage location the app supports:
 *  - direct file paths (internal memory / public DCIM on legacy storage) -> [File.renameTo]
 *  - MediaStore content Uris (scoped storage fallback) -> [ContentResolver.update] on DISPLAY_NAME
 *  - SAF document Uris (SD card / restricted folders) -> [DocumentsContract.renameDocument]
 *
 * Detecting the storage type from the Uri at rename time (instead of relying on the current
 * "save to" setting) keeps the rename working even after the save folder is changed.
 */
class PhotoRenamer(private val context: Context) {

    private val contentResolver = context.contentResolver

    fun getDisplayName(uri: Uri): String? {
        return if (uri.scheme == "file") {
            uri.path?.let { File(it).name }
        } else {
            queryDisplayName(uri)
        }
    }

    /**
     * Renames the photo at [uri] to [newBaseName], preserving its extension.
     *
     * @return the resulting Uri (the same Uri for MediaStore, a new Uri for file/SAF), or null on
     * failure. When the new name equals the current one, the original [uri] is returned untouched.
     */
    fun renamePhoto(uri: Uri, newBaseName: String): Uri? {
        val currentName = getDisplayName(uri) ?: return null
        val extension = currentName.substringAfterLast('.', "")
        val newName = if (extension.isEmpty()) newBaseName else "$newBaseName.$extension"
        if (newName == currentName) {
            return uri
        }

        return when {
            uri.scheme == "file" -> renameFile(uri, newName)
            isMediaStoreUri(uri) -> renameMediaStore(uri, newBaseName, newName)
            else -> renameDocument(uri, newName)
        }
    }

    private fun isMediaStoreUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == "media"

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun renameMediaStore(uri: Uri, newBaseName: String, newName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                put(MediaStore.MediaColumns.TITLE, newBaseName)
            }
            val updated = contentResolver.update(uri, values, null, null)
            if (updated > 0) uri else null
        } catch (e: Exception) {
            null
        }
    }

    private fun renameFile(uri: Uri, newName: String): Uri? {
        val oldFile = File(uri.path ?: return null)
        val newFile = File(oldFile.parentFile, newName)
        if (!oldFile.exists() || !oldFile.renameTo(newFile)) {
            return null
        }

        // Keep the gallery in sync: drop any stale entry for the old path and scan the new one.
        context.deleteFromMediaStore(oldFile.absolutePath)
        context.rescanPath(newFile.absolutePath)
        return Uri.fromFile(newFile)
    }

    private fun renameDocument(uri: Uri, newName: String): Uri? {
        return try {
            DocumentsContract.renameDocument(contentResolver, uri, newName)
        } catch (e: Exception) {
            null
        }
    }
}
