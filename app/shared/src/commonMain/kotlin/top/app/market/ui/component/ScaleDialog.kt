package top.app.market.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.app.market.resources.Res
import top.app.market.resources.cancel
import top.app.market.resources.confirm
import top.app.market.resources.theme_page_scale
import top.app.market.resources.theme_page_scale_range
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ScaleDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    scaleProvider: () -> Float,
    onScaleChange: (Float) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(Res.string.theme_page_scale),
        summary = stringResource(Res.string.theme_page_scale_range),
        onDismissRequest = onDismissRequest,
    ) {
        var text by remember(show) { mutableStateOf((scaleProvider() * 100).toInt().toString()) }
        TextField(
            modifier = Modifier.padding(bottom = 16.dp),
            value = text,
            maxLines = 1,
            trailingIcon = {
                Text(
                    text = "%",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            onValueChange = { newValue ->
                if (newValue.isEmpty() || newValue.all(Char::isDigit)) text = newValue
            },
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.cancel),
                onClick = onDismissRequest,
            )
            Spacer(Modifier.width(20.dp))
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    val value = text.toIntOrNull()?.coerceIn(80, 110)
                        ?: (scaleProvider() * 100).toInt()
                    onScaleChange(value / 100f)
                    onDismissRequest()
                },
            )
        }
    }
}
