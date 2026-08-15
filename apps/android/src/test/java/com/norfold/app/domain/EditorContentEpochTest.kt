package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor content epoch as it behaves in backups and restores.
 *
 * The reset itself needs a database and lives in the instrumented tests. What is checked here is
 * everything that can be decided without one: who is eligible to be erased, which payloads an
 * install accepts, what a refused backup still gives back, and that a stamped backup stays readable
 * by a build that predates the stamp.
 *
 * The eligibility rule is deliberately a pure function so that it is provable here, on the desktop
 * JVM, in the build gate. The alternative — proving the fence only through a Room test — means the
 * one rule that decides whether a user's writing is deleted is checked only when a device is
 * attached, and the rule would be defended by whatever the last device run happened to cover.
 */
class EditorContentEpochTest {
    @Test
    fun onlyPositiveProofOfAnOldGenerationAllowsTheReset() {
        // The one eligible install: the marker written while migrating a database that predates the
        // epoch columns, on a row that has not crossed the boundary yet.
        assertTrue(
            eligibleForLegacyEditorReset(
                legacyGenerationPending = true,
                installEpoch = UnstampedEditorContentEpoch,
            ),
        )
        // No marker, same epoch number. This is the case that used to be erased: content that
        // arrived from a restore or a sign-in before anything stamped the settings row reads exactly
        // like a pre-boundary install if the number is all you look at.
        assertFalse(
            eligibleForLegacyEditorReset(
                legacyGenerationPending = false,
                installEpoch = UnstampedEditorContentEpoch,
            ),
        )
    }

    @Test
    fun anInstallThatHasCrossedTheBoundaryCannotCrossItAgain() {
        // Belt and braces against the marker outliving the reset: a stamped install is ineligible on
        // the epoch alone, so a stale marker cannot spend a second erasure.
        assertFalse(
            eligibleForLegacyEditorReset(legacyGenerationPending = true, installEpoch = CurrentEditorContentEpoch),
        )
        assertFalse(eligibleForLegacyEditorReset(legacyGenerationPending = true, installEpoch = 7))
        // Raising the epoch is what re-opens the question, and only for an install still carrying
        // proof that it holds the older generation.
        assertTrue(
            eligibleForLegacyEditorReset(
                legacyGenerationPending = true,
                installEpoch = CurrentEditorContentEpoch,
                currentEpoch = CurrentEditorContentEpoch + 1,
            ),
        )
        assertFalse(
            eligibleForLegacyEditorReset(
                legacyGenerationPending = false,
                installEpoch = CurrentEditorContentEpoch,
                currentEpoch = CurrentEditorContentEpoch + 1,
            ),
        )
    }

    @Test
    fun installAcceptsItsOwnEpochAndNewerButNeverOlder() {
        assertTrue(acceptsEditorArtifacts(installEpoch = 1, payloadEpoch = 1))
        // Newer is accepted deliberately: refusing it would discard content authored on a device
        // that is further ahead, which is worse than carrying an epoch this build does not know.
        assertTrue(acceptsEditorArtifacts(installEpoch = 1, payloadEpoch = 2))
        assertFalse(acceptsEditorArtifacts(installEpoch = 1, payloadEpoch = UnstampedEditorContentEpoch))
        // An install that has crossed no boundary accepts everything, including unstamped payloads.
        assertTrue(acceptsEditorArtifacts(installEpoch = UnstampedEditorContentEpoch, payloadEpoch = UnstampedEditorContentEpoch))
    }

    @Test
    fun backupCarriesTheEpochItWasWrittenIn() {
        val snapshot = BackupSnapshot(
            notes = listOf(Note(1, "Kept", "body", null, false, false, false, false, 1, 2)),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
            editorContentEpoch = CurrentEditorContentEpoch,
        )

        val restored = BackupCodec.decode(BackupCodec.encode(snapshot))

        assertEquals(CurrentEditorContentEpoch, restored.editorContentEpoch)
        assertEquals("Kept", restored.notes.single().title)
    }

