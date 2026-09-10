package com.norfold.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.rounded.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.CallSplit
import androidx.compose.material.icons.automirrored.sharp.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.sharp.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.sharp.Label
import androidx.compose.material.icons.automirrored.sharp.Redo
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.sharp.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.composables.icons.bootstrap.Bootstrap
import com.composables.icons.bootstrap.outline.*
import com.composables.icons.feather.*
import com.composables.icons.feather.Feather
import com.composables.icons.fontawesome.FontAwesome
import com.composables.icons.fontawesome.solid.*
import com.composables.icons.heroicons.Heroicons
import com.composables.icons.heroicons.outline.*
import com.composables.icons.lucide.*
import com.composables.icons.lucide.Lucide
import com.composables.icons.radix.*
import com.composables.icons.radix.Radix
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.outline.*
import com.norfold.app.domain.AppIconFamily
import com.norfold.app.domain.EditorIconFamily
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.ui.LocalAppIconFamily
import com.norfold.app.ui.LocalEditorIconFamily

data class IconFamilyInfo(
    val family: AppIconFamily,
    val displayName: String,
    val license: String,
    val source: String,
)

val NorfoldIconFamilies = listOf(
    IconFamilyInfo(AppIconFamily.MaterialSymbolsRounded, "Material Symbols Rounded", "Apache-2.0", "Google"),
    IconFamilyInfo(AppIconFamily.MaterialSymbolsOutlined, "Material Symbols Outlined", "Apache-2.0", "Google"),
    IconFamilyInfo(AppIconFamily.MaterialSymbolsSharp, "Material Symbols Sharp", "Apache-2.0", "Google"),
    IconFamilyInfo(AppIconFamily.Lucide, "Lucide", "ISC", "Lucide Contributors"),
    IconFamilyInfo(AppIconFamily.Tabler, "Tabler Icons", "MIT", "Paweł Kuna and contributors"),
    IconFamilyInfo(AppIconFamily.Feather, "Feather", "MIT", "Cole Bemis"),
    IconFamilyInfo(AppIconFamily.Bootstrap, "Bootstrap Icons", "MIT", "Bootstrap Authors"),
    IconFamilyInfo(AppIconFamily.Heroicons, "Heroicons", "MIT", "Tailwind Labs"),
    IconFamilyInfo(AppIconFamily.Radix, "Radix Icons", "MIT", "WorkOS"),
    IconFamilyInfo(AppIconFamily.FontAwesome, "Font Awesome Free", "Font Awesome Free License", "Fonticons, Inc."),
    IconFamilyInfo(AppIconFamily.Custom, "Imported custom pack", "User supplied", "Device storage"),
)

fun AppIconFamily.displayName(): String =
    NorfoldIconFamilies.firstOrNull { it.family == this }?.displayName ?: name

fun EditorIconFamily.displayName(): String = when (this) {
    EditorIconFamily.UseApp -> "Use app icon family"
    else -> AppIconFamily.entries.firstOrNull { it.name == name }?.displayName() ?: name
}

@Composable
fun SemanticEditorIcon(
    action: SemanticActionId,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val app = LocalAppIconFamily.current
    val editor = LocalEditorIconFamily.current
    val family = if (editor == EditorIconFamily.UseApp) app else AppIconFamily.entries.firstOrNull { it.name == editor.name }
        ?: AppIconFamily.Lucide
    SemanticFamilyIcon(family, action, contentDescription, modifier, tint, customNamespace = "editor")
}

@Composable
fun SemanticAppIcon(
    action: SemanticActionId,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    SemanticFamilyIcon(LocalAppIconFamily.current, action, contentDescription, modifier, tint)
}

@Composable
fun SemanticFamilyIcon(
    family: AppIconFamily,
    action: SemanticActionId,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    customNamespace: String = "app",
) {
    if (family == AppIconFamily.Custom) {
        val context = LocalContext.current
        val file = CustomIconPackStore(context, customNamespace).iconFile(action)
        if (file != null) {
            val version = "${file.absolutePath}:${file.lastModified()}"
            var decodeFailed by remember(version) { mutableStateOf(false) }
            if (!decodeFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .decoderFactory(SvgDecoder.Factory())
                        .memoryCacheKey(version)
                        .diskCacheKey(version)
                        .build(),
                    contentDescription = contentDescription,
                    modifier = modifier.then(Modifier.size(24.dp)),
                    colorFilter = ColorFilter.tint(tint),
                    onError = { decodeFailed = true },
                )
                return
            }
        }
    }
    Icon(prebuiltIcon(family, action), contentDescription, modifier, tint = tint)
}

