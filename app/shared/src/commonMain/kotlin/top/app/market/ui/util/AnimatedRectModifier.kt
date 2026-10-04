package top.app.market.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * 按 [rect] 定位并定尺，在布局阶段求值，逐帧动画不触发重组。
 * 必须放在裁剪类修饰符之前，否则裁剪区停在原点而内容被移到动画位置，画面会被裁没。
 */
internal fun Modifier.animatedRect(rect: () -> Rect): Modifier = layout { measurable, _ ->
    val target = rect()
    val width = target.width.roundToInt().coerceAtLeast(0)
    val height = target.height.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) {
        placeable.place(target.left.roundToInt(), target.top.roundToInt())
    }
}
