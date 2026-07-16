package com.norfold.app.ui.components

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.NoteRenderEngine
import kotlinx.coroutines.delay

/**
 * Markdown editor with a debounced live preview underneath and a formatting
 * toolbar. The toolbar hugs the field as a docked strip while the IME is open
 * and floats as a rounded pill when the keyboard is hidden.
 *
 * Preview engine selection:
 * - [NoteRenderEngine.Native] renders inline styles/headings/lists with Compose text.
 * - [NoteRenderEngine.WebView] routes through [MarkdownPreview] (and the render cache).
 * - [NoteRenderEngine.Auto] picks WebView only when the content needs it (tables,
 *   fenced code, math, images, raw HTML) and the device is comfortably modern
 *   (API 26+); plain prose stays native to avoid the WebView RAM cost.
 */
@Composable
fun LiveMarkdownField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Write markdown…",
    engine: NoteRenderEngine = NoteRenderEngine.Auto,
    minFieldHeight: Int = 96,
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) {
        // External reset (e.g. Cancel) — keep selection in-bounds.
        field = TextFieldValue(value, TextRange(value.length))
    }
    var preview by remember { mutableStateOf(value) }
    LaunchedEffect(field.text) {
        delay(200)
        preview = field.text
    }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    fun update(next: TextFieldValue) {
        field = next
        onValueChange(next.text)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = ::update,
            modifier = Modifier.fillMaxWidth().heightIn(min = minFieldHeight.dp),
            placeholder = { Text(placeholder) },
            colors = OutlinedTextFieldDefaults.colors(),
        )
        MarkdownFormatToolbar(
            docked = imeVisible,
            onAction = { action -> update(action.apply(field)) },
        )
        if (preview.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val useWebView = when (engine) {
                    NoteRenderEngine.WebView -> true
                    NoteRenderEngine.Native -> false
                    NoteRenderEngine.Auto -> Build.VERSION.SDK_INT >= 26 && needsWebView(preview)
                }
                if (useWebView) {
                    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
                    val accentHex = remember(primaryArgb) { "#%06X".format(primaryArgb and 0xFFFFFF) }
                    MarkdownPreview(
                        markdown = preview,
                        dark = dark,
                        accentHex = accentHex,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                } else {
                    NativeMarkdownPreview(preview, Modifier.fillMaxWidth().padding(12.dp))
                }
            }
        }
    }
}

private enum class MarkdownFormat(val label: String) {
    Bold("Bold"),
    Italic("Italic"),
    Heading("Heading"),
    BulletList("List"),
    Checklist("Checklist"),
    Link("Link"),
    Code("Code"),
    Math("Math"),
    ;

    /** Wraps the current selection (or inserts a stub at the cursor) and re-positions the caret. */
    fun apply(value: TextFieldValue): TextFieldValue {
        val start = minOf(value.selection.start, value.selection.end)
        val end = maxOf(value.selection.start, value.selection.end)
        val selected = value.text.substring(start, end)
        return when (this) {
            Bold -> wrap(value, start, end, selected, "**", "**")
            Italic -> wrap(value, start, end, selected, "*", "*")
            Code ->
                if (selected.contains('\n')) wrap(value, start, end, selected, "```\n", "\n```")
                else wrap(value, start, end, selected, "`", "`")
            Math -> wrap(value, start, end, selected, "$", "$")
            Heading -> prefixLine(value, start, "# ")
            BulletList -> prefixLine(value, start, "- ")
            Checklist -> prefixLine(value, start, "- [ ] ")
            Link -> {
                val text = selected.ifEmpty { "text" }
                val inserted = "[$text](url)"
                val next = value.text.replaceRange(start, end, inserted)
                // Select the "url" placeholder so typing replaces it.
                val urlStart = start + text.length + 3
                TextFieldValue(next, TextRange(urlStart, urlStart + 3))
            }
        }
    }

    private fun wrap(value: TextFieldValue, start: Int, end: Int, selected: String, open: String, close: String): TextFieldValue {
        val next = value.text.replaceRange(start, end, "$open$selected$close")
        val caret = if (selected.isEmpty()) start + open.length else start + open.length + selected.length + close.length
        return TextFieldValue(next, TextRange(caret))
    }

