package top.app.market.domain.model.install

import kotlin.test.Test
import kotlin.test.assertEquals

class InstallArtifactTest {
    @Test
    fun deltaArtifactReportsPatchTransferSize() {
        val artifact = InstallArtifact(
            name = "base.apk",
            source = InstallSource.Delta(
                full = InstallSource.Remote("https://example.test/full.apk"),
                patch = InstallSource.Remote("https://example.test/update.patch"),
                patchSize = 128L,
                patchVersion = 3,
                baseApkPath = "/installed/base.apk",
            ),
            size = 1024L,
        )

        assertEquals(128L, artifact.transferSize)
    }

    @Test
    fun fullArtifactReportsFinalSize() {
        val artifact = InstallArtifact(
            name = "base.apk",
            source = InstallSource.Remote("https://example.test/full.apk"),
            size = 1024L,
        )

        assertEquals(1024L, artifact.transferSize)
    }
}
