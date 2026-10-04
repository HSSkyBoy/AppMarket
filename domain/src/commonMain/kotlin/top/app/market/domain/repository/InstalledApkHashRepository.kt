package top.app.market.domain.repository

/** Computes a base APK hash used by delta update metadata. */
fun interface InstalledApkHashRepository {
    suspend fun md5(path: String): String

    /** TapTap hashes sorted META-INF contents followed by the final MiB of the APK. */
    suspend fun tapTap(path: String): String = ""

    /** SHA-256 is required by system-component update services before they disclose a patch. */
    suspend fun sha256(path: String): String = ""
}
