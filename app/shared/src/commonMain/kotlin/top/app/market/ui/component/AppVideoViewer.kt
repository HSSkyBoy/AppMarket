package top.app.market.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.app.market.domain.model.market.AppVideo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.resources.Res
import top.app.market.resources.video_close
import top.app.market.resources.video_load_failed
import top.app.market.resources.video_mute
import top.app.market.resources.video_pause
import top.app.market.resources.video_play
import top.app.market.resources.video_unmute
import top.app.market.ui.util.animatedRect
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.VolumeOff
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private const val VIDEO_VIEWER_ANIM_MS = 400
private const val VIDEO_VIEWER_CORNER_DP = 16f

@Composable
fun AppVideoViewer(
    video: AppVideo,
    cardBounds: Rect?,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playerState = rememberVideoPlayerState()
    LaunchedEffect(video.url) { playerState.openUri(video.url) }

    val progress = remember { Animatable(0f) }
    var browsing by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(VIDEO_VIEWER_ANIM_MS))
        browsing = true
    }
    val animationScope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            browsing = false
            playerState.pause()
            animationScope.launch {
                progress.animateTo(0f, tween(VIDEO_VIEWER_ANIM_MS))
                onClosed()
            }
        }
    }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = !closing,
        onBackCompleted = dismiss,
    )

    var viewerOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { viewerOrigin = it.positionInRoot() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(Color.Black),
        )

        if (browsing) {
            VideoPlaybackLayer(
                playerState = playerState,
                onClose = dismiss,
            )
        } else {
            val ratio = if (video.orientation == ScreenshotOrientation.LANDSCAPE) 16f / 9f else 9f / 16f
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()
            val fitWidth = minOf(containerWidth, containerHeight * ratio)
            val fitRect = Rect(
                Offset((containerWidth - fitWidth) / 2f, (containerHeight - fitWidth / ratio) / 2f),
                Size(fitWidth, fitWidth / ratio),
            )
            val cardRect = cardBounds?.translate(-viewerOrigin) ?: fitRect
            val cornerDp by remember(progress) {
                derivedStateOf { (VIDEO_VIEWER_CORNER_DP * (1f - progress.value)).roundToInt() }
            }
            AppAsyncImage(
                url = video.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .animatedRect { lerp(cardRect, fitRect, progress.value) }
                    .squircleClip(cornerDp.dp),
            )
        }
    }
}

@Composable
private fun VideoPlaybackLayer(
    playerState: io.github.kdroidfilter.composemediaplayer.VideoPlayerState,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(playerState) {
                detectTapGestures {
                    if (playerState.isPlaying) playerState.pause() else playerState.play()
                }
            },
    ) {
        VideoPlayerSurface(
            playerState = playerState,
            modifier = Modifier.fillMaxSize(),
        )

        if (playerState.isLoading) {
            InfiniteProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (playerState.error != null) {
            Text(
                text = stringResource(Res.string.video_load_failed),
                color = Color.White,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Close,
                contentDescription = stringResource(Res.string.video_close),
                tint = Color.White,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val playing = playerState.isPlaying
            IconButton(onClick = { if (playing) playerState.pause() else playerState.play() }) {
                Icon(
                    imageVector = if (playing) MiuixIcons.Pause else MiuixIcons.Play,
                    contentDescription = stringResource(
                        if (playing) Res.string.video_pause else Res.string.video_play
                    ),
                    tint = Color.White,
                )
            }
            Text(
                text = playerState.positionText,
                color = Color.White,
                style = MiuixTheme.textStyles.footnote1,
            )
            VideoProgressBar(
                progress = { playerState.sliderPos },
                onSeek = playerState::seekStart,
                onSeekFinished = playerState::seekFinished,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = playerState.durationText,
                color = Color.White,
                style = MiuixTheme.textStyles.footnote1,
            )
            var volumeBeforeMute by remember { mutableFloatStateOf(playerState.volume) }
            val muted = playerState.volume == 0f
            IconButton(
                onClick = {
                    if (muted) {
                        playerState.volume = volumeBeforeMute.takeIf { it > 0f } ?: 1f
                    } else {
                        volumeBeforeMute = playerState.volume
                        playerState.volume = 0f
                    }
                },
            ) {
                Icon(
                    imageVector = if (muted) MiuixIcons.VolumeOff else MiuixIcons.VolumeUp,
                    contentDescription = stringResource(
                        if (muted) Res.string.video_unmute else Res.string.video_mute
                    ),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun VideoProgressBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f) * 1000f)
                    onSeekFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onSeek((change.position.x / size.width).coerceIn(0f, 1f) * 1000f)
                    },
                    onDragEnd = onSeekFinished,
                    onDragCancel = onSeekFinished,
                )
            }
            .drawBehind {
                val trackHeight = 3.dp.toPx()
                val radius = CornerRadius(trackHeight / 2f)
                val top = (size.height - trackHeight) / 2f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.3f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, trackHeight),
                    cornerRadius = radius,
                    style = Fill,
                )
                val filled = (progress() / 1000f).coerceIn(0f, 1f) * size.width
                if (filled > 0f) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(0f, top),
                        size = Size(filled, trackHeight),
                        cornerRadius = radius,
                        style = Fill,
                    )
                }
            },
    )
}