    @Test
    fun backupWrittenBeforeEpochsExistedDecodesAsUnstamped() {
        val stamped = BackupCodec.encode(
            BackupSnapshot(
                notes = listOf(Note(1, "Kept", "body", null, false, false, false, false, 1, 2)),
                notebooks = emptyList(),
                tags = emptyList(),
                attachments = emptyList(),
                editorContentEpoch = CurrentEditorContentEpoch,
            ),
        )
        // Dropping the stamp is exactly what a backup from before epochs existed looks like, and
        // what an older build writing the same content produces. The rest must still decode: the
        // stamp is one line, not a format change.
        val unstamped = stamped.lineSequence().filterNot { it.startsWith("EDITOR_EPOCH") }.joinToString("\n")

        val restored = BackupCodec.decode(unstamped)

        assertEquals(UnstampedEditorContentEpoch, restored.editorContentEpoch)
        assertEquals("Kept", restored.notes.single().title)
    }

    @Test
    fun encryptedBackupCarriesTheEpochToo() {
        val snapshot = BackupSnapshot(
            notes = emptyList(),
            notebooks = emptyList(),
            tags = emptyList(),
            attachments = emptyList(),
            editorContentEpoch = 3,
        )

        val restored = BackupCodec.decrypt(BackupCodec.encrypt(snapshot, "123456".toCharArray()), "123456".toCharArray())

        assertEquals(3, restored.editorContentEpoch)
    }

    @Test
    fun refusedBackupStillGivesBackEverythingTheBoundaryPreserves() {
        val stripped = fullSnapshot().withoutEditorArtifacts()

        // Erased: the editor generation.
        assertTrue(stripped.notes.isEmpty())
        assertTrue(stripped.notebooks.isEmpty())
        assertTrue(stripped.attachments.isEmpty())
        assertTrue(stripped.embeds.isEmpty())
        assertTrue(stripped.ownedDocuments.isEmpty())
        assertTrue(stripped.tags.none { it.scope == NoteTagScope })
        assertTrue(stripped.workspaceObjects.none { it.objectType == WorkspaceObjectType.Note })

        // Preserved: accounts' workspaces, settings, task and calendar data, chat, files, and the
        // board-scoped tags that are card labels rather than note tags.
        assertEquals(1, stripped.workspaces.size)
        assertEquals(1, stripped.deviceSettingsProfiles.size)
        assertEquals(1, stripped.tasks.size)
        assertEquals(1, stripped.taskBoards.size)
        assertEquals(1, stripped.taskColumns.size)
        assertEquals(1, stripped.goals.size)
        assertEquals(1, stripped.calendarEvents.size)
        assertEquals(1, stripped.chatChannels.size)
        assertEquals(1, stripped.chatMessages.size)
        assertEquals(1, stripped.workspaceFiles.size)
        assertEquals(listOf("board:3"), stripped.tags.map { it.scope })
        assertEquals("Table", stripped.taskWorkspacePrefs.viewMode)
    }

    @Test
    fun refusedBackupLeavesNoGraphRowPointingAtAnErasedNote() {
        val stripped = fullSnapshot().withoutEditorArtifacts()

        val survivingObjectIds = stripped.workspaceObjects.map { it.id }.toSet()
        // The note object is gone, so nothing that referenced it may survive: a link, comment,
        // history entry or activity row aimed at a missing object is a dangling reference the
        // index rebuild would have to guess about.
        assertTrue(stripped.workspaceObjectLinks.all { it.fromObjectId in survivingObjectIds && it.toObjectId in survivingObjectIds })
        assertTrue(stripped.workspaceActivities.all { it.objectId in survivingObjectIds })
        assertTrue(stripped.workspaceObjectHistory.all { it.objectId in survivingObjectIds })
        assertTrue(stripped.workspaceComments.all { it.objectId in survivingObjectIds })
        // The file object and its own graph rows are untouched.
        assertEquals(listOf(12L), stripped.workspaceObjects.map { it.id })
        assertEquals(1, stripped.workspaceActivities.size)
    }

