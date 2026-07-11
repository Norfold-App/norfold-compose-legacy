package com.norfold.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.branding.NorfoldLogo

@Composable
fun LockScreen(
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    onUnlock: (String) -> Unit,
    onBiometricUnlock: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var triedAutomaticBiometric by remember { mutableStateOf(false) }
    LaunchedEffect(biometricEnabled, biometricAvailable) {
        if (biometricEnabled && biometricAvailable && !triedAutomaticBiometric) {
            triedAutomaticBiometric = true
            onBiometricUnlock()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NorfoldLogo(86.dp)
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Text("Vault locked", fontWeight = FontWeight.Black, fontSize = 26.sp)
                if (biometricEnabled) {
                    if (biometricAvailable) {
                        ElevatedButton(onClick = onBiometricUnlock, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Fingerprint, null)
                            Text("Unlock with biometric")
                        }
                    } else {
                        Text("Biometric unlock is enabled but unavailable on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("PIN or password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onUnlock(secret) }, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
            }
        }
    }
}
