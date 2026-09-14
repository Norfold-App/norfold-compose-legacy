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
    data class Payload(
        val json: String?,
        val plainText: String?,
        val source: String,
        val privateMimeAdvertised: Boolean,
        val privateMimeRead: Boolean,
    )

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
            ?: return Payload(null, null, "plain-text", false, false)
        var json: String? = null
        var plainText: String? = null
        val privateMimeAdvertised = clip.description.hasMimeType(PRIVATE_MIME)
        var privateMimeRead = false
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            if (json == null && privateMimeAdvertised) {
                json = item.uri?.let(::readUri)
                privateMimeRead = json != null
            }
            if (plainText == null) {
                plainText = item.text?.toString()
            }
        }
        if (json == null && plainText?.looksLikePayload() == true) {
            json = plainText
        }
        val source = when {
            privateMimeRead -> "private-uri"
            json != null -> "json-text-fallback"
            else -> "plain-text"
        }
        return Payload(json, plainText, source, privateMimeAdvertised, privateMimeRead)
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
