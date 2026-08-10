package com.norfold.app.collaboration.protocol

import kotlinx.serialization.json.JsonPrimitive

/** Provider-independent protection for invariants that must hold before any editor domain runs. */
object SemanticProjectionValidator {
    fun rejectReason(operations: List<SemanticMutation>): String? {
        if (operations.size > 50_000) return "canonical_state_too_large"
        operations.forEach { mutation ->
            if (!mutation.isSupported) return "unsupported_semantic_operation"
            if (mutation.domain == "spreadsheet" && mutation.payload.keys.any { it in CALCULATED_KEYS }) {
                return "calculated_spreadsheet_state_forbidden"
            }
            if (mutation.domain == "chart" && mutation.payload.keys.any { it in RENDERED_CHART_KEYS }) {
                return "rendered_chart_geometry_forbidden"
            }
            if (mutation.domain == "image" && "preview" in mutation.action) return "image_preview_forbidden"
        }
        return structuralCycle(operations)
    }

    private fun structuralCycle(operations: List<SemanticMutation>): String? {
        val parents = HashMap<String, String>()
        operations.forEach { mutation ->
            if (!mutation.action.contains("move") && !mutation.action.contains("reparent")) return@forEach
            val item = (mutation.payload["itemId"] as? JsonPrimitive)?.content ?: return@forEach
            val parent = (mutation.payload["parentId"] as? JsonPrimitive)?.content ?: return@forEach
            if (item == parent) return "structural_parent_cycle"
            parents[item] = parent
        }
        parents.keys.forEach { start ->
            val path = HashSet<String>()
            var cursor: String? = start
            while (cursor != null && path.add(cursor)) cursor = parents[cursor]
            if (cursor != null) return "structural_parent_cycle"
        }
        return null
    }

    private val CALCULATED_KEYS = setOf("calculated", "result", "displayValue", "cachedValue")
    private val RENDERED_CHART_KEYS = setOf("renderedGeometry", "pixelBounds", "previewFrame")
}