    @Test
    fun selectiveRestoreKeepsTheEpochOfWhicheverSideSuppliedTheNotes() {
        val remote = fullSnapshot().copy(editorContentEpoch = UnstampedEditorContentEpoch)
        val local = fullSnapshot().copy(editorContentEpoch = CurrentEditorContentEpoch)

        val takingWorkspaceContent = remote.mergeSelectedInto(local, setOf(BackupCategory.WorkspaceContent))
        val takingSettingsOnly = remote.mergeSelectedInto(local, setOf(BackupCategory.DeviceSettings))

        // Taking the backup's notes means answering for the backup's epoch, which is what lets the
        // restore be refused. Taking only settings keeps the local notes, so it keeps their epoch
        // too — otherwise a settings-only restore would refuse the install's own content.
        assertEquals(UnstampedEditorContentEpoch, takingWorkspaceContent.editorContentEpoch)
        assertEquals(CurrentEditorContentEpoch, takingSettingsOnly.editorContentEpoch)
    }

    @Test
    fun aDeviceThatIsMerelyOfflineOrSignedOutKeepsOwingTheRemoteReset() {
        // Every one of these is a device that will be able to do the reset later, so the debt has
        // to survive. The last case is the important one: an unrecognised message is retryable,
        // because giving up on a message this build cannot read would leave the erased generation
        // on the server permanently.
        listOf(
            "Failed to connect to supabase.co",
            "JWT expired",
            "HTTP 401 Unauthorized",
            "Bad Gateway",
            null,
        ).forEach { message ->
            assertTrue(
                "expected retryable for: $message",
                classifyRemoteEditorResetFailure(message) is RemoteEditorResetOutcome.Retryable,
            )
        }
    }

    @Test
    fun aDeviceThatWillNeverBeAllowedToResetStopsAsking() {
        // A viewer or ordinary member is refused by the server's role gate, and no amount of
        // retrying changes that. Same for a workspace that no longer exists.
        assertEquals(
            RemoteEditorResetOutcome.Refused("authorization_denied"),
            classifyRemoteEditorResetFailure("editor_reset_denied"),
        )
        assertEquals(
            RemoteEditorResetOutcome.Refused("authorization_denied"),
            classifyRemoteEditorResetFailure("HTTP 403 Forbidden"),
        )
        assertEquals(
            RemoteEditorResetOutcome.Refused("unknown_workspace"),
            classifyRemoteEditorResetFailure("editor_reset_unknown_workspace"),
        )
        assertEquals(
            RemoteEditorResetOutcome.Refused("invalid_epoch"),
            classifyRemoteEditorResetFailure("editor_reset_invalid_epoch"),
        )
    }

    @Test
    fun anExpiredTokenIsRetriedEvenThoughTheServerCallsItAnAuthorizationProblem() {
        // The two categories overlap in the wire message, and the ordering has to favour the
        // retryable reading: a signed-out device still owes the reset, while treating it as a
        // permanent refusal would silently abandon the boundary at the next token expiry.
        assertEquals(
            RemoteEditorResetOutcome.Retryable("authentication_expired"),
            classifyRemoteEditorResetFailure("permission denied: JWT expired"),
        )
    }

