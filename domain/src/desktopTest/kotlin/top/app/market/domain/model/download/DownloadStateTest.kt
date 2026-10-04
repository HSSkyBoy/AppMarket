package top.app.market.domain.model.download

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStateTest {
    private fun state(phase: DownloadPhase, savedId: String? = null) = DownloadState(
        appId = 1L,
        packageName = "com.example.app",
        displayName = "Example",
        progress = null,
        phase = phase,
        savedPackageId = savedId,
    )

    @Test
    fun onlyPublishedDownloadIsComplete() {
        assertTrue(state(DownloadPhase.DOWNLOADED, "saved").isComplete)
        assertFalse(state(DownloadPhase.DOWNLOADED).isComplete)
        assertFalse(state(DownloadPhase.INSTALLING, "saved").isComplete)
    }

    @Test
    fun pausedStateIsExplicit() {
        assertTrue(state(DownloadPhase.PAUSED).isPaused)
        assertFalse(state(DownloadPhase.DOWNLOADING).isPaused)
    }
}
