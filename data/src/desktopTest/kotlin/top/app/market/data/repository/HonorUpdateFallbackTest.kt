package top.app.market.data.repository

import top.app.market.data.remote.honor.HonorAppRecord
import top.app.market.data.remote.honor.HonorSystemUpdatePolicy
import top.app.market.domain.model.installed.InstalledPackage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HonorUpdateFallbackTest {
    @Test
    fun selectsOnlyConfiguredPackagesMissingFromBatchResponse() {
        val installed = listOf(
            installed("com.example.hidden"),
            installed("com.example.returned"),
            installed("com.example.unlisted"),
        )
        val policies = mapOf(
            "com.example.hidden" to policy(),
            "com.example.returned" to policy(),
        )
        val batch = listOf(record("com.example.returned"))

        val candidates = honorDetailSupplementCandidates(installed, policies, batch)

        assertEquals(listOf("com.example.hidden"), candidates.map { it.packageName })
    }

    @Test
    fun packageMatchingIsCaseInsensitive() {
        val candidates = honorDetailSupplementCandidates(
            installed = listOf(installed("Com.Example.Hidden")),
            policies = mapOf("com.example.hidden" to policy()),
            batchRecords = emptyList(),
        )

        assertEquals(1, candidates.size)
    }

    private fun installed(packageName: String) = InstalledPackage(
        packageName = packageName,
        versionCode = 1L,
        versionName = "1.0",
        isSystemApp = false,
    )

    private fun record(packageName: String) = HonorAppRecord(
        buildJsonObject {
            put("pName", packageName)
            put("verCode", 2)
            put("verName", "2.0")
        },
    )

    private fun policy() = HonorSystemUpdatePolicy(
        displayOnUpdatePage = true,
        priorityUpdate = false,
    )
}
