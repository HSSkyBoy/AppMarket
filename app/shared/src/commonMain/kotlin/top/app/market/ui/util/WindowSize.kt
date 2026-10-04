package top.app.market.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

private val WideScreenMinWidth = 600.dp

@Composable
fun rememberIsWideScreen(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { containerSize.width.toDp() } >= WideScreenMinWidth
}
