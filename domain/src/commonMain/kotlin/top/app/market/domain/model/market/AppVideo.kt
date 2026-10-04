package top.app.market.domain.model.market

data class AppVideo(
    val url: String,
    val coverUrl: String,
    val orientation: ScreenshotOrientation,
    val title: String = "",
)
