package top.app.market.domain.repository

import top.app.market.domain.model.install.InstallEvent
import top.app.market.domain.model.install.InstallRequest
import kotlinx.coroutines.flow.Flow

/** Android implements this boundary; desktop does not register an installer. */
interface InstallRepository {
    fun install(request: InstallRequest): Flow<InstallEvent>
    suspend fun cancel(id: String)

    /** 回收无任务认领的安装会话：session 跨进程存活，且系统限制单个 installer 的活跃数量。 */
    suspend fun reclaimSessions(retainedSessionIds: Set<Int>) = Unit
}
