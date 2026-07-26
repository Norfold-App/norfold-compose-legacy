package com.norfold.app.ui.components

import android.content.Context
import android.net.Uri
import com.norfold.app.domain.SemanticActionId
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class CustomIconImportResult(
    val mapped: Int,
    val missing: List<SemanticActionId>,
    val rejectedFiles: Int,
)

/** Installs a user-supplied SVG ZIP without ever executing or loading external SVG resources. */
class CustomIconPackStore(private val context: Context, namespace: String = "app") {
    private val safeNamespace = namespace.takeIf { it in setOf("app", "editor") } ?: "app"
    private val directory = File(context.filesDir, "icon-packs/$safeNamespace/custom")
    private val supportedActions = if (safeNamespace == "editor") editorActions else appActions
    fun iconFile(action: SemanticActionId): File? =
        File(directory, action.fileName()).takeIf(File::isFile)

    fun installedCount(): Int = supportedActions.count { iconFile(it) != null }

    fun missing(): List<SemanticActionId> = supportedActions.filter { iconFile(it) == null }

    fun importSingle(action: SemanticActionId, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(MAX_SVG_BYTES + 1) }
            ?: error("The selected SVG could not be opened")
        require(bytes.size <= MAX_SVG_BYTES) { "The selected SVG is too large" }
        require(isSafeSvg(bytes)) { "The selected file is not a safe standalone SVG" }
        directory.mkdirs()
        val target = File(directory, action.fileName())
        val staging = File(context.cacheDir, "custom-icon-${action.stableId}-${System.nanoTime()}.svg")
        try {
            staging.writeBytes(bytes)
            staging.copyTo(target, overwrite = true)
        } finally {
            staging.delete()
        }
    }

    fun clear() {
        directory.deleteRecursively()
    }

    fun importZip(uri: Uri): CustomIconImportResult {
        val candidates = linkedMapOf<String, ByteArray>()
        var rejected = 0
        var entries = 0
        var totalBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { source ->
            ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= 2_000) { "Icon archive contains too many files" }
                    if (entry.isDirectory || !entry.name.endsWith(".svg", ignoreCase = true)) continue
                    val bytes = zip.readBytesLimited(MAX_SVG_BYTES + 1)
                    totalBytes += bytes.size
                    require(totalBytes <= MAX_ARCHIVE_BYTES) { "Icon archive is too large" }
                    if (bytes.size > MAX_SVG_BYTES || !isSafeSvg(bytes)) {
                        rejected++
                        continue
                    }
                    candidates[entry.name] = bytes
                }
            }
        } ?: error("The selected archive could not be opened")

        val selected = linkedMapOf<SemanticActionId, String>()
        val usedEntries = hashSetOf<String>()
        supportedActions.forEach { action ->
            chooseCandidate(action, candidates, usedEntries)?.let { entry ->
                selected[action] = entry
                usedEntries += entry
            }
        }
        require(selected.isNotEmpty()) { "No recognizable Norfold toolbar icons were found" }

        val staging = File(context.cacheDir, "custom-icon-pack-${System.nanoTime()}")
        staging.mkdirs()
        try {
            selected.forEach { (action, entry) ->
                File(staging, action.fileName()).writeBytes(candidates.getValue(entry))
            }
            File(staging, "manifest.json").writeText(
                JSONObject()
                    .put("version", 1)
                    .put("license", "User supplied - not verified by Norfold")
                    .put("mapped", JSONObject(selected.mapKeys { it.key.stableId }.mapValues { it.value }))
                    .toString(2),
            )
            directory.deleteRecursively()
            directory.parentFile?.mkdirs()
            require(staging.renameTo(directory)) { "Could not finish installing the icon pack" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return CustomIconImportResult(
            mapped = selected.size,
            missing = supportedActions.filterNot(selected::containsKey),
            rejectedFiles = rejected,
        )
    }

    private fun chooseCandidate(
        action: SemanticActionId,
        candidates: Map<String, ByteArray>,
        excludedEntries: Set<String>,
    ): String? {
        val aliases = aliases.getValue(action)
        return candidates.keys
            .filterNot(excludedEntries::contains)
            .map { path -> path to path.substringAfterLast('/').substringBeforeLast('.').normalizedName() }
            .filter { (_, name) -> name in aliases }
            .minWithOrNull(
                compareBy<Pair<String, String>>(
                    { pathPriority(it.first) },
                    { aliases.indexOf(it.second) },
                    { it.first.length },
                ),
            )
            ?.first
    }

    private fun pathPriority(path: String): Int = when {
        "/document-editor/" in "/$path" -> 0
        "/general-ui/" in "/$path" -> 1
        "/app-ui-duotone/" in "/$path" -> 2
        "/design-editor/" in "/$path" -> 3
        "/presentation-editor/" in "/$path" -> 4
        else -> 5
    }

    private fun isSafeSvg(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (!text.startsWith("<svg", ignoreCase = true) && !text.startsWith("<?xml", ignoreCase = true)) return false
        val lower = text.lowercase(Locale.ROOT)
        return forbiddenSvgTokens.none(lower::contains)
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun SemanticActionId.fileName() = stableId.replace('.', '_') + ".svg"
    private fun String.normalizedName() = lowercase(Locale.ROOT).replace('_', '-').replace(' ', '-')

    companion object {
        private const val MAX_SVG_BYTES = 256 * 1024
        private const val MAX_ARCHIVE_BYTES = 24L * 1024L * 1024L
        private val appActions = listOf(
            SemanticActionId.AppHome, SemanticActionId.AppToday, SemanticActionId.AppUpcoming,
            SemanticActionId.AppFavorites, SemanticActionId.AppNotes, SemanticActionId.AppDocuments,
            SemanticActionId.AppTasks, SemanticActionId.AppFiles, SemanticActionId.AppChat,
            SemanticActionId.AppInbox, SemanticActionId.AppDatabase, SemanticActionId.AppGraph,
            SemanticActionId.AppActivity, SemanticActionId.AppTemplates, SemanticActionId.AppSettings,
            SemanticActionId.AppFolder, SemanticActionId.AppTags,
        )
        private val editorActions = listOf(
            SemanticActionId.Back, SemanticActionId.Menu, SemanticActionId.Search,
            SemanticActionId.Done, SemanticActionId.Save, SemanticActionId.Undo,
            SemanticActionId.Redo, SemanticActionId.Bold, SemanticActionId.Italic,
            SemanticActionId.Underline, SemanticActionId.Insert, SemanticActionId.Toolsets,
            SemanticActionId.Paragraph, SemanticActionId.Checklist, SemanticActionId.Table,
            SemanticActionId.Image, SemanticActionId.Attachment, SemanticActionId.Shapes,
            SemanticActionId.Arrange, SemanticActionId.Review, SemanticActionId.More,
        )
        private val forbiddenSvgTokens = listOf(
            "<script", "<foreignobject", "<!entity", "<!doctype", "javascript:",
            "data:text/html", "href=", "xlink:href=", "url(", "@import",
        )
        private val aliases = mapOf(
            SemanticActionId.Back to listOf("back", "arrow-left", "chevron-left"),
            SemanticActionId.Menu to listOf("menu", "hamburger", "navigation"),
            SemanticActionId.Search to listOf("search", "find"),
            SemanticActionId.AppHome to listOf("home", "house"),
            SemanticActionId.AppToday to listOf("today", "calendar"),
            SemanticActionId.AppUpcoming to listOf("upcoming", "calendar-event", "history", "clock"),
            SemanticActionId.AppFavorites to listOf("favorites", "star"),
            SemanticActionId.AppNotes to listOf("edit-doc", "notes", "notebook-pen", "pencil-square"),
            SemanticActionId.AppDocuments to listOf("document", "docs", "file-text"),
            SemanticActionId.AppTasks to listOf("tasks", "todo", "checklist", "list-todo"),
            SemanticActionId.AppFiles to listOf("files", "file", "folder"),
            SemanticActionId.AppChat to listOf("chat", "chat-dots", "message-circle", "comments"),
            SemanticActionId.AppInbox to listOf("inbox", "archive"),
            SemanticActionId.AppDatabase to listOf("database", "storage", "table-view"),
            SemanticActionId.AppGraph to listOf("graph", "network", "relation", "link"),
            SemanticActionId.AppActivity to listOf("activity", "history", "recent"),
            SemanticActionId.AppTemplates to listOf("template", "templates", "grid", "layout-template"),
            SemanticActionId.AppSettings to listOf("settings", "gear", "cog"),
            SemanticActionId.AppFolder to listOf("folder-open", "folder", "folders"),
            SemanticActionId.AppTags to listOf("tags", "tag", "label"),
            SemanticActionId.Done to listOf("done", "check", "check-circle", "accept"),
            SemanticActionId.Save to listOf("save", "floppy-disk", "save-to-cloud"),
            SemanticActionId.Undo to listOf("undo", "arrow-uturn-left"),
            SemanticActionId.Redo to listOf("redo", "arrow-uturn-right"),
            SemanticActionId.Bold to listOf("bold"),
            SemanticActionId.Italic to listOf("italic"),
            SemanticActionId.Underline to listOf("underline"),
            SemanticActionId.Insert to listOf("add", "plus", "new"),
            SemanticActionId.Toolsets to listOf("icons", "tools", "toolbox", "toolbar"),
            SemanticActionId.Paragraph to listOf("paragraph", "text", "heading"),
            SemanticActionId.Checklist to listOf("checklist", "list-check", "list-checks"),
            SemanticActionId.Table to listOf("table", "table-view"),
            SemanticActionId.Image to listOf("image", "images"),
            SemanticActionId.Attachment to listOf("attachment", "attach", "paperclip"),
            SemanticActionId.Shapes to listOf("shapes", "shape"),
            SemanticActionId.Arrange to listOf("arrange", "layers", "position"),
            SemanticActionId.Review to listOf("review", "in-review", "comments"),
            SemanticActionId.More to listOf("more", "more-horizontal", "ellipsis"),
        )
    }
}