/** Kept for non-custom call sites that require an ImageVector. */
@Composable
fun resolvedEditorIcon(action: SemanticActionId): ImageVector {
    val app = LocalAppIconFamily.current
    val editor = LocalEditorIconFamily.current
    val family = if (editor == EditorIconFamily.UseApp) app else AppIconFamily.entries.firstOrNull { it.name == editor.name }
        ?: AppIconFamily.Lucide
    return prebuiltIcon(family, action)
}

private fun prebuiltIcon(family: AppIconFamily, action: SemanticActionId): ImageVector = when (family) {
    AppIconFamily.MaterialSymbolsRounded -> roundedVector(action)
    AppIconFamily.MaterialSymbolsOutlined -> materialVector(action)
    AppIconFamily.MaterialSymbolsSharp -> sharpVector(action)
    AppIconFamily.Lucide -> lucideVector(action)
    AppIconFamily.Tabler -> tablerVector(action)
    AppIconFamily.Feather -> featherVector(action)
    AppIconFamily.Bootstrap -> bootstrapVector(action)
    AppIconFamily.Heroicons -> heroVector(action)
    AppIconFamily.Radix -> radixVector(action)
    AppIconFamily.FontAwesome -> fontAwesomeVector(action)
    AppIconFamily.Custom -> roundedVector(action)
}

private fun roundedVector(action: SemanticActionId): ImageVector = when (action) {
    SemanticActionId.Back -> Icons.AutoMirrored.Rounded.ArrowBack
    SemanticActionId.Menu -> Icons.Rounded.Menu
    SemanticActionId.Search -> Icons.Rounded.Search
    SemanticActionId.AppHome -> Icons.Rounded.Home
    SemanticActionId.AppToday -> Icons.Rounded.Today
    SemanticActionId.AppUpcoming -> Icons.Rounded.Upcoming
    SemanticActionId.AppFavorites -> Icons.Rounded.Star
    SemanticActionId.AppNotes -> Icons.Rounded.EditNote
    SemanticActionId.AppDocuments -> Icons.Rounded.Description
    SemanticActionId.AppTasks -> Icons.Rounded.Checklist
    SemanticActionId.AppFiles -> Icons.Rounded.Folder
    SemanticActionId.AppChat -> Icons.Rounded.ChatBubble
    SemanticActionId.AppTopics -> Icons.Rounded.Tag
    SemanticActionId.AppPeople -> Icons.Rounded.People
    SemanticActionId.AppGroups -> Icons.Rounded.Groups
    SemanticActionId.AppInbox -> Icons.Rounded.Inbox
    SemanticActionId.AppDatabase -> Icons.Rounded.Storage
    SemanticActionId.AppGraph -> Icons.Rounded.AccountTree
    SemanticActionId.AppActivity -> Icons.Rounded.History
    SemanticActionId.AppTemplates -> Icons.Rounded.DashboardCustomize
    SemanticActionId.AppSettings -> Icons.Rounded.Settings
    SemanticActionId.AppFolder -> Icons.Rounded.FolderOpen
    SemanticActionId.AppTags -> Icons.AutoMirrored.Rounded.Label
    SemanticActionId.Done -> Icons.Rounded.Check
    SemanticActionId.Save -> Icons.Rounded.Save
    SemanticActionId.Undo -> Icons.AutoMirrored.Rounded.Undo
    SemanticActionId.Redo -> Icons.AutoMirrored.Rounded.Redo
    SemanticActionId.Bold -> Icons.Rounded.FormatBold
    SemanticActionId.Italic -> Icons.Rounded.FormatItalic
    SemanticActionId.Underline -> Icons.Rounded.FormatUnderlined
    SemanticActionId.Insert -> Icons.Rounded.Add
    SemanticActionId.Toolsets -> Icons.Rounded.Tune
    SemanticActionId.Paragraph -> Icons.Rounded.Title
    SemanticActionId.Checklist -> Icons.Rounded.Checklist
    SemanticActionId.Table -> Icons.Rounded.TableChart
    SemanticActionId.Image -> Icons.Rounded.Image
    SemanticActionId.Attachment -> Icons.Rounded.AttachFile
    SemanticActionId.Shapes -> Icons.Rounded.Category
    SemanticActionId.Arrange -> Icons.Rounded.Layers
    SemanticActionId.Review -> Icons.Rounded.RateReview
    SemanticActionId.Strikethrough -> Icons.Rounded.StrikethroughS
    SemanticActionId.Code -> Icons.Rounded.Code
    SemanticActionId.Link -> Icons.Rounded.Link
    SemanticActionId.Color -> Icons.Rounded.FormatColorText
    SemanticActionId.Style -> Icons.Rounded.Palette
    SemanticActionId.Edit -> Icons.Rounded.Edit
    SemanticActionId.Move -> Icons.Rounded.OpenWith
    SemanticActionId.MoveUp -> Icons.Rounded.ArrowUpward
    SemanticActionId.MoveDown -> Icons.Rounded.ArrowDownward
    SemanticActionId.NestIn -> Icons.AutoMirrored.Rounded.FormatIndentIncrease
    SemanticActionId.NestOut -> Icons.AutoMirrored.Rounded.FormatIndentDecrease
    SemanticActionId.Split -> Icons.AutoMirrored.Rounded.CallSplit
    SemanticActionId.Merge -> Icons.Rounded.Merge
    SemanticActionId.WrapColumns -> Icons.Rounded.ViewColumn
    SemanticActionId.Unwrap -> Icons.Rounded.ViewStream
    SemanticActionId.Duplicate -> Icons.Rounded.ContentCopy
    SemanticActionId.Delete -> Icons.Rounded.Delete
    SemanticActionId.Cancel -> Icons.Rounded.Close
    SemanticActionId.More -> Icons.Rounded.MoreHoriz
}

