package tachiyomi.presentation.core.components.material

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * @param refreshing Whether the layout is currently refreshing
 * @param onRefresh Lambda which is invoked when a swipe to refresh gesture is completed.
 * @param enabled Whether the the layout should react to swipe gestures or not.
 * @param indicatorPadding Content padding for the indicator, to inset the indicator in if required.
 * @param content The content containing a vertically scrollable composable.
 */
@Composable
fun PullRefresh(
    refreshing: Boolean,
    enabled: () -> Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        val contentPadding = remember(indicatorPadding) {
            object : PaddingValues {
                override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
                    indicatorPadding.calculateLeftPadding(layoutDirection)

                override fun calculateTopPadding(): Dp = 0.dp

                override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
                    indicatorPadding.calculateRightPadding(layoutDirection)

                override fun calculateBottomPadding(): Dp =
                    indicatorPadding.calculateBottomPadding()
            }
        }
        PullToRefreshBox(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(contentPadding),
            isRefreshing = refreshing,
            onRefresh = onRefresh) {
            content()
        }
    }
}
