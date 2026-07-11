package com.norfold.app.ui.components

import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.norfold.app.R
import com.norfold.app.branding.BrandPalette
import com.norfold.app.branding.AnimatedNorfoldLogo
import com.norfold.app.branding.NorfoldLogo
import com.norfold.app.branding.palette
import com.norfold.app.domain.ThemeProfile

@Composable
fun ComposeLoadingScreen(palette: BrandPalette = ThemeProfile.Neon.palette()) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColors = if (dark) listOf(Color(0xFF070A12), Color(0xFF130D2A)) else listOf(Color(0xFFF7F8FC), Color(0xFFEEE8FF))
    val titleColor = if (dark) Color.White else Color(0xFF171725)
    val pulse = rememberInfiniteTransition(label = "loadingPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingTextAlpha",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(bgColors)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedNorfoldLogo(size = 132.dp, palette = palette, dark = dark)
            Text("Norfold", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = titleColor)
            Text("Your own private workspace.", fontSize = 13.sp, color = titleColor.copy(alpha = 0.68f))
            Text("Opening workspace", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = pulse.value))
        }
    }
}

@Composable
fun AnimatedFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.primary) {
        Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, onValueChange, modifier = modifier, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, label = { Text("Search everything") })
}

@Composable
fun NavBarItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun EmptyNotes(onCreate: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NorfoldLogo(72.dp)
            Text("No notes yet", fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.padding(top = 12.dp))
            Text("Capture ideas before they fade away.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onCreate, modifier = Modifier.padding(top = 12.dp)) { Text("Create note") }
        }
    }
}

@Composable
fun EmptyEditor(onCreate: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(100.dp))
        NorfoldLogo(96.dp)
        Text("Choose or create a note", fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onCreate, modifier = Modifier.padding(top = 12.dp)) { Text("New note") }
    }
}
