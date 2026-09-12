package com.norfold.flow.c1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp

/**
 * C1 toolchain proof only. Exercises the modern Compose text-input state
 * (`TextFieldState` / `rememberTextFieldState`) plus a rich `SpanStyle` so the
 * experiment can prove the Compose 1.12 rich-editing APIs actually resolve and
 * compile. No editor behaviour is implemented here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state = rememberTextFieldState("Flow C1 toolchain proof")
                var mark by remember { mutableStateOf(SpanStyle()) }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    BasicTextField(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("length=${state.text.length}")
                    Text("spanStyleResolved=${mark.fontSize == null}")
                }
            }
        }
    }
}
