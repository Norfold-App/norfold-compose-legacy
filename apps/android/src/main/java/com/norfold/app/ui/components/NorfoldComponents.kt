package com.norfold.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NorfoldPageHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Surface(Modifier.align(Alignment.CenterStart), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun NorfoldCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun NorfoldMetric(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    NorfoldCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Surface(shape = RoundedCornerShape(9.dp), color = tint.copy(alpha = 0.12f)) {
                Icon(icon, null, Modifier.padding(7.dp), tint = tint)
            }
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NorfoldSegmentedControl(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { option ->
                val active = option == selected
                Surface(
                    Modifier.clickable { onSelect(option) },
                    shape = RoundedCornerShape(7.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                ) {
                    Text(option, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun NorfoldSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(placeholder, fontSize = 12.sp) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        shape = RoundedCornerShape(10.dp),
    )
}

@Composable
fun NorfoldStatusPill(label: String, color: Color) {
    Text(
        label,
        Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}