private fun materialVector(action: SemanticActionId): ImageVector = when (action) {
    SemanticActionId.Back -> Icons.AutoMirrored.Outlined.ArrowBack
    SemanticActionId.Menu -> Icons.Outlined.Menu
    SemanticActionId.Search -> Icons.Outlined.Search
    SemanticActionId.AppHome -> Icons.Outlined.Home
    SemanticActionId.AppToday -> Icons.Outlined.Today
    SemanticActionId.AppUpcoming -> Icons.Outlined.Upcoming
    SemanticActionId.AppFavorites -> Icons.Outlined.Star
    SemanticActionId.AppNotes -> Icons.Outlined.EditNote
    SemanticActionId.AppDocuments -> Icons.Outlined.Description
    SemanticActionId.AppTasks -> Icons.Outlined.Checklist
    SemanticActionId.AppFiles -> Icons.Outlined.Folder
    SemanticActionId.AppChat -> Icons.Outlined.ChatBubbleOutline
    SemanticActionId.AppTopics -> Icons.Outlined.Tag
    SemanticActionId.AppPeople -> Icons.Outlined.People
    SemanticActionId.AppGroups -> Icons.Outlined.Groups
    SemanticActionId.AppInbox -> Icons.Outlined.Inbox
    SemanticActionId.AppDatabase -> Icons.Outlined.Storage
    SemanticActionId.AppGraph -> Icons.Outlined.AccountTree
    SemanticActionId.AppActivity -> Icons.Outlined.History
    SemanticActionId.AppTemplates -> Icons.Outlined.DashboardCustomize
    SemanticActionId.AppSettings -> Icons.Outlined.Settings
    SemanticActionId.AppFolder -> Icons.Outlined.FolderOpen
    SemanticActionId.AppTags -> Icons.AutoMirrored.Outlined.Label
    SemanticActionId.Done -> Icons.Outlined.Check
    SemanticActionId.Save -> Icons.Outlined.Save
    SemanticActionId.Undo -> Icons.AutoMirrored.Outlined.Undo
    SemanticActionId.Redo -> Icons.AutoMirrored.Outlined.Redo
    SemanticActionId.Bold -> Icons.Outlined.FormatBold
    SemanticActionId.Italic -> Icons.Outlined.FormatItalic
    SemanticActionId.Underline -> Icons.Outlined.FormatUnderlined
    SemanticActionId.Insert -> Icons.Outlined.Add
    SemanticActionId.Toolsets -> Icons.Outlined.Tune
    SemanticActionId.Paragraph -> Icons.Outlined.Title
    SemanticActionId.Checklist -> Icons.Outlined.Checklist
    SemanticActionId.Table -> Icons.Outlined.TableChart
    SemanticActionId.Image -> Icons.Outlined.Image
    SemanticActionId.Attachment -> Icons.Outlined.AttachFile
    SemanticActionId.Shapes -> Icons.Outlined.Category
    SemanticActionId.Arrange -> Icons.Outlined.Layers
    SemanticActionId.Review -> Icons.Outlined.RateReview
    SemanticActionId.Strikethrough -> Icons.Outlined.StrikethroughS
    SemanticActionId.Code -> Icons.Outlined.Code
    SemanticActionId.Link -> Icons.Outlined.Link
    SemanticActionId.Color -> Icons.Outlined.FormatColorText
    SemanticActionId.Style -> Icons.Outlined.Palette
    SemanticActionId.Edit -> Icons.Outlined.Edit
    SemanticActionId.Move -> Icons.Outlined.OpenWith
    SemanticActionId.MoveUp -> Icons.Outlined.ArrowUpward
    SemanticActionId.MoveDown -> Icons.Outlined.ArrowDownward
    SemanticActionId.NestIn -> Icons.AutoMirrored.Outlined.FormatIndentIncrease
    SemanticActionId.NestOut -> Icons.AutoMirrored.Outlined.FormatIndentDecrease
    SemanticActionId.Split -> Icons.AutoMirrored.Outlined.CallSplit
    SemanticActionId.Merge -> Icons.Outlined.Merge
    SemanticActionId.WrapColumns -> Icons.Outlined.ViewColumn
    SemanticActionId.Unwrap -> Icons.Outlined.ViewStream
    SemanticActionId.Duplicate -> Icons.Outlined.ContentCopy
    SemanticActionId.Delete -> Icons.Outlined.Delete
    SemanticActionId.Cancel -> Icons.Outlined.Close
    SemanticActionId.More -> Icons.Outlined.MoreHoriz
}

