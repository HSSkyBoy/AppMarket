package top.app.market.ui.util

import androidx.compose.runtime.Composable
import top.app.market.domain.model.market.AppSource
import top.app.market.resources.Res
import top.app.market.resources.source_box7723
import top.app.market.resources.source_honor
import top.app.market.resources.source_huawei
import top.app.market.resources.source_oppo
import top.app.market.resources.source_samsung
import top.app.market.resources.source_taptap
import top.app.market.resources.source_vivo
import top.app.market.resources.source_wandoujia
import top.app.market.resources.source_xiaomi
import org.jetbrains.compose.resources.stringResource

@Composable
fun appSourceLabel(source: AppSource): String = when (source) {
    AppSource.XIAOMI -> stringResource(Res.string.source_xiaomi)
    AppSource.VIVO -> stringResource(Res.string.source_vivo)
    AppSource.WANDOUJIA -> stringResource(Res.string.source_wandoujia)
    AppSource.OPPO -> stringResource(Res.string.source_oppo)
    AppSource.SAMSUNG -> stringResource(Res.string.source_samsung)
    AppSource.HONOR -> stringResource(Res.string.source_honor)
    AppSource.HUAWEI -> stringResource(Res.string.source_huawei)
    AppSource.TAPTAP -> stringResource(Res.string.source_taptap)
    AppSource.BOX7723 -> stringResource(Res.string.source_box7723)
}
