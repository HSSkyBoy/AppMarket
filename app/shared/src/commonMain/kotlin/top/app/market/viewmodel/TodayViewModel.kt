package top.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeaturedItem
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    /** 当前今日内容来源；卡片布局（如小米的完全覆盖式）据此分派。 */
    val source: AppSource = AppSource.XIAOMI,
    val feed: TodayFeedPage = TodayFeedPage(emptyList(), true),
    val article: TodayArticle? = null,
    val articleLoading: Boolean = false,
    val articleError: String = "",
    val feedLoading: Boolean = true,
    val feedLoadingMore: Boolean = false,
    val feedError: String = "",
)

class TodayViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
) : ViewModel() {
    // 文章正文含全部区块与 HTML，单篇可达数十 KB；长会话连续浏览需要上限
    private val articleCache = object : LinkedHashMap<String, TodayArticle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, TodayArticle>): Boolean = size > ARTICLE_CACHE_SIZE
    }
    private var loadingArticleId: String? = null
    private var activeSource = prefs.todaySource.value
    private var sourceGeneration = 0L
    private var nextFeedPage = 0
    private val _uiState = MutableStateFlow(TodayUiState(source = prefs.todaySource.value))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // StateFlow 自身去重，无需 distinctUntilChanged
            prefs.todaySource.collectLatest(::switchSource)
        }
    }

    private fun loadTodayData() {
        val source = activeSource
        val generation = sourceGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(feedLoading = true, feedError = "") }
            nextFeedPage = 0
            loadFeedPage(source, generation, replace = true)
        }
    }

    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.feedLoading || snapshot.feedLoadingMore || !snapshot.feed.hasMore) return
        _uiState.update { it.copy(feedLoadingMore = true, feedError = "") }

        val source = activeSource
        val generation = sourceGeneration
        viewModelScope.launch {
            loadFeedPage(source, generation, replace = false)
        }
    }

    fun retryFeed() {
        val snapshot = _uiState.value
        if (snapshot.feedLoading || snapshot.feedLoadingMore) return
        if (snapshot.feed.items.isEmpty()) loadTodayData() else loadMore()
    }

    private suspend fun switchSource(source: AppSource) {
        activeSource = source
        sourceGeneration++
        nextFeedPage = 0
        articleCache.clear()
        loadingArticleId = null
        _uiState.value = TodayUiState(source = source)
        loadFeedPage(source, sourceGeneration, replace = true)
    }

    private suspend fun loadFeedPage(source: AppSource, generation: Long, replace: Boolean) {
        val requestedPage = nextFeedPage
        val result = runCatchingCancellable { sources.goldMiFeed(source, requestedPage) }
        if (source != activeSource || generation != sourceGeneration) return
        val page = result.getOrNull()
        if (page != null && requestedPage == nextFeedPage) nextFeedPage++

        _uiState.update { before ->
            val existing = if (replace) emptyList() else before.feed.items
            val existingKeys = existing.mapTo(HashSet(), TodayFeaturedItem::feedKey)
            val appended = page?.items.orEmpty().filter { existingKeys.add(it.feedKey()) }
            before.copy(
                source = source,
                feed = TodayFeedPage(
                    items = existing + appended,
                    hasMore = page?.hasMore ?: before.feed.hasMore,
                ),
                feedLoading = false,
                feedLoadingMore = false,
                feedError = result.exceptionOrNull()?.message.orEmpty(),
            )
        }
    }

    fun loadArticle(rId: String) {
        val topicId = rId.trim()
        if (topicId.isBlank()) return
        val source = activeSource
        val generation = sourceGeneration
        val cacheKey = "$source:$topicId"

        articleCache[cacheKey]?.let { article ->
            _uiState.update { it.copy(article = article, articleLoading = false, articleError = "") }
            return
        }
        if (loadingArticleId == cacheKey) return

        loadingArticleId = cacheKey
        viewModelScope.launch {
            _uiState.update { it.copy(article = null, articleLoading = true, articleError = "") }
            runCatchingCancellable {
                sources.todayArticle(source, topicId).also { article ->
                    check(article.hasRenderableContent()) { "Today article $topicId has no content" }
                }
            }.onSuccess { article ->
                if (source != activeSource || generation != sourceGeneration) return@onSuccess
                articleCache[cacheKey] = article
                if (loadingArticleId == cacheKey) {
                    _uiState.update { it.copy(article = article, articleLoading = false) }
                }
            }.onFailure { error ->
                if (source == activeSource && generation == sourceGeneration && loadingArticleId == cacheKey) {
                    _uiState.update {
                        it.copy(
                            articleLoading = false,
                            articleError = error.message ?: "Unable to load article",
                        )
                    }
                }
            }
            if (loadingArticleId == cacheKey) loadingArticleId = null
        }
    }

    private companion object {
        const val ARTICLE_CACHE_SIZE = 20
    }
}

private fun TodayArticle.hasRenderableContent(): Boolean =
    blocks.isNotEmpty() || headerImage.isNotBlank() || richTextHtml.isNotBlank() || apps.isNotEmpty()

private fun TodayFeaturedItem.feedKey(): String = rId.ifBlank { articleLink }

