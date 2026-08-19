package com.norfold.app.ui.shapes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.norfold.app.domain.ShapeKind
import com.norfold.app.domain.VectorShapeAsset
import com.norfold.app.ui.components.NorfoldBottomSheet
import com.norfold.app.ui.components.NorfoldPopupSectionLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.ui.focusableChrome

@Composable
fun ShapeCatalogSheet(
    selectedAssetId: String?,
    selectedPrimitive: ShapeKind,
    tint: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onSelectPrimitive: (ShapeKind) -> Unit,
    onSelectAsset: (VectorShapeAsset) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ShapeCatalogItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            searching = false
            results = emptyList()
            error = null
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        error = null
        runCatching { ShapeCatalog.search(normalized) }
            .onSuccess { results = it }
            .onFailure { error = it.message ?: "Couldn't search the shape catalog." }
        searching = false
    }

    fun select(item: ShapeCatalogItem) {
        if (downloadingId != null) return
        downloadingId = item.id
        error = null
        scope.launch {
            runCatching { ShapeCatalog.download(item) }
                .onSuccess { asset -> onSelectAsset(asset); onDismiss() }
                .onFailure { error = it.message ?: "Couldn't download this shape." }
            downloadingId = null
        }
    }

    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Shape library",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            "Professional vectors from license-verified open-source collections. Selected shapes are saved into the document for offline use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            singleLine = true,
            label = { Text("Search shapes") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
        )
        error?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 590.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
        ) {
            if (query.isBlank()) {
                item("featured-label") { NorfoldPopupSectionLabel("Available offline • Norfold Core • CC0") }
                items(ShapeCatalog.featured, key = { "featured-${it.id}" }) { item ->
                    ShapeCatalogRow(item, item.id == selectedAssetId, downloadingId == item.id, tint) { select(item) }
                }
                item("basic-label") { NorfoldPopupSectionLabel("Basic diagram shapes") }
                items(ShapeKind.entries, key = { "basic-${it.name}" }) { kind ->
                    PrimitiveShapeRow(kind, selectedAssetId == null && kind == selectedPrimitive) {
                        onSelectPrimitive(kind)
                        onDismiss()
                    }
                }
            } else if (!searching && results.isEmpty() && error == null) {
                item("empty") {
                    Text(
                        "No approved shapes match “${query.trim()}”.",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(results, key = ShapeCatalogItem::id) { item ->
                    ShapeCatalogRow(item, item.id == selectedAssetId, downloadingId == item.id, tint) { select(item) }
                }
            }
        }
    }
}

@Composable
private fun ShapeCatalogRow(
    item: ShapeCatalogItem,
    selected: Boolean,
    downloading: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).focusableChrome(
            corner = ChromeGeometry.Corner.Media,
            surface = fill,
            enabled = !downloading,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(14.dp),
        color = fill,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(52.dp)) {
                Box(Modifier.fillMaxSize().padding(9.dp), contentAlignment = Alignment.Center) {
                    if (item.isOffline) {
                        Icon(Icons.Outlined.Category, item.title, tint = tint, modifier = Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = item.svgUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(tint),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.collection} • ${item.spdxLicense} • ${if (item.isOffline) "Offline" else "Download"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                downloading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                selected -> Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PrimitiveShapeRow(kind: ShapeKind, selected: Boolean, onClick: () -> Unit) {
    val label = kind.name.replace("RoundedRectangle", "Rounded rectangle").replace("SpeechBubble", "Speech bubble")
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).focusableChrome(
            corner = ChromeGeometry.Corner.Media,
            surface = fill,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(14.dp),
        color = fill,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Category, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
