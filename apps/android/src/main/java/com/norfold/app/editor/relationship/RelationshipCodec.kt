package com.norfold.app.editor.relationship

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

object Phase7ArtifactCodec {
    const val SCHEMA_VERSION = 1
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val known = setOf("v", "relationships", "threads", "snapshots")

    fun encode(state: Phase7ArtifactState): String {
        state.opaqueFuturePayload?.let { return it }
        val canonical = json.encodeToJsonElement(Phase7ArtifactState.serializer(), state).jsonObject
        return buildJsonObject {
            state.unknownTopLevel.forEach { (key, value) -> if (key !in known) put(key, value) }
            put("v", JsonPrimitive(SCHEMA_VERSION))
            put("relationships", canonical.getValue("relationships"))
            put("threads", canonical.getValue("threads"))
            put("snapshots", canonical.getValue("snapshots"))
        }.toString()
    }

    fun decode(payload: String?): Phase7ArtifactState {
        if (payload.isNullOrBlank()) return Phase7ArtifactState()
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return Phase7ArtifactState(opaqueFuturePayload = payload)
        val version = (root["v"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > SCHEMA_VERSION) return Phase7ArtifactState(opaqueFuturePayload = payload)
        val normalized = buildJsonObject {
            put("relationships", root["relationships"] ?: json.parseToJsonElement("[]"))
            put("threads", root["threads"] ?: json.parseToJsonElement("[]"))
            put("snapshots", root["snapshots"] ?: json.parseToJsonElement("[]"))
        }
        val decoded = runCatching { json.decodeFromJsonElement(Phase7ArtifactState.serializer(), normalized) }
            .getOrElse { return Phase7ArtifactState(opaqueFuturePayload = payload) }
        return decoded.copy(unknownTopLevel = JsonObject(root - known))
    }
}
