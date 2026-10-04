package top.app.market.data.install

import top.app.market.domain.model.install.DeltaFallback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class DeltaFallbackBus {
    private val mutable = MutableSharedFlow<DeltaFallback>(extraBufferCapacity = 8)
    val events: SharedFlow<DeltaFallback> = mutable.asSharedFlow()

    fun emit(artifactName: String, reason: String) {
        mutable.tryEmit(DeltaFallback(artifactName, reason))
    }
}
