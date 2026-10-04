package top.app.market.platform

/** 平台只报结果，文案由 UI 侧走 Compose Resources 决定。 */
enum class ImageSaveResult {
    Saved,
    Failed,
    Unsupported,
}
