package top.app.market.data.remote.xiaomi

import kotlin.test.Test
import kotlin.test.assertEquals

class ResolvedUrlTest {
    @Test
    fun absoluteUrlIsReturnedVerbatim() {
        val host = "https://pfga-accelerate.market.xiaomi.com/download/"
        val absolute = "https://pfga-accelerate.market.xiaomi.com/package/AppStore/abc/payload/md5"

        assertEquals(absolute, resolvedUrl(host, absolute))
    }

    @Test
    fun relativePathJoinsWithSingleSlash() {
        assertEquals(
            "https://fgb0.market.xiaomi.com/download/AppStore/base.apk",
            resolvedUrl("https://fgb0.market.xiaomi.com/download/", "/AppStore/base.apk"),
        )
        assertEquals(
            "https://fgb0.market.xiaomi.com/download/AppStore/base.apk",
            resolvedUrl("https://fgb0.market.xiaomi.com/download", "AppStore/base.apk"),
        )
    }

    @Test
    fun emptyOperandsFallBackToTheOther() {
        assertEquals("AppStore/base.apk", resolvedUrl("", "AppStore/base.apk"))
        assertEquals("https://host/download/", resolvedUrl("https://host/download/", ""))
    }
}
