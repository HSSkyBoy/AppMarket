package top.app.market.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import top.app.market.domain.model.update.AppReleaseInfo
import top.app.market.resources.Res
import top.app.market.resources.update_dialog_title
import top.app.market.resources.update_download_mirror
import top.app.market.resources.update_download_official
import top.app.market.resources.update_later
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AppUpdateDialog(
    releaseInfo: AppReleaseInfo?,
    onDownload: (url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (releaseInfo == null) return

    WindowDialog(
        show = true,
        title = stringResource(Res.string.update_dialog_title, releaseInfo.versionName),
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = releaseInfo.releaseNotes.ifBlank { "修復已知問題並最佳化體驗" },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            AppButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.update_download_mirror),
                onClick = {
                    onDownload(releaseInfo.mirrorDownloadUrl)
                    onDismiss()
                },
            )

            AppButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.update_download_official),
                onClick = {
                    onDownload(releaseInfo.downloadUrl)
                    onDismiss()
                },
            )

            AppTextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.update_later),
                onClick = onDismiss,
            )
        }
    }
}
