package top.app.market.domain.model.market

data class AppComment(
    val userName: String,
    val content: String,
    val score: Double,
)

data class AppComments(
    val items: List<AppComment>,
    val totalCount: Long,
)
