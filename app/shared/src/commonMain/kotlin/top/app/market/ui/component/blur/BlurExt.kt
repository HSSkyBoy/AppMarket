package top.app.market.ui.component.blur

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.app.market.ui.theme.LocalEnableBlur
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun Modifier.defaultBlurEffect(
    backdrop: LayerBackdrop,
): Modifier = this.textureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = 25f,
    colors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
        ),
    ),
)

@Composable
fun Modifier.progressiveBarBlur(
    backdrop: LayerBackdrop,
): Modifier = this.progressiveTextureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = 10f,
    gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
    colors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.3f)),
        ),
    ),
)

@Composable
fun Modifier.progressiveBottomBarBlur(
    backdrop: LayerBackdrop,
): Modifier = this.progressiveTextureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = 10f,
    gradient = ProgressiveBlur.Bottom.copy(curve = 2.2f),
    colors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.3f)),
        ),
    ),
)

@Composable
fun rememberBlurEnabled(): State<Boolean> {
    val enabled = LocalEnableBlur.current
    return remember(enabled) { mutableStateOf(enabled && isRuntimeShaderSupported()) }
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!rememberBlurEnabled().value || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = rememberBlurEnabled().value,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.defaultBlurEffect(backdrop)
        } else {
            Modifier
        },
    ) {
        content()
    }
}
