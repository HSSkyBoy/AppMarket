package top.app.market.ui.model

import androidx.compose.runtime.Immutable
import top.app.market.domain.model.market.MarketAppInfo

@Immutable
data class SearchResultItem(
    val app: MarketAppInfo,
    val actionKind: AppActionKind,
)