private fun sharpVector(action: SemanticActionId): ImageVector = when (action) {
    SemanticActionId.Back -> Icons.AutoMirrored.Sharp.ArrowBack
    SemanticActionId.Menu -> Icons.Sharp.Menu
    SemanticActionId.Search -> Icons.Sharp.Search
    SemanticActionId.AppHome -> Icons.Sharp.Home
    SemanticActionId.AppToday -> Icons.Sharp.Today
    SemanticActionId.AppUpcoming -> Icons.Sharp.Upcoming
    SemanticActionId.AppFavorites -> Icons.Sharp.Star
    SemanticActionId.AppNotes -> Icons.Sharp.EditNote
    SemanticActionId.AppDocuments -> Icons.Sharp.Description
    SemanticActionId.AppTasks -> Icons.Sharp.Checklist
    SemanticActionId.AppFiles -> Icons.Sharp.Folder
    SemanticActionId.AppChat -> Icons.Sharp.ChatBubble
    SemanticActionId.AppTopics -> Icons.Sharp.Tag
    SemanticActionId.AppPeople -> Icons.Sharp.People
    SemanticActionId.AppGroups -> Icons.Sharp.Groups
    SemanticActionId.AppInbox -> Icons.Sharp.Inbox
    SemanticActionId.AppDatabase -> Icons.Sharp.Storage
    SemanticActionId.AppGraph -> Icons.Sharp.AccountTree
    SemanticActionId.AppActivity -> Icons.Sharp.History
    SemanticActionId.AppTemplates -> Icons.Sharp.DashboardCustomize
    SemanticActionId.AppSettings -> Icons.Sharp.Settings
    SemanticActionId.AppFolder -> Icons.Sharp.FolderOpen
    SemanticActionId.AppTags -> Icons.AutoMirrored.Sharp.Label
    SemanticActionId.Done -> Icons.Sharp.Check
    SemanticActionId.Save -> Icons.Sharp.Save
    SemanticActionId.Undo -> Icons.AutoMirrored.Sharp.Undo
    SemanticActionId.Redo -> Icons.AutoMirrored.Sharp.Redo
    SemanticActionId.Bold -> Icons.Sharp.FormatBold
    SemanticActionId.Italic -> Icons.Sharp.FormatItalic
    SemanticActionId.Underline -> Icons.Sharp.FormatUnderlined
    SemanticActionId.Insert -> Icons.Sharp.Add
    SemanticActionId.Toolsets -> Icons.Sharp.Tune
    SemanticActionId.Paragraph -> Icons.Sharp.Title
    SemanticActionId.Checklist -> Icons.Sharp.Checklist
    SemanticActionId.Table -> Icons.Sharp.TableChart
    SemanticActionId.Image -> Icons.Sharp.Image
    SemanticActionId.Attachment -> Icons.Sharp.AttachFile
    SemanticActionId.Shapes -> Icons.Sharp.Category
    SemanticActionId.Arrange -> Icons.Sharp.Layers
    SemanticActionId.Review -> Icons.Sharp.RateReview
    SemanticActionId.Strikethrough -> Icons.Sharp.StrikethroughS
    SemanticActionId.Code -> Icons.Sharp.Code
    SemanticActionId.Link -> Icons.Sharp.Link
    SemanticActionId.Color -> Icons.Sharp.FormatColorText
    SemanticActionId.Style -> Icons.Sharp.Palette
    SemanticActionId.Edit -> Icons.Sharp.Edit
    SemanticActionId.Move -> Icons.Sharp.OpenWith
    SemanticActionId.MoveUp -> Icons.Sharp.ArrowUpward
    SemanticActionId.MoveDown -> Icons.Sharp.ArrowDownward
    SemanticActionId.NestIn -> Icons.AutoMirrored.Sharp.FormatIndentIncrease
    SemanticActionId.NestOut -> Icons.AutoMirrored.Sharp.FormatIndentDecrease
    SemanticActionId.Split -> Icons.AutoMirrored.Sharp.CallSplit
    SemanticActionId.Merge -> Icons.Sharp.Merge
    SemanticActionId.WrapColumns -> Icons.Sharp.ViewColumn
    SemanticActionId.Unwrap -> Icons.Sharp.ViewStream
    SemanticActionId.Duplicate -> Icons.Sharp.ContentCopy
    SemanticActionId.Delete -> Icons.Sharp.Delete
    SemanticActionId.Cancel -> Icons.Sharp.Close
    SemanticActionId.More -> Icons.Sharp.MoreHoriz
}

