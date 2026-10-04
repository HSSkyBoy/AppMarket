package top.app.market.data.repository

import top.app.market.data.install.platform.SavedPackageInstallLauncher
import top.app.market.data.install.storage.MediaStorePackageStore
import top.app.market.domain.model.installer.SavedPackage
import top.app.market.domain.repository.SavedPackageRepository

internal class SavedPackageRepositoryImpl(
    private val store: MediaStorePackageStore,
    private val installLauncher: SavedPackageInstallLauncher,
) : SavedPackageRepository {
    override suspend fun list(): List<SavedPackage> = store.list()

    override suspend fun install(id: String) {
        installLauncher.installSavedPackage(id)
    }

    override suspend fun delete(id: String) = store.delete(id)
}
