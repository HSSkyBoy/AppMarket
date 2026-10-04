package top.app.market.domain.model.download

data class DownloadPatch(
    val url: String,
    val size: Long,
    val hash: String = "",
    val version: Int = 0,
    val oldApkHash: String = "0",
    /** Patch format selected by the source (plain file, SFPat21, OPPO OFbF, ...). */
    val protocol: String = DownloadPatchProtocol.FILE,
    /** Headers may be URL-signed independently from the full APK request. */
    val requestHeaders: Map<String, String> = emptyMap(),
)

object DownloadPatchProtocol {
    const val FILE = "file"
    const val SFPATCH = "sfpatch"
    const val VCDIFF = "vcdiff"
}
