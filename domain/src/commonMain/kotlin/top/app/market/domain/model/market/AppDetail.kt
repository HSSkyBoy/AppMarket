package top.app.market.domain.model.market

data class AppDetail(
    val app: MarketAppInfo,
    val brief: String,
    val introduction: String,
    val changeLog: String,
    val category: String,
    val ageClassification: String,
    val downloadCount: Long,
    val registrationNum: String,
    val updateTime: Long = 0L,
    val privacyUrl: String,
    val screenshots: List<AppScreenshot>,
    val videos: List<AppVideo> = emptyList(),
    val commentCount: Long = 0L,
    val comments: List<AppComment>,
    val sameDeveloperApps: List<MarketAppInfo>,
    val promotions: List<AppPromotion> = emptyList(),
)
