package com.norfold.app.qa

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.norfold.app.data.DiagnosticsStore
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.ThemeMode
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.ui.NorfoldTheme

/**
 * Debug-build-only launcher for one controlled Crashlytics verification crash.
 *
 * This activity is declared only in src/debug/AndroidManifest.xml. It deliberately requires two taps,
 * re-reads the production diagnostics preference immediately before throwing, and offers no shortcut
 * for changing that preference. A test operator must opt in through the real Settings screen first.
 */
class CrashlyticsTestActivity : FragmentActivity() {
    private var consentEnabled by mutableStateOf(false)
    private var armed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshConsent()
        render()
    }

    override fun onResume() {
        super.onResume()
        refreshConsent()
    }

    private fun refreshConsent() {
        consentEnabled = DiagnosticsStore(applicationContext).state().enabled
        if (!consentEnabled) armed = false
    }

    private fun render() {
        setContent {
            NorfoldTheme(
                settings = AppSettings(
                    themeMode = ThemeMode.System,
                    themeProfile = ThemeProfile.Norfold,
                    accentColor = 0xFF3D7C45,
                    backupFolderUri = null,
                    vaultLockEnabled = false,
                    vaultSecretHash = null,
                    onboardingComplete = true,
                ),
            ) {
                CrashlyticsTestScreen(
                    consentEnabled = consentEnabled,
                    armed = armed,
                    onArm = {
                        refreshConsent()
                        if (consentEnabled) armed = true
                    },
                    onCrash = {
                        refreshConsent()
                        if (consentEnabled && armed) {
                            throw RuntimeException(ControlledCrashMessage)
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val ControlledCrashMessage = "Norfold controlled Crashlytics verification"
    }
}

@Composable
private fun CrashlyticsTestScreen(
    consentEnabled: Boolean,
    armed: Boolean,
    onArm: () -> Unit,
    onCrash: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Controlled Crashlytics test",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Debug build only. This deliberately terminates Norfold and sends one report after restart.")
            Text(
                text = if (consentEnabled) {
                    "Diagnostics and crash reporting: enabled"
                } else {
                    "Diagnostics and crash reporting: disabled. Enable it in Norfold Settings first."
                },
                color = if (consentEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("crashlytics-consent-state"),
            )
            OutlinedButton(
                onClick = onArm,
                enabled = consentEnabled && !armed,
                modifier = Modifier.fillMaxWidth().testTag("crashlytics-arm"),
            ) {
                Text(if (armed) "Test crash armed" else "Arm test crash")
            }
            Button(
                onClick = onCrash,
                enabled = consentEnabled && armed,
                modifier = Modifier.fillMaxWidth().testTag("crashlytics-crash-now"),
            ) {
                Text("Crash now")
            }
        }
    }
}
