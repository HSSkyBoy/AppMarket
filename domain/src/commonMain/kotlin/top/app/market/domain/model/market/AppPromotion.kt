package top.app.market.domain.model.market

data class AppPromotion(
    val previewImageUrl: String,
    val expandedImageUrl: String,
    val title: String,
    val description: String,
    val category: String,
    val activityTag: String,
    val jumpUrl: String,
)
