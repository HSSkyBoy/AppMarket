package top.app.market.ui.screen

import top.app.market.domain.model.download.DownloadPhase
import top.app.market.domain.model.download.DownloadState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoricalVersionsScreenTest {
    @Test
    fun failedAndPausedDownloadsCanBeRetried() {
        assertTrue(historicalDownloadButtonEnabled(state(DownloadPhase.FAILED)))
        assertTrue(historicalDownloadButtonEnabled(state(DownloadPhase.PAUSED)))
    }

    @Test
    fun activeTransfersCanBeClickedToPause() {
        assertTrue(historicalDownloadButtonEnabled(state(DownloadPhase.QUEUED)))
        assertTrue(historicalDownloadButtonEnabled(state(DownloadPhase.DOWNLOADING)))
    }

    @Test
    fun installPhasesAndCompletedDownloadsStayDisabled() {
        assertFalse(historicalDownloadButtonEnabled(state(DownloadPhase.INSTALLING)))
        assertFalse(historicalDownloadButtonEnabled(state(DownloadPhase.AWAITING_USER_ACTION)))
        assertFalse(historicalDownloadButtonEnabled(state(DownloadPhase.DOWNLOADED, savedPackageId = "saved")))
    }

    @Test
    fun incompleteDownloadedStateCanRestart() {
        assertTrue(historicalDownloadButtonEnabled(state(DownloadPhase.DOWNLOADED)))
    }

    @Test
    fun anotherVersionWithoutStateRemainsDownloadable() {
        assertTrue(historicalDownloadButtonEnabled(null))
    }

    private fun state(
        phase: DownloadPhase,
        savedPackageId: String? = null,
        versionName: String = "",
        versionCode: Long = 0L,
    ) = DownloadState(
        appId = 1L,
        packageName = "com.example.app",
        displayName = "Example",
        progress = null,
        phase = phase,
        savedPackageId = savedPackageId,
        versionName = versionName,
        versionCode = versionCode,
    )

}
