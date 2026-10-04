package top.app.market.data.remote.xiaomi

import top.app.market.domain.exception.AppNotListedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class XiaomiApiExternalDetailTest {

    @Test
    fun externalDetailParamsMatchOfficialMarketRequest() {
        val query = "id=com.baidu.carlife.xiaomi"

        val params = externalDetailRequestParams(query)

        assertEquals("true", params["needInnovateDmConfig"])
        assertEquals("true", params["checkPermission"])
        assertEquals(query, params["deeplinkParams"])
        assertEquals("true", params["needUrlDecode"])
        assertEquals("2", params["supportFloatCard"])
        assertEquals("2", params["supportExpType"])
    }

    @Test
    fun emptyExternalQueryDoesNotSendDecodeFields() {
        val params = externalDetailRequestParams("")

        assertFalse("deeplinkParams" in params)
        assertFalse("needUrlDecode" in params)
        assertEquals("true", params["checkPermission"])
    }

    @Test
    fun responseWithoutAppIdIsNotListed() {
        val error = assertFailsWith<AppNotListedException> {
            requireListedAppInfo(json("""{"appInfo":{},"errorMessage":"应用不存在"}"""))
        }

        assertEquals("应用不存在", error.message)
    }

    @Test
    fun responseWithAppIdReturnsAppInfo() {
        val appInfo = requireListedAppInfo(
            json("""{"appInfo":{"appId":"123","packageName":"com.example.app"}}""")
        )

        assertEquals(123L, appInfo.long("appId"))
        assertEquals("com.example.app", appInfo.str("packageName"))
    }

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
