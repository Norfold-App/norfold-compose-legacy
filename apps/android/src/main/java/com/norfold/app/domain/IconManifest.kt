package com.norfold.app.domain

/** Stable cross-platform command vocabulary. Asset names may differ by renderer; meanings may not. */
enum class SemanticActionId(
    val stableId: String,
    val defaultLabel: String,
    val universallyFamiliar: Boolean,
) {
    Back("navigation.back", "Back", true),
    Menu("navigation.menu", "Menu", true),
    Search("navigation.search", "Search", true),
    AppHome("app.home", "Home", true),
    AppToday("app.today", "Today", true),
    AppUpcoming("app.upcoming", "Upcoming", false),
    AppFavorites("app.favorites", "Favorites", true),
    AppNotes("app.notes", "Notes", false),
    AppDocuments("app.documents", "Documents", false),
    AppTasks("app.tasks", "Tasks", false),
    AppFiles("app.files", "Files", false),
    AppChat("app.chat", "Chat", false),
    // Display name renamed Topics -> Channels; the stableId string is persisted in channel rows
    // and icon preferences, so it keeps its historical value.
    AppTopics("app.topics", "Channels", false),
    AppPeople("app.people", "People", true),
    AppGroups("app.groups", "Groups", false),
    AppInbox("app.inbox", "Inbox", false),
    AppDatabase("app.database", "Database", false),
    AppGraph("app.graph", "Graph", false),
    AppActivity("app.activity", "Activity", false),
    AppTemplates("app.templates", "Templates", false),
    AppSettings("app.settings", "Settings", true),
    AppFolder("app.folder", "Folder", true),
    AppTags("app.tags", "Tags", true),
    Done("command.done", "Done", true),
    Save("command.save", "Save", true),
    Undo("edit.undo", "Undo", true),
    Redo("edit.redo", "Redo", true),
    Bold("format.bold", "Bold", true),
    Italic("format.italic", "Italic", true),
    Underline("format.underline", "Underline", true),
    Insert("document.insert", "Insert", false),
    Toolsets("editor.toolsets", "Toolsets", false),
    Paragraph("format.paragraph", "Paragraph", false),
    Checklist("document.checklist", "Checklist", false),
    Table("document.table", "Table", false),
    Image("document.image", "Image", false),
    Attachment("document.attachment", "Attachment", false),
    Shapes("document.shapes", "Shapes", false),
    Arrange("document.arrange", "Arrange", false),
    Review("document.review", "Review", false),
    More("command.more", "More", true),
}

data class SemanticIconMapping(
    val action: SemanticActionId,
    val upstreamName: String,
    val assetName: String,
)

data class IconFamilyManifest(
    val id: String,
    val displayName: String,
    val license: String,
    val source: String,
    val mappings: Map<SemanticActionId, SemanticIconMapping>,
) {
    fun mapping(action: SemanticActionId): SemanticIconMapping =
        mappings[action] ?: requireNotNull(mappings[SemanticActionId.More]) {
            "Icon manifest $id has no mapping or neutral fallback for ${action.stableId}"
        }
}

object NorfoldIconManifests {
    val materialSymbolsRounded = IconFamilyManifest(
        id = "material-symbols-rounded-v1",
        displayName = "Material Symbols Rounded",
        license = "Apache-2.0",
        source = "https://github.com/google/material-design-icons",
        mappings = listOf(
            SemanticActionId.Back to "arrow_back",
            SemanticActionId.Menu to "menu",
            SemanticActionId.Search to "search",
            SemanticActionId.AppHome to "home",
            SemanticActionId.AppToday to "today",
            SemanticActionId.AppUpcoming to "upcoming",
            SemanticActionId.AppFavorites to "star",
            SemanticActionId.AppNotes to "edit_note",
            SemanticActionId.AppDocuments to "description",
            SemanticActionId.AppTasks to "checklist",
            SemanticActionId.AppFiles to "folder",
            SemanticActionId.AppChat to "chat_bubble",
            SemanticActionId.AppTopics to "tag",
            SemanticActionId.AppPeople to "group",
            SemanticActionId.AppGroups to "groups",
            SemanticActionId.AppInbox to "inbox",
            SemanticActionId.AppDatabase to "storage",
            SemanticActionId.AppGraph to "account_tree",
            SemanticActionId.AppActivity to "history",
            SemanticActionId.AppTemplates to "dashboard_customize",
            SemanticActionId.AppSettings to "settings",
            SemanticActionId.AppFolder to "folder_open",
            SemanticActionId.AppTags to "label",
            SemanticActionId.Done to "done",
            SemanticActionId.Save to "save",
            SemanticActionId.Undo to "undo",
            SemanticActionId.Redo to "redo",
            SemanticActionId.Bold to "format_bold",
            SemanticActionId.Italic to "format_italic",
            SemanticActionId.Underline to "format_underlined",
            SemanticActionId.Insert to "add",
            SemanticActionId.Toolsets to "dashboard_customize",
            SemanticActionId.Paragraph to "format_paragraph",
            SemanticActionId.Checklist to "checklist",
            SemanticActionId.Table to "table",
            SemanticActionId.Image to "image",
            SemanticActionId.Attachment to "attach_file",
            SemanticActionId.Shapes to "category",
            SemanticActionId.Arrange to "layers",
            SemanticActionId.Review to "rate_review",
            SemanticActionId.More to "more_horiz",
        ).associate { (action, upstream) ->
            action to SemanticIconMapping(action, upstream, "norfold_ms_${upstream}")
        },
    )

    fun resolve(appFamily: AppIconFamily): IconFamilyManifest = materialSymbolsRounded

    fun resolveEditor(appFamily: AppIconFamily, editorFamily: EditorIconFamily): IconFamilyManifest = when (editorFamily) {
        EditorIconFamily.UseApp -> resolve(appFamily)
        else -> materialSymbolsRounded
    }
}
