package eu.kanade.tachiyomi.ui.reader

import androidx.activity.ComponentActivity

/** Debug-only component host: reader tests must not depend on launcher splash/library startup. */
class ReaderUiTestActivity : ComponentActivity()
