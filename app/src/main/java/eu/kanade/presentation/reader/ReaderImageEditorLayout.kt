package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Keep the image viewport usable when rotated or when controls need more text space. */
@Composable
internal fun ReaderImageEditorLayout(
    toolbar: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    imageContent: @Composable (Modifier) -> Unit,
    footer: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("reader_image_editor_layout").padding(16.dp)) {
        val wide = maxWidth > maxHeight
        val panelWidth = minOf(300.dp, maxWidth * 0.45f)
        val controlsHeight = maxHeight * 0.34f
        val footerHeight = maxHeight * 0.18f
        val controlScroll = rememberScrollState()
        val footerScroll = rememberScrollState()
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            toolbar()
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(panelWidth).fillMaxHeight().verticalScroll(controlScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        controls()
                        footer()
                    }
                    imageContent(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).verticalScroll(controlScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { controls() }
                imageContent(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().heightIn(max = footerHeight).verticalScroll(footerScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) { footer() }
            }
        }
    }
}
