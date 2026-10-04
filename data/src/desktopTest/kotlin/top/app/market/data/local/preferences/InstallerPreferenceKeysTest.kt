package top.app.market.data.local.preferences

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerPreferenceKeysTest {
    @Test
    fun deltaUpdatesDefaultToEnabled() {
        assertTrue(InstallerPreferenceKeys.DeltaUpdate.default)
    }

    @Test
    fun userActionNotRequiredDefaultsToDisabled() {
        assertFalse(InstallerPreferenceKeys.UserActionNotRequired.default)
    }
}
