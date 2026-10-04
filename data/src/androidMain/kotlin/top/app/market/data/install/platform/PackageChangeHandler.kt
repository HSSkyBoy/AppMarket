package top.app.market.data.install.platform

interface PackageChangeHandler {
    /** Returns false when an install signal arrived before PackageManager could resolve the package. */
    suspend fun onPackageChanged(packageName: String, installed: Boolean): Boolean
}
