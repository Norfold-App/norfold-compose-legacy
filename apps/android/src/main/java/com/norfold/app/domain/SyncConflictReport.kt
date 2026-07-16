package com.norfold.app.domain

import org.json.JSONArray
import org.json.JSONObject

data class ParsedSyncConflictReport(
    val format: String,
    val deviceName: String,
    val localHash: String,
    val remoteHash: String,
    val createdAt: Long,
    val local: ConflictSideSummary,
    val remote: ConflictSideSummary,
    val diff: ConflictDiffSummary,
)

data class ConflictSideSummary(
    val notes: Int,
    val taskBoards: Int,
    val taskColumns: Int,
    val tasks: Int,
    val taskProperties: Int,
    val taskPropertyValues: Int,
    val taskChecklistItems: Int,
    val goals: Int,
    val calendarEvents: Int,
    val chatMessages: Int,
    val workspaceObjects: Int,
    val objectLinks: Int,
    val files: Int,
    val comments: Int,
    val history: Int,
    val lastUpdatedAt: Long,
    val recent: List<String>,
)

data class ConflictDiffSummary(
    val localOnly: List<String>,
    val remoteOnly: List<String>,
    val localNewer: List<String>,
    val remoteNewer: List<String>,
    val changedCount: Int,
)

object SyncConflictReport {
    fun build(
        format: String,
        localHash: String,
        remoteHash: String,
        deviceName: String,
        localSnapshot: BackupSnapshot,
        remoteSnapshot: BackupSnapshot,
    ): String = JSONObject()
        .put("format", format)
        .put("deviceName", deviceName)
        .put("localHash", localHash)
        .put("remoteHash", remoteHash)
        .put("createdAt", System.currentTimeMillis())
        .put("resolution", "open Norfold conflict review")
        .put("local", localSnapshot.toSummary())
        .put("remote", remoteSnapshot.toSummary())
        .put("diff", compare(localSnapshot, remoteSnapshot))
        .toString(2)