    private fun fullSnapshot(): BackupSnapshot {
        val noteObject = WorkspaceObject(
            id = 11,
            objectType = WorkspaceObjectType.Note,
            sourceId = 1,
            title = "Erased",
            summary = "",
            tags = "sync",
            icon = "note",
            color = 0xFF9D6CFF,
            pinned = false,
            archived = false,
            createdAt = 10,
            updatedAt = 20,
        )
        val fileObject = noteObject.copy(
            id = 12,
            objectType = WorkspaceObjectType.File,
            sourceId = null,
            title = "brief.pdf",
        )
        return BackupSnapshot(
            workspaces = listOf(Workspace(id = 1, name = "Team", icon = "N", palette = ThemeProfile.Norfold, createdAt = 1)),
            taskWorkspacePrefs = TaskWorkspacePrefs(viewMode = "Table"),
            deviceSettingsProfiles = listOf(
                DeviceSettingsProfile(
                    deviceId = "device-1",
                    deviceName = "This device",
                    deviceType = "phone",
                    screenClass = "compact",
                    updatedAt = 1,
                ),
            ),
            notes = listOf(Note(1, "Erased", "body", 1, false, false, false, false, 1, 2)),
            notebooks = listOf(Notebook(1, "Work", null, 0xFF8B5CF6, 0)),
            tags = listOf(Tag(1, "sync", 0xFF8B5CF6), Tag(2, "Planning", 0xFF8B5CF6, scope = "board:3")),
            attachments = listOf(Attachment(1, 1, "image.png", "image/png", "content://image", 128)),
            embeds = listOf(NoteEmbedItem(1, 1, NoteEmbedType.Link, "Link", "https://x.y", "", 5)),
            taskBoards = listOf(TaskBoardItem(3, "Project board", 1, 21, 22)),
            taskColumns = listOf(TaskColumnItem(4, 3, "Review", TaskStatus.Doing, 0xFF4FACFE, 1, 23, 24)),
            tasks = listOf(
                TaskItem(
                    id = 7,
                    title = "Review brief",
                    description = "Check file",
                    assignee = "@owner",
                    status = TaskStatus.Doing,
                    createdAt = 30,
                    updatedAt = 40,
                    taskBoardId = 3,
                    taskColumnId = 4,
                ),
            ),
            ownedDocuments = listOf(
                OwnedDocument(
                    owner = DocumentOwner.task(7),
                    document = BlockDocument(listOf(ParagraphBlock(content = listOf(InlineText("Erased body"))))),
                    createdAt = 10,
                    updatedAt = 20,
                ),
            ),
            goals = listOf(GoalItem(72, 1, "goal-72", "Ship", "", "You", 100.0, 65.0, "%", 240, GoalStatus.InProgress, 141, 161)),
            calendarEvents = listOf(
                CalendarEventItem(73, 1, "event-73", "Review", "", 200, 220, false, 0xFF9A48FF, CalendarEventSource.Local, null, 142, 162),
            ),
            chatChannels = listOf(
                ChatChannel(id = 44, syncId = "1a3f1f0e-8c1e-4a8b-9f5a-2c7d0d5a9b11", kind = ChatChannelKind.Topic, name = "General", createdAt = 1, workspaceId = 1),
            ),
            chatMessages = listOf(
                ChatMessageItem(id = 9, authorUsername = "member", authorDisplayName = "Member", body = "hi", color = 0xFF8B5CF6, createdAt = 22, workspaceId = 1, channelId = 44),
            ),
            workspaceObjects = listOf(noteObject, fileObject),
            workspaceObjectLinks = listOf(WorkspaceObjectLink(21, 11, 12, WorkspaceLinkType.Attachment, "brief.pdf", 80)),
            workspaceActivities = listOf(
                WorkspaceActivity(31, 11, WorkspaceActivityType.Updated, "You", "Updated note", "Erased", 90),
                WorkspaceActivity(32, 12, WorkspaceActivityType.Updated, "You", "Added file", "brief.pdf", 91),
            ),
            workspaceObjectHistory = listOf(WorkspaceObjectHistory(41, 11, WorkspaceHistoryType.Updated, "You", "Updated note", "before", "after", 100)),
            workspaceComments = listOf(WorkspaceComment(51, 11, "you", "You", "Looks good", false, 110, 120)),
            workspaceFiles = listOf(WorkspaceFileItem(61, 12, "brief.pdf", "application/pdf", "content://brief", 4096, 130, 140)),
            editorContentEpoch = UnstampedEditorContentEpoch,
        )
    }
}
