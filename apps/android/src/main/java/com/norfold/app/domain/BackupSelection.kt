package com.norfold.app.domain

enum class BackupCategory(val label: String, val description: String) {
    WorkspaceContent(
        "Workspace content",
        "Notes, documents, tasks, calendar, links, comments, history, and workspace structure",
    ),
    ConversationsAndFiles(
        "Conversations and files",
        "Chat channels, messages, attachment references, and the workspace file library",
    ),
    DeviceSettings(
        "Device settings",
        "Appearance, editor, accessibility, and other device-specific preferences",
    ),
}

data class BackupCategoryCounts(
    val workspaceContent: Int,
    val conversationsAndFiles: Int,
    val deviceSettings: Int,
)

fun BackupSnapshot.categoryCounts(): BackupCategoryCounts = BackupCategoryCounts(
    workspaceContent = notes.size + notebooks.size + tags.size + attachments.size + embeds.size +
        taskBoards.size + taskColumns.size + tasks.size + taskPropertyDefinitions.size +
        taskPropertyValues.size + taskChecklistItems.size + ownedDocuments.size + goals.size +
        calendarEvents.size + workspaceObjects.size + workspaceObjectLinks.size +
        workspaceActivities.size + workspaceObjectHistory.size + workspaceComments.size,
    conversationsAndFiles = chatMessages.size + chatChannels.size + workspaceFiles.size,
    deviceSettings = deviceSettingsProfiles.size,
)

/**
 * Creates one internally consistent snapshot for an atomic restore. Unselected categories are
 * retained from [local], so a selective restore never erases data the user chose to keep.
 */
fun BackupSnapshot.mergeSelectedInto(
    local: BackupSnapshot,
    categories: Set<BackupCategory>,
): BackupSnapshot {
    val workspace = BackupCategory.WorkspaceContent in categories
    val conversations = BackupCategory.ConversationsAndFiles in categories
    val settings = BackupCategory.DeviceSettings in categories
    return BackupSnapshot(
        workspaces = if (workspace) workspaces else local.workspaces,
        taskWorkspacePrefs = if (workspace) taskWorkspacePrefs else local.taskWorkspacePrefs,
        deviceSettingsProfiles = if (settings) deviceSettingsProfiles else local.deviceSettingsProfiles,
        notes = if (workspace) notes else local.notes,
        notebooks = if (workspace) notebooks else local.notebooks,
        tags = if (workspace) tags else local.tags,
        attachments = if (workspace) attachments else local.attachments,
        embeds = if (workspace) embeds else local.embeds,
        taskBoards = if (workspace) taskBoards else local.taskBoards,
        taskColumns = if (workspace) taskColumns else local.taskColumns,
        tasks = if (workspace) tasks else local.tasks,
        taskPropertyDefinitions = if (workspace) taskPropertyDefinitions else local.taskPropertyDefinitions,
        taskPropertyValues = if (workspace) taskPropertyValues else local.taskPropertyValues,
        taskChecklistItems = if (workspace) taskChecklistItems else local.taskChecklistItems,
        ownedDocuments = if (workspace) ownedDocuments else local.ownedDocuments,
        goals = if (workspace) goals else local.goals,
        calendarEvents = if (workspace) calendarEvents else local.calendarEvents,
        chatMessages = if (conversations) chatMessages else local.chatMessages,
        chatChannels = if (conversations) chatChannels else local.chatChannels,
        workspaceObjects = if (workspace) workspaceObjects else local.workspaceObjects,
        workspaceObjectLinks = if (workspace) workspaceObjectLinks else local.workspaceObjectLinks,
        workspaceActivities = if (workspace) workspaceActivities else local.workspaceActivities,
        workspaceObjectHistory = if (workspace) workspaceObjectHistory else local.workspaceObjectHistory,
        workspaceComments = if (workspace) workspaceComments else local.workspaceComments,
        workspaceFiles = if (conversations) workspaceFiles else local.workspaceFiles,
    )
}