private fun lucideVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> Lucide.House; SemanticActionId.AppToday -> Lucide.Calendar; SemanticActionId.AppUpcoming -> Lucide.CalendarDays
    SemanticActionId.AppFavorites -> Lucide.Star; SemanticActionId.AppNotes -> Lucide.NotebookPen; SemanticActionId.AppDocuments -> Lucide.FileText
    SemanticActionId.AppTasks -> Lucide.ListTodo; SemanticActionId.AppFiles -> Lucide.Folder; SemanticActionId.AppChat -> Lucide.MessageCircle
    SemanticActionId.AppTopics -> Lucide.Tag; SemanticActionId.AppPeople -> Lucide.Users; SemanticActionId.AppGroups -> Lucide.Users
    SemanticActionId.AppInbox -> Lucide.Inbox; SemanticActionId.AppDatabase -> Lucide.Database; SemanticActionId.AppGraph -> Lucide.Network
    SemanticActionId.AppActivity -> Lucide.Activity; SemanticActionId.AppTemplates -> Lucide.LayoutTemplate; SemanticActionId.AppSettings -> Lucide.Settings
    SemanticActionId.AppFolder -> Lucide.FolderOpen; SemanticActionId.AppTags -> Lucide.Tags
    SemanticActionId.Back -> Lucide.ArrowLeft; SemanticActionId.Menu -> Lucide.Menu; SemanticActionId.Search -> Lucide.Search
    SemanticActionId.Done -> Lucide.Check; SemanticActionId.Save -> Lucide.Save; SemanticActionId.Undo -> Lucide.Undo2; SemanticActionId.Redo -> Lucide.Redo2
    SemanticActionId.Bold -> Lucide.Bold; SemanticActionId.Italic -> Lucide.Italic; SemanticActionId.Underline -> Lucide.Underline
    SemanticActionId.Insert -> Lucide.Plus; SemanticActionId.Toolsets -> Lucide.LayoutDashboard; SemanticActionId.Paragraph -> Lucide.Heading
    SemanticActionId.Checklist -> Lucide.ListChecks; SemanticActionId.Table -> Lucide.Table; SemanticActionId.Image -> Lucide.Image
    SemanticActionId.Attachment -> Lucide.Paperclip; SemanticActionId.Shapes -> Lucide.Shapes; SemanticActionId.Arrange -> Lucide.Layers
    SemanticActionId.Review -> Lucide.MessageSquare
    SemanticActionId.Strikethrough -> Lucide.Strikethrough; SemanticActionId.Code -> Lucide.Code; SemanticActionId.Link -> Lucide.Link
    SemanticActionId.Color -> Lucide.PaintBucket; SemanticActionId.Style -> Lucide.Palette; SemanticActionId.Edit -> Lucide.Pencil
    SemanticActionId.Move -> Lucide.Move; SemanticActionId.Duplicate -> Lucide.Copy; SemanticActionId.Delete -> Lucide.Trash2; SemanticActionId.Cancel -> Lucide.X
    SemanticActionId.MoveUp -> Lucide.ArrowUp; SemanticActionId.MoveDown -> Lucide.ArrowDown
    SemanticActionId.NestIn -> Lucide.ListIndentIncrease; SemanticActionId.NestOut -> Lucide.ListIndentDecrease
    SemanticActionId.Split -> Lucide.Split; SemanticActionId.Merge -> Lucide.Merge
    SemanticActionId.WrapColumns -> Lucide.Columns2; SemanticActionId.Unwrap -> Lucide.Rows2
    SemanticActionId.More -> Lucide.Ellipsis
}

