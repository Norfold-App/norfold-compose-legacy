package com.norfold.app.ui.fonts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.components.NorfoldBottomSheet
import com.norfold.app.ui.components.NorfoldPopupSectionLabel

private val BuiltInFontFamilies = listOf("System", "Sans Serif", "Serif", "Monospace", "Cursive")

/**
 * Shared app/editor font browser. The catalog is metadata-only; font files are fetched lazily by
 * Android's verified Google Play services provider when a visible preview or authored span needs
 * them. Hiding a family therefore applies consistently to search, A-Z, recent, and most-used lists.
 */
@Composable
fun FontCatalogPickerSheet(
    store: FontCatalogStore,
    selectedFamily: String?,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var forceNetworkRefresh by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<FontCatalogSnapshot?>(null) }
    LaunchedEffect(reloadToken) {
        snapshot = store.snapshot(refresh = forceNetworkRefresh)
        forceNetworkRefresh = false
    }
    val data = snapshot
    val normalizedQuery = query.trim()
    val builtIns = remember(normalizedQuery) {
        BuiltInFontFamilies.filter { it.contains(normalizedQuery, ignoreCase = true) }
    }
    val visible = remember(data, normalizedQuery) {
        val hidden = data?.hidden.orEmpty()
        data?.all.orEmpty().filter {
            it.family !in hidden && it.family.contains(normalizedQuery, ignoreCase = true)
        }
    }

    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text(
            "1,900+ open-source families are listed from Google Fonts. Files load only when needed through Android's verified font provider.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            singleLine = true,
            label = { Text("Search fonts") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                IconButton(onClick = { forceNetworkRefresh = true; reloadToken++ }) {
                    Icon(Icons.Outlined.Refresh, "Refresh catalog")
                }
            },
        )
        if (data == null) {
            Text("Loading catalog…", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
            ) {
                if (builtIns.isNotEmpty()) {
                    item("built-in-label") { NorfoldPopupSectionLabel("Device fonts") }
                    items(builtIns, key = { "built-in-$it" }) { family ->
                        FontCatalogRow(family, selectedFamily.equals(family, ignoreCase = true), onSelect, onHide = null)
                    }
                }
                if (normalizedQuery.isEmpty() && data.recent.isNotEmpty()) {
                    item("recent-label") { NorfoldPopupSectionLabel("Recently used") }
                    items(data.recent, key = { "recent-$it" }) { family ->
                        FontCatalogRow(family, selectedFamily == family, onSelect, onHide = { store.hide(family); reloadToken++ })
                    }
                }
                if (normalizedQuery.isEmpty() && data.mostUsed.isNotEmpty()) {
                    item("used-label") { NorfoldPopupSectionLabel("Most used") }
                    items(data.mostUsed, key = { "used-$it" }) { family ->
                        FontCatalogRow(family, selectedFamily == family, onSelect, onHide = { store.hide(family); reloadToken++ })
                    }
                } else if (normalizedQuery.isEmpty() && !data.rankingAvailable) {
                    item("used-wait") {
                        Text(
                            "Most used unlocks after your first week of editing, then ranks authored characters and explicit font choices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                }
                item("all-label") {
                    NorfoldPopupSectionLabel(if (normalizedQuery.isEmpty()) "Open-source fonts · A–Z" else "Search results")
                }
                items(visible, key = { "all-${it.family}" }) { entry ->
                    FontCatalogRow(entry.family, selectedFamily == entry.family, onSelect, onHide = { store.hide(entry.family); reloadToken++ })
                }
                if (visible.isEmpty() && builtIns.isEmpty()) item("empty") {
                    Text("No visible fonts match this search.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FontCatalogRow(
    family: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onHide: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(family) }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                family,
                fontFamily = NorfoldFontFamilies.resolve(family),
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) Text("Selected", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        }
        onHide?.let { hide -> TextButton(onClick = hide) { Text("Hide") } }
    }
}
