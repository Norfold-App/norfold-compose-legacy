package com.norfold.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.MermaidBlock
import java.util.Locale

internal enum class DiagramKind(val label: String, val guided: Boolean) {
    Flowchart("Flowchart", true),
    Sequence("Sequence", true),
    Class("Class", false),
    State("State", false),
    Pie("Pie", false),
    Gantt("Gantt", false),
    Mindmap("Mindmap", false),
}

internal enum class FlowDirection {
    TD,
    LR,
}

internal enum class DiagramNodeShape(val label: String) {
    Rounded("Rounded"),
    Circle("Circle"),
    Diamond("Diamond"),
}

internal data class DiagramNodeInput(
    val id: String,
    val label: String,
    val color: String,
    val shape: DiagramNodeShape = DiagramNodeShape.Rounded,
)

internal data class DiagramEdgeInput(
    val from: String,
    val to: String,
    val label: String = "",
)

internal data class DiagramParticipantInput(
    val id: String,
    val label: String,
)

internal data class DiagramMessageInput(
    val from: String,
    val to: String,
    val text: String,
    val dashed: Boolean = false,
)

internal data class DiagramBuilderModel(
    val kind: DiagramKind = DiagramKind.Flowchart,
    val direction: FlowDirection = FlowDirection.TD,
    val nodes: List<DiagramNodeInput> = emptyList(),
    val edges: List<DiagramEdgeInput> = emptyList(),
    val participants: List<DiagramParticipantInput> = emptyList(),
    val messages: List<DiagramMessageInput> = emptyList(),
)

internal data class DiagramTemplate(
    val label: String,
    val source: String,
)

/**
 * The guided builder intentionally stores plain Mermaid in [MermaidBlock]. This codec makes the
 * form a lossless view over the subset Norfold creates while still accepting common hand-written
 * flowcharts and sequence diagrams when an existing block is reopened.
 */
internal object MermaidDiagramCodec {
    private val validId = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val validColor = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
    private val flowHeader = Regex("(?im)^\\s*(flowchart|graph)\\s+(TD|TB|LR|RL|BT)(?=\\s*(?:;|$))")
    private val flowHeaderStatement = Regex("(?i)^\\s*(flowchart|graph)\\s+(TD|TB|LR|RL|BT)\\s*$")
    private val sequenceHeader = Regex("(?im)^\\s*sequenceDiagram(?=\\s*(?:;|$))")
    private val classDef = Regex(
        """(?im)^\s*classDef\s+([A-Za-z][A-Za-z0-9_]*)\s+[^\n]*?fill\s*:\s*(#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?)[^\n]*$""",
    )
    private val classAssignment = Regex(
        """(?im)^\s*class\s+([A-Za-z][A-Za-z0-9_, \t]*)\s+([A-Za-z][A-Za-z0-9_]*)\s*;?[ \t]*$""",
    )
    private val directStyle = Regex(
        """(?im)^\s*style\s+([A-Za-z][A-Za-z0-9_]*)\s+[^\n]*?fill\s*:\s*(#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?)[^\n]*$""",
    )
    private val participant = Regex(
        """(?im)^\s*participant\s+([A-Za-z][A-Za-z0-9_]*)\s*(?:as\s+(.+?))?\s*$""",
    )
    private val message = Regex(
        """(?im)^\s*([A-Za-z][A-Za-z0-9_]*)\s*(-->>|->>)\s*([A-Za-z][A-Za-z0-9_]*)\s*:\s*(.+?)\s*$""",
    )

    fun defaultModel(defaultColor: String): DiagramBuilderModel = DiagramBuilderModel(
        nodes = listOf(
            DiagramNodeInput("Start", "Start", normalizeColor(defaultColor, defaultColor), DiagramNodeShape.Circle),
            DiagramNodeInput("Done", "Done", normalizeColor(defaultColor, defaultColor), DiagramNodeShape.Rounded),
        ),
        edges = listOf(DiagramEdgeInput("Start", "Done")),
    )

    fun encode(model: DiagramBuilderModel, defaultColor: String): String = when (model.kind) {
        DiagramKind.Flowchart -> encodeFlowchart(model, defaultColor)
        DiagramKind.Sequence -> encodeSequence(model)
        else -> templateCatalog(model.kind, defaultColor).first().source
    }

