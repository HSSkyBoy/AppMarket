package top.app.market.domain.model.download

data class DownloadPart(
    val name: String,
    val type: String,
    val url: String,
    val size: Long,
    val hash: String = "",
    val patch: DownloadPatch? = null,
)