private fun tablerVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> Tabler.Outline.Home; SemanticActionId.AppToday -> Tabler.Outline.Calendar; SemanticActionId.AppUpcoming -> Tabler.Outline.CalendarWeek
    SemanticActionId.AppFavorites -> Tabler.Outline.Star; SemanticActionId.AppNotes -> Tabler.Outline.Edit; SemanticActionId.AppDocuments -> Tabler.Outline.FileText
    SemanticActionId.AppTasks -> Tabler.Outline.Checklist; SemanticActionId.AppFiles -> Tabler.Outline.Folder; SemanticActionId.AppChat -> Tabler.Outline.MessageCircle
    SemanticActionId.AppInbox -> Tabler.Outline.Inbox; SemanticActionId.AppDatabase -> Tabler.Outline.Database; SemanticActionId.AppGraph -> Tabler.Outline.Share
    SemanticActionId.AppActivity -> Tabler.Outline.Activity; SemanticActionId.AppTemplates -> Tabler.Outline.LayoutGrid; SemanticActionId.AppSettings -> Tabler.Outline.Settings
    SemanticActionId.AppFolder -> Tabler.Outline.FolderOpen; SemanticActionId.AppTags -> Tabler.Outline.Tags
    SemanticActionId.Back -> Tabler.Outline.ArrowLeft; SemanticActionId.Menu -> Tabler.Outline.Menu; SemanticActionId.Search -> Tabler.Outline.Search
    SemanticActionId.Done -> Tabler.Outline.Check; SemanticActionId.Bold -> Tabler.Outline.Bold; SemanticActionId.Italic -> Tabler.Outline.Italic
    SemanticActionId.Underline -> Tabler.Outline.Underline; SemanticActionId.Insert -> Tabler.Outline.Plus; SemanticActionId.Toolsets -> Tabler.Outline.LayoutDashboard
    SemanticActionId.Paragraph -> Tabler.Outline.Heading; SemanticActionId.Checklist -> Tabler.Outline.Checklist; SemanticActionId.Table -> Tabler.Outline.Table
    SemanticActionId.Attachment -> Tabler.Outline.Paperclip; SemanticActionId.Shapes -> Tabler.Outline.Shape
    else -> materialVector(a)
}

private fun featherVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> Feather.Home; SemanticActionId.AppToday -> Feather.Calendar; SemanticActionId.AppUpcoming -> Feather.Clock
    SemanticActionId.AppFavorites -> Feather.Star; SemanticActionId.AppNotes -> Feather.Edit3; SemanticActionId.AppDocuments -> Feather.FileText
    SemanticActionId.AppTasks -> Feather.CheckSquare; SemanticActionId.AppFiles -> Feather.Folder; SemanticActionId.AppChat -> Feather.MessageCircle
    SemanticActionId.AppInbox -> Feather.Inbox; SemanticActionId.AppDatabase -> Feather.Database; SemanticActionId.AppGraph -> Feather.Share2
    SemanticActionId.AppActivity -> Feather.Activity; SemanticActionId.AppTemplates -> Feather.Grid; SemanticActionId.AppSettings -> Feather.Settings
    SemanticActionId.AppFolder -> Feather.FolderPlus; SemanticActionId.AppTags -> Feather.Tag
    SemanticActionId.Back -> Feather.ArrowLeft; SemanticActionId.Menu -> Feather.Menu; SemanticActionId.Search -> Feather.Search
    SemanticActionId.Done -> Feather.Check; SemanticActionId.Save -> Feather.Save; SemanticActionId.Bold -> Feather.Bold
    SemanticActionId.Italic -> Feather.Italic; SemanticActionId.Underline -> Feather.Underline; SemanticActionId.Insert -> Feather.Plus
    SemanticActionId.Toolsets -> Feather.Layout; SemanticActionId.Paragraph -> Feather.Type; SemanticActionId.Table -> Feather.Table
    SemanticActionId.Image -> Feather.Image; SemanticActionId.Attachment -> Feather.Paperclip; SemanticActionId.Arrange -> Feather.Layers
    SemanticActionId.Review -> Feather.MessageSquare; SemanticActionId.More -> Feather.MoreHorizontal
    else -> materialVector(a)
}

