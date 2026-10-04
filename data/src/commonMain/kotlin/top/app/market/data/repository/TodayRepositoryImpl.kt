package top.app.market.data.repository

import top.app.market.data.remote.xiaomi.XiaomiApi
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.repository.AccountRepository
import top.app.market.domain.repository.ProfileRepository
import top.app.market.domain.repository.TodayRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform-agnostic Today / 金米奖 repository: all networking/parsing lives in commonMain ([XiaomiApi]).
 * The platform supplies the device [profileStore] and account [cookies] (empty = anonymous on desktop).
 */
internal class TodayRepositoryImpl(
    private val api: XiaomiApi,
    private val profileStore: ProfileRepository,
    private val cookies: AccountRepository,
) : TodayRepository {

    override suspend fun goldMiFeed(page: Int, pageSize: Int): TodayFeedPage = withContext(Dispatchers.Default) {
        api.goldMiFeed(page, pageSize, profileStore.load(), cookies.cookie())
    }

    override suspend fun todayArticle(rId: String): TodayArticle = withContext(Dispatchers.Default) {
        api.todayArticle(rId, profileStore.load(), cookies.cookie())
    }
}
