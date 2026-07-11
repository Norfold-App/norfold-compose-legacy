package com.norfold.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupFolderStore(private val context: Context) {
    suspend fun writeEncryptedBackup(treeUri: Uri, payload: String): String = withContext(Dispatchers.IO) {
        persistTreePermission(treeUri)
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open selected backup folder.")
        val folder = root.findFile(AppFolderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(AppFolderName)
            ?: error("Cannot create $AppFolderName folder.")
        val fileName = "norfold-backup-${Timestamp.format(Date())}.enc"
        val file = folder.createFile("application/octet-stream", fileName) ?: error("Cannot create backup file.")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use { it.write(payload) }
            ?: error("Cannot write backup file.")
        fileName
    }

    private fun persistTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
    }

    companion object {
        private const val AppFolderName = "Norfold"
        private val Timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