    fun decodeOrDefault(source: String?, defaultColor: String): DiagramBuilderModel =
        source?.takeIf(String::isNotBlank)?.let { decodeOrNull(it, defaultColor) }
            ?: defaultModel(defaultColor)

    fun decodeOrNull(source: String, defaultColor: String): DiagramBuilderModel? {
        val kind = detectKind(source) ?: return null
        return when (kind) {
            DiagramKind.Flowchart -> decodeFlowchart(source, defaultColor)
            DiagramKind.Sequence -> decodeSequence(source)
            else -> DiagramBuilderModel(kind = kind)
        }
    }

    fun detectKind(source: String): DiagramKind? = when {
        flowHeader.containsMatchIn(source) -> DiagramKind.Flowchart
        sequenceHeader.containsMatchIn(source) -> DiagramKind.Sequence
        Regex("(?im)^\\s*classDiagram\\b").containsMatchIn(source) -> DiagramKind.Class
        Regex("(?im)^\\s*stateDiagram(?:-v2)?\\b").containsMatchIn(source) -> DiagramKind.State
        Regex("(?im)^\\s*pie\\b").containsMatchIn(source) -> DiagramKind.Pie
        Regex("(?im)^\\s*gantt\\b").containsMatchIn(source) -> DiagramKind.Gantt
        Regex("(?im)^\\s*mindmap\\b").containsMatchIn(source) -> DiagramKind.Mindmap
        else -> null
    }

    fun validate(model: DiagramBuilderModel): List<String> = buildList {
        when (model.kind) {
            DiagramKind.Flowchart -> {
                if (model.nodes.isEmpty()) add("Add at least one node.")
                val ids = model.nodes.map { it.id.trim() }
                ids.filterNot(validId::matches).forEach { id ->
                    add("Node ID ${id.ifBlank { "(blank)" }} must start with a letter and use only letters, numbers, or underscores.")
                }
                ids.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
                    add("Node ID $id is used more than once.")
                }
                val known = ids.toSet()
                model.edges.forEachIndexed { index, edge ->
                    if (edge.from.trim() !in known || edge.to.trim() !in known) {
                        add("Connection ${index + 1} must reference existing node IDs.")
                    }
                }
            }

            DiagramKind.Sequence -> {
                if (model.participants.size < 2) add("Add at least two participants.")
                val ids = model.participants.map { it.id.trim() }
                ids.filterNot(validId::matches).forEach { id ->
                    add("Participant ID ${id.ifBlank { "(blank)" }} must start with a letter and use only letters, numbers, or underscores.")
                }
                ids.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
                    add("Participant ID $id is used more than once.")
                }
                val known = ids.toSet()
                model.messages.forEachIndexed { index, item ->
                    if (item.from.trim() !in known || item.to.trim() !in known) {
                        add("Message ${index + 1} must reference existing participant IDs.")
                    }
                    if (item.text.isBlank()) add("Message ${index + 1} needs text.")
                }
            }

