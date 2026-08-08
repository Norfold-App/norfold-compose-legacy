package com.norfold.app.editor.image

import com.norfold.app.domain.ImageBlock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class ImageEditEnvelope(val schemaVersion: Int, val document: ImageEditDocument)

@Serializable
private data class ImagePresetEnvelope(val schemaVersion: Int = 1, val presets: List<ImagePreset>)

sealed interface ImageEditDecodeResult {
    data class Success(val document: ImageEditDocument) : ImageEditDecodeResult
    data class UnsupportedVersion(val version: Int, val raw: String) : ImageEditDecodeResult
    data class Malformed(val raw: String) : ImageEditDecodeResult
}

object ImageEditDocumentCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    fun encode(document: ImageEditDocument): String = json.encodeToString(
        ImageEditEnvelope(ImageEditDocument.CURRENT_SCHEMA_VERSION, document.normalized()),
    )

    fun decode(raw: String): ImageEditDecodeResult {
        if (raw.isBlank()) return ImageEditDecodeResult.Malformed(raw)
        val version = runCatching {
            json.parseToJsonElement(raw).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: return ImageEditDecodeResult.Malformed(raw)
        if (version != ImageEditDocument.CURRENT_SCHEMA_VERSION) {
            return ImageEditDecodeResult.UnsupportedVersion(version, raw)
        }
        return runCatching { json.decodeFromString<ImageEditEnvelope>(raw).document.normalized() }
            .fold(ImageEditDecodeResult::Success) { ImageEditDecodeResult.Malformed(raw) }
    }
}

object ImagePresetCodec {
    private const val VERSION = 1
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    fun encode(presets: List<ImagePreset>): String = json.encodeToString(
        ImagePresetEnvelope(
            schemaVersion = VERSION,
            presets = presets.take(64).map { preset ->
                preset.copy(id = preset.id.trim().take(128), name = preset.name.trim().take(128), parameters = preset.parameters.normalized())
            },
        ),
    )

    fun decode(raw: String): List<ImagePreset>? = runCatching {
        val envelope = json.decodeFromString<ImagePresetEnvelope>(raw)
        if (envelope.schemaVersion != VERSION) return null
        envelope.presets.take(64).map { preset ->
            preset.copy(id = preset.id.trim().take(128), name = preset.name.trim().take(128), parameters = preset.parameters.normalized())
        }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
    }.getOrNull()
}

data class LoadedImageEditDocument(
    val document: ImageEditDocument,
    val origin: Origin,
    val preservedPayload: String?,
) {
    enum class Origin { CANONICAL, LEGACY_EMPTY, UNSUPPORTED_CANONICAL, MALFORMED_CANONICAL }
    val isEditable get() = origin != Origin.UNSUPPORTED_CANONICAL && origin != Origin.MALFORMED_CANONICAL
}

/** Loading is side-effect free; saving changes only the additive canonical field. */
object ImageBlockAdapter {
    fun load(block: ImageBlock): LoadedImageEditDocument {
        val raw = block.imageEditDocument
            ?: return LoadedImageEditDocument(ImageEditDocument(), LoadedImageEditDocument.Origin.LEGACY_EMPTY, null)
        return when (val decoded = ImageEditDocumentCodec.decode(raw)) {
            is ImageEditDecodeResult.Success -> LoadedImageEditDocument(
                decoded.document, LoadedImageEditDocument.Origin.CANONICAL, raw,
            )
            is ImageEditDecodeResult.UnsupportedVersion -> LoadedImageEditDocument(
                ImageEditDocument(), LoadedImageEditDocument.Origin.UNSUPPORTED_CANONICAL, decoded.raw,
            )
            is ImageEditDecodeResult.Malformed -> LoadedImageEditDocument(
                ImageEditDocument(), LoadedImageEditDocument.Origin.MALFORMED_CANONICAL, decoded.raw,
            )
        }
    }

    fun save(block: ImageBlock, document: ImageEditDocument): ImageBlock =
        block.copy(imageEditDocument = ImageEditDocumentCodec.encode(document))
}
