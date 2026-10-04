package top.app.market.ui.component.blur

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode

internal object ColorBlendToken {

    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF.toInt()), BlurBlendMode.HardLight),
    )

    val Overlay_Extra_Thin_Dark = listOf(
        BlendColorEntry(Color(0x757A7A7A), BlurBlendMode.Luminosity),
    )
}