    private fun prefixLine(value: TextFieldValue, at: Int, prefix: String): TextFieldValue {
        val lineStart = value.text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val next = value.text.replaceRange(lineStart, lineStart, prefix)
        return TextFieldValue(next, TextRange(at + prefix.length))
    }
}

@Composable
private fun MarkdownFormatToolbar(docked: Boolean, onAction: (MarkdownFormat) -> Unit) {
    val icons = listOf(
        MarkdownFormat.Bold to Icons.Outlined.FormatBold,
        MarkdownFormat.Italic to Icons.Outlined.FormatItalic,
        MarkdownFormat.Heading to Icons.Outlined.Title,
        MarkdownFormat.BulletList to Icons.AutoMirrored.Outlined.FormatListBulleted,
        MarkdownFormat.Checklist to Icons.Outlined.Checklist,
        MarkdownFormat.Link to Icons.Outlined.Link,
        MarkdownFormat.Code to Icons.Outlined.Code,
        MarkdownFormat.Math to Icons.Outlined.Functions,
    )
    Surface(
        color = if (docked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(if (docked) 10.dp else 22.dp),
        tonalElevation = if (docked) 0.dp else 4.dp,
        modifier = if (docked) Modifier.fillMaxWidth() else Modifier,
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            icons.forEach { (action, icon) ->
                IconButton(onClick = { onAction(action) }) {
                    Icon(icon, action.label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** True when the markdown uses constructs the native preview can't draw. */
private fun needsWebView(markdown: String): Boolean =
    markdown.contains("```") ||
        markdown.contains("~~~") ||
        markdown.contains('|') ||
        markdown.contains('$') ||
        markdown.contains("![") ||
        markdown.contains('<')

@Composable
private fun NativeMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("### ") -> Text(nativeInline(trimmed.removePrefix("### ")), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                trimmed.startsWith("## ") -> Text(nativeInline(trimmed.removePrefix("## ")), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                trimmed.startsWith("# ") -> Text(nativeInline(trimmed.removePrefix("# ")), fontSize = 20.sp, fontWeight = FontWeight.Black)
                trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") ->
                    Text(buildAnnotatedString {
                        append("☑ ")
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(nativeInline(trimmed.drop(6)))
                    })
                trimmed.startsWith("- [ ] ") -> Text(buildAnnotatedString { append("☐ "); append(nativeInline(trimmed.drop(6))) })
                trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                    Text(buildAnnotatedString { append("• "); append(nativeInline(trimmed.drop(2))) })
                trimmed.startsWith("> ") ->
                    Text(nativeInline(trimmed.drop(2)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                trimmed == "---" || trimmed == "***" -> Text("―――", color = MaterialTheme.colorScheme.outlineVariant)
                line.isBlank() -> Unit
                else -> Text(nativeInline(line))
            }
        }
    }
}

/** Minimal inline pass: **bold**, *italic*, `code`, ~~strike~~. */
private fun nativeInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val bold = text.startsWith("**", i)
        val strike = text.startsWith("~~", i)
        val italic = !bold && text[i] == '*'
        val code = text[i] == '`'
        when {
            bold || strike -> {
                val token = if (bold) "**" else "~~"
                val close = text.indexOf(token, i + 2)
                if (close > i) {
                    val style = if (bold) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(textDecoration = TextDecoration.LineThrough)
                    pushStyle(style)
                    append(text.substring(i + 2, close))
                    pop()
                    i = close + 2
                } else {
                    append(text[i]); i++
                }
            }
            italic -> {
                val close = text.indexOf('*', i + 1)
                if (close > i) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, close))
                    pop()
                    i = close + 1
                } else {
                    append(text[i]); i++
                }
            }
            code -> {
                val close = text.indexOf('`', i + 1)
                if (close > i) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.18f)))
                    append(text.substring(i + 1, close))
                    pop()
                    i = close + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}
