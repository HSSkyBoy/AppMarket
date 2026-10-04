package top.app.market.domain.repository

import top.app.market.domain.model.installer.InstallerCandidate

interface InstallerDiscoveryRepository {
    suspend fun listCandidates(): List<InstallerCandidate>
}
