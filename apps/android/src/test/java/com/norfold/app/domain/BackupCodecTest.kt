package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCodecTest {
    @Test
    fun backupRoundTripsNotesNotebooksTagsAndAttachments() {
        val snapshot = BackupSnapshot(
            taskWorkspacePrefs = TaskWorkspacePrefs(
                viewMode = "Table",
                selectedBoardId = 3,
                sortMode = "DueDate",
                compactLayout = false,
                kanbanEngine = "BoardPointer",
            ),
            notebooks = listOf(Notebook(1, "Work", null, 0xFF8B5CF6, 0)),
            tags = listOf(
                Tag(1, "sync", 0xFF8B5CF6),
                Tag(2, "Planning", 0xFF8B5CF6, scope = "board:3"),
            ),
            notes = listOf(
                Note(
                    id = 1,
                    title = "Project",
                    bodyMarkdown = "# Project\n\n- [x] Done",
                    notebookId = 1,
                    pinned = true,
                    starred = true,
                    archived = false,
                    locked = false,
                    createdAt = 10,
                    updatedAt = 20,
                    tags = listOf(Tag(1, "sync", 0xFF8B5CF6)),
                ),
            ),
            attachments = listOf(Attachment(1, 1, "image.png", "image/png", "content://image", 128)),
            taskBoards = listOf(TaskBoardItem(3, "Project board", 1, 21, 22)),
            taskColumns = listOf(TaskColumnItem(4, 3, "Review", TaskStatus.Doing, 0xFF4FACFE, 1, 23, 24)),
            taskPropertyDefinitions = listOf(TaskPropertyDefinition(8, 3, "Checklist", TaskPropertyType.Checklist, 2, false, "", 25, 26)),
            taskPropertyValues = listOf(TaskPropertyValue(9, 7, 8, "2/3", 41)),
            taskChecklistItems = listOf(TaskChecklistItem(10, 7, 8, "Chapter 1", true, 0, 42, 43)),
            tasks = listOf(
                TaskItem(
                    id = 7,
                    title = "Review brief",
                    description = "Check file",
                    assignee = "@owner",
                    status = TaskStatus.Doing,
                    createdAt = 30,
                    updatedAt = 40,
                    priority = TaskPriority.High,
                    startAt = 45,
                    dueAt = 50,
                    allDay = false,
                    reminderMinutesBefore = 60,
                    labels = "planning,review",
                    attachmentName = "brief.pdf",
                    attachmentMimeType = "application/pdf",
                    attachmentUri = "content://brief",
                    attachmentSizeBytes = 2048,
                    taskBoardId = 3,
                    taskColumnId = 4,
                    sortOrder = 2,
                ),
            ),
            goals = listOf(
                GoalItem(72, 1, "goal-sync-72", "Frontend acceptance", "Complete the primary destinations", "You", 100.0, 65.0, "%", 240, GoalStatus.InProgress, 141, 161),
            ),
            calendarEvents = listOf(
                CalendarEventItem(73, 1, "event-sync-73", "Design review", "Review adaptive layouts", 200, 220, false, 0xFF9A48FF, CalendarEventSource.Local, null, 142, 162),
            ),
            workspaceObjects = listOf(
                WorkspaceObject(
                    id = 11,
                    objectType = WorkspaceObjectType.Note,
                    sourceId = 1,
                    title = "Project",
                    summary = "Object summary",
                    tags = "sync",
                    icon = "note",
                    color = 0xFF9D6CFF,
                    pinned = true,
                    archived = false,
                    createdAt = 10,
                    updatedAt = 20,
                ),
                WorkspaceObject(
                    id = 12,
                    objectType = WorkspaceObjectType.File,
                    sourceId = null,
                    title = "brief.pdf",
                    summary = "application/pdf",
                    tags = "",
                    icon = "file",
                    color = 0xFF4AADFF,
                    pinned = false,
                    archived = false,
                    createdAt = 60,
                    updatedAt = 70,
                ),
            ),
            workspaceObjectLinks = listOf(WorkspaceObjectLink(21, 11, 12, WorkspaceLinkType.Attachment, "brief.pdf", 80)),
            workspaceActivities = listOf(WorkspaceActivity(31, 11, WorkspaceActivityType.Updated, "You", "Updated note", "Project", 90)),
            workspaceObjectHistory = listOf(WorkspaceObjectHistory(41, 11, WorkspaceHistoryType.Updated, "You", "Updated note", "before", "after", 100)),
            workspaceComments = listOf(WorkspaceComment(51, 11, "you", "You", "Looks good", false, 110, 120)),
            workspaceFiles = listOf(WorkspaceFileItem(61, 12, "brief.pdf", "application/pdf", "content://brief", 4096, 130, 140)),
        )

        val restored = BackupCodec.decode(BackupCodec.encode(snapshot))

        assertEquals("Project", restored.notes.single().title)
        assertEquals("Work", restored.notebooks.single().name)
        assertEquals("sync", restored.tags.first { it.scope == "notes" }.name)
        assertEquals("Planning", restored.tags.first { it.scope == "board:3" }.name)
        assertEquals("image.png", restored.attachments.single().displayName)
        assertEquals("planning,review", restored.tasks.single().labels)
        assertEquals("brief.pdf", restored.tasks.single().attachmentName)
        assertEquals("Project board", restored.taskBoards.single().name)
        assertEquals(45L, restored.tasks.single().startAt)
        assertEquals(false, restored.tasks.single().allDay)
        assertEquals(60, restored.tasks.single().reminderMinutesBefore)
        assertEquals("Review", restored.taskColumns.single().name)
        assertEquals("Checklist", restored.taskPropertyDefinitions.single().name)
        assertEquals("2/3", restored.taskPropertyValues.single().valueJson)
        assertEquals("Chapter 1", restored.taskChecklistItems.single().text)
        assertEquals(true, restored.taskChecklistItems.single().checked)
        assertEquals(4L, restored.tasks.single().taskColumnId)
        assertEquals(2, restored.tasks.single().sortOrder)
        assertEquals("Project", restored.workspaceObjects.first().title)
        assertEquals(WorkspaceLinkType.Attachment, restored.workspaceObjectLinks.single().linkType)
        assertEquals("Updated note", restored.workspaceActivities.single().title)
        assertEquals(WorkspaceHistoryType.Updated, restored.workspaceObjectHistory.single().historyType)
        assertEquals("Looks good", restored.workspaceComments.single().body)
        assertEquals("brief.pdf", restored.workspaceFiles.single().displayName)
        assertEquals("Table", restored.taskWorkspacePrefs.viewMode)
        assertEquals(3L, restored.taskWorkspacePrefs.selectedBoardId)
        assertEquals("DueDate", restored.taskWorkspacePrefs.sortMode)
        assertEquals(false, restored.taskWorkspacePrefs.compactLayout)
        assertEquals("BoardPointer", restored.taskWorkspacePrefs.kanbanEngine)
        assertEquals(65.0, restored.goals.single().progress, 0.0)
        assertEquals("Design review", restored.calendarEvents.single().title)
        assertEquals(CalendarEventSource.Local, restored.calendarEvents.single().source)
    }

    @Test
    fun documentBackupRoundTripIsExactAndLayoutKeysStayLive() {
        val blocks = listOf(
            ParagraphBlock(
                content = listOf(
                    InlineText("plain "),
                    BoldInline(listOf(InlineText("bold"))),
                    ItalicInline(listOf(InlineText("it"))),
                    StrikethroughInline(listOf(InlineText("st"))),
                    CodeInline("code"),
                    LinkInline("https://x.y", listOf(InlineText("link"))),
                    EmojiInline("smile", "😄"),
                    MathInline("a^2"),
                    TagInline("tag"),
                    MentionInline("me"),
                ),
            ),
            HeadingBlock(level = 2, content = listOf(InlineText("Heading"))),
            BulletListBlock(items = listOf(ListItem(content = listOf(InlineText("a")), children = listOf(ListItem(content = listOf(InlineText("a.1"))))))),
            NumberedListBlock(start = 4, items = listOf(ListItem(content = listOf(InlineText("n"))))),
            TodoListBlock(items = listOf(TodoItem(checked = true, content = listOf(InlineText("done"))))),
            QuoteBlock(children = listOf(ParagraphBlock(content = listOf(InlineText("q"))))),
            CalloutBlock(tone = "warning", title = "Careful", children = listOf(ParagraphBlock(content = listOf(InlineText("c"))))),
            ContainerBlock(
                axis = ContainerAxis.Row,
                children = listOf(ParagraphBlock(content = listOf(InlineText("left"))), ParagraphBlock(content = listOf(InlineText("right")))),
                weights = listOf(2f, 1f),
            ),
            DividerBlock(),
            CodeBlock(language = "kotlin", code = "fun x() = 1", editorHeightDp = 240f, renderMode = BlockRenderMode.Source),
            TableBlock(
                headers = listOf(TableCell(listOf(InlineText("H")))),
                rows = listOf(listOf(TableCell(listOf(InlineText("v"))))),
                columnWidthsDp = listOf(148f),
                columnAlignments = listOf(TableAlignment.Center),
                renderMode = BlockRenderMode.Source,
            ),
            ImageBlock(source = "content://img", caption = "cap", layout = ImageLayout.Wide, displayHeightDp = 321f),
            FileBlock(name = "f.bin", mimeType = "application/octet-stream", sizeBytes = 9, uri = "content://f"),
            EmbedBlock(url = "https://e", metadata = EmbedMetadata("t", "d", "fav"), displayHeightDp = 99f),
            ChartBlock(vegaLiteSpec = "{\"mark\":\"bar\"}", editorHeightDp = 200f, renderMode = BlockRenderMode.Source),
            MathBlock(tex = "x^2", display = false, editorHeightDp = 150f, renderMode = BlockRenderMode.Source),
            MermaidBlock(code = "graph TD; A-->B", editorHeightDp = 190f),
            UnknownBlock(id = "future-1", rawJson = """{"v":9,"block":{"kind":"hologram","id":"future-1","beam":1}}"""),
        )
        val layout = blocks.mapIndexed { index, block ->
            block.id to FreeformPlacement(x = index * 10f, y = index * 20f, width = 300f, height = 120f, z = index)
        }.toMap()
        val note = Note(
            id = 1,
            title = "Everything",
            document = BlockDocument(blocks),
            notebookId = null,
            pinned = false,
            starred = false,
            archived = false,
            locked = false,
            createdAt = 1,
            updatedAt = 2,
            overlapMode = DocOverlapMode.Overlap,
            freeformLayout = layout,
        )
        val snapshot = BackupSnapshot(notes = listOf(note), notebooks = emptyList(), tags = emptyList(), attachments = emptyList())

        val restored = BackupCodec.decode(BackupCodec.encode(snapshot)).notes.single()

        // Exact structured equality — same blocks, same ids, no markdown laundering.
        assertEquals(
            BlockDocumentJson.encode(note.document.normalized()),
            BlockDocumentJson.encode(restored.document),
        )
        // Every layout key must still resolve to a live block id after the round trip.
        val restoredIds = restored.document.blocks.map { it.id }.toSet()
        assertEquals(restored.freeformLayout.keys, restored.freeformLayout.keys.intersect(restoredIds))
        assertEquals(layout, restored.freeformLayout)
    }

    @Test
    fun nonNorfoldBackupHeaderIsRejected() {
        val current = BackupCodec.encode(
            BackupSnapshot(
                notes = listOf(Note(1, "Imported", "legacy", null, false, false, false, false, 1, 2)),
                notebooks = emptyList(),
                tags = emptyList(),
                attachments = emptyList(),
            ),
        )
        val legacy = current.replaceFirst(BackupCodec.Header, "NOTESNYNC-BACKUP-V1")

        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(legacy) }
    }

    @Test
    fun encryptedBackupRoundTrips() {
        val snapshot = BackupSnapshot(
            notes = listOf(Note(1, "Secret", "private", null, false, false, false, false, 1, 2)),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
        )

        val restored = BackupCodec.decrypt(BackupCodec.encrypt(snapshot, "123456".toCharArray()), "123456".toCharArray())

        assertEquals("Secret", restored.notes.single().title)
    }
}
