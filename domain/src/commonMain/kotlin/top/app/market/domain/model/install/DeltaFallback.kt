package top.app.market.domain.model.install

/** 增量合成失败、已回退全量下载。仅用于诊断提示。 */
data class DeltaFallback(
    val artifactName: String,
    val reason: String,
)
