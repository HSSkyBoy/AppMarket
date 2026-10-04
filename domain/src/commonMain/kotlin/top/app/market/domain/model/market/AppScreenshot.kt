package top.app.market.domain.model.market

data class AppScreenshot(
    val url: String,
    val orientation: ScreenshotOrientation,
    // 全屏查看与保存用的高清原图地址
    val expandedUrl: String = url,
)

enum class ScreenshotOrientation {
    PORTRAIT,
    LANDSCAPE,
}
