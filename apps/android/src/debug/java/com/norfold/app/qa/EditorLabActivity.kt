package com.norfold.app.qa

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.editor.lab.EditorLabFlag
import com.norfold.app.ui.NorfoldTheme
import com.norfold.app.ui.editor.lab.EditorLab

/**
 * Debug-variant-only switchboard for the Editor Lab.
 *
 * Lists every [EditorLabFlag] with a switch that writes straight through [EditorLab] to `SharedPreferences`, so
 * a developer can turn a candidate treatment on, back out to the real editor, and compare it against the shipped
 * behaviour on a device. It has no route from the product surface and is launched directly:
 *
 * ```
 * adb shell am start -n com.norfold.app.debug/com.norfold.app.qa.EditorLabActivity
 * ```
 *
 * Because the switches persist per device and default off, a release build — which never launches this activity
 * — always resolves every experiment to off.
 */
class EditorLabActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EditorLab.ensureHydrated(applicationContext)
        setContent {
            NorfoldTheme(
                settings = AppSettings(
                    themeMode = ThemeMode.System,
                    themeProfile = ThemeProfile.Norfold,
                    accentColor = 0xFF2A2A2A,
                    backupFolderUri = null,
                    vaultLockEnabled = false,
                    vaultSecretHash = null,
                    onboardingComplete = true,
                ),
            ) {
                EditorLabScreen()
            }
        }
    }
}

@Composable
private fun EditorLabScreen() {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 40.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Editor Lab",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Debug-only. Every experiment ships off; toggling one here changes only this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { EditorLab.reset(context) },
                        modifier = Modifier.testTag("editor_lab_reset"),
                    ) {
                        Text("Reset all to shipped defaults")
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
            items(EditorLabFlag.entries, key = EditorLabFlag::name) { flag ->
                EditorLabRow(
                    flag = flag,
                    enabled = EditorLab.isEnabled(flag),
                    onToggle = { checked -> EditorLab.setEnabled(context, flag, checked) },
                )
            }
        }
    }
}

@Composable
private fun EditorLabRow(
    flag: EditorLabFlag,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(flag.title, fontWeight = FontWeight.SemiBold)
            Text(
                flag.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("editor_lab_switch_${flag.name.lowercase()}"),
        )
    }
}
