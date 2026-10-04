package top.app.market.domain.model.install

sealed interface InstallEvent {
    data object Preparing : InstallEvent
    data class Progress(val completed: Long, val total: Long) : InstallEvent
    data class Downloaded(val savedPackageId: String) : InstallEvent
    data class SessionReady(val sessionId: Int, val savedPackageId: String?) : InstallEvent
    data class Committed(val sessionId: Int, val savedPackageId: String?) : InstallEvent
    data class ExternalInstallerPrepared(
        val installerPackageName: String,
        val savedPackageId: String,
    ) : InstallEvent

    data class ExternalInstallerLaunched(
        val installerPackageName: String,
        val savedPackageId: String,
    ) : InstallEvent

    data class Failed(val code: InstallFailureCode, val message: String) : InstallEvent
}

enum class InstallFailureCode {
    UNKNOWN_SOURCES_PERMISSION,
    SHIZUKU_UNAVAILABLE,
    SHIZUKU_PERMISSION_DENIED,
    ROOT_UNAVAILABLE,
    THIRD_PARTY_UNAVAILABLE,
    NETWORK,
    STORAGE,
    INTEGRITY,
    SESSION,
    CANCELLED,
    UNKNOWN,
}