            else -> Unit
        }
    }.distinct()

    fun nextId(existingIds: Collection<String>, prefix: String): String {
        val used = existingIds.map { it.lowercase() }.toSet()
        return generateSequence(1) { it + 1 }
            .map { "$prefix$it" }
            .first { it.lowercase() !in used }
    }

    fun templateCatalog(kind: DiagramKind, defaultColor: String): List<DiagramTemplate> {
        val safeColor = normalizeColor(defaultColor, "#6750A4")
        return when (kind) {
            DiagramKind.Flowchart -> {
                val linear = defaultModel(safeColor)
                val decision = DiagramBuilderModel(
                    nodes = listOf(
                        DiagramNodeInput("Idea", "Idea", safeColor, DiagramNodeShape.Circle),
                        DiagramNodeInput("Review", "Ready to publish?", safeColor, DiagramNodeShape.Diamond),
                        DiagramNodeInput("Publish", "Publish", safeColor, DiagramNodeShape.Rounded),
                    ),
                    edges = listOf(
                        DiagramEdgeInput("Idea", "Review"),
                        DiagramEdgeInput("Review", "Publish", "Yes"),
                    ),
                )
                listOf(
                    DiagramTemplate("Simple flow", encodeFlowchart(linear, safeColor)),
                    DiagramTemplate("Decision", encodeFlowchart(decision, safeColor)),
                )
            }

            DiagramKind.Sequence -> listOf(
                DiagramTemplate(
                    "Conversation",
                    """sequenceDiagram
  participant You as You
  participant Norfold as Norfold
  You->>Norfold: Capture an idea
  Norfold-->>You: Saved""",
                ),
                DiagramTemplate(
                    "Sync",
                    """sequenceDiagram
  participant App as App
  participant Vault as Encrypted vault
  participant Drive as Google Drive
  App->>Vault: Create snapshot
  Vault->>Drive: Upload
  Drive-->>App: Synced""",
                ),
            )

            DiagramKind.Class -> listOf(
                DiagramTemplate(
                    "Workspace objects",
                    """classDiagram
  class Workspace {
    +String title
    +open()
  }
  class Document {
    +String content
    +save()
  }
  Workspace "1" --> "many" Document""",
                ),
            )

            DiagramKind.State -> listOf(
                DiagramTemplate(
                    "Publishing",
                    """stateDiagram-v2
  [*] --> Draft
  Draft --> Review: submit
  Review --> Published: approve
  Published --> [*]""",
                ),
            )

            DiagramKind.Pie -> listOf(
                DiagramTemplate(
                    "Workspace mix",
                    """pie showData
  title Workspace mix
  "Docs" : 45
  "Tasks" : 30
  "Canvas" : 25""",
                ),
            )

            DiagramKind.Gantt -> listOf(
                DiagramTemplate(
                    "Project plan",
                    """gantt
  title Project plan
  dateFormat YYYY-MM-DD
  section Build
  Design :done, design, 2026-07-01, 4d
  Implement :active, build, after design, 7d
  Verify :verify, after build, 3d""",
                ),
            )

            DiagramKind.Mindmap -> listOf(
                DiagramTemplate(
                    "Workspace map",
                    """mindmap
  root((Workspace))
    Docs
      Notes
      Files
    Planning
      Tasks
      Calendar
    Explore
      Canvas
      Graph""",
                ),
            )
        }
    }

    private fun encodeFlowchart(model: DiagramBuilderModel, defaultColor: String): String = buildString {
        appendLine("flowchart ${model.direction.name}")
        val nodes = model.nodes.ifEmpty { defaultModel(defaultColor).nodes }
        nodes.forEachIndexed { index, node ->
            val id = normalizedId(node.id, "Node${index + 1}")
            val label = escapeLabel(node.label.ifBlank { id })
            val declaration = when (node.shape) {
                DiagramNodeShape.Rounded -> "$id(\"$label\")"
                DiagramNodeShape.Circle -> "$id((\"$label\"))"
                DiagramNodeShape.Diamond -> "$id{\"$label\"}"
            }
            appendLine("  $declaration")
        }
        model.edges.forEach { edge ->
            val from = normalizedId(edge.from, nodes.first().id)
            val to = normalizedId(edge.to, nodes.last().id)
            if (edge.label.isBlank()) {
                appendLine("  $from --> $to")
            } else {
                appendLine("  $from -->|${escapeEdgeLabel(edge.label)}| $to")
            }
        }
        nodes.forEachIndexed { index, node ->
            val id = normalizedId(node.id, "Node${index + 1}")
            val className = "norfold_${id}_$index"
            val color = normalizeColor(node.color, defaultColor)
            appendLine("  classDef $className fill:$color,stroke:$color,color:${contrastTextColor(color)}")
            appendLine("  class $id $className")
        }
    }.trimEnd()

    private fun encodeSequence(model: DiagramBuilderModel): String = buildString {
        appendLine("sequenceDiagram")
        val participants = model.participants.ifEmpty {
            listOf(DiagramParticipantInput("You", "You"), DiagramParticipantInput("Norfold", "Norfold"))
        }
        participants.forEachIndexed { index, item ->
            val id = normalizedId(item.id, "Actor${index + 1}")
            appendLine("  participant $id as ${escapeSequenceText(item.label.ifBlank { id })}")
        }
        model.messages.forEach { item ->
            val arrow = if (item.dashed) "-->>" else "->>"
            appendLine(
                "  ${normalizedId(item.from, participants.first().id)}$arrow" +
                    "${normalizedId(item.to, participants.last().id)}: ${escapeSequenceText(item.text)}",
            )
        }
    }.trimEnd()

    private fun decodeFlowchart(source: String, defaultColor: String): DiagramBuilderModel {
        val statements = sourceStatements(source)
        val normalizedSource = statements.joinToString("\n")
        val direction = when (flowHeader.find(source)?.groupValues?.getOrNull(2)?.uppercase(Locale.ROOT)) {
            "LR", "RL" -> FlowDirection.LR
            else -> FlowDirection.TD
        }
        val colorsByClass = classDef.findAll(normalizedSource).associate { match ->
            match.groupValues[1] to normalizeColor(match.groupValues[2], defaultColor)
        }
        val classByNode = buildMap {
            classAssignment.findAll(normalizedSource).forEach { match ->
                match.groupValues[1].split(',').map(String::trim).filter(String::isNotBlank).forEach { id ->
                    put(id, match.groupValues[2])
                }
            }
        }
        val directColors = directStyle.findAll(normalizedSource).associate { match ->
            match.groupValues[1] to normalizeColor(match.groupValues[2], defaultColor)
        }
        val nodes = linkedMapOf<String, DiagramNodeInput>()
        val edges = mutableListOf<DiagramEdgeInput>()

        statements.forEach { rawLine ->
            val line = rawLine.trim().removeSuffix(";")
            if (
                line.isBlank() || line.startsWith("%%") || flowHeaderStatement.matches(line) ||
                line.startsWith("classDef ") || line.startsWith("class ") || line.startsWith("style ") ||
                line.startsWith("subgraph ") || line == "end"
            ) return@forEach

            parseEdge(line)?.let { parsed ->
                parsed.from.node?.let { mergeNode(nodes, it, defaultColor) }
                parsed.to.node?.let { mergeNode(nodes, it, defaultColor) }
                val from = parsed.from.id
                val to = parsed.to.id
                if (validId.matches(from) && validId.matches(to)) {
                    nodes.putIfAbsent(from, DiagramNodeInput(from, from, defaultColor))
                    nodes.putIfAbsent(to, DiagramNodeInput(to, to, defaultColor))
                    edges += DiagramEdgeInput(from, to, decodeLabel(parsed.label))
                }
                return@forEach
            }

            parseNodeToken(line)?.node?.let { mergeNode(nodes, it, defaultColor) }
        }

        val colored = nodes.values.map { node ->
            val classColor = classByNode[node.id]?.let(colorsByClass::get)
            node.copy(color = directColors[node.id] ?: classColor ?: normalizeColor(node.color, defaultColor))
        }
        return DiagramBuilderModel(
            kind = DiagramKind.Flowchart,
            direction = direction,
            nodes = colored.ifEmpty { defaultModel(defaultColor).nodes },
            edges = edges.ifEmpty {
                if (colored.size >= 2) listOf(DiagramEdgeInput(colored[0].id, colored[1].id)) else emptyList()
            },
        )
    }

    private fun decodeSequence(source: String): DiagramBuilderModel {
        val normalizedSource = sourceStatements(source).joinToString("\n")
        val participants = linkedMapOf<String, DiagramParticipantInput>()
        participant.findAll(normalizedSource).forEach { match ->
            val id = match.groupValues[1]
            val label = match.groupValues[2].trim().ifBlank { id }
            participants[id] = DiagramParticipantInput(id, decodeLabel(label))
        }
        val messages = message.findAll(normalizedSource).map { match ->
            val from = match.groupValues[1]
            val to = match.groupValues[3]
            participants.putIfAbsent(from, DiagramParticipantInput(from, from))
            participants.putIfAbsent(to, DiagramParticipantInput(to, to))
            DiagramMessageInput(
                from = from,
                to = to,
                text = decodeLabel(match.groupValues[4]),
                dashed = match.groupValues[2] == "-->>",
            )
        }.toList()
        val safeParticipants = participants.values.toList().ifEmpty {
            listOf(DiagramParticipantInput("You", "You"), DiagramParticipantInput("Norfold", "Norfold"))
        }
        return DiagramBuilderModel(
            kind = DiagramKind.Sequence,
            participants = safeParticipants,
            messages = messages.ifEmpty {
                listOf(DiagramMessageInput(safeParticipants.first().id, safeParticipants.last().id, "Message"))
            },
        )
    }

    private data class ParsedToken(val id: String, val node: DiagramNodeInput?)
    private data class ParsedEdge(val from: ParsedToken, val to: ParsedToken, val label: String)

    private fun parseEdge(line: String): ParsedEdge? {
        val match = Regex("""^(.+?)\s*-->\s*(?:\|([^|]*)\|\s*)?(.+?)$""").matchEntire(line) ?: return null
        val from = parseNodeToken(match.groupValues[1]) ?: return null
        val to = parseNodeToken(match.groupValues[3]) ?: return null
        return ParsedEdge(from, to, match.groupValues[2])
    }

    private fun parseNodeToken(raw: String): ParsedToken? {
        val token = raw.trim()
        val patterns = listOf(
            DiagramNodeShape.Circle to Regex("""^([A-Za-z][A-Za-z0-9_]*)\(\((?:\"(.*)\"|(.*))\)\)$"""),
            DiagramNodeShape.Diamond to Regex("""^([A-Za-z][A-Za-z0-9_]*)\{(?:\"(.*)\"|(.*))\}$"""),
            DiagramNodeShape.Rounded to Regex("""^([A-Za-z][A-Za-z0-9_]*)\((?:\"(.*)\"|(.*))\)$"""),
            DiagramNodeShape.Rounded to Regex("""^([A-Za-z][A-Za-z0-9_]*)\[(?:\"(.*)\"|(.*))\]$"""),
        )
        patterns.forEach { (shape, pattern) ->
            pattern.matchEntire(token)?.let { match ->
                val id = match.groupValues[1]
                val label = match.groupValues.drop(2).firstOrNull(String::isNotBlank).orEmpty().ifBlank { id }
                return ParsedToken(id, DiagramNodeInput(id, decodeLabel(label), "", shape))
            }
        }
        return if (validId.matches(token)) ParsedToken(token, null) else null
    }

    private fun mergeNode(
        destination: MutableMap<String, DiagramNodeInput>,
        node: DiagramNodeInput,
        defaultColor: String,
    ) {
        val existing = destination[node.id]
        destination[node.id] = node.copy(
            label = node.label.ifBlank { existing?.label ?: node.id },
            color = normalizeColor(existing?.color.orEmpty().ifBlank { node.color }, defaultColor),
        )
    }

    /** Splits legacy one-line Mermaid without breaking quoted/bracketed labels or HTML entities. */
    private fun sourceStatements(source: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var pipeLabel = false
        var entity = false
        var delimiterDepth = 0

        fun flush() {
            current.toString().trim().takeIf(String::isNotBlank)?.let(statements::add)
            current.clear()
        }

        source.forEach { character ->
            when {
                character == '&' && !quoted -> {
                    entity = true
                    current.append(character)
                }

                character == ';' && entity -> {
                    entity = false
                    current.append(character)
                }

                character == '"' && !entity -> {
                    quoted = !quoted
                    current.append(character)
                }

                character == '|' && !quoted && delimiterDepth == 0 -> {
                    pipeLabel = !pipeLabel
                    current.append(character)
                }

                character in "([{"
                    && !quoted && !pipeLabel && !entity -> {
                    delimiterDepth++
                    current.append(character)
                }

                character in ")]}"
                    && !quoted && !pipeLabel && !entity -> {
                    delimiterDepth = (delimiterDepth - 1).coerceAtLeast(0)
                    current.append(character)
                }

                (character == ';' || character == '\n' || character == '\r') &&
                    !quoted && !pipeLabel && !entity && delimiterDepth == 0 -> flush()

                else -> current.append(character)
            }
        }
        flush()
        return statements
    }

    private fun normalizedId(value: String, fallback: String): String {
        val candidate = value.trim()
        if (validId.matches(candidate)) return candidate
        val sanitized = candidate.replace(Regex("[^A-Za-z0-9_]"), "_")
            .let { if (it.firstOrNull()?.isLetter() == true) it else "N$it" }
            .trimEnd('_')
        return sanitized.takeIf(validId::matches) ?: fallback.filter(Char::isLetterOrDigit).ifBlank { "Node" }
    }

    private fun normalizeColor(value: String, fallback: String): String = when {
        validColor.matches(value.trim()) -> value.trim().uppercase(Locale.ROOT)
        validColor.matches(fallback.trim()) -> fallback.trim().uppercase(Locale.ROOT)
        else -> "#6750A4"
    }

    private fun contrastTextColor(background: String): String {
        val rgb = background.removePrefix("#").take(6).toIntOrNull(16) ?: return "#FFFFFF"
        fun linear(channel: Int): Double {
            val normalized = channel / 255.0
            return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * linear(rgb shr 16 and 0xFF) +
            0.7152 * linear(rgb shr 8 and 0xFF) +
            0.0722 * linear(rgb and 0xFF)
        val blackContrast = (luminance + 0.05) / 0.05
        val whiteContrast = 1.05 / (luminance + 0.05)
        return if (blackContrast >= whiteContrast) "#000000" else "#FFFFFF"
    }

    private fun escapeLabel(value: String): String = value.trim()
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("[\\r\\n]+"), "<br/>")

    private fun escapeEdgeLabel(value: String): String = escapeLabel(value).replace("|", "&#124;")

    private fun escapeSequenceText(value: String): String = value.trim()
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(";", "&#59;")

    private fun decodeLabel(value: String): String = value.trim().removeSurrounding("\"")
        .replace("<br/>", "\n", ignoreCase = true)
        .replace("&quot;", "\"")
        .replace("&#124;", "|")
        .replace("&#59;", ";")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagramBuilderSheet(
    initialSource: String? = null,
    onDismiss: () -> Unit,
    onCreate: (MermaidBlock) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val defaultColor = colors.primary.toDiagramHex()
    val palette = remember(colors) {
        listOf(
            colors.primary,
            colors.secondary,
            colors.tertiary,
            colors.primaryContainer,
            colors.secondaryContainer,
            colors.tertiaryContainer,
            colors.errorContainer,
        ).distinctBy(Color::toArgb)
    }
    val seeded = remember(initialSource) {
        MermaidDiagramCodec.decodeOrDefault(initialSource, defaultColor)
    }
    var model by remember(initialSource) { mutableStateOf(seeded) }
    var source by remember(initialSource) {
        mutableStateOf(
            initialSource?.trim()?.takeIf(String::isNotBlank)
                ?: MermaidDiagramCodec.encode(seeded, defaultColor),
        )
    }
    var showAdvanced by remember(initialSource) { mutableStateOf(false) }
    val errors = remember(model) { MermaidDiagramCodec.validate(model) }
    val sourceKind = remember(source) { MermaidDiagramCodec.detectKind(source) }
    val dark = colors.surface.luminance() < 0.5f

    fun updateModel(updated: DiagramBuilderModel) {
        model = updated
        source = MermaidDiagramCodec.encode(updated, defaultColor)
    }

    fun selectTemplate(template: DiagramTemplate) {
        source = template.source
        model = MermaidDiagramCodec.decodeOrNull(template.source, defaultColor)
            ?: model.copy(kind = MermaidDiagramCodec.detectKind(template.source) ?: model.kind)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (initialSource.isNullOrBlank()) "Create diagram" else "Edit diagram",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Build visually, then use Advanced only when you need Mermaid source.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagramKind.entries.forEach { candidate ->
                    FilterChip(
                        selected = model.kind == candidate,
                        onClick = {
                            if (model.kind != candidate) {
                                selectTemplate(MermaidDiagramCodec.templateCatalog(candidate, defaultColor).first())
                            }
                        },
                        label = { Text(candidate.label) },
                    )
                }
            }

            TemplatePicker(
                templates = MermaidDiagramCodec.templateCatalog(model.kind, defaultColor),
                onSelect = ::selectTemplate,
            )

            DiagramPreview(source = source, dark = dark, accentHex = defaultColor)

            when (model.kind) {
                DiagramKind.Flowchart -> FlowchartForm(
                    model = model,
                    palette = palette,
                    onChange = ::updateModel,
                )

                DiagramKind.Sequence -> SequenceForm(model = model, onChange = ::updateModel)
                else -> Text(
                    text = "${model.kind.label} starts from a working template. Use Advanced to customize its Mermaid source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            if (errors.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Edit raw Mermaid source",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showAdvanced,
                    onCheckedChange = { showAdvanced = it },
                    modifier = Modifier.semantics { contentDescription = "Advanced Mermaid source" },
                )
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = source,
                    onValueChange = { updated ->
                        source = updated
                        MermaidDiagramCodec.decodeOrNull(updated, defaultColor)?.let { decoded -> model = decoded }
                    },
                    label = { Text("Mermaid source") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = {
                        if (sourceKind == null) {
                            Text("Add a supported Mermaid diagram header to update the visual form.")
                        } else {
                            Text("Preview and visual fields stay synchronized when this source is parseable.")
                        }
                    },
                    isError = sourceKind == null,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onCreate(MermaidBlock(code = source.trim())) },
                    enabled = source.isNotBlank() && sourceKind != null &&
                        (showAdvanced || errors.isEmpty() || !model.kind.guided),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (initialSource.isNullOrBlank()) "Create" else "Save")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TemplatePicker(templates: List<DiagramTemplate>, onSelect: (DiagramTemplate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Templates", fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            templates.forEach { template ->
                FilterChip(
                    selected = false,
                    onClick = { onSelect(template) },
                    label = { Text(template.label) },
                )
            }
        }
    }
}

