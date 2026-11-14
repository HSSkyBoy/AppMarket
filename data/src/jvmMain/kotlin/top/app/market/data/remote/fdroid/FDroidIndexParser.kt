package top.app.market.data.remote.fdroid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.Reader

private const val ZhLocale = "zh-CN"
private val EnLocales = listOf("en-US", "en")

/**
 * 流式解析 index-v1.json（约 70MB）：只逐个读出 `apps` / `packages` 的单个元素再交给 Json 解析，
 * 峰值内存与单个应用大小同级，不会把整份索引读成树。
 */
internal object FDroidIndexParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(reader: Reader): FDroidIndex {
        val scanner = JsonStreamScanner(reader)
        val apps = LinkedHashMap<String, AppDraft>()

        scanner.expect('{')
        while (true) {
            val next = scanner.skipWs()
            if (next == -1 || next == '}'.code) break
            if (next == ','.code) {
                scanner.next()
                continue
            }
            when (scanner.readKey()) {
                "apps" -> scanner.forEachArrayElement { raw ->
                    val draft = draftOf(json.parseToJsonElement(raw) as JsonObject) ?: return@forEachArrayElement
                    apps[draft.packageName] = draft
                }
                "packages" -> scanner.forEachObjectEntry { pkg, raw ->
                    val draft = apps[pkg] ?: return@forEachObjectEntry
                    applyVersion(draft, json.parseToJsonElement(raw) as? JsonArray)
                }
                else -> scanner.skipValue()
            }
        }
        return FDroidIndex(apps.values.map { it.toApp() })
    }

    /** 精简索引的落盘格式：每行一个应用的 JSON，读取时同样逐行解析。 */
    fun encode(app: FDroidIndexApp): String = buildJsonObject {
        put("p", app.packageName)
        put("i", app.icon)
        put("vn", app.versionName)
        put("vc", app.versionCode)
        put("a", app.apkName)
        put("s", app.apkSize)
        put("t", app.lastUpdated)
        put("h", app.apkHash)
        put("sg", app.signer)
        app.zh?.let { put("zh", encodeText(it)) }
        app.en?.let { put("en", encodeText(it)) }
    }.toString()

    fun decode(line: String): FDroidIndexApp {
        val o = json.parseToJsonElement(line) as JsonObject
        return FDroidIndexApp(
            packageName = o.str("p"),
            icon = o.str("i"),
            versionName = o.str("vn"),
            versionCode = o.long("vc"),
            apkName = o.str("a"),
            apkSize = o.long("s"),
            lastUpdated = o.long("t"),
            apkHash = o.str("h"),
            signer = o.str("sg"),
            zh = (o["zh"] as? JsonObject)?.let(::decodeText),
            en = (o["en"] as? JsonObject)?.let(::decodeText),
        )
    }

    private fun encodeText(t: FDroidLocalizedText) = buildJsonObject {
        put("l", t.locale)
        put("n", t.name)
        put("s", t.summary)
        put("d", t.description)
        put("w", t.whatsNew)
        put("i", t.icon)
        put("ss", buildJsonArray { t.screenshots.forEach { add(JsonPrimitive(it)) } })
    }

    private fun decodeText(o: JsonObject) = FDroidLocalizedText(
        locale = o.str("l"),
        name = o.str("n"),
        summary = o.str("s"),
        description = o.str("d"),
        whatsNew = o.str("w"),
        icon = o.str("i"),
        screenshots = o.strings("ss"),
    )

    private class AppDraft(
        val packageName: String,
        val icon: String,
        val lastUpdated: Long,
        val suggestedVersionCode: Long,
        val zh: FDroidLocalizedText?,
        val en: FDroidLocalizedText?,
    ) {
        var versionName: String = ""
        var versionCode: Long = 0L
        var apkName: String = ""
        var apkSize: Long = 0L
        var apkHash: String = ""
        var signer: String = ""

        fun toApp() = FDroidIndexApp(
            apkHash = apkHash,
            signer = signer,
            packageName = packageName,
            icon = icon,
            versionName = versionName,
            versionCode = versionCode,
            apkName = apkName,
            apkSize = apkSize,
            lastUpdated = lastUpdated,
            zh = zh,
            en = en,
        )
    }

    private fun draftOf(app: JsonObject): AppDraft? {
        val pkg = app.str("packageName").ifBlank { return null }
        val localized = app["localized"] as? JsonObject
        val zh = localizedText(localized, listOf(ZhLocale))
        // 没有英文时取任意一种语言兜底，保证至少有名称可展示
        val en = localizedText(localized, EnLocales)
            ?: localized?.keys?.firstOrNull { it != ZhLocale }?.let { localizedText(localized, listOf(it)) }
        return AppDraft(
            packageName = pkg,
            icon = app.str("icon"),
            lastUpdated = app.long("lastUpdated"),
            suggestedVersionCode = app.long("suggestedVersionCode"),
            zh = zh,
            en = en,
        )
    }

    private fun localizedText(localized: JsonObject?, locales: List<String>): FDroidLocalizedText? {
        localized ?: return null
        for (locale in locales) {
            val o = localized[locale] as? JsonObject ?: continue
            return FDroidLocalizedText(
                locale = locale,
                name = o.str("name"),
                summary = o.str("summary"),
                description = o.str("description"),
                whatsNew = o.str("whatsNew"),
                icon = o.str("icon"),
                screenshots = o.strings("phoneScreenshots"),
            )
        }
        return null
    }

    /** 版本按新到旧排列：优先取推荐版本，找不到则取最新一条。 */
    private fun applyVersion(draft: AppDraft, versions: JsonArray?) {
        val list = versions?.mapNotNull { it as? JsonObject }.orEmpty()
        val picked = list.firstOrNull { it.long("versionCode") == draft.suggestedVersionCode }
            ?: list.firstOrNull()
            ?: return
        draft.versionName = picked.str("versionName")
        draft.versionCode = picked.long("versionCode")
        draft.apkName = picked.str("apkName")
        draft.apkSize = picked.long("size")
        if (picked.str("hashType").equals("sha256", ignoreCase = true)) {
            draft.apkHash = picked.str("hash").lowercase()
        }
        draft.signer = picked.str("signer").lowercase()
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    // suggestedVersionCode 在索引里是字符串，versionCode 是数字，统一按内容解析
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: 0L

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

/** 只负责定位 JSON 值的边界（识别字符串与转义、括号配对），值本身交给 kotlinx.serialization 解析。 */
internal class JsonStreamScanner(private val reader: Reader) {
    private var peeked = NONE

    fun peek(): Int {
        if (peeked == NONE) peeked = reader.read()
        return peeked
    }

    fun next(): Int = peek().also { peeked = NONE }

    fun skipWs(): Int {
        while (true) {
            val c = peek()
            if (c == -1 || !c.toChar().isWhitespace()) return c
            next()
        }
    }

    fun expect(ch: Char) {
        val c = skipWs()
        check(c == ch.code) { "Malformed index: expected '$ch' but found ${if (c == -1) "EOF" else "'${c.toChar()}'"}" }
        next()
    }

    /** 读取对象键，消耗其后的冒号。 */
    fun readKey(): String {
        val raw = StringBuilder()
        readValue(raw)
        expect(':')
        return Json.parseToJsonElement(raw.toString()).jsonPrimitive.content
    }

    fun skipValue() = readValue(null)

    fun forEachArrayElement(block: (raw: String) -> Unit) {
        expect('[')
        while (true) {
            when (skipWs()) {
                -1 -> error("Malformed index: unterminated array")
                ']'.code -> { next(); return }
                ','.code -> next()
                else -> block(StringBuilder().also(::readValue).toString())
            }
        }
    }

    fun forEachObjectEntry(block: (key: String, raw: String) -> Unit) {
        expect('{')
        while (true) {
            when (skipWs()) {
                -1 -> error("Malformed index: unterminated object")
                '}'.code -> { next(); return }
                ','.code -> next()
                else -> {
                    val key = readKey()
                    block(key, StringBuilder().also(::readValue).toString())
                }
            }
        }
    }

    /** 读入一个完整 JSON 值；[sink] 为空时仅跳过。 */
    fun readValue(sink: StringBuilder?) {
        when (skipWs()) {
            '"'.code -> readString(sink)
            '{'.code, '['.code -> readContainer(sink)
            else -> readLiteral(sink)
        }
    }

    private fun readString(sink: StringBuilder?) {
        sink?.append(next().toChar())
        while (true) {
            val c = next()
            check(c != -1) { "Malformed index: unterminated string" }
            sink?.append(c.toChar())
            when (c) {
                '\\'.code -> {
                    val escaped = next()
                    check(escaped != -1) { "Malformed index: unterminated string" }
                    sink?.append(escaped.toChar())
                }
                '"'.code -> return
            }
        }
    }

    private fun readContainer(sink: StringBuilder?) {
        var depth = 0
        while (true) {
            val c = peek()
            check(c != -1) { "Malformed index: unterminated container" }
            if (c == '"'.code) {
                readString(sink)
                continue
            }
            next()
            sink?.append(c.toChar())
            if (c == '{'.code || c == '['.code) depth++
            else if (c == '}'.code || c == ']'.code) {
                depth--
                if (depth == 0) return
            }
        }
    }

    private fun readLiteral(sink: StringBuilder?) {
        while (true) {
            val c = peek()
            if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || c.toChar().isWhitespace()) return
            sink?.append(next().toChar())
        }
    }

    private companion object {
        const val NONE = -2
    }
}
