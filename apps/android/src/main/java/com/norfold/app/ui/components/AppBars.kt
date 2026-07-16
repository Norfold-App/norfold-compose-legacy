package com.norfold.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.branding.NorfoldLogo
import com.norfold.app.branding.palette
import com.norfold.app.domain.Destination
import com.norfold.app.domain.ThemeMode
import com.norfold.app.ui.DocsUiState
import com.norfold.app.ui.DocsViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileTopBar(state: DocsUiState, viewModel: DocsViewModel) {
    CenterAlignedTopAppBar(
        title = {
            Text(destinationTitle(state.destination), fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        },
        navigationIcon = { IconButton(onClick = viewModel::toggleSidebar) { Icon(Icons.Outlined.Menu, null) } },
        actions = { IconButton(onClick = { viewModel.go(Destination.Search) }) { Icon(Icons.Outlined.Search, null) } },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)),
    )
}

private fun destinationTitle(d: Destination): String = when (d) {
    Destination.WorkspaceHub -> "Home"
    Destination.Inbox -> "Inbox"
    Destination.Calendar -> "Calendar"
    Destination.Goals -> "Goals"
    Destination.Files -> "Files"
    Destination.Database -> "Database"
    Destination.Graph -> "Graph"
    Destination.ObjectDetail -> "Object"
    Destination.Activity -> "Activity"
    Destination.Templates -> "Templates"
    Destination.CommandPalette -> "Command"
    Destination.NotesHome -> "Docs"
    Destination.NoteEditor -> "Editor"
    Destination.Notebooks -> "Notebooks"
    Destination.Tags -> "Tags"
    Destination.Search -> "Search"
    Destination.Tasks -> "Tasks"
    Destination.Chat -> "Chat"
    Destination.ConflictReview -> "Conflicts"
    Destination.SyncMonitor -> "Sync"
    Destination.Vault -> "Vault"
    Destination.Settings -> "Settings"
    Destination.ImportExport -> "Import / Export"
}

@Composable
fun DesktopTopBar(state: DocsUiState, viewModel: DocsViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(WindowInsets.statusBars.asPaddingValues()).padding(18.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NorfoldLogo(
            size = 44.dp,
        )
        Column(Modifier.weight(1f)) {
            Text("Norfold", fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("${state.notes.size} docs · ${state.tasks.size} tasks · local vault", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        SearchField(state.searchQuery, viewModel::search, Modifier.width(320.dp))
        IconButton(onClick = { viewModel.setTheme(if (state.settings.themeMode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark) }) {
            Icon(if (state.settings.themeMode == ThemeMode.Dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null)
        }
        IconButton(onClick = viewModel::lock) { Icon(Icons.Outlined.Lock, null) }
    }
}

@Composable
fun MobileBottomBar(state: DocsUiState, viewModel: DocsViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(88.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(68.dp).align(Alignment.BottomCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
            shadowElevation = 14.dp,
            tonalElevation = 3.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavBarItem("Home", Icons.Outlined.Home, state.destination == Destination.WorkspaceHub, Modifier.weight(1f)) { viewModel.go(Destination.WorkspaceHub) }
                NavBarItem("Docs", Icons.Outlined.Description, state.destination in setOf(Destination.NotesHome, Destination.NoteEditor, Destination.Notebooks, Destination.Tags), Modifier.weight(1f)) { viewModel.go(Destination.NotesHome) }
                Spacer(Modifier.weight(1f))
                NavBarItem("Tasks", Icons.Outlined.Checklist, state.destination in setOf(Destination.Tasks, Destination.Calendar), Modifier.weight(1f)) { viewModel.go(Destination.Tasks) }
                NavBarItem("Chat", Icons.Outlined.ChatBubbleOutline, state.destination == Destination.Chat, Modifier.weight(1f)) { viewModel.go(Destination.Chat) }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-4).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(62.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                border = BorderStroke(5.dp, MaterialTheme.colorScheme.background),
                shadowElevation = 16.dp,
                onClick = {
                    when (state.destination) {
                        Destination.Tasks, Destination.Calendar -> viewModel.createTaskAndOpen()
                        else -> viewModel.createNote()
                    }
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, "Create", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
                }
            }
            Text("Create", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
