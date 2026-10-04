package top.app.market.domain.model.installed

/** A locally installed package as needed by update checking. */
data class InstalledPackage(
    val packageName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val versionName: String = "",
    val installedBy: String = "0",
    val splits: String = "0",
    val oldApkHash: String = "0",
    val apkSource: String = "0",
    val baseApkPath: String = "",
    val targetSdkVersion: Int = 0,
    /** OPPO UpgradeReqV2 field 7: legacy uppercase signature digest. */
    val signature: String = "",
    /** OPPO UpgradeReqV2 field 9: lowercase certificate MD5 values. */
    val signatureList: List<String> = emptyList(),
    /** Package installer name, or PRELOAD for a preinstalled system package. */
    val installOrigin: String = "",
    /** User-visible application label, used to resolve hidden store entries by local name. */
    val displayName: String = "",
    /** SHA-256 of the current X.509 signing certificate. */
    val signerSha256: String = "",
    /** SHA-256 signing-certificate history, including the current signer. */
    val signerSha256List: List<String> = emptyList(),
)
