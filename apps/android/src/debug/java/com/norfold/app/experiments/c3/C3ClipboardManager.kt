package com.norfold.app.experiments.c3

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

class C3FileProvider : FileProvider()

class C3ClipboardManager(private val context: Context) {
    data class Payload(val json: String?, val plainText: String?)

    fun copy(json: String, plainText: String) {
        val file = File(context.cacheDir, "norfold-clipboard-${UUID.randomUUID()}.json")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.c3.fileprovider", file)
        val description = ClipDescription(
            "norfold-structured",
            arrayOf(PRIVATE_MIME, ClipDescription.MIMETYPE_TEXT_PLAIN),
        )
        val clip = ClipData(description, ClipData.Item(plainText.ifEmpty { json }, null, null, uri))
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    fun paste(): Payload {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            ?: return Payload(null, null)
        var json: String? = null
        var plainText: String? = null
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            if (json == null && clip.description.hasMimeType(PRIVATE_MIME)) {
                json = item.uri?.let(::readUri)
            }
            if (plainText == null) {
                plainText = item.text?.toString()
            }
        }
        if (json == null && plainText?.looksLikePayload() == true) {
            json = plainText
        }
        return Payload(json, plainText)
    }

    private fun readUri(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private fun String.looksLikePayload(): Boolean =
        trimStart().startsWith("{") && contains("\"type\"")

    companion object {
        const val PRIVATE_MIME = "application/vnd.norfold.blockslice+json"
    }
}
