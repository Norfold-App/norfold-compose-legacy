package com.norfold.app.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictReportTest {
    @Test
    fun reportIncludesSnapshotSummariesAndChangedObjects() {
        val local = BackupSnapshot(
            notes = listOf(
                Note(1, "Shared note", "local body", null, false, false, false, false, 1, 30),
                Note(2, "Local only", "draft", null, false, false, false, false, 1, 40),
            ),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
            tasks = listOf(TaskItem(1, "Local task", "finish", "@owner", TaskStatus.Doing, 1, 50)),
            taskPropertyDefinitions = listOf(TaskPropertyDefinition(1, 1, "Checklist", TaskPropertyType.Checklist, 0, false, "", 1, 51)),
            taskChecklistItems = listOf(TaskChecklistItem(1, 1, 1, "Local chapter", true, 0, 1, 52)),
            goals = listOf(GoalItem(1, 1, "goal-1", "Local goal", "goal", "You", 10.0, 4.0, "items", null, GoalStatus.InProgress, 1, 54)),
            calendarEvents = listOf(CalendarEventItem(1, 1, "event-1", "Local event", "review", 55, 65, false, 0xFF9A48FF, CalendarEventSource.Local, null, 1, 56)),
        )
        val remote = BackupSnapshot(
            notes = listOf(
                Note(1, "Shared note", "remote body", null, false, false, false, false, 1, 20),
                Note(3, "Remote only", "remote draft", null, false, false, false, false, 1, 60),
            ),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
        )

        val json = JSONObject(
            SyncConflictReport.build(
                format = "norfold-conflict-v3",
                localHash = "local",
                remoteHash = "remote",
                deviceName = "Pixel",
                localSnapshot = local,
                remoteSnapshot = remote,
            ),
        )

        assertEquals("norfold-conflict-v3", json.getString("format"))
        assertEquals(2, json.getJSONObject("local").getInt("notes"))
        assertEquals(2, json.getJSONObject("remote").getInt("notes"))
        assertEquals(1, json.getJSONObject("local").getInt("taskProperties"))
        assertEquals(1, json.getJSONObject("local").getInt("taskChecklistItems"))
        assertEquals(1, json.getJSONObject("local").getInt("goals"))
        assertEquals(1, json.getJSONObject("local").getInt("calendarEvents"))
        val diff = json.getJSONObject("diff")
        assertTrue(diff.getJSONArray("localOnly").toString().contains("Local only"))
        assertTrue(diff.getJSONArray("localOnly").toString().contains("Local chapter"))
        assertTrue(diff.getJSONArray("localOnly").toString().contains("Local goal"))
        assertTrue(diff.getJSONArray("localOnly").toString().contains("Local event"))
        assertTrue(diff.getJSONArray("remoteOnly").toString().contains("Remote only"))
        assertTrue(diff.getJSONArray("localNewer").toString().contains("Shared note"))
    }

    @Test
    fun parseRoundTripsGeneratedConflictReport() {
        val local = BackupSnapshot(
            notes = listOf(Note(10, "Local memo", "body", null, false, false, false, false, 1, 70)),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
            tasks = listOf(TaskItem(5, "Ship conflict review", "render remote side", "@owner", TaskStatus.Doing, 1, 80)),
        )
        val remote = BackupSnapshot(
            notes = listOf(Note(11, "Remote memo", "body", null, false, false, false, false, 1, 90)),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
        )

        val parsed = SyncConflictReport.parseOrNull(
            SyncConflictReport.build(
                format = "norfold-google-drive-conflict-v3",
                localHash = "local-hash",
                remoteHash = "remote-hash",
                deviceName = "Pixel 9",
                localSnapshot = local,
                remoteSnapshot = remote,
            ),
        )

        requireNotNull(parsed)
        assertEquals("norfold-google-drive-conflict-v3", parsed.format)
        assertEquals("Pixel 9", parsed.deviceName)
        assertEquals(1, parsed.local.notes)
        assertEquals(1, parsed.local.tasks)
        assertEquals(1, parsed.remote.notes)
        assertTrue(parsed.local.recent.any { it.contains("Ship conflict review") })
        assertTrue(parsed.diff.localOnly.any { it.contains("Local memo") })
        assertTrue(parsed.diff.remoteOnly.any { it.contains("Remote memo") })
    }
}
