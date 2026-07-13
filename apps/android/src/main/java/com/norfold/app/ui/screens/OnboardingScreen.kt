package com.norfold.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.ThemeMode
import com.norfold.app.ui.NotesUiState
import com.norfold.app.ui.NotesViewModel
import coil.compose.AsyncImage

private const val OnboardingPreferences = "norfold_onboarding_draft"

@Composable
fun NorfoldOnboardingScreen(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onRestoreBackup: (String) -> Unit = {},
    onGoogleSignIn: () -> Unit = {},
    onEmailSignIn: (String, String, Boolean) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(OnboardingPreferences, 0) }
    var step by rememberSaveable { mutableStateOf(preferences.getInt("step", 0).coerceIn(0, 9)) }
    var fullName by rememberSaveable { mutableStateOf(preferences.getString("full_name", "") ?: "") }
    var displayName by rememberSaveable { mutableStateOf(preferences.getString("display_name", "") ?: "") }
    var workspaceName by rememberSaveable { mutableStateOf(preferences.getString("workspace", "My Workspace") ?: "My Workspace") }
    var workspaceTemplate by rememberSaveable { mutableStateOf(preferences.getString("template", "Start empty") ?: "Start empty") }
    var collaborationMode by rememberSaveable { mutableStateOf(preferences.getString("collaboration", "Just me for now") ?: "Just me for now") }
    var avatarUri by rememberSaveable { mutableStateOf(preferences.getString("avatar_uri", "") ?: "") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var backupPassword by rememberSaveable { mutableStateOf("") }
    var signUp by rememberSaveable { mutableStateOf(false) }
    var emailUpdates by rememberSaveable { mutableStateOf(false) }
    var reminders by rememberSaveable { mutableStateOf(true) }
    var productTips by rememberSaveable { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            avatarUri = uri.toString()
        }
    }

    LaunchedEffect(step, fullName, displayName, workspaceName, workspaceTemplate, collaborationMode) {
        preferences.edit()
            .putInt("step", step)
            .putString("full_name", fullName)
            .putString("display_name", displayName)
            .putString("workspace", workspaceName)
            .putString("template", workspaceTemplate)
            .putString("collaboration", collaborationMode)
            .putString("avatar_uri", avatarUri)
            .apply()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) IconButton(onClick = { step-- }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    else Spacer(Modifier.size(48.dp))
                    Spacer(Modifier.weight(1f))
                    if (step in 1..3) TextButton(onClick = { step = 5 }) { Text("Skip") }
                }
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(180)))
                        }
                    },
                    label = "onboarding-step",
                ) { visibleStep ->
                // AnimatedContent lays its children out in a Box (top-start stacking); each step
                // function emits several sibling composables, so without this Column they overlap.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                when (visibleStep) {
                    0 -> WelcomeStep(onNext = { step = 1 }, onRestore = { step = 4 }, onGoogle = onGoogleSignIn, onOffline = { step = 5 })
                    1 -> ProductStep(
                        title = "Everything in one place",
                        subtitle = "Notes, tasks, calendar, files, and chat - all connected to keep you in flow.",
                        preview = { WorkspacePreview() },
                        footerTitle = "Your data, your control",
                        footer = "Everything is private and secure. You're always in control.",
                    )
                    2 -> ProductStep(
                        title = "Work your way",
                        subtitle = "Customize views, organize your workspace, and build habits that stick.",
                        preview = { WorkflowPreview() },
                        footerTitle = "Made for deep work",
                        footer = "Distraction-free, offline-ready, and built to help you focus.",
                    )
                    3 -> SignInStep(email, { email = it }, password, { password = it }, signUp, { signUp = it }, onGoogleSignIn, onEmailSignIn) { step = 5 }
                    4 -> RestoreStep(backupPassword, { backupPassword = it }) { onRestoreBackup(backupPassword) }
                    5 -> ProfileStep(fullName, { fullName = it }, displayName, { displayName = it }, avatarUri) {
                        avatarPicker.launch(arrayOf("image/*"))
                    }
                    6 -> WorkspaceStep(workspaceName, { workspaceName = it }, workspaceTemplate) { workspaceTemplate = it }
                    7 -> CollaborationStep(collaborationMode) { collaborationMode = it }
                    8 -> NotificationStep(emailUpdates, { emailUpdates = it }, reminders, { reminders = it }, productTips, { productTips = it })
                    else -> CompletionStep(workspaceName)
                }
                }
                }
            }
            if (step in 1..2 || step in 5..8) {
                OnboardingPrimaryButton(if (step == 8) "Continue" else "Next") { step++ }
            } else if (step == 9) {
                OnboardingPrimaryButton("Go to workspace") {
                    preferences.edit().clear().apply()
                    viewModel.finishOnboarding(
                        workspaceName = workspaceName,
                        purpose = collaborationMode,
                        themeMode = ThemeMode.System,
                        fullName = fullName,
                        displayName = displayName,
                        template = workspaceTemplate,
                        emailUpdates = emailUpdates,
                        reminders = reminders,
                        avatarUri = avatarUri,
                    )
                }
            }
            if (step != 4) StepDots(step)
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit, onRestore: () -> Unit, onGoogle: () -> Unit, onOffline: () -> Unit) {
    NorfoldWordmark()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Welcome to\nNorfold", fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black)
            Text("A private place for notes, tasks, calendar, chat, and focused work.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, lineHeight = 24.sp)
            Text("NOTES  |  CALENDAR  |  CHAT  |  TASKS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
    OnboardingPrimaryButton("Get started", onNext)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onRestore, Modifier.weight(1f).height(50.dp)) { Icon(Icons.Outlined.Restore, null); Text(" Restore backup") }
        OutlinedButton(onClick = onGoogle, Modifier.weight(1f).height(50.dp)) { Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black); Text("  Sign in with Google") }
    }
    Text("What you can do", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard(Icons.Outlined.Description, "Notes", "Capture ideas and keep them organized.", Modifier.weight(1f))
            FeatureCard(Icons.Outlined.TaskAlt, "Tasks", "Plan your day and get things done.", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard(Icons.Outlined.CalendarMonth, "Calendar", "Manage your schedule with ease.", Modifier.weight(1f))
            FeatureCard(Icons.Outlined.ChatBubbleOutline, "Chat", "Private conversations that stay with you.", Modifier.weight(1f))
        }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .28f)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
            Column { Text("Private by default", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Your data stays on your device unless you choose to sync.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {}) { Text("Terms") }; Text("•"); TextButton(onClick = {}) { Text("Privacy") }; Text("•"); TextButton(onClick = onOffline) { Text("Continue offline") }
    }
}