@Composable
private fun DiagramPreview(source: String, dark: Boolean, accentHex: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Live preview", fontWeight = FontWeight.SemiBold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            MarkdownPreview(
                markdown = "```mermaid\n${source.trim()}\n```",
                dark = dark,
                accentHex = accentHex,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
            )
        }
    }
}

@Composable
private fun FlowchartForm(
    model: DiagramBuilderModel,
    palette: List<Color>,
    onChange: (DiagramBuilderModel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Direction", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowDirection.entries.forEach { direction ->
                FilterChip(
                    selected = model.direction == direction,
                    onClick = { onChange(model.copy(direction = direction)) },
                    label = { Text(if (direction == FlowDirection.TD) "Top to bottom" else "Left to right") },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Boxes", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = {
                    val id = MermaidDiagramCodec.nextId(model.nodes.map { it.id }, "Node")
                    val color = palette[model.nodes.size % palette.size].toDiagramHex()
                    onChange(model.copy(nodes = model.nodes + DiagramNodeInput(id, "New box", color)))
                },
            ) { Text("+ Box") }
        }
        model.nodes.forEachIndexed { index, node ->
            FlowchartNodeCard(
                index = index,
                node = node,
                canDelete = model.nodes.size > 1,
                palette = palette,
                onUpdate = { updated ->
                    val oldId = node.id
                    onChange(
                        model.copy(
                            nodes = model.nodes.updated(index, updated),
                            edges = model.edges.map { edge ->
                                edge.copy(
                                    from = if (edge.from == oldId) updated.id else edge.from,
                                    to = if (edge.to == oldId) updated.id else edge.to,
                                )
                            },
                        ),
                    )
                },
                onDelete = {
                    onChange(
                        model.copy(
                            nodes = model.nodes.filterIndexed { itemIndex, _ -> itemIndex != index },
                            edges = model.edges.filterNot { it.from == node.id || it.to == node.id },
                        ),
                    )
                },
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Connections", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = {
                    val from = model.nodes.firstOrNull()?.id.orEmpty()
                    val to = model.nodes.getOrNull(1)?.id ?: from
                    onChange(model.copy(edges = model.edges + DiagramEdgeInput(from, to)))
                },
                enabled = model.nodes.isNotEmpty(),
            ) { Text("+ Connection") }
        }
        model.edges.forEachIndexed { index, edge ->
            FlowchartEdgeCard(
                index = index,
                edge = edge,
                onUpdate = { onChange(model.copy(edges = model.edges.updated(index, it))) },
                onDelete = { onChange(model.copy(edges = model.edges.filterIndexed { itemIndex, _ -> itemIndex != index })) },
            )
        }
        if (model.edges.isEmpty()) {
            Text(
                "Add a connection, then enter node IDs in the From and To boxes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FlowchartNodeCard(
    index: Int,
    node: DiagramNodeInput,
    canDelete: Boolean,
    palette: List<Color>,
    onUpdate: (DiagramNodeInput) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Box ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = onDelete, enabled = canDelete) { Text("Remove") }
            }
            OutlinedTextField(
                value = node.id,
                onValueChange = { onUpdate(node.copy(id = it)) },
                label = { Text("ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = node.label,
                onValueChange = { onUpdate(node.copy(label = it)) },
                label = { Text("Box text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3,
            )
            Text("Shape", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DiagramNodeShape.entries.forEach { shape ->
                    FilterChip(
                        selected = node.shape == shape,
                        onClick = { onUpdate(node.copy(shape = shape)) },
                        label = { Text(shape.label) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Color", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(node.color, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                palette.forEachIndexed { colorIndex, candidate ->
                    val hex = candidate.toDiagramHex()
                    val selected = node.color.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(candidate)
                            .clickable { onUpdate(node.copy(color = hex)) }
                            .semantics {
                                contentDescription = "Box ${index + 1} color ${colorIndex + 1}${if (selected) ", selected" else ""}"
                            }
                            .then(
                                if (selected) {
                                    Modifier.padding(5.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowchartEdgeCard(
    index: Int,
    edge: DiagramEdgeInput,
    onUpdate: (DiagramEdgeInput) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Connection ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = onDelete) { Text("Remove") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = edge.from,
                    onValueChange = { onUpdate(edge.copy(from = it)) },
                    label = { Text("From") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Text("→", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = edge.to,
                    onValueChange = { onUpdate(edge.copy(to = it)) },
                    label = { Text("To") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = edge.label,
                onValueChange = { onUpdate(edge.copy(label = it)) },
                label = { Text("Edge label (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun SequenceForm(model: DiagramBuilderModel, onChange: (DiagramBuilderModel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Participants", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = {
                    val id = MermaidDiagramCodec.nextId(model.participants.map { it.id }, "Actor")
                    onChange(model.copy(participants = model.participants + DiagramParticipantInput(id, "New participant")))
                },
            ) { Text("+ Participant") }
        }
        model.participants.forEachIndexed { index, participant ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Participant ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        TextButton(
                            onClick = {
                                onChange(
                                    model.copy(
                                        participants = model.participants.filterIndexed { itemIndex, _ -> itemIndex != index },
                                        messages = model.messages.filterNot {
                                            it.from == participant.id || it.to == participant.id
                                        },
                                    ),
                                )
                            },
                            enabled = model.participants.size > 2,
                        ) { Text("Remove") }
                    }
                    OutlinedTextField(
                        value = participant.id,
                        onValueChange = { updatedId ->
                            val updated = participant.copy(id = updatedId)
                            onChange(
                                model.copy(
                                    participants = model.participants.updated(index, updated),
                                    messages = model.messages.map { item ->
                                        item.copy(
                                            from = if (item.from == participant.id) updatedId else item.from,
                                            to = if (item.to == participant.id) updatedId else item.to,
                                        )
                                    },
                                ),
                            )
                        },
                        label = { Text("ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = participant.label,
                        onValueChange = {
                            onChange(model.copy(participants = model.participants.updated(index, participant.copy(label = it))))
                        },
                        label = { Text("Display name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Messages", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = {
                    val from = model.participants.firstOrNull()?.id.orEmpty()
                    val to = model.participants.getOrNull(1)?.id ?: from
                    onChange(model.copy(messages = model.messages + DiagramMessageInput(from, to, "Message")))
                },
                enabled = model.participants.size >= 2,
            ) { Text("+ Message") }
        }
        model.messages.forEachIndexed { index, message ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Message ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        TextButton(
                            onClick = {
                                onChange(model.copy(messages = model.messages.filterIndexed { itemIndex, _ -> itemIndex != index }))
                            },
                        ) { Text("Remove") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = message.from,
                            onValueChange = {
                                onChange(model.copy(messages = model.messages.updated(index, message.copy(from = it))))
                            },
                            label = { Text("From") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Text("→", modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = message.to,
                            onValueChange = {
                                onChange(model.copy(messages = model.messages.updated(index, message.copy(to = it))))
                            },
                            label = { Text("To") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = message.text,
                        onValueChange = {
                            onChange(model.copy(messages = model.messages.updated(index, message.copy(text = it))))
                        },
                        label = { Text("Message text") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Dashed reply", Modifier.weight(1f))
                        Switch(
                            checked = message.dashed,
                            onCheckedChange = {
                                onChange(model.copy(messages = model.messages.updated(index, message.copy(dashed = it))))
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> =
    mapIndexed { itemIndex, current -> if (itemIndex == index) value else current }

private fun Color.toDiagramHex(): String = "#%06X".format(toArgb() and 0x00FFFFFF)
