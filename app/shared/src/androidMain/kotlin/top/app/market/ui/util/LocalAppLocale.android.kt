package top.app.market.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

actual object LocalAppLocale {
    private var defaultLocale: Locale? = null

    actual val current: String
        @Composable get() = Locale.getDefault().toLanguageTag()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        val context = LocalContext.current

        if (defaultLocale == null) {
            defaultLocale = Locale.getDefault()
        }

        val targetLocale = when {
            value.isNullOrBlank() -> defaultLocale!!
            else -> Locale.forLanguageTag(value)
        }

        Locale.setDefault(targetLocale)
        configuration.setLocale(targetLocale)
        val resources = context.resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)

        return LocalConfiguration.provides(configuration)
    }
}
