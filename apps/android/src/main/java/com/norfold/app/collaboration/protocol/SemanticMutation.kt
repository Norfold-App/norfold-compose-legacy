package com.norfold.app.collaboration.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Transport-independent, forward-compatible mutation of one stable editor-domain object. */
@Serializable
data class SemanticMutation(
    val version: Int = CURRENT_VERSION,
    val domain: String,
    val action: String,
    val targetId: String,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(version > 0) { "Semantic version must be positive" }
        require(domain.matches(TOKEN)) { "Invalid semantic domain" }
        require(action.matches(TOKEN)) { "Invalid semantic action" }
        require(targetId.isNotBlank() && targetId.length <= MAX_ID_CHARS) { "Invalid stable target id" }
    }

    val isSupported: Boolean get() = version <= CURRENT_VERSION && domain in SUPPORTED_DOMAINS

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ID_CHARS = 256
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        const val MAX_BATCH_OPERATIONS = 256
        private val TOKEN = Regex("[a-z][a-z0-9_.-]{0,63}")

        val SUPPORTED_DOMAINS = setOf(
            "flow", "document", "style", "spreadsheet", "chart", "image",
            "relationship", "comment", "interchange",
        )

        val LOCAL_ONLY_DOMAINS = setOf(
            "guide", "history-ui", "recovery", "tool-state", "hover", "preview",
            "selection-handle", "scroll", "preference", "instrumentation",
        )
    }
}

/** One atomic editor transaction. Unknown mutations stay byte-for-byte representable in [operations]. */
@Serializable
data class SemanticBatch(
    val batchId: String,
    val commandId: String,
    val operations: List<SemanticMutation>,
    val atomic: Boolean = true,
) {
    init {
        require(batchId.isNotBlank() && batchId.length <= SemanticMutation.MAX_ID_CHARS)
        require(commandId.isNotBlank() && commandId.length <= SemanticMutation.MAX_ID_CHARS)
        require(operations.isNotEmpty() && operations.size <= SemanticMutation.MAX_BATCH_OPERATIONS)
        require(operations.none { it.domain in SemanticMutation.LOCAL_ONLY_DOMAINS }) {
            "Local-only editor state cannot enter collaboration"
        }
    }
}

sealed interface SemanticDecodeResult {
    data class Supported(val batch: SemanticBatch) : SemanticDecodeResult
    data class Future(val raw: JsonElement, val semanticVersion: Int) : SemanticDecodeResult
    data class Rejected(val reason: String) : SemanticDecodeResult
}

/** Canonical JSON is used for hashes, snapshots and deterministic simulation—not provider wire types. */
object SemanticOperationCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encode(batch: SemanticBatch): ByteArray {
        val element = json.encodeToJsonElement(SemanticBatch.serializer(), batch)
        require(structureWithinBounds(element)) { "Semantic batch structure exceeds bound" }
        val bytes = json.encodeToString(JsonElement.serializer(), canonicalize(element)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= SemanticMutation.MAX_PAYLOAD_BYTES) { "Semantic batch exceeds payload limit" }
        return bytes
    }

    fun decode(bytes: ByteArray): SemanticDecodeResult {
        if (bytes.size > SemanticMutation.MAX_PAYLOAD_BYTES) return SemanticDecodeResult.Rejected("payload_too_large")
        if (!jsonDepthWithin(bytes)) return SemanticDecodeResult.Rejected("payload_structure_too_deep")
        return try {
            val raw = json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            if (!structureWithinBounds(raw)) return SemanticDecodeResult.Rejected("payload_structure_too_complex")
            val maxVersion = (raw as? JsonObject)
                ?.get("operations")
                ?.let { it as? JsonArray }
                ?.maxOfOrNull { (it as? JsonObject)?.get("version")?.toString()?.trim('"')?.toIntOrNull() ?: 1 }
                ?: 1
            if (maxVersion > SemanticMutation.CURRENT_VERSION) {
                SemanticDecodeResult.Future(canonicalize(raw), maxVersion)
            } else {
                SemanticDecodeResult.Supported(json.decodeFromJsonElement(SemanticBatch.serializer(), raw))
            }
        } catch (_: RuntimeException) {
            SemanticDecodeResult.Rejected("malformed_semantic_batch")
        }
    }

    fun hash(batch: SemanticBatch): String = sha256(encode(batch))

    fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        is JsonPrimitive -> element
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun structureWithinBounds(root: JsonElement): Boolean {
        val pending = java.util.ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(root to 1)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            if (++nodes > 10_000 || depth > 64) return false
            when (element) {
                is JsonObject -> element.values.forEach { pending.add(it to depth + 1) }
                is JsonArray -> element.forEach { pending.add(it to depth + 1) }
                is JsonPrimitive -> Unit
            }
        }
        return true
    }

    private fun jsonDepthWithin(bytes: ByteArray): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        bytes.forEach { raw ->
            val char = raw.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }
}
