package top.app.market.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.app.market.domain.model.download.DownloadPhase
import top.app.market.domain.model.download.DownloadState
import top.app.market.resources.Res
import top.app.market.resources.download_paused
import top.app.market.resources.downloading_short
import top.app.market.resources.install
import top.app.market.resources.installing_short
import top.app.market.resources.waiting_install_confirmation
import top.app.market.ui.model.AppActionKind
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

// App-wide replacement for miuix Button/TextButton: same API surface, but labels render at
// body2 (14.sp) instead of the stock 17.sp button style, shrinking down to 8.sp before
// ellipsizing when the button is narrower than its text.
private val AppButtonMinFontSize = 8.sp

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
) {
    AppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = cornerRadius,
        minWidth = minWidth,
        minHeight = minHeight,
        colors = colors,
        insideMargin = insideMargin,
    ) {
        AppButtonText(text)
    }
}

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = cornerRadius,
        minWidth = minWidth,
        minHeight = minHeight,
        colors = colors,
        insideMargin = insideMargin,
        content = content,
    )
}

@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
) {
    val mappedColors = remember(colors) {
        ButtonColors(
            color = colors.color,
            disabledColor = colors.disabledColor,
            contentColor = colors.textColor,
            disabledContentColor = colors.disabledTextColor,
        )
    }
    AppButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        cornerRadius = cornerRadius,
        minWidth = minWidth,
        minHeight = minHeight,
        colors = mappedColors,
        insideMargin = insideMargin,
    ) {
        AppButtonText(text)
    }
}

/** Standalone button label for custom [AppButton] content; color defaults to the button's content color. */
@Composable
fun AppButtonText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val style = MiuixTheme.textStyles.body2
    val maxFontSize = style.fontSize.takeUnless { it == TextUnit.Unspecified } ?: 14.sp
    val minFontSize = if (maxFontSize.value < AppButtonMinFontSize.value) maxFontSize else AppButtonMinFontSize
    Text(
        text = text,
        modifier = modifier,
        color = color ?: Color.Unspecified,
        autoSize = DescendingSpAutoSize(minFontSize = minFontSize, maxFontSize = maxFontSize),
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

private data class DescendingSpAutoSize(
    val minFontSize: TextUnit,
    val maxFontSize: TextUnit,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(
        constraints: Constraints,
        text: AnnotatedString,
    ): TextUnit {
        var currentSp = maxFontSize.value
        while (currentSp > minFontSize.value) {
            val result = performLayout(constraints, text, currentSp.sp)
            val overflowed = result.hasVisualOverflow ||
                    (result.lineCount > 0 && result.isLineEllipsized(0))
            if (!overflowed) return currentSp.sp
            currentSp -= 0.5f
        }
        return minFontSize
    }
}

// Compact action-button metrics, matching the payload_extract_gui look.
private val ActionButtonMinHeight = 32.dp
private val ActionButtonInsideMargin = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
private val ActiveDownloadPhases = setOf(
    DownloadPhase.QUEUED,
    DownloadPhase.DOWNLOADING,
    DownloadPhase.PAUSED,
    DownloadPhase.INSTALLING,
    DownloadPhase.AWAITING_USER_ACTION,
)
private val CancelableDownloadPhases = setOf(
    DownloadPhase.QUEUED,
    DownloadPhase.DOWNLOADING,
    DownloadPhase.AWAITING_USER_ACTION,
)

@Composable
fun AppCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = appTintedButtonColors(),
    minHeight: Dp = ActionButtonMinHeight,
    insideMargin: PaddingValues = ActionButtonInsideMargin,
) {
    AppButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        cornerRadius = 100.dp,
        minHeight = minHeight,
        insideMargin = insideMargin,
        modifier = modifier,
    )
}

@Composable
private fun tintedContainerColor(): Color =
    MiuixTheme.colorScheme.primary.copy(alpha = if (isSystemInDarkTheme()) 0.2f else 0.08f)

@Composable
private fun appTintedButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    color = tintedContainerColor(),
    contentColor = MiuixTheme.colorScheme.primary,
)

