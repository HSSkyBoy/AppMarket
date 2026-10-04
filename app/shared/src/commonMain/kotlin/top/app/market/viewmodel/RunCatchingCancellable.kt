package top.app.market.viewmodel

import kotlinx.coroutines.CancellationException

/** 放行取消的 [runCatching]：否则退出页面会把取消当成失败，弹出「…was cancelled」的 toast。 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
