package com.norfold.app.ui.editor.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.norfold.app.editor.guide.DiscoveryState
import com.norfold.app.editor.guide.GuideBrowserState
import com.norfold.app.editor.guide.GuideContext
import com.norfold.app.editor.guide.GuideDomain
import com.norfold.app.editor.guide.GuideRuntime
import com.norfold.app.editor.guide.GuideShowMeResult
import com.norfold.app.editor.guide.GuideTipState
import com.norfold.app.editor.guide.GuideTopic
import com.norfold.app.editor.guide.GuideTopicId
import com.norfold.app.editor.presentation.PresentationMode

/** Compose invalidation adapter only; all durable and presentation-independent state stays in GuideRuntime. */
@Stable
class GuideUiController internal constructor(val runtime: GuideRuntime?) {
    var revision by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun updateContext(context: GuideContext) { runtime?.updateContext(context); invalidate() }
    fun open(topicId: GuideTopicId? = null) { if (runtime?.openBrowser(topicId) == true) invalidate() }
    fun close() { runtime?.closeBrowser(); invalidate() }
    fun query(value: String) { runtime?.setQuery(value); invalidate() }
    fun category(value: GuideDomain?) { runtime?.setCategory(value); invalidate() }
    fun topic(value: GuideTopicId) { runtime?.selectTopic(value); invalidate() }
    fun next() { runtime?.nextStep(); invalidate() }
    fun previous() { runtime?.previousStep(); invalidate() }
    fun later() { runtime?.later(); invalidate() }
    fun dismiss() { runtime?.snooze(); invalidate() }
    fun complete(topicId: GuideTopicId? = null) { runtime?.complete(topicId); invalidate() }
    fun reset() { runtime?.resetDiscovery(); message = "Guide discovery was reset on this device."; invalidate() }
    fun back() { runtime?.back(); invalidate() }
    fun showMe(topicId: GuideTopicId? = null) {
        message = when (val result = runtime?.showMe(topicId)) {
            is GuideShowMeResult.Explained -> result.text
            is GuideShowMeResult.Unavailable -> result.reason
            is GuideShowMeResult.Invoked -> when (val command = result.result) {
                is com.norfold.app.editor.command.CommandResult.Failure -> command.reason
                is com.norfold.app.editor.command.CommandResult.Success -> command.message ?: "Opened from the same editor command."
                com.norfold.app.editor.command.CommandResult.NoOp -> "That action is already complete."
            }
            null -> null
        }
        invalidate()
    }
    fun clearMessage() { message = null; invalidate() }
    fun signal(signal: com.norfold.app.editor.guide.GuideSignal) { runtime?.onSignal(signal); invalidate() }

    private fun invalidate() { revision++ }
}

@Composable
fun rememberGuideUiController(runtime: GuideRuntime?): GuideUiController = remember(runtime) { GuideUiController(runtime) }

@Composable
fun GuideEntryButton(
    contextLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Open Guide for $contextLabel"
            role = Role.Button
        },
    ) {
        Text("?", style = MaterialTheme.typography.titleLarge)
    }
}

/** Host overlay: one lightweight contextual card plus an adaptive, lazy manual browser. */
@Composable
fun GuideHostLayer(
    controller: GuideUiController,
    context: GuideContext,
    modifier: Modifier = Modifier,
) {
    val runtime = controller.runtime ?: return
    controller.revision
    LaunchedEffect(context) { controller.updateContext(context) }

    val browser = runtime.browserState()
    val tip = runtime.tipState()
    BackHandler(enabled = browser.open || tip != null) { controller.back() }

    Box(modifier.fillMaxSize()) {
        tip?.let { state ->
            GuideTipCard(
                runtime = runtime,
                state = state,
                onLater = controller::later,
                onDismiss = controller::dismiss,
                onComplete = { controller.complete(state.topicId) },
                onShowMe = { controller.showMe(state.topicId) },
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 72.dp),
            )
        }
    }

    if (browser.open) {
        GuideBrowserDialog(controller, runtime, browser)
    }
}