    fun parseOrNull(payload: String?): ParsedSyncConflictReport? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(payload)
            val format = json.optString("format")
            if (!format.contains("conflict", ignoreCase = true)) return@runCatching null
            ParsedSyncConflictReport(
                format = format,
                deviceName = json.optString("deviceName"),
                localHash = json.optString("localHash"),
                remoteHash = json.optString("remoteHash"),
                createdAt = json.optLong("createdAt"),
                local = json.optJSONObject("local").toSideSummary(),
                remote = json.optJSONObject("remote").toSideSummary(),
                diff = json.optJSONObject("diff").toDiffSummary(),
            )
        }.getOrNull()
    }

    private fun JSONObject?.toSideSummary(): ConflictSideSummary {
        val json = this ?: JSONObject()
        return ConflictSideSummary(
            notes = json.optInt("notes"),
            taskBoards = json.optInt("taskBoards"),
            taskColumns = json.optInt("taskColumns"),
            tasks = json.optInt("tasks"),
            taskProperties = json.optInt("taskProperties"),
            taskPropertyValues = json.optInt("taskPropertyValues"),
            taskChecklistItems = json.optInt("taskChecklistItems"),
            goals = json.optInt("goals"),
            calendarEvents = json.optInt("calendarEvents"),
            chatMessages = json.optInt("chatMessages"),
            workspaceObjects = json.optInt("workspaceObjects"),
            objectLinks = json.optInt("objectLinks"),
            files = json.optInt("files"),
            comments = json.optInt("comments"),
            history = json.optInt("history"),
            lastUpdatedAt = json.optLong("lastUpdatedAt"),
            recent = json.optJSONArray("recent").toStringList(),
        )
    }

    private fun JSONObject?.toDiffSummary(): ConflictDiffSummary {
        val json = this ?: JSONObject()
        return ConflictDiffSummary(
            localOnly = json.optJSONArray("localOnly").toStringList(),
            remoteOnly = json.optJSONArray("remoteOnly").toStringList(),
            localNewer = json.optJSONArray("localNewer").toStringList(),
            remoteNewer = json.optJSONArray("remoteNewer").toStringList(),
            changedCount = json.optInt("changedCount"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun BackupSnapshot.toSummary(): JSONObject = JSONObject()
        .put("notes", notes.size)
        .put("taskBoards", taskBoards.size)
        .put("taskColumns", taskColumns.size)
        .put("tasks", tasks.size)
        .put("taskProperties", taskPropertyDefinitions.size)
        .put("taskPropertyValues", taskPropertyValues.size)
        .put("taskChecklistItems", taskChecklistItems.size)
        .put("goals", goals.size)
        .put("calendarEvents", calendarEvents.size)
        .put("chatMessages", chatMessages.size)
        .put("workspaceObjects", workspaceObjects.size)
        .put("objectLinks", workspaceObjectLinks.size)
        .put("files", workspaceFiles.size)
        .put("comments", workspaceComments.size)
        .put("history", workspaceObjectHistory.size)
        .put("lastUpdatedAt", lastUpdatedAt())
        .put("recent", JSONArray(recentObjectLabels()))

    private fun BackupSnapshot.lastUpdatedAt(): Long = listOf(
        notes.maxOfOrNull { it.updatedAt } ?: 0L,
        taskBoards.maxOfOrNull { it.updatedAt } ?: 0L,
        taskColumns.maxOfOrNull { it.updatedAt } ?: 0L,
        tasks.maxOfOrNull { it.updatedAt } ?: 0L,
        taskPropertyDefinitions.maxOfOrNull { it.updatedAt } ?: 0L,
        taskPropertyValues.maxOfOrNull { it.updatedAt } ?: 0L,
        taskChecklistItems.maxOfOrNull { it.updatedAt } ?: 0L,
        goals.maxOfOrNull { it.updatedAt } ?: 0L,
        calendarEvents.maxOfOrNull { it.updatedAt } ?: 0L,
        chatMessages.maxOfOrNull { it.createdAt } ?: 0L,
        workspaceObjects.maxOfOrNull { it.updatedAt } ?: 0L,
        workspaceFiles.maxOfOrNull { it.updatedAt } ?: 0L,
        workspaceComments.maxOfOrNull { it.updatedAt } ?: 0L,
        workspaceObjectHistory.maxOfOrNull { it.createdAt } ?: 0L,
    ).max()

    private fun BackupSnapshot.recentObjectLabels(): List<String> = buildList {
        addAll(notes.map { SnapshotObject("Doc", it.id.toString(), it.title, it.updatedAt, it.bodyMarkdown.take(120)) })
        addAll(taskBoards.map { SnapshotObject("Task board", it.id.toString(), it.name, it.updatedAt, "") })
        addAll(taskColumns.map { SnapshotObject("Task column", it.id.toString(), it.name, it.updatedAt, it.status?.name.orEmpty()) })
        addAll(tasks.map { SnapshotObject("Task", it.id.toString(), it.title, it.updatedAt, it.description.take(120)) })
        addAll(taskPropertyDefinitions.map { SnapshotObject("Task property", it.id.toString(), it.name, it.updatedAt, it.type.name) })
        addAll(taskChecklistItems.map { SnapshotObject("Task checklist", it.id.toString(), it.text, it.updatedAt, if (it.checked) "checked" else "open") })
        addAll(goals.map { SnapshotObject("Goal", it.syncId, it.title, it.updatedAt, "${it.progress}/${it.target} ${it.unit}") })
        addAll(calendarEvents.map { SnapshotObject("Calendar event", it.syncId, it.title, it.updatedAt, it.description.take(120)) })
        addAll(workspaceFiles.map { SnapshotObject("File", it.id.toString(), it.displayName, it.updatedAt, it.mimeType) })
        addAll(workspaceObjects.map { SnapshotObject(if (it.objectType == WorkspaceObjectType.Note) "Doc" else it.objectType.name, it.sourceId?.toString() ?: "object-${it.id}", it.title, it.updatedAt, it.summary.take(120)) })
    }
        .sortedByDescending { it.updatedAt }
        .distinctBy { "${it.type}:${it.key}" }
        .take(10)
        .map { "${it.type}: ${it.title}" }

    private fun compare(local: BackupSnapshot, remote: BackupSnapshot): JSONObject {
        val localObjects = local.snapshotObjects().associateBy { it.identity }
        val remoteObjects = remote.snapshotObjects().associateBy { it.identity }
        val localOnly = localObjects.keys.minus(remoteObjects.keys).mapNotNull(localObjects::get).sortedByDescending { it.updatedAt }
        val remoteOnly = remoteObjects.keys.minus(localObjects.keys).mapNotNull(remoteObjects::get).sortedByDescending { it.updatedAt }
        val changed = localObjects.keys.intersect(remoteObjects.keys).mapNotNull { key ->
            val a = localObjects[key] ?: return@mapNotNull null
            val b = remoteObjects[key] ?: return@mapNotNull null
            if (a.signature == b.signature) null else a to b
        }
        return JSONObject()
            .put("localOnly", JSONArray(localOnly.take(20).map { it.toLabel() }))
            .put("remoteOnly", JSONArray(remoteOnly.take(20).map { it.toLabel() }))
            .put("localNewer", JSONArray(changed.filter { it.first.updatedAt >= it.second.updatedAt }.sortedByDescending { it.first.updatedAt }.take(20).map { it.first.toLabel() }))
            .put("remoteNewer", JSONArray(changed.filter { it.second.updatedAt > it.first.updatedAt }.sortedByDescending { it.second.updatedAt }.take(20).map { it.second.toLabel() }))
            .put("changedCount", changed.size)
    }

    private fun BackupSnapshot.snapshotObjects(): List<SnapshotObject> = buildList {
        addAll(notes.map { SnapshotObject("Doc", it.id.toString(), it.title, it.updatedAt, "${it.title}\n${it.bodyMarkdown}\n${it.tags.joinToString { tag -> tag.name }}") })
        addAll(taskBoards.map { SnapshotObject("Task board", it.id.toString(), it.name, it.updatedAt, "${it.name}\n${it.workspaceId}") })
        addAll(taskColumns.map { SnapshotObject("Task column", it.id.toString(), it.name, it.updatedAt, "${it.boardId}\n${it.name}\n${it.status}\n${it.sortOrder}") })
        addAll(tasks.map { SnapshotObject("Task", it.id.toString(), it.title, it.updatedAt, "${it.title}\n${it.description}\n${it.status}\n${it.priority}\n${it.assignee}\n${it.labels}\n${it.taskBoardId}\n${it.taskColumnId}\n${it.sortOrder}\n${it.colorArgb}\n${it.startAt}\n${it.dueAt}\n${it.allDay}\n${it.reminderMinutesBefore}") })
        addAll(taskPropertyDefinitions.map { SnapshotObject("Task property", it.id.toString(), it.name, it.updatedAt, "${it.boardId}\n${it.name}\n${it.type}\n${it.sortOrder}\n${it.hiddenWhenEmpty}\n${it.optionsJson}") })
        addAll(taskPropertyValues.map { SnapshotObject("Task property value", it.id.toString(), "${it.taskId}:${it.propertyId}", it.updatedAt, "${it.taskId}\n${it.propertyId}\n${it.valueJson}") })
        addAll(taskChecklistItems.map { SnapshotObject("Task checklist", it.id.toString(), it.text, it.updatedAt, "${it.taskId}\n${it.propertyId}\n${it.text}\n${it.checked}\n${it.sortOrder}") })
        addAll(goals.map { SnapshotObject("Goal", it.syncId, it.title, it.updatedAt, "${it.workspaceId}\n${it.title}\n${it.description}\n${it.owner}\n${it.target}\n${it.progress}\n${it.unit}\n${it.dueAt}\n${it.status}") })
        addAll(calendarEvents.map { SnapshotObject("Calendar event", it.syncId, it.title, it.updatedAt, "${it.workspaceId}\n${it.title}\n${it.description}\n${it.startAt}\n${it.endAt}\n${it.allDay}\n${it.color}\n${it.source}\n${it.externalId}") })
        addAll(chatMessages.map { SnapshotObject("Chat", it.id.toString(), it.authorDisplayName, it.createdAt, "${it.authorUsername}\n${it.body}\n${it.attachmentName.orEmpty()}") })
        addAll(workspaceFiles.map { SnapshotObject("File", it.id.toString(), it.displayName, it.updatedAt, "${it.displayName}\n${it.mimeType}\n${it.uri}\n${it.sizeBytes}") })
        addAll(workspaceComments.map { SnapshotObject("Comment", it.id.toString(), it.authorDisplayName, it.updatedAt, "${it.objectId}\n${it.body}\n${it.resolved}") })
    }

    private data class SnapshotObject(
        val type: String,
        val key: String,
        val title: String,
        val updatedAt: Long,
        val signature: String,
    ) {
        val identity: String = "$type:$key"
        fun toLabel(): String = "$type: ${title.ifBlank { key }}"
    }
}
