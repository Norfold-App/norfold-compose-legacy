package com.norfold.app.domain

import org.json.JSONObject

object TaskDateRangeCodec {
    fun encode(value: TaskDateRange): String {
        val normalized = value.normalized()
        if (normalized.startAt == null && normalized.endAt == null) return ""
        return JSONObject().apply {
            normalized.startAt?.let { put("startAt", it) }
            normalized.endAt?.let { put("endAt", it) }
            put("allDay", normalized.allDay)
            normalized.reminderMinutesBefore?.let { put("reminderMinutesBefore", it) }
        }.toString()
    }

    fun decode(raw: String, fallbackEndAt: Long? = null): TaskDateRange {
        if (raw.isBlank()) return TaskDateRange(fallbackEndAt, fallbackEndAt)
        raw.toLongOrNull()?.let { return TaskDateRange(it, it) }
        return runCatching {
            val json = JSONObject(raw)
            TaskDateRange(
                startAt = json.optLongOrNull("startAt"),
                endAt = json.optLongOrNull("endAt") ?: fallbackEndAt,
                allDay = json.optBoolean("allDay", true),
                reminderMinutesBefore = json.optIntOrNull("reminderMinutesBefore"),
            ).normalized()
        }.getOrElse { TaskDateRange(fallbackEndAt, fallbackEndAt) }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
}