private fun bootstrapVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> Bootstrap.Outline.House; SemanticActionId.AppToday -> Bootstrap.Outline.Calendar; SemanticActionId.AppUpcoming -> Bootstrap.Outline.CalendarEvent
    SemanticActionId.AppFavorites -> Bootstrap.Outline.Star; SemanticActionId.AppNotes -> Bootstrap.Outline.PencilSquare; SemanticActionId.AppDocuments -> Bootstrap.Outline.FileText
    SemanticActionId.AppTasks -> Bootstrap.Outline.ListCheck; SemanticActionId.AppFiles -> Bootstrap.Outline.Folder; SemanticActionId.AppChat -> Bootstrap.Outline.ChatDots
    SemanticActionId.AppInbox -> Bootstrap.Outline.Inbox; SemanticActionId.AppDatabase -> Bootstrap.Outline.Database; SemanticActionId.AppGraph -> Bootstrap.Outline.Share
    SemanticActionId.AppActivity -> Bootstrap.Outline.Activity; SemanticActionId.AppTemplates -> Bootstrap.Outline.Grid; SemanticActionId.AppSettings -> Bootstrap.Outline.Gear
    SemanticActionId.AppFolder -> Bootstrap.Outline.Folder2Open; SemanticActionId.AppTags -> Bootstrap.Outline.Tags
    SemanticActionId.Back -> Bootstrap.Outline.ArrowLeft; SemanticActionId.Search -> Bootstrap.Outline.Search; SemanticActionId.Done -> Bootstrap.Outline.Check
    SemanticActionId.Save -> Bootstrap.Outline.Save; SemanticActionId.Insert -> Bootstrap.Outline.Plus; SemanticActionId.Paragraph -> Bootstrap.Outline.Type
    SemanticActionId.Checklist -> Bootstrap.Outline.ListCheck; SemanticActionId.Table -> Bootstrap.Outline.Table; SemanticActionId.Image -> Bootstrap.Outline.Image
    SemanticActionId.Attachment -> Bootstrap.Outline.Paperclip; SemanticActionId.Arrange -> Bootstrap.Outline.Layers
    else -> materialVector(a)
}

private fun heroVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> Heroicons.Outline.Home; SemanticActionId.AppToday -> Heroicons.Outline.Calendar; SemanticActionId.AppUpcoming -> Heroicons.Outline.CalendarDays
    SemanticActionId.AppFavorites -> Heroicons.Outline.Star; SemanticActionId.AppNotes -> Heroicons.Outline.PencilSquare; SemanticActionId.AppDocuments -> Heroicons.Outline.DocumentText
    SemanticActionId.AppTasks -> Heroicons.Outline.ClipboardDocumentCheck; SemanticActionId.AppFiles -> Heroicons.Outline.Folder; SemanticActionId.AppChat -> Heroicons.Outline.ChatBubbleLeftRight
    SemanticActionId.AppInbox -> Heroicons.Outline.Inbox; SemanticActionId.AppDatabase -> Heroicons.Outline.CircleStack; SemanticActionId.AppGraph -> Heroicons.Outline.Share
    SemanticActionId.AppActivity -> Heroicons.Outline.Bolt; SemanticActionId.AppTemplates -> Heroicons.Outline.Squares2x2; SemanticActionId.AppSettings -> Heroicons.Outline.Cog6Tooth
    SemanticActionId.AppFolder -> Heroicons.Outline.FolderOpen; SemanticActionId.AppTags -> Heroicons.Outline.Tag
    SemanticActionId.Back -> Heroicons.Outline.ArrowLeft; SemanticActionId.Done -> Heroicons.Outline.Check; SemanticActionId.Undo -> Heroicons.Outline.ArrowUturnLeft
    SemanticActionId.Redo -> Heroicons.Outline.ArrowUturnRight; SemanticActionId.Bold -> Heroicons.Outline.Bold; SemanticActionId.Italic -> Heroicons.Outline.Italic
    SemanticActionId.Underline -> Heroicons.Outline.Underline; SemanticActionId.Insert -> Heroicons.Outline.Plus; SemanticActionId.More -> Heroicons.Outline.EllipsisHorizontal
    else -> materialVector(a)
}

