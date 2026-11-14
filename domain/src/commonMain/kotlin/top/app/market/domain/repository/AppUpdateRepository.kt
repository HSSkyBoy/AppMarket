package top.app.market.domain.repository

import top.app.market.domain.model.update.AppReleaseInfo

interface AppUpdateRepository {
    /**
     * 檢查 GitHub 最新 Release
     * @param currentVersionName 目前 App 的 versionName (例如 "1.0.0")
     * @return 若有新版本則回傳 AppReleaseInfo，否則回傳 null
     */
    suspend fun checkForUpdate(currentVersionName: String): AppReleaseInfo?
}
