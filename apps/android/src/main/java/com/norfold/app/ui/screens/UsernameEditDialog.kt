package com.norfold.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.ui.DocsViewModel
import com.norfold.app.ui.components.NorfoldDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HandleRegex = Regex("^[a-z][a-z0-9_]{2,29}$")

@Composable
fun UsernameEditDialog(
    current: String,
    viewModel: DocsViewModel,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    val normalized = value.trim().removePrefix("@").lowercase()
    val validFormat = normalized.matches(HandleRegex)

    LaunchedEffect(value) {
        available = null
        suggestions = emptyList()
        debounceJob?.cancel()
        if (!validFormat) return@LaunchedEffect
        debounceJob = scope.launch {
            delay(500)
            checking = true
            val free = viewModel.isUsernameAvailable(normalized)
            checking = false
            available = free
            if (!free) suggestions = viewModel.usernameSuggestions(normalized)
        }
    }

    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text("Username", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("@handle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        when {
                            checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            available == true -> Icon(Icons.Outlined.Check, "Available", tint = MaterialTheme.colorScheme.primary)
                            available == false -> Icon(Icons.Outlined.Close, "Taken", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    supportingText = when {
                        value.isNotBlank() && !validFormat -> {{ Text("3–30 chars, start with a letter, letters/numbers/_ only", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }}
                        available == false -> {{ Text("@$normalized is taken", color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }}
                        available == true -> {{ Text("@$normalized is available", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp) }}
                        else -> null
                    },
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Try one of these:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        suggestions.take(3).forEach { s ->
                            SuggestionChip(onClick = { value = s }, label = { Text("@$s", fontSize = 12.sp) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saving = true
                    onSave(normalized)
                    onDismiss()
                },
                enabled = validFormat && available != false && !checking && !saving,
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