private fun radixVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> Radix.Home; SemanticActionId.AppToday -> Radix.Calendar; SemanticActionId.AppUpcoming -> Radix.Clock
    SemanticActionId.AppFavorites -> Radix.Star; SemanticActionId.AppNotes -> Radix.Pencil2; SemanticActionId.AppDocuments -> Radix.FileText
    SemanticActionId.AppTasks -> Radix.Checkbox; SemanticActionId.AppFiles -> Radix.File; SemanticActionId.AppChat -> Radix.ChatBubble
    SemanticActionId.AppInbox -> Radix.Archive; SemanticActionId.AppDatabase -> Radix.Database; SemanticActionId.AppGraph -> Radix.Share2
    SemanticActionId.AppActivity -> Radix.ActivityLog; SemanticActionId.AppTemplates -> Radix.Dashboard; SemanticActionId.AppSettings -> Radix.Gear
    SemanticActionId.AppFolder -> Radix.FilePlus; SemanticActionId.AppTags -> Radix.Bookmark
    SemanticActionId.Back -> Radix.ArrowLeft; SemanticActionId.Done -> Radix.Check; SemanticActionId.Insert -> Radix.Plus
    SemanticActionId.Toolsets -> Radix.Layout; SemanticActionId.Paragraph -> Radix.Heading; SemanticActionId.Table -> Radix.Table
    SemanticActionId.Image -> Radix.Image; SemanticActionId.Underline -> Radix.Underline; SemanticActionId.Arrange -> Radix.Layers
    else -> materialVector(a)
}

private fun fontAwesomeVector(a: SemanticActionId): ImageVector = when (a) {
    SemanticActionId.AppHome -> FontAwesome.Solid.Home; SemanticActionId.AppToday -> FontAwesome.Solid.Calendar; SemanticActionId.AppUpcoming -> FontAwesome.Solid.Clock
    SemanticActionId.AppFavorites -> FontAwesome.Solid.Star; SemanticActionId.AppNotes -> FontAwesome.Solid.StickyNote; SemanticActionId.AppDocuments -> FontAwesome.Solid.FileAlt
    SemanticActionId.AppTasks -> FontAwesome.Solid.Tasks; SemanticActionId.AppFiles -> FontAwesome.Solid.Folder; SemanticActionId.AppChat -> FontAwesome.Solid.Comments
    SemanticActionId.AppInbox -> FontAwesome.Solid.Inbox; SemanticActionId.AppDatabase -> FontAwesome.Solid.Database; SemanticActionId.AppGraph -> FontAwesome.Solid.NetworkWired
    SemanticActionId.AppActivity -> FontAwesome.Solid.Stream; SemanticActionId.AppTemplates -> FontAwesome.Solid.ThLarge; SemanticActionId.AppSettings -> FontAwesome.Solid.Cog
    SemanticActionId.AppFolder -> FontAwesome.Solid.FolderOpen; SemanticActionId.AppTags -> FontAwesome.Solid.Tags
    SemanticActionId.Back -> FontAwesome.Solid.ArrowLeft; SemanticActionId.Search -> FontAwesome.Solid.Search; SemanticActionId.Done -> FontAwesome.Solid.Check
    SemanticActionId.Save -> FontAwesome.Solid.Save; SemanticActionId.Undo -> FontAwesome.Solid.Undo; SemanticActionId.Redo -> FontAwesome.Solid.Redo
    SemanticActionId.Bold -> FontAwesome.Solid.Bold; SemanticActionId.Italic -> FontAwesome.Solid.Italic; SemanticActionId.Underline -> FontAwesome.Solid.Underline
    SemanticActionId.Insert -> FontAwesome.Solid.Plus; SemanticActionId.Paragraph -> FontAwesome.Solid.Heading; SemanticActionId.Table -> FontAwesome.Solid.Table
    SemanticActionId.Image -> FontAwesome.Solid.Image; SemanticActionId.Attachment -> FontAwesome.Solid.Paperclip; SemanticActionId.Shapes -> FontAwesome.Solid.Shapes
    else -> materialVector(a)
}
