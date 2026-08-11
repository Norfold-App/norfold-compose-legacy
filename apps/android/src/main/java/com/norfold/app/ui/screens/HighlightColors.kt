package com.norfold.app.ui.screens

import androidx.compose.ui.graphics.Color

/**
 * Resolves a stored highlight token to the colour it renders as.
 *
 * Highlights are persisted as a token, not a colour: `"yellow"`, `"green"`, `"blue"`, `"pink"`,
 * `"orange"`, or a raw `#RRGGBB` string a person picked. Every editor host reads the same document
 * model, so all of them must resolve a token identically — a note that changed colour on the way into
 * a different host would be the document lying about itself.
 *
 * An unparseable token falls back to yellow rather than throwing. A highlight is decoration; a
 * corrupt or future token is not a reason to fail rendering the text underneath it.
 */
internal fun highlightColor(token: String?): Color = when (token) {
    null, "yellow" -> Color(0xFFFFF176)
    "green" -> Color(0xFFAED581)
    "blue" -> Color(0xFF81D4FA)
    "pink" -> Color(0xFFF48FB1)
    "orange" -> Color(0xFFFFB74D)
    else -> runCatching { Color(android.graphics.Color.parseColor(token)) }.getOrDefault(Color(0xFFFFF176))
}.copy(alpha = .55f)
