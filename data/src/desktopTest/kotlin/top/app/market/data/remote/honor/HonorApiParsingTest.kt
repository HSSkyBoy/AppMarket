package top.app.market.data.remote.honor

import top.app.market.data.remote.xiaomi.LenientJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HonorApiParsingTest {
    @Test
    fun downloadPurposesUseOfficialGenericDispatcherBusinesses() {
        assertEquals("AppmarketActiveDownload", HonorDownloadPurpose.ACTIVE.dispatchBiz)
        assertEquals("AppmarketUpdateDownload", HonorDownloadPurpose.UPDATE.dispatchBiz)
    }

    @Test
    fun backgroundUpdaterUsesOfficialRegionalBgApiDomains() {
        assertEquals(
            "https://appmarket-bgapi-drcn.hispace.hihonorcloud.com",
            honorBackgroundApiBase("cn"),
        )
        assertEquals(
            "https://appmarket-bgapi-drru.hispace.hihonorcloud.com",
            honorBackgroundApiBase("RU"),
        )
        assertEquals(
            "https://appmarket-bgapi-dre.hispace.hihonorcloud.com",
            honorBackgroundApiBase("DE"),
        )
        assertEquals(
            "https://appmarket-bgapi-dra.hispace.hihonorcloud.com",
            honorBackgroundApiBase("SG"),
        )
    }

    @Test
    fun legacyPolicyStringsAreMergedAndNormalized() {
        val policies = parseHonorUpdatePolicies(
            json(
                """
                {
                  "systemAppWhiteList": "Com.Example.System, com.example.shared",
                  "updateAppWhiteList": "com.example.update,com.example.shared"
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            setOf("com.example.system", "com.example.shared", "com.example.update"),
            policies.keys,
        )
        assertTrue(policies.values.all(HonorSystemUpdatePolicy::displayOnUpdatePage))
        assertTrue(policies.values.none(HonorSystemUpdatePolicy::priorityUpdate))
    }

    @Test
    fun newPolicyArrayOverridesLegacyDefaults() {
        val policies = parseHonorUpdatePolicies(
            json(
                """
                {
                  "systemAppWhiteList": "com.example.system",
                  "newUpdateAppWhiteList": [
                    {
                      "packageName": "COM.EXAMPLE.SYSTEM",
                      "displayUpdatePageFlag": false,
                      "priorityUpdateFlag": 1
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val policy = policies.getValue("com.example.system")
        assertFalse(policy.displayOnUpdatePage)
        assertTrue(policy.priorityUpdate)
    }

    @Test
    fun associationParserReturnsOnlyExactServerCandidatesWithoutVersionData() {
        val records = exactHonorAssociationApps(
            json(
                """
                {
                  "data": {
                    "assemblyVOList": [
                      {
                        "appList": [
                          {
                            "id": 42,
                            "pName": "com.example.directory",
                            "name": "Directory",
                            "imgUrl": "https://cdn.example/icon.webp",
                            "stars": 4.3,
                            "downTm": 42042375
                          },
                          {
                            "id": 43,
                            "pName": "com.example.unrelated",
                            "name": "Unrelated"
                          }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
            "directory",
        )

        assertEquals(listOf("com.example.directory"), records.map { it.packageName })
        assertEquals(0L, records.single().versionCode)
        assertEquals(4.3, records.single().toApp().ratingScore)
        assertEquals(42042375L, records.single().toApp().downloadCount)
    }

    private fun json(value: String) = LenientJson.parseToJsonElement(value) as JsonObject
}
