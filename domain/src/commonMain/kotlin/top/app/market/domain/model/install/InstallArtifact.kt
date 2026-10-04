package top.app.market.domain.model.install

data class InstallArtifact(
    val name: String,
    val source: InstallSource,
    val size: Long = -1L,
    val checksum: String = "",
) {
    /** Bytes transferred from the network or local source, which may be smaller than the final APK. */
    val transferSize: Long
        get() = (source as? InstallSource.Delta)?.patchSize ?: size
}

sealed interface InstallSource {
    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : InstallSource

    data class Delta(
        val full: Remote,
        val patch: Remote,
        val patchSize: Long,
        val patchChecksum: String = "",
        val patchVersion: Int,
        val baseApkPath: String,
        val patchProtocol: String = "file",
    ) : InstallSource

    data class Local(val uri: String) : InstallSource
}