@Composable
private fun ProductStep(title: String, subtitle: String, preview: @Composable () -> Unit, footerTitle: String, footer: String) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 16.sp, lineHeight = 22.sp)
    preview()
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Column { Text(footerTitle, fontWeight = FontWeight.Bold); Text(footer, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun WorkspacePreview() {
    Surface(Modifier.fillMaxWidth().height(360.dp), shape = RoundedCornerShape(22.dp), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.widthIn(min = 92.dp).fillMaxSize().weight(.32f), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("N  Norfold", fontWeight = FontWeight.Bold)
                listOf("Home", "Notes", "Tasks", "Calendar", "Chat", "Files", "More").forEach { Text(it, color = if (it == "Home") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            }
            Column(Modifier.weight(.68f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text("Search everything", Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Notes\n12", "Tasks\n8", "Events\n5", "Messages\n3").forEach { PreviewTile(it, Modifier.weight(1f)) } }
                PreviewPanel("Today's tasks", listOf("Draft project roadmap", "Polish Android workspace"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PreviewPanel("Upcoming events", listOf("Team sync", "Design review"), Modifier.weight(1f)); PreviewPanel("Recent notes", listOf("Welcome to Norfold", "Meeting notes"), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WorkflowPreview() {
    Surface(Modifier.fillMaxWidth().height(360.dp), shape = RoundedCornerShape(22.dp), border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("Table", "Board", "Calendar", "List").forEach { Text(it, color = if (it == "Table") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) } }
            PreviewPanel("To do", listOf("Design onboarding flow", "Write launch plan", "Review feedback"))
            Text("Calendar", fontWeight = FontWeight.Bold)
            Text("July 2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("S 6", "M 7", "T 8", "W 9", "T 10", "F 11", "S 12").forEach { Text(it, fontSize = 11.sp) } }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { listOf("Table", "Board", "List", "Calendar").forEach { PreviewTile(it, Modifier.size(68.dp, 44.dp)) } }
        }
    }
}

@Composable
private fun SignInStep(email: String, onEmail: (String) -> Unit, password: String, onPassword: (String) -> Unit, signUp: Boolean, onMode: (Boolean) -> Unit, onGoogle: () -> Unit, onEmailAction: (String, String, Boolean) -> Unit, onOffline: () -> Unit) {
    Text("Let's get you in", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Sign in to access your workspace or create a new account.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(3.dp)) {
        listOf(false to "Sign in", true to "Sign up").forEach { (mode, label) -> Surface(Modifier.weight(1f).clickable { onMode(mode) }, color = if (signUp == mode) MaterialTheme.colorScheme.surface else Color.Transparent, shape = RoundedCornerShape(8.dp)) { Text(label, Modifier.padding(10.dp), textAlign = TextAlign.Center, color = if (signUp == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
    OutlinedButton(onClick = onGoogle, Modifier.fillMaxWidth().height(50.dp)) { Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black); Text("  Continue with Google") }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text("Apple sign-in will be available in a later release", Modifier.padding(15.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    HorizontalDivider()
    OutlinedTextField(email, onEmail, Modifier.fillMaxWidth(), label = { Text("Email address") }, leadingIcon = { Icon(Icons.Outlined.Email, null) }, singleLine = true)
    OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
    OnboardingPrimaryButton(if (signUp) "Create account" else "Sign in") { onEmailAction(email.trim(), password, signUp) }
    TextButton(onClick = onOffline) { Text("Continue offline") }
}

@Composable
private fun RestoreStep(secret: String, onSecret: (String) -> Unit, onRestore: () -> Unit) {
    Text("Restoring your data", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Choose an encrypted Norfold backup. This may take a few moments.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(progress = { .72f }, Modifier.fillMaxSize(), strokeWidth = 6.dp); Icon(Icons.Outlined.CloudDownload, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary) }
    Text("72%", fontWeight = FontWeight.Bold)
    OutlinedTextField(secret, onSecret, Modifier.fillMaxWidth(), label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation())
    listOf("Notes", "Tasks", "Calendar", "Files & media", "Settings").forEachIndexed { index, label -> Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Icon(if (index < 3) Icons.Outlined.CheckCircle else Icons.Outlined.Restore, null, tint = MaterialTheme.colorScheme.primary) } } }
    OnboardingPrimaryButton("Choose backup", onRestore)
}

@Composable
private fun ProfileStep(fullName: String, onFullName: (String) -> Unit, displayName: String, onDisplayName: (String) -> Unit, avatarUri: String, onPickAvatar: () -> Unit) {
    Surface(
        Modifier.size(72.dp).clickable(onClick = onPickAvatar),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
    ) {
        if (avatarUri.isNotBlank()) {
            AsyncImage(avatarUri, "Profile image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Outlined.Person, "Choose profile image", Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    Text("Tell us about you", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("This helps personalize your experience.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(fullName, onFullName, Modifier.fillMaxWidth(), label = { Text("Full name") }, singleLine = true)
    OutlinedTextField(displayName, onDisplayName, Modifier.fillMaxWidth(), label = { Text("What should we call you?") }, singleLine = true)
    OutlinedTextField("(GMT+06:00) Dhaka, Bangladesh", {}, Modifier.fillMaxWidth(), label = { Text("Timezone") }, readOnly = true)
}

@Composable
private fun WorkspaceStep(name: String, onName: (String) -> Unit, template: String, onTemplate: (String) -> Unit) {
    StepIcon(Icons.Outlined.Workspaces)
    Text("Create your workspace", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Give your workspace a name.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(name, { onName(it.take(50)) }, Modifier.fillMaxWidth(), label = { Text("Workspace name") }, supportingText = { Text("${name.length}/50") }, singleLine = true)
    OutlinedTextField("Personal", {}, Modifier.fillMaxWidth(), label = { Text("Workspace type") }, readOnly = true)
    Text("Starting point", Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
    listOf(
        "Start empty" to "A clean workspace with no demo content",
        "Norfold Guide" to "A complete interactive tour of notes, tasks, tables, charts, and rich blocks",
        "Personal organizer" to "A daily note and a weekly planning task",
        "Study planner" to "Study notes and revision tasks",
        "Team workspace" to "A shared brief, task, and collaboration chat",
    ).forEach { (label, detail) ->
        Surface(
            Modifier.fillMaxWidth().clickable { onTemplate(label) },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (template == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            color = if (template == label) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                if (template == label) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Text("You can always change this later.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CollaborationStep(selected: String, onSelect: (String) -> Unit) {
    StepIcon(Icons.Outlined.Groups)
    Text("Collaborate your way", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Invite people or skip this step.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceCard("Just me for now", "I'll invite people later", selected == "Just me for now") { onSelect("Just me for now") }
    ChoiceCard("Invite people", "Add teammates or friends", selected == "Invite people") { onSelect("Invite people") }
    ChoiceCard("Join a team", "Use an invite code to join", selected == "Join a team") { onSelect("Join a team") }
}

@Composable
private fun NotificationStep(email: Boolean, onEmail: (Boolean) -> Unit, reminders: Boolean, onReminders: (Boolean) -> Unit, tips: Boolean, onTips: (Boolean) -> Unit) {
    StepIcon(Icons.Outlined.NotificationsNone)
    Text("Stay in the loop", fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text("Choose what you want to receive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    ToggleCard("Email updates", "Important updates and activity", email, onEmail)
    ToggleCard("Task reminders", "Reminders for tasks and deadlines", reminders, onReminders)
    ToggleCard("Product tips", "Tips to help you get more done", tips, onTips)
}

@Composable
private fun CompletionStep(workspace: String) {
    Surface(Modifier.size(92.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) { Icon(Icons.Outlined.Check, null, Modifier.padding(24.dp), tint = Color.White) }
    Text("You're all set!", fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text("Welcome to Norfold. Let's get things done.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { listOf("Workspace created: $workspace", "Profile set", "You're ready to go").forEach { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF27AE60)); Text(it, Modifier.padding(start = 10.dp)) } } }
    }
}

@Composable private fun NorfoldWordmark() { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("N  Norfold", fontSize = 28.sp, fontWeight = FontWeight.Black); Text("Your private workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun StepIcon(icon: ImageVector) { Surface(Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) { Icon(icon, null, Modifier.padding(20.dp), tint = MaterialTheme.colorScheme.primary) } }
@Composable private fun FeatureCard(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) { Surface(modifier, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column { Text(title, fontWeight = FontWeight.Bold); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp) } } } }
@Composable private fun PreviewTile(text: String, modifier: Modifier = Modifier) { Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) { Text(text, Modifier.padding(9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) } }
@Composable private fun PreviewPanel(title: String, rows: List<String>, modifier: Modifier = Modifier) { Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 11.sp); rows.forEach { Text("○  $it", fontSize = 10.sp) } } } }
@Composable private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(18.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)); Column(Modifier.padding(start = 14.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } } } }
@Composable private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; Switch(checked, onChecked) } } }

@Composable
private fun OnboardingPrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().widthIn(max = 472.dp).padding(horizontal = 24.dp, vertical = 8.dp).height(54.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
        Text(label, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
    }
}

@Composable
private fun StepDots(step: Int) {
    Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(10) { index -> Box(Modifier.size(if (index == step) 9.dp else 7.dp).background(if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)) }
    }
}
