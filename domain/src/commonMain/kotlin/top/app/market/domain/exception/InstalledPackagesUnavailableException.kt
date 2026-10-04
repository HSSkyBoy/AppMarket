package top.app.market.domain.exception

class InstalledPackagesUnavailableException(
    message: String = "Installed packages unavailable; app list permission required",
) : RuntimeException(message)
