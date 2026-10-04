package top.app.market.viewmodel

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppComment
import top.app.market.domain.model.market.AppComments
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.repository.MarketRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppDetailExternalLookupTest {

    @Test
    fun missingAppIdUsesDirectPackageLookup() = runBlocking {
        val packageName = "com.baidu.carlife.xiaomi"
        val repository = RecordingMarketRepository(detail(appId = 123L, packageName = packageName))

        val result = loadRequestedDetail(
            market = repository,
            appId = 0L,
            packageName = packageName,
            externalQuery = "id=$packageName",
        )

        assertEquals(123L, result.app.appId)
        assertEquals(DetailRequest(0L, packageName, "id=$packageName"), repository.request)
    }

    @Test
    fun invalidDetailResponseIsReportedAsNotListed() = runBlocking {
        val repository = RecordingMarketRepository(detail(appId = 0L, packageName = ""))

        val error = assertFailsWith<IllegalStateException> {
            loadRequestedDetail(
                market = repository,
                appId = 0L,
                packageName = "com.example.missing",
                externalQuery = "id=com.example.missing",
            )
        }

        assertEquals(APP_NOT_LISTED_MESSAGE, error.message)
    }

    @Test
    fun disabledOptionalSectionsMakeNoRequests() = runBlocking {
        val repository = RecordingMarketRepository(detail(appId = 123L, packageName = "com.example.app"))

        loadOptionalDetailSections(
            market = repository,
            detail = repository.detail,
            loadComments = false,
            loadSameDeveloper = false,
            onCommentsLoaded = { error("Comments callback must not run") },
            onSameDeveloperLoaded = { error("Same-developer callback must not run") },
        )

        assertEquals(0, repository.commentRequests)
        assertEquals(0, repository.sameDeveloperRequests)
    }

    @Test
    fun enabledOptionalSectionsLoadConcurrently() = runBlocking {
        val commentsStarted = CompletableDeferred<Unit>()
        val sameDeveloperStarted = CompletableDeferred<Unit>()
        val comment = AppComment("user", "content", 5.0)
        val relatedApp = detail(456L, "com.example.related").app
        val basicDetail = detail(123L, "com.example.app")
        val repository = RecordingMarketRepository(
            detail = basicDetail,
            commentsLoader = { appId, versionCode ->
                assertEquals(123L, appId)
                assertEquals(1L, versionCode)
                commentsStarted.complete(Unit)
                sameDeveloperStarted.await()
                AppComments(listOf(comment), 42L)
            },
            sameDeveloperLoader = { appId ->
                assertEquals(123L, appId)
                sameDeveloperStarted.complete(Unit)
                commentsStarted.await()
                listOf(relatedApp)
            },
        )
        var loadedComments = AppComments(emptyList(), 0L)
        var loadedRelatedApps = emptyList<MarketAppInfo>()

        withTimeout(5_000L) {
            loadOptionalDetailSections(
                market = repository,
                detail = basicDetail,
                loadComments = true,
                loadSameDeveloper = true,
                onCommentsLoaded = { loadedComments = it },
                onSameDeveloperLoaded = { loadedRelatedApps = it },
            )
        }

        assertEquals(AppComments(listOf(comment), 42L), loadedComments)
        assertEquals(listOf(relatedApp), loadedRelatedApps)
        assertEquals(1, repository.commentRequests)
        assertEquals(1, repository.sameDeveloperRequests)
    }

    @Test
    fun optionalSectionFailureDoesNotCancelOtherSection() = runBlocking {
        val relatedApp = detail(456L, "com.example.related").app
        val basicDetail = detail(123L, "com.example.app")
        val repository = RecordingMarketRepository(
            detail = basicDetail,
            commentsLoader = { _, _ -> error("comments unavailable") },
            sameDeveloperLoader = { listOf(relatedApp) },
        )
        var commentsCallbackCalled = false
        var loadedRelatedApps = emptyList<MarketAppInfo>()

        loadOptionalDetailSections(
            market = repository,
            detail = basicDetail,
            loadComments = true,
            loadSameDeveloper = true,
            onCommentsLoaded = { commentsCallbackCalled = true },
            onSameDeveloperLoaded = { loadedRelatedApps = it },
        )

        assertEquals(false, commentsCallbackCalled)
        assertEquals(listOf(relatedApp), loadedRelatedApps)
    }

    private data class DetailRequest(
        val appId: Long,
        val packageName: String,
        val externalQuery: String?,
    )

    private class RecordingMarketRepository(
        val detail: AppDetail,
        private val commentsLoader: suspend (Long, Long) -> AppComments = { _, _ -> error("Not used") },
        private val sameDeveloperLoader: suspend (Long) -> List<MarketAppInfo> = { error("Not used") },
    ) : MarketRepository {
        var request: DetailRequest? = null
            private set
        var commentRequests = 0
            private set
        var sameDeveloperRequests = 0
            private set

        override suspend fun categoryApps(categoryId: Int, page: Int): SearchPage = error("Not used")

        override suspend fun search(keyword: String, page: Int): SearchPage =
            error("Package lookup must not use search")

        override suspend fun appDetail(
            appId: Long,
            packageName: String,
            externalQuery: String?,
        ): AppDetail {
            request = DetailRequest(appId, packageName, externalQuery)
            return detail
        }

        override suspend fun appComments(appId: Long, versionCode: Long): AppComments {
            commentRequests++
            return commentsLoader(appId, versionCode)
        }

        override suspend fun sameDeveloperApps(appId: Long): List<MarketAppInfo> {
            sameDeveloperRequests++
            return sameDeveloperLoader(appId)
        }

        override suspend fun downloadMeta(app: MarketAppInfo, keyword: String): DownloadMeta =
            error("Not used")

        override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta =
            error("Not used")

        override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> =
            error("Not used")

        override fun checkUpdatesFlow(): Flow<List<MarketAppInfo>> = emptyFlow()

        override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
            error("Not used")
    }

    private fun detail(appId: Long, packageName: String) = AppDetail(
        app = MarketAppInfo(
            appId = appId,
            packageName = packageName,
            displayName = packageName,
            publisherName = "",
            versionName = "1.0",
            versionCode = 1L,
            icon = "",
            apkSize = 1L,
            ratingScore = 0.0,
        ),
        brief = "",
        introduction = "",
        changeLog = "",
        category = "",
        ageClassification = "",
        downloadCount = 0L,
        registrationNum = "",
        privacyUrl = "",
        screenshots = emptyList(),
        comments = emptyList(),
        sameDeveloperApps = emptyList(),
    )
}
