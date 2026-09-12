package com.norfold.flow.c1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * C1 Stage-1 spike. The whole fixture lives in one `TextFieldState`; atomic
 * objects are a single object-replacement character (`U+FFFC`) with a span
 * style, shown as a box glyph through `OutputTransformation`. One Compose
 * selection / one composing region over the whole document. Test buttons are
 * programmatic: a scripted model operation is feasibility evidence, not proof
 * that the platform handles, clipboard, IME or accessibility path works.
 */
class C1FixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { C1FixtureScreen() }
        }
    }
}

private const val ATOM = '\uFFFC'
private val ATOM_GLYPHS = listOf("\u25A3", "\u25A4", "\u25A5")

private data class Fixture(
    val text: String,
    val bold: IntRange?,
    val link: IntRange?,
    val tag: IntRange?,
    val mention: IntRange?,
    val atomOffsets: List<Int>,
)

private fun buildFixture(): Fixture {
    val sb = StringBuilder()
    var bold: IntRange? = null
    var link: IntRange? = null
    var tag: IntRange? = null
    var mention: IntRange? = null
    val atoms = mutableListOf<Int>()
    fun newline() = sb.append('\n')

    sb.append("Alpha paragraph with ")
    bold = sb.length until sb.length + 4
    sb.append("bold")
    sb.append(" and ")
    link = sb.length until sb.length + 4
    sb.append("link")
    sb.append(".")
    newline()

    sb.append("Text before image.")
    newline()

    atoms += sb.length
    sb.append(ATOM)
    newline()

    sb.append("Text after image.")
    newline()

    atoms += sb.length
    sb.append(ATOM)
    newline()

    sb.append("Paragraph after table.")
    newline()

    atoms += sb.length
    sb.append(ATOM)
    newline()

    sb.append("See ")
    tag = sb.length until sb.length + 7
    sb.append("#stage1")
    sb.append(" and ")
    mention = sb.length until sb.length + 3
    sb.append("@qa")
    sb.append(" for context.")
    newline()

    sb.append("Final paragraph.")

    return Fixture(sb.toString(), bold, link, tag, mention, atoms)
}

@Composable
private fun C1FixtureScreen() {
    val fx = remember { buildFixture() }
    val state = rememberTextFieldState(fx.text)
    var status by remember { mutableStateOf("ready len=${fx.text.length} atoms=${fx.atomOffsets}") }

    val output = remember {
        OutputTransformation {
            var i = 0
            var k = 0
            while (i < length) {
                if (charAt(i) == ATOM) {
                    replace(i, i + 1, ATOM_GLYPHS[k % ATOM_GLYPHS.size])
                    k++
                }
                i++
            }
        }
    }

    LaunchedEffect(Unit) {
        state.edit {
            fx.bold?.let { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) }
            fx.link?.let {
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = Color(0xFF1565C0)), it.first, it.last + 1)
            }
            fx.tag?.let {
                addStyle(SpanStyle(color = Color(0xFF6A1B9A), background = Color(0x226A1B9A)), it.first, it.last + 1)
            }
            fx.mention?.let {
                addStyle(SpanStyle(color = Color(0xFF00796B), background = Color(0x2200796B)), it.first, it.last + 1)
            }
            fx.atomOffsets.forEach { addStyle(SpanStyle(background = Color(0x33FF9800), color = Color(0xFFE65100)), it, it + 1) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("C1 Compose 1.12 fixture editor", fontWeight = FontWeight.Bold)
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.Gray).padding(8.dp),
            outputTransformation = output,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
        )
        Row {
            Button(onClick = {
                val a = state.text.indexOf("Text before image.") + 5
                val b = state.text.indexOf("Text after image.") + 5
                state.edit { selection = TextRange(a, b) }
                val atoms = fx.atomOffsets.count { it in a until b }
                status = "B0.2 programmatic selection [$a,$b] len=${b - a} atomsInRange=$atoms"
            }) { Text("B0.2 select") }
            Button(onClick = {
                val s = state.selection
                val copied = state.text.subSequence(s.min, s.max).toString().replace(ATOM, '#')
                status = "copy(programmatic, not system clipboard)='${copied.replace("\n", "\\n")}'"
            }) { Text("Copy sel") }
        }
        Row {
            Button(onClick = {
                val s = state.selection
                val removed = state.text.subSequence(s.min, s.max).toString()
                state.edit { replace(s.min, s.max, "") }
                status = "cut(programmatic) removedLen=${removed.length} atoms=${removed.count { it == ATOM }}"
            }) { Text("Cut sel") }
            Button(onClick = {
                val s = state.selection
                state.edit { addStyle(SpanStyle(fontWeight = FontWeight.Bold), s.min, s.max) }
                status = "bold applied to [${s.min},${s.max}]"
            }) { Text("Bold sel") }
        }
        Button(onClick = {
            val s = state.selection
            status = "len=${state.text.length} sel=[${s.min},${s.max}] atoms=${fx.atomOffsets}"
        }) { Text("Report") }
    }
}
