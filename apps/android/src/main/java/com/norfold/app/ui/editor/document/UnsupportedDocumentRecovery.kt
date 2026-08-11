package com.norfold.app.ui.editor.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.MarkdownBlockCodec
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.editor.document.LegacyDocumentMigration

/**
 * What a person sees when this build refuses to convert their document.
 *
 * A refusal is rare and it is *deliberate*: [LegacyDocumentMigration] returns a report that is not lossless,
 * [com.norfold.app.editor.document.DocumentHostRuntime.migrateLegacyDocument] declines to commit it, and the
 * original artifact is left exactly as it was found. That leaves one honest thing to render, and this is it.
 *
 * It is a recovery surface, not an editor. There is no second document architecture behind it — no legacy
 * host, no alternate engine, no writable path of any kind. It reads the untouched original out of the
 * controller and shows it. That is the whole implementation, and it is the point: a fallback that could edit
 * would be a second product to maintain, and a second product is how the two drift apart until the "safe"
 * one silently becomes the lossy one.
 *
 * Three obligations, all of them load-bearing:
 *
 *  - **Never a blank page.** The content is shown, selectable, in full. A migration that was refused
 *    precisely so nothing would be lost must not then present as an empty document — that reads as data
 *    loss whether or not any occurred, and the owner has no way to tell the difference.
 *  - **The raw data leaves with them.** Both exports copy the *original*, not the rejected candidate:
 *    Markdown for something readable elsewhere, and the structured payload for something lossless. The
 *    structured copy is the one that matters here, because it carries the blocks this build cannot render
 *    and the markdown export cannot express.
 *  - **The report is specific.** Named ids, not a count. "3 stale positions" is not something anyone can
 *    check; the ids are.
 *
 * Nothing here offers to force the conversion. `allowLossy` is a decision only the owner of the content can
 * make, and a button on a screen shown at the moment of failure is not informed consent — it is the fastest
 * possible route to an irreversible mistake.
 */
@Composable
internal fun UnsupportedDocumentRecovery(
    title: String,
    document: BlockDocument,
    report: LegacyDocumentMigration.Report,
    onOpenSidebar: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    // Both projections are derived once from the original document, not from the refused candidate.
    val markdown = remember(document) { MarkdownBlockCodec.export(document) }
    val payload = remember(document) { BlockDocumentJson.encode(document) }
    // Markdown has no form for a block this build cannot interpret, so those blocks vanish from that export
    // and only that export. Counting them is what keeps the reassurance underneath honest.
    val unreadable = remember(document) { document.normalized().blocks.count { it is UnknownBlock } }
    var copied by remember(document) { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenSidebar) { Text("Menu") }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onExit) { Text("Close") }
        }

        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("This document was not converted", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = report.refusalDetail(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Nothing was changed or removed. Your content is below, and you can copy all of " +
                        "it out. Converting it would have discarded the positions listed above, so this " +
                        "build stopped instead of guessing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (unreadable > 0) {
                    Text(
                        text = "$unreadable block${if (unreadable == 1) "" else "s"} use a format this " +
                            "build cannot display. They are not shown below, and they are not lost — " +
                            "\"Copy structured data\" includes them exactly as stored.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(markdown))
                            copied = "Text copied"
                        },
                    ) { Text("Copy as text") }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(payload))
                            copied = "Structured data copied"
                        },
                    ) { Text("Copy structured data") }
                    copied?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // Selectable, so the content can be taken out by hand as well as by the copy buttons — and readable
        // rather than raw, because someone arriving here wants to see that their writing is still there.
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (markdown.isBlank()) {
                    // Everything in this document is in a form with no text projection. Saying so beats an
                    // empty scroll area, which would look exactly like the loss this refusal prevented.
                    Text(
                        text = "This document holds no plain text — its content is entirely in formats this " +
                            "build cannot display. Use \"Copy structured data\" to take all of it with you.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        text = markdown,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