@Composable
fun AppActionButton(
    packageName: String,
    actionText: String,
    actionKind: AppActionKind,
    downloadState: DownloadState?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    minHeight: Dp = ActionButtonMinHeight,
    insideMargin: PaddingValues = ActionButtonInsideMargin,
    onAction: () -> Unit,
    onResumeDownload: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    // 无账号体系可执行预约,按钮仅作状态展示。
    if (actionKind == AppActionKind.RESERVE) {
        AppCompactButton(
            text = actionText,
            onClick = {},
            enabled = false,
            modifier = modifier,
            minHeight = minHeight,
            insideMargin = insideMargin,
        )
        return
    }

    val activePhase = downloadState?.phase in ActiveDownloadPhases
    // Active transfer or install: the button itself becomes the progress indicator.
    if (downloadState != null && activePhase) {
        val percent = downloadState.progress
        DownloadProgressButton(
            label = when (downloadState.phase) {
                DownloadPhase.PAUSED -> stringResource(Res.string.download_paused)
                DownloadPhase.INSTALLING -> stringResource(Res.string.installing_short)
                DownloadPhase.AWAITING_USER_ACTION -> stringResource(Res.string.waiting_install_confirmation)
                else -> percent?.let { "$it%" } ?: stringResource(Res.string.downloading_short)
            },
            fraction = (percent ?: 0).coerceIn(0, 100) / 100f,
            onClick = {
                if (downloadState.isPaused) {
                    onResumeDownload()
                } else if (downloadState.phase in CancelableDownloadPhases) {
                    onCancel(packageName)
                }
            },
            modifier = modifier,
            minHeight = minHeight,
            insideMargin = insideMargin,
        )
        return
    }

    val buttonText = when {
        // Downloaded and waiting for the user to install it.
        downloadState?.isComplete == true -> stringResource(Res.string.install)
        else -> actionText
    }
    val click: () -> Unit =
        if (downloadState?.isComplete == true) ({ onInstallDownloaded(packageName) }) else onAction
    // "Open" (already installed) keeps the neutral look; actionable states use the tinted-primary style.
    val neutral = downloadState == null && actionKind == AppActionKind.OPEN
    AppCompactButton(
        text = buttonText,
        onClick = click,
        enabled = enabled,
        modifier = modifier,
        colors = if (neutral) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
        minHeight = minHeight,
        insideMargin = insideMargin,
    )
}

/**
 * Download button whose solid-primary fill grows left -> right with [fraction] over a translucent
 * base. The percentage label is rendered twice — once in the base-contrast color, once in the
 * fill-contrast color clipped to the filled region — so the text auto-splits between black/white
 * at the moving fill boundary. The fast-changing [fraction] is read only inside draw blocks.
 */
@Composable
private fun DownloadProgressButton(
    label: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    minHeight: Dp = ActionButtonMinHeight,
    insideMargin: PaddingValues = ActionButtonInsideMargin,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val base = tintedContainerColor()
    val baseTextColor = contrastColor(base.compositeOver(MiuixTheme.colorScheme.surface))
    val fillTextColor = contrastColor(primary)
    val animatedFraction = animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "downloadFill",
    )
    Box(
        modifier = modifier
            .widthIn(min = ButtonDefaults.MinWidth)
            .heightIn(min = minHeight)
            .squircleSurface(color = base, cornerRadius = 100.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Solid fill (draw phase reads the animated fraction).
        Box(
            Modifier.matchParentSize().drawBehind {
                val filledWidth = size.width * animatedFraction.value
                if (filledWidth > 0f) drawRect(
                    color = primary,
                    size = Size(filledWidth, size.height)
                )
            },
        )
        // Base label — visible over the translucent (unfilled) area. Size-defining child.
        AppButtonText(
            text = label,
            color = baseTextColor,
            modifier = Modifier.padding(insideMargin),
        )
        // Same label in the fill-contrast color, clipped to the filled region so it covers the
        // base label only where the fill has reached.
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    clipRect(right = size.width * animatedFraction.value) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AppButtonText(
                text = label,
                color = fillTextColor,
                modifier = Modifier.padding(insideMargin),
            )
        }
    }
}

/** Black or white, whichever contrasts the given background luminance. */
@Composable
private fun contrastColor(background: Color): Color =
    if (background.luminance() > 0.5f) MiuixTheme.colorScheme.primary else Color.White
