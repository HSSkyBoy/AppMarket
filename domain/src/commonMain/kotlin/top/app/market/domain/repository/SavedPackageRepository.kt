package top.app.market.domain.repository

import top.app.market.domain.model.installer.SavedPackage

/** Locally saved APK files. */
interface SavedPackageRepository {
    suspend fun list(): List<SavedPackage>
    suspend fun install(id: String)
    suspend fun delete(id: String)
}
