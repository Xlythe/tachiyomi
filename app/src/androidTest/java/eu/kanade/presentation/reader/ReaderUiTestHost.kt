package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Match the real reader's measured parent window instead of a zero-size dialog-only root. */
@Composable
internal fun ReaderUiTestHost(content: @Composable () -> Unit) {
    MaterialTheme { Box(Modifier.fillMaxSize()) { content() } }
}