@Composable
private fun GuideTipCard(
    runtime: GuideRuntime,
    state: GuideTipState,
    onLater: () -> Unit,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onShowMe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topic = runtime.catalog.topic(state.topicId) ?: return
    val step = topic.steps.getOrNull(state.stepIndex)
    Surface(modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Column(
            Modifier.padding(16.dp).semantics {
                contentDescription = buildString {
                    append("Guide tip. ${topic.title}. ${step?.accessibilityDescription ?: topic.accessibilityDescription}")
                    if (topic.steps.isNotEmpty()) append(" Step ${state.stepIndex + 1} of ${topic.steps.size}.")
                }
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(topic.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(step?.instruction ?: topic.explanation, style = MaterialTheme.typography.bodyMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val canShowTarget = topic.target != null || step?.target != null
                if (maxWidth < 400.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onLater, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Later") }
                            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Dismiss tip") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (canShowTarget) {
                                TextButton(onClick = onShowMe, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Show me") }
                            }
                            Button(onClick = onComplete, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Got it") }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        TextButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("Later") }
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss tip") }
                        if (canShowTarget) {
                            TextButton(onClick = onShowMe, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show me") }
                        }
                        Button(onClick = onComplete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Got it") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideBrowserDialog(
    controller: GuideUiController,
    runtime: GuideRuntime,
    browser: GuideBrowserState,
) {
    val mode = runtime.presentationMode() ?: PresentationMode.PHONE_SHEET
    val shell = when (mode) {
        PresentationMode.PHONE_POPOVER, PresentationMode.PHONE_SHEET -> Modifier.fillMaxSize()
        PresentationMode.TABLET_PANE -> Modifier.fillMaxHeight(.92f).widthIn(max = 680.dp)
        PresentationMode.EXPANDED_THREE_PANEL -> Modifier.fillMaxHeight().widthIn(max = 760.dp)
    }
    Dialog(
        onDismissRequest = controller::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(shell, shape = if (mode == PresentationMode.PHONE_SHEET) RectangleShape else MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guide", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text("Offline help for ${runtime.context()?.host?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "this editor"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = controller::close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
                OutlinedTextField(
                    value = browser.query,
                    onValueChange = controller::query,
                    label = { Text("Search Guide") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = browser.category == null,
                            onClick = { controller.category(null) },
                            label = { Text("All") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    items(GuideDomain.entries, key = { it.name }) { domain ->
                        if (runtime.availableTopics().any { it.domain == domain }) {
                            FilterChip(
                                selected = browser.category == domain,
                                onClick = { controller.category(domain) },
                                label = { Text(domain.label()) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                controller.message?.let { message ->
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f))
                            TextButton(onClick = controller::clearMessage) { Text("Dismiss") }
                        }
                    }
                }
                val selected = browser.topicId?.let(runtime.catalog::topic)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (selected == null) GuideTopicList(controller, runtime)
                    else GuideTopicDetail(controller, runtime, selected, browser.stepIndex)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = controller::reset, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset discovery") }
                }
            }
        }
    }
}

@Composable
private fun GuideTopicList(controller: GuideUiController, runtime: GuideRuntime) {
    val results = runtime.searchResults()
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(results, key = { it.id.value }) { topic ->
            val state = runtime.state(topic.id) ?: DiscoveryState.UNSEEN
            Button(
                onClick = { controller.topic(topic.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics {
                    contentDescription = "${topic.title}. ${topic.explanation}. Discovery state ${state.label()}."
                    stateDescription = state.label()
                },
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(topic.title, style = MaterialTheme.typography.titleSmall)
                    Text(topic.explanation, style = MaterialTheme.typography.bodySmall)
                    Text(state.label(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (results.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No Guide topics match", style = MaterialTheme.typography.titleMedium)
                    Text("Try another search or category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GuideTopicDetail(controller: GuideUiController, runtime: GuideRuntime, topic: GuideTopic, stepIndex: Int) {
    val step = topic.steps.getOrNull(stepIndex)
    val count = topic.steps.size.coerceAtLeast(1)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { controller.category(runtime.browserState().category) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to topics") }
        Text(topic.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(topic.explanation)
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Step ${stepIndex + 1} of $count", style = MaterialTheme.typography.labelLarge)
                Text(step?.title ?: topic.title, style = MaterialTheme.typography.titleMedium)
                Text(step?.instruction ?: topic.explanation)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = controller::previous, enabled = stepIndex > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
            TextButton(onClick = controller::next, enabled = stepIndex < count - 1, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
            Spacer(Modifier.weight(1f))
            if (topic.target != null || step?.target != null) {
                TextButton(onClick = { controller.showMe(topic.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show me") }
            }
            Button(onClick = { controller.complete(topic.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Mark learned") }
        }
    }
}

private fun GuideDomain.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)
private fun DiscoveryState.label(): String = when (this) {
    DiscoveryState.UNSEEN -> "Not started"
    DiscoveryState.ELIGIBLE -> "Ready to discover"
    DiscoveryState.SHOWN -> "Shown"
    DiscoveryState.LEARNED -> "Learned"
    DiscoveryState.LATER -> "Saved for later"
    DiscoveryState.SNOOZED -> "Dismissed"
    DiscoveryState.AUTO_DISABLED -> "Unavailable"
}
