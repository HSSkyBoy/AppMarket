package top.app.market.data.remote.vivo

import top.app.market.data.repository.isVivoHash32
import top.app.market.data.repository.matchesVivoKeyword
import top.app.market.data.repository.mergeVivoSearchItems
import top.app.market.data.repository.mergeVivoUpdates
import top.app.market.data.repository.vivoHash32FromMd5
import top.app.market.data.repository.vivoSelfPatch
import top.app.market.data.repository.vivoSelfUpdateCandidates
import top.app.market.data.repository.vivoUpdateCandidates
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.profile.MarketProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VivoUpdateAuroraParseTest {
    @Test
    fun systemAppsAreSerializedWithoutOptionpkgFiltering() {
        val gallery = InstalledPackage(
            packageName = "com.android.gallery3d",
            versionCode = 40030,
            versionName = "1.1.40030",
            isSystemApp = true,
            displayName = "图库",
        )
        val hiboard = InstalledPackage(
            packageName = "com.vivo.hiboard",
            versionCode = 77415000,
            versionName = "7.74.15.0",
            isSystemApp = true,
            displayName = "智慧桌面",
        )

        assertEquals(listOf(gallery, hiboard), vivoUpdateCandidates(listOf(gallery, hiboard)))
        assertEquals(
            "com.android.gallery3d|40030|0,com.vivo.hiboard|77415000|0",
            vivoPackagesValue(listOf(gallery, hiboard)),
        )
        assertEquals(listOf(gallery, hiboard), vivoSelfUpdateCandidates(listOf(gallery, hiboard)))
    }

    @Test
    fun selfUpgradeResponseAndMandatoryPlaintextParamsAreParsed() {
        val entry = parseVivoSelfUpdateResponse(
            parseVivoObject(
                """
            {"retcode":0,"message":"operate success","data":{
              "pkgName":"com.vivo.hiboard","versionCode":78005000,"versionName":"7.80.5.0",
              "downloadUrl":"https://appupgrade.vivo.com.cn/appDownload?releaseId=44360",
              "apkSize":48819180,"apkMd5":"26afdb22c20f135a0a19830ba156a330",
              "apkSha256":"4121f843af5c4c116956248e24608cad700dbab43e02d82a0cf7c68aa7bafe30",
              "patch":"v3_78005000_77415000:19281:1508331082:96109337",
              "patchSize":19744217,"patchMd5":"23629ee6c630663593b6e8153858f6be",
              "patchSha256":"2fbaf45c61af45652c869edaf12970974d2c52b04d92547941c27f99b1bb15a2",
              "notifyContent":"修复已知问题。","updateDate":1785850022000}}
        """.trimIndent()
            )
        )

        assertEquals("com.vivo.hiboard", entry?.packageName)
        assertEquals(78005000L, entry?.versionCode)
        assertEquals(48819180L, entry?.size)
        assertEquals(64, entry?.sha256?.length)
        assertEquals(19744217L, entry?.patchSize)
        assertEquals(64, entry?.patchSha256?.length)
        assertEquals(
            null, parseVivoSelfUpdateResponse(
                parseVivoObject(
                    """{"retcode":0,"message":"operate success","data":null}"""
                )
            )
        )

        val params = vivoSelfUpdateParams(
            installed = InstalledPackage(
                packageName = "com.vivo.hiboard",
                versionCode = 77415000,
                versionName = "7.74.15.0",
                isSystemApp = false,
            ),
            profile = vivoProfile(),
            manual = true,
        )
        assertEquals("V2408A", params["model"])
        assertEquals("36", params["av"])
        assertEquals("16", params["an"])
        assertEquals("6650", params["sdkVersion"])
        assertEquals("", params["vaid"])
        assertEquals("", params["imei"])
        assertEquals("[arm64-v8a]", params["abiList"])
        assertEquals("1", params["manual"])
        assertEquals("compiler260710223223", params["build_number"])
        assertEquals("000000", params["appSha256"])
        assertEquals("0", params["supPatch"])
        assertEquals("0", params["supPadding"])
        listOf("elapsedtime", "nt", "appSha256", "supPatch", "supPadding").forEach {
            assertTrue(params.containsKey(it), "$it is mandatory for appSelfUpgrade")
        }

        val patchParams = vivoSelfUpdateParams(
            installed = InstalledPackage(
                packageName = "com.vivo.hiboard",
                versionCode = 77415000,
                versionName = "7.74.15.0",
                isSystemApp = false,
            ),
            profile = vivoProfile(),
            manual = false,
            appSha256 = "61687e0abd8365213b2bd3cd591b4fae17a7df1bccb1070ff9ddc0dc2c0c1ecb",
        )
        assertEquals("61687e0abd8365213b2bd3cd591b4fae17a7df1bccb1070ff9ddc0dc2c0c1ecb", patchParams["appSha256"])
        assertEquals("3", patchParams["supPatch"])
        assertEquals("1", patchParams["supPadding"])
    }

    @Test
    fun selfUpgradePatchBecomesAValidSfPat21Download() {
        val entry = requireNotNull(
            parseVivoSelfUpdateResponse(
                parseVivoObject(
                    """
            {"retcode":0,"data":{
              "pkgName":"com.vivo.hiboard","versionCode":78005000,"versionName":"7.80.5.0",
              "downloadUrl":"https://appupgrade.vivo.com.cn/appDownload?releaseId=44360&pkgName=com.vivo.hiboard",
              "apkSize":48819180,"apkMd5":"26afdb22c20f135a0a19830ba156a330",
              "apkSha256":"4121f843af5c4c116956248e24608cad700dbab43e02d82a0cf7c68aa7bafe30",
              "patch":"v3_78005000_77415000:19281:1508331082:96109337",
              "patchSize":19744217,"patchMd5":"23629ee6c630663593b6e8153858f6be",
              "patchSha256":"2fbaf45c61af45652c869edaf12970974d2c52b04d92547941c27f99b1bb15a2"}}
        """.trimIndent()
                )
            )
        )
        val local = InstalledPackage(
            packageName = "com.vivo.hiboard",
            versionCode = 77415000,
            versionName = "7.74.15.0",
            isSystemApp = false,
            oldApkHash = "96109337",
            baseApkPath = "/data/app/com.vivo.hiboard/base.apk",
        )

        val patch = requireNotNull(vivoSelfPatch(entry, local))
        assertEquals(19744217L, patch.size)
        assertEquals(3, patch.version)
        assertEquals("sfpatch", patch.protocol)
        assertEquals(entry.patchSha256, patch.hash)
        assertTrue(patch.url.contains("patchFullInfo=v3_78005000_77415000%3A19281%3A1508331082%3A96109337"))
        assertEquals(null, vivoSelfPatch(entry, local.copy(oldApkHash = "1")))
    }

    @Test
    fun vivoDownloadRedirectIsUpgradedOnlyForVivoHosts() {
        assertEquals(
            "https://apkselftxdl.vivo.com.cn/appupgrade/patch/example.patch",
            secureVivoDownloadUrl("http://apkselftxdl.vivo.com.cn/appupgrade/patch/example.patch"),
        )
        assertEquals(null, secureVivoDownloadUrl("http://example.com/example.patch"))
        assertEquals(null, secureVivoDownloadUrl("not a URL"))
    }

    @Test
    fun selfUpgradeWinsWhenSystemAndStoreEndpointsReturnTheSamePackage() {
        fun app(version: Long, link: String) = MarketAppInfo(
            appId = 1,
            packageName = "com.vivo.hiboard",
            displayName = "智慧桌面",
            publisherName = "",
            versionName = version.toString(),
            versionCode = version,
            icon = "",
            apkSize = 1,
            ratingScore = 0.0,
            openLink = link,
            source = AppSource.VIVO,
        )

        val merged = mergeVivoUpdates(
            regular = listOf(app(6162110, "https://appstore.vivo.com.cn/old")),
            selfUpdates = listOf(app(78005000, "https://appupgrade.vivo.com.cn/new")),
        )
        assertEquals(78005000L, merged.single().versionCode)
    }

    @Test
    fun hiddenCatalogSearchMatchesServerLabelAndPackageName() {
        val snapshot = parseVivoPackageSnapshot(
            """<Package info="1"><package_name><![CDATA[com.vivo.hiboard]]></package_name><title_zh><![CDATA[智慧桌面]]></title_zh></Package>""",
            "com.vivo.hiboard",
        )
        requireNotNull(snapshot)
        assertTrue(snapshot.matchesVivoKeyword("智慧桌面"))
        assertTrue(snapshot.matchesVivoKeyword("VIVO.HIBOARD"))
        assertFalse(snapshot.matchesVivoKeyword("相册"))
        assertFalse(snapshot.matchesVivoKeyword("  "))
    }

    @Test
    fun serverCatalogueMatchesAreShownFirstWithoutDroppingNativePromotion() {
        fun app(packageName: String, isAd: Boolean = false) = MarketAppInfo(
            appId = 1L,
            packageName = packageName,
            displayName = packageName,
            publisherName = "",
            versionName = "1",
            versionCode = 1L,
            icon = "",
            apkSize = 1L,
            ratingScore = 0.0,
            isAd = isAd,
            source = AppSource.VIVO,
        )

        val merged = mergeVivoSearchItems(
            native = listOf(app("com.vivo.gallery", isAd = true)),
            serverCatalogue = listOf(app("com.vivo.gallery"), app("com.vivo.hiboard")),
            removeAds = false,
        )
        assertEquals(listOf("com.vivo.gallery", "com.vivo.hiboard"), merged.map { it.packageName })
        assertTrue(merged.first().isAd)
        assertEquals(
            listOf("com.vivo.hiboard"),
            mergeVivoSearchItems(
                native = listOf(app("com.vivo.gallery", isAd = true)),
                serverCatalogue = listOf(app("com.vivo.gallery"), app("com.vivo.hiboard")),
                removeAds = true,
            ).map { it.packageName },
        )
    }

    @Test
    fun supportCatalogueParsesServerPackagesWithoutLocalWhitelist() {
        val packages = parseVivoSupportPackages(
            parseVivoObject(
                """{"retcode":0,"data":[{"pkgName":"com.vivo.gallery","versionCode":100305020,"tabletVersionCode":0},{"pkgName":"com.example.other","versionCode":2}]}"""
            )
        )
        assertEquals(listOf("com.vivo.gallery", "com.example.other"), packages.map { it.packageName })
    }

    @Test
    fun updateValueAndSummaryDescriptorsAreParsed() {
        val updates = parseVivoUpdateResponse(
            parseVivoObject(
                """
            {"code":0,"value":[{"id":40413,"package_name":"com.tencent.mm","title_zh":"微信",
            "icon_url":"http://img.example/icon.png","version_name":"8.0.76","version_code":"3141",
            "size":259865,"originalMd5":"70278477978e85acb5a88c610b7582d7","update_des":"修复",
            "patchs":"","sfPatchs":"","download_url":"http://download"}]}
        """.trimIndent()
            )
        )
        assertEquals(1, updates.size)
        assertEquals("com.tencent.mm", updates.single().packageName)
        assertEquals("70278477978e85acb5a88c610b7582d7", updates.single().md5)

        val descriptors = parseVivoPatchDescriptors(
            "v3_3141_3140:4224:2022897939:3686944289,v3_3141_3060:154528:1107493213:3543661155"
        )
        assertEquals(2, descriptors.size)
        assertEquals(3, descriptors[0].plan)
        assertEquals(3140L, descriptors[0].baseVersion)
        assertEquals(3686944289L, descriptors[0].oldApkHash)
        assertEquals(
            descriptors[0],
            selectVivoPatch(
                "v3_3141_3140:4224:2022897939:3686944289",
                targetVersion = 3141L,
                baseVersion = 3140L,
                oldApkHash = 3686944289L,
            ),
        )
    }

    @Test
    fun vivoHash32MatchesTheStoreFormula() {
        assertEquals(1295454733L, vivoHash32FromMd5("70278477978e85acb5a88c610b7582d7"))
        assertEquals(2517553488L, vivoHash32FromMd5("7b8db481b01c4146e5f2900aaeb4a39a"))
        assertEquals(null, vivoHash32FromMd5("not-md5"))
        assertFalse("0".isVivoHash32())
        assertTrue("2517553488".isVivoHash32())
    }

    @Test
    fun auroraPagePreservesPeriodAndAppOrder() {
        val page = parseVivoAuroraPage(
            parseVivoObject(
                """
            {"result":"true","value":{"list":[{"numberId":590,"data":[
              {"appType":0,"backgroundPic":["http://img.example/bg.jpg"],"app":{"id":1,"package_name":"a.b","title_zh":"A","version_code":"2"}},
              {"appType":1,"backgroundPic":["http://img.example/bg2.jpg"],"app":{"id":2,"package_name":"c.d","title_zh":"B","version_code":"3"}}
            ]}]}}
        """.trimIndent()
            )
        )
        assertEquals(1, page.size)
        assertEquals(590L, page.single().numberId)
        assertEquals(listOf("a.b", "c.d"), page.single().apps.map { it.app.packageName })
        assertTrue(page.single().apps.first().coverImage.startsWith("https://"))
        assertEquals(AppSource.VIVO, page.single().apps.first().app.source)
    }

    @Test
    fun auroraAppDownloadLinkIsNormalized() {
        val app = parseVivoAuroraApp(
            parseVivoObject(
                """
            {"id":1,"package_name":"a.b","title_zh":"A","version_code":"2",
             "download_url":"http://appstore.vivo.com.cn/appinfo/downloadApkFile?id=1"}
        """.trimIndent()
            )
        )
        assertEquals("https://appstore.vivo.com.cn/appinfo/downloadApkFile?id=1", app?.openLink)
    }

    private fun vivoProfile() = MarketProfile(
        co = "CN",
        la = "zh",
        lo = "CN",
        cpuArchitecture = "arm64-v8a",
        device = "PD2408",
        model = "V2408A",
        os = "compiler260710223223",
        osV2 = "compiler260710223223",
        androidVersion = "16",
        sdk = "36",
        resolution = "1440*2560",
        densityDpi = "640",
        densityScaleFactor = "4.0",
        miuiBigVersionCode = "",
        miuiBigVersionName = "",
        osBigVersionCode = "",
        osBigVersionName = "",
        marketVersion = "61510",
        pageConfigVersion = "18411801",
        webResVersion = "3211",
        hybridFrameworkVersion = "",
        buildId = "V417IR",
        instanceId = "test",
        hasGMSCore = "true",
        supportedIslandVersion = "",
    )
}
