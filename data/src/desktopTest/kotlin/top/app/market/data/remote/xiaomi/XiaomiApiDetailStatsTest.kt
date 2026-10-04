package top.app.market.data.remote.xiaomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class XiaomiApiDetailStatsTest {

    @Test
    fun ratingTotalCountTakesPriority() {
        val appInfo = Json.parseToJsonElement(
            """{"ratingTotalCount":53131,"commentCount":42}"""
        ).jsonObject

        assertEquals(53_131L, parseDetailRatingCount(appInfo))
    }

    @Test
    fun commentHeaderCardIsUsedAsFallback() {
        val appInfo = Json.parseToJsonElement(
            """
            {
              "headerCardInfos": [
                {"type":"downloadCount","topValue":"10000"},
                {"type":"comment","topValue":"3.5","bottomValue":"53131"}
              ]
            }
            """.trimIndent()
        ).jsonObject

        assertEquals(53_131L, parseDetailRatingCount(appInfo))
    }

    @Test
    fun ageClassificationFieldTakesPriority() {
        val appInfo = Json.parseToJsonElement(
            """{"ageClassification":"18+","extraData":{"gameAgeRating":2}}"""
        ).jsonObject

        assertEquals("18+", parseAgeClassification(appInfo))
    }

    @Test
    fun gameAgeRatingMapsToLabelWhenClassificationMissing() {
        for ((rating, expected) in mapOf(0 to "", 1 to "8+", 2 to "12+", 3 to "16+")) {
            val appInfo = Json.parseToJsonElement(
                """{"extraData":{"gameAgeRating":$rating}}"""
            ).jsonObject

            assertEquals(expected, parseAgeClassification(appInfo))
        }
    }
}
