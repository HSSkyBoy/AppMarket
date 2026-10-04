package top.app.market.domain.model.installed

data class PackageChange(
    val packageName: String,
    val installedVersionCode: Long?,
    val installedVersionName: String = "",
) {
    val isInstalled: Boolean get() = installedVersionCode != null
}
