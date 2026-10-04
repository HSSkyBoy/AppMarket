package top.app.market.ui.util

import androidx.compose.runtime.Composable
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.download
import top.app.market.resources.install
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** 平台不支持直接安装（桌面端）时，「安装」动作实际是保存安装包，文案改用「下载」。 */
@Composable
fun installActionText(): String {
    val uiPlatform = koinInject<UiPlatform>()
    return stringResource(
        if (uiPlatform.packageInstallationSupported) Res.string.install else Res.string.download,
    )
}
