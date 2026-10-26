package top.app.market.ui.component

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppButtonContrastTest {

    @Test
    fun testContrastRatioCalculation() {
        val black = Color.Black
        val white = Color.White
        val ratio = contrastRatio(black, white)
        // (1.0 + 0.05) / (0.0 + 0.05) = 21.0
        assertEquals(21.0f, ratio, 0.01f)
    }

    @Test
    fun testMonetDarkModeFillTextIsDarkOnLightPrimary() {
        // In Monet dark mode, primary is light pastel (e.g. lavender ~0.57 luminance)
        // and onPrimary is dark purple/slate (~0.04 luminance).
        val monetDarkPrimary = Color(0xFFD0BCFF)
        val monetDarkOnPrimary = Color(0xFF381E72)

        val fillTextColor = contrastColor(
            foreground = monetDarkOnPrimary,
            background = monetDarkPrimary,
        )

        // Should use onPrimary (dark color), giving high contrast (> 7:1) over light primary background
        assertEquals(monetDarkOnPrimary, fillTextColor)
        assertTrue(contrastRatio(fillTextColor, monetDarkPrimary) >= 3.0f)
    }

    @Test
    fun testLightModeFillTextIsWhiteOnDarkPrimary() {
        // In standard light mode, primary is dark/saturated (e.g. #007AFF) and onPrimary is White
        val lightPrimary = Color(0xFF007AFF)
        val lightOnPrimary = Color.White

        val fillTextColor = contrastColor(
            foreground = lightOnPrimary,
            background = lightPrimary,
        )

        assertEquals(Color.White, fillTextColor)
        assertTrue(contrastRatio(fillTextColor, lightPrimary) >= 3.0f)
    }

    @Test
    fun testMonetDarkModeBaseTextHasHighContrastOnDarkContainer() {
        val monetDarkPrimary = Color(0xFFD0BCFF)
        val darkSurface = Color(0xFF141218)
        val baseContainer = monetDarkPrimary.copy(alpha = 0.2f)

        // Base over surface
        val baseOverSurface = Color(
            red = monetDarkPrimary.red * 0.2f + darkSurface.red * 0.8f,
            green = monetDarkPrimary.green * 0.2f + darkSurface.green * 0.8f,
            blue = monetDarkPrimary.blue * 0.2f + darkSurface.blue * 0.8f,
        )

        val baseTextColor = contrastColor(
            foreground = monetDarkPrimary,
            background = baseOverSurface,
        )

        // Light lavender has sufficient contrast over the dark container
        assertEquals(monetDarkPrimary, baseTextColor)
        assertTrue(contrastRatio(baseTextColor, baseOverSurface) >= 3.0f)
    }

    @Test
    fun testFallbackToWhiteWhenForegroundLacksContrastOnDarkBackground() {
        // Dark blue on dark background
        val darkPrimary = Color(0xFF102040)
        val darkBackground = Color(0xFF0A0A0A)

        val resolvedColor = contrastColor(
            foreground = darkPrimary,
            background = darkBackground,
        )

        // Must fall back to White
        assertEquals(Color.White, resolvedColor)
        assertTrue(contrastRatio(resolvedColor, darkBackground) >= 3.0f)
    }

    @Test
    fun testFallbackToBlackWhenForegroundLacksContrastOnLightBackground() {
        // Light yellow on white background
        val lightYellow = Color(0xFFFFFFE0)
        val whiteBackground = Color.White

        val resolvedColor = contrastColor(
            foreground = lightYellow,
            background = whiteBackground,
        )

        // Must fall back to Black
        assertEquals(Color.Black, resolvedColor)
        assertTrue(contrastRatio(resolvedColor, whiteBackground) >= 3.0f)
    }
}
