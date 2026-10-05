package top.app.market.data.remote.oppo

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayArticleBlock
import top.app.market.domain.model.today.TodayFeaturedItem
import top.app.market.domain.model.today.TodayFeedPage

/** A small protobuf/protostuff reader. OPPO's store payloads use the protobuf wire format. */
internal data class OppoProtoField(
    val number: Int,
    val wireType: Int,
    val raw: ByteArray? = null,
    val varint: Long? = null,
    val fixed32: Int? = null,
    val fixed64: Long? = null,
)

internal class OppoProtoMessage(val fields: List<OppoProtoField>) {
    fun first(number: Int): OppoProtoField? = fields.firstOrNull { it.number == number }
    fun all(number: Int): List<OppoProtoField> = fields.filter { it.number == number }
    fun long(number: Int, default: Long = 0L): Long = first(number)?.varint ?: default
    fun string(number: Int): String = first(number)?.raw?.decodeToString().orEmpty()
    fun bytes(number: Int): ByteArray? = first(number)?.raw
    fun child(field: OppoProtoField): OppoProtoMessage? = field.raw?.let(::parseOppoProto)
}

internal fun parseOppoProto(bytes: ByteArray): OppoProtoMessage {
    val fields = ArrayList<OppoProtoField>()
    var offset = 0
    while (offset < bytes.size) {
        val key = readVarint(bytes, offset) ?: break
        offset = key.nextOffset
        val number = (key.value ushr 3).toInt()
        val wireType = (key.value and 7L).toInt()
        if (number <= 0) break
        when (wireType) {
            0 -> {
                val value = readVarint(bytes, offset) ?: break
                offset = value.nextOffset
                fields += OppoProtoField(number, wireType, varint = value.value)
            }

            1 -> {
                if (offset + 8 > bytes.size) break
                var value = 0L
                repeat(8) { index -> value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8)) }
                offset += 8
                fields += OppoProtoField(number, wireType, fixed64 = value)
            }

            2 -> {
                val length = readVarint(bytes, offset) ?: break
                offset = length.nextOffset
                if (length.value < 0L || length.value > bytes.size - offset) break
                val end = offset + length.value.toInt()
                fields += OppoProtoField(number, wireType, raw = bytes.copyOfRange(offset, end))
                offset = end
            }

            5 -> {
                if (offset + 4 > bytes.size) break
                var value = 0
                repeat(4) { index -> value = value or ((bytes[offset + index].toInt() and 0xff) shl (index * 8)) }
                offset += 4
                fields += OppoProtoField(number, wireType, fixed32 = value)
            }

            else -> break
        }
    }
    return OppoProtoMessage(fields)
}

/** OPPO「分类」页的大类。 */
internal data class OppoCategory(val id: Long, val name: String)

internal data class OppoResource(
    val appId: Long,
    val versionId: Long,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val size: Long,
    val sizeDescription: String,
    val md5: String,
    val checksum: String,
    val icon: String,
    val url: String,
    val brief: String,
    val description: String,
    val downloadCount: Long,
    val downloadCountDescription: String,
    val commentCount: Long,
    val rating: Float,
    val changeLog: String,
    val category: String,
    val registrationNum: String,
    val ageClassification: String,
    val screenshots: List<String>,
    val hdScreenshots: List<String>,
    val patches: List<OppoPatch>,
    val isAd: Boolean,
)

/** UpgradeDtoV2 field 109 / AppPatchDto. */
internal data class OppoPatch(
    val size: Long,
    val url: String,
    val md5: String,
    val obitVersion: Int,
)

/** DownloadFileWrapDto / DownloadFileInfoDto returned by the global v2 download endpoint. */
internal data class OppoDownloadFile(
    val id: String,
    val splitName: String,
    val revisionCode: Long,
    val type: Int,
    val md5: String,
    val headerMd5: String,
    val cdnUrl: String,
    val fallbackUrl: String,
    val size: Long,
    val essential: Boolean,
) {
    val url: String get() = cdnUrl.ifBlank { fallbackUrl }
}

internal data class OppoDownloadFileWrap(
    val code: Int,
    val versionCode: Long,
    val files: List<OppoDownloadFile>,
    val size: Long,
)

internal fun parseOppoDownloadFileWrap(bytes: ByteArray): OppoDownloadFileWrap {
    val response = parseOppoProto(bytes)
    val files = response.all(3).mapNotNull { field ->
        val file = response.child(field) ?: return@mapNotNull null
        val hasFileType = file.all(127).any {
            it.raw?.decodeToString()?.endsWith(".DownloadFileInfoDto") == true
        }
        if (!hasFileType && file.string(7).isBlank() && file.string(8).isBlank()) {
            return@mapNotNull null
        }
        OppoDownloadFile(
            id = file.string(1),
            splitName = file.string(2),
            revisionCode = file.long(3),
            type = file.long(4).toInt(),
            md5 = file.string(5),
            headerMd5 = file.string(6),
            cdnUrl = file.string(7),
            fallbackUrl = file.string(8),
            // nSize is the long-size field used by the current client. Field 9 is retained for
            // compatibility with older responses where APK sizes were encoded as an int.
            size = file.long(10).takeIf { it > 0L } ?: file.long(9),
            essential = file.long(11) != 0L,
        )
    }
    return OppoDownloadFileWrap(
        code = response.long(1).toInt(),
        versionCode = response.long(2),
        files = files,
        size = response.long(4),
    )
}

/**
 * ResourceDto is the common superclass of OPPO search, detail and update result messages.
 * Searching recursively is intentional: the enclosing response type differs between endpoints.
 */
internal fun parseOppoResources(bytes: ByteArray): List<OppoResource> {
    val result = ArrayList<OppoResource>()
    val seen = HashSet<String>()

    fun visit(message: OppoProtoMessage, promotedContext: Boolean = false) {
        val classNames = message.all(127).mapNotNull { it.raw?.decodeToString() }
        val promoted = promotedContext || classNames.any(::isOppoPromotionContainer)
        val packageName = message.string(7)
        if (packageName.contains('.')) {
            val url = message.string(22)
            val screenshots = (
                    message.all(32).mapNotNull(OppoProtoField::imageUrl) +
                            message.all(107).flatMap(OppoProtoField::nestedHttpUrls)
                    ).distinct()
            val hdScreenshots = message.all(102).mapNotNull(OppoProtoField::imageUrl).distinct()
            val changeLog = message.string(102).takeUnless(::isOppoImageUrl).orEmpty()
            val patches = message.all(109).mapNotNull { field ->
                val patch = message.child(field) ?: return@mapNotNull null
                OppoPatch(
                    size = patch.long(1),
                    url = patch.string(2),
                    md5 = patch.string(3),
                    obitVersion = patch.long(4).toInt(),
                ).takeIf { it.size > 0L && it.url.isNotBlank() && it.obitVersion > 0 }
            }
            val resource = OppoResource(
                appId = message.long(1),
                versionId = message.long(2),
                packageName = packageName,
                displayName = message.string(3),
                versionName = message.string(8),
                versionCode = message.long(9),
                size = message.long(10),
                sizeDescription = message.string(11),
                md5 = message.string(12),
                checksum = message.string(13),
                icon = message.string(14),
                url = url,
                brief = message.string(26),
                description = message.string(27).ifBlank { message.string(64) },
                downloadCount = message.long(15),
                downloadCountDescription = message.string(16),
                commentCount = message.long(17),
                rating = message.first(18)?.let { field ->
                    field.fixed32?.let(Float::fromBits) ?: field.varint?.toFloat()
                } ?: 0f,
                changeLog = changeLog,
                category = message.string(30),
                registrationNum = message.string(56),
                ageClassification = message.string(112),
                screenshots = screenshots,
                hdScreenshots = hdScreenshots,
                patches = patches,
                isAd = promoted ||
                        message.hasOppoAdMetadata() ||
                        message.long(23) > 0L ||
                        url.contains("ref=com.ad.", ignoreCase = true),
            )
            // Keep an advertised and an organic copy separate. The repository filters ads first,
            // then deduplicates, so removing a promoted placement does not also erase its organic row.
            val key = "${resource.appId}:${resource.versionId}:${resource.packageName}:${resource.versionCode}:${resource.isAd}"
            if (seen.add(key) && resource.appId > 0L && resource.versionCode > 0L) result += resource
        }
        message.fields.asSequence()
            .filter { it.wireType == 2 && it.raw != null }
            .mapNotNull { field ->
                val child = field.raw?.let(::parseOppoProto) ?: return@mapNotNull null
                // Class metadata and arbitrary JSON/string fields are not useful messages. A child
                // with at least one legal field is still traversed; malformed bytes yield an empty node.
                child.takeIf { it.fields.isNotEmpty() }
            }
            .forEach { visit(it, promoted) }
    }

    visit(parseOppoProto(bytes))
    return result
}

internal fun parseOppoBeautyFeed(bytes: ByteArray, pageSize: Int): TodayFeedPage {
    val response = parseOppoProto(bytes)
    val feedTitle = response.string(2)
    val items = response.all(3).mapNotNull { cardField ->
        val card = response.child(cardField) ?: return@mapNotNull null
        val detailField = card.all(103).firstOrNull { field ->
            card.child(field)?.hasClass(BEAUTY_DETAIL_CLASS) == true
        } ?: return@mapNotNull null
        val detail = card.child(detailField) ?: return@mapNotNull null
        val snippetId = detail.long(109).takeIf { it > 0L } ?: return@mapNotNull null
        val resource = detailField.raw?.let(::parseOppoResources)
            ?.firstOrNull { it.appId == detail.long(1) }
            ?: return@mapNotNull null
        val banner = detail.first(108)?.let(detail::child)
        val ext = detail.stringMap(41)
        TodayFeaturedItem(
            rId = snippetId.toString(),
            title = detail.string(107).ifBlank { feedTitle },
            summary = detail.string(103)
                .ifBlank { banner?.string(6).orEmpty() }
                .ifBlank { resource.brief },
            coverImage = banner?.string(2).orEmpty()
                .ifBlank { ext["largeImage"].orEmpty() }
                .ifBlank { resource.icon },
            app = resource.toTodayApp(),
            articleLink = banner?.string(4).orEmpty(),
            awardName = ext["columnType"].orEmpty().ifBlank { feedTitle },
        )
    }.distinctBy(TodayFeaturedItem::rId)
    return TodayFeedPage(
        items = items,
        hasMore = items.size >= pageSize,
    )
}

internal fun parseOppoSnippetArticle(rId: String, bytes: ByteArray): TodayArticle {
    val response = parseOppoProto(bytes)
    val header = response.first(1)?.let(response::child)
    val body = response.first(2)?.let(response::child)
    val components = body?.let { value -> value.all(1).mapNotNull(value::child) }.orEmpty()
    val firstImage = components.firstNotNullOfOrNull(OppoProtoMessage::imageComponent)
    val headerImage = header?.string(5).orEmpty().ifBlank { firstImage?.url.orEmpty() }
    val blocks = mutableListOf<TodayArticleBlock>()
    if (headerImage.isNotBlank()) {
        blocks += TodayArticleBlock.Banner(
            imageUrl = headerImage,
            width = firstImage?.width ?: 0,
            height = firstImage?.height ?: 0,
        )
    }

    val seenApps = HashSet<String>()
    var skippedHeaderImage = false
    components.forEach { component ->
        when (component.simpleClassName()) {
            "ImageComponent", "ImageComponentV2", "SubjectImageComponent" -> {
                val image = component.imageComponent() ?: return@forEach
                if (!skippedHeaderImage && image.url == headerImage) {
                    skippedHeaderImage = true
                } else {
                    blocks += TodayArticleBlock.Image(
                        imageUrl = image.url,
                        width = image.width,
                        height = image.height,
                    )
                }
            }

            "TextComponent" -> {
                val html = component.first(2)?.let(component::child)?.string(101).orEmpty()
                if (html.isNotBlank()) blocks += TodayArticleBlock.RichText(html)
            }

            "CardComponent" -> {
                component.bytes(101)?.let(::parseOppoResources).orEmpty()
                    .map(OppoResource::toTodayApp)
                    .filter { seenApps.add(it.packageName.lowercase()) }
                    .forEach { blocks += TodayArticleBlock.App(it) }
            }
        }
    }

    if (seenApps.isEmpty()) {
        parseOppoResources(bytes)
            .map(OppoResource::toTodayApp)
            .filter { seenApps.add(it.packageName.lowercase()) }
            .forEach { blocks += TodayArticleBlock.App(it) }
    }

    val apps = blocks.filterIsInstance<TodayArticleBlock.App>()
        .map(TodayArticleBlock.App::value)
    val richText = blocks.filterIsInstance<TodayArticleBlock.RichText>()
        .joinToString("\n\n", transform = TodayArticleBlock.RichText::html)
    return TodayArticle(
        rId = rId,
        title = header?.string(1).orEmpty().ifBlank { header?.string(7).orEmpty() },
        awardName = response.stringMap(99)["columnType"].orEmpty(),
        headerImage = headerImage,
        richTextHtml = richText,
        app = apps.firstOrNull(),
        apps = apps,
        blocks = blocks,
    )
}

private data class OppoArticleImage(
    val url: String,
    val width: Int,
    val height: Int,
)

private fun OppoProtoMessage.imageComponent(): OppoArticleImage? {
    if (simpleClassName() !in OPPO_IMAGE_COMPONENTS) return null
    val props = first(2)?.let(::child) ?: return null
    val url = props.string(101)
    if (url.isBlank()) return null
    return OppoArticleImage(
        url = url,
        width = props.long(102).toInt(),
        height = props.long(103).toInt(),
    )
}

private fun OppoProtoMessage.hasClass(className: String): Boolean =
    all(127).any { it.raw?.decodeToString() == className }

private fun OppoProtoMessage.simpleClassName(): String =
    all(127).firstNotNullOfOrNull { it.raw?.decodeToString() }
        ?.substringAfterLast('.').orEmpty()

private fun OppoProtoMessage.stringMap(number: Int): Map<String, String> = buildMap {
    all(number).forEach mapField@{ mapField ->
        val map = child(mapField) ?: return@mapField
        map.all(1).forEach entryField@{ entryField ->
            val entry = map.child(entryField) ?: return@entryField
            val key = entry.string(1)
            val valueField = entry.first(2)
            val value = valueField?.raw?.let { raw ->
                parseOppoProto(raw).string(9).ifBlank { raw.decodeToString() }
            }.orEmpty()
            if (key.isNotBlank() && value.isNotBlank()) put(key, value)
        }
    }
}

private fun OppoResource.toTodayApp(): MarketAppInfo = MarketAppInfo(
    appId = appId,
    packageName = packageName,
    displayName = displayName.ifBlank { packageName },
    publisherName = "",
    versionName = versionName,
    versionCode = versionCode,
    icon = icon,
    apkSize = size,
    ratingScore = rating.toDouble(),
    changeLog = changeLog,
    openLink = url,
    isAd = isAd,
    source = AppSource.OPPO,
    category = category,
    downloadCount = resolvedDownloadCount(),
)

/** Keeps OPPO's server order within each relevance bucket, but never buries an exact app match. */
internal fun prioritizeOppoSearchResults(resources: List<OppoResource>, keyword: String): List<OppoResource> {
    val query = keyword.trim()
    if (query.isEmpty()) return resources
    return resources.sortedBy { it.oppoSearchRelevance(query) }
}

/** OPPO caps dlCount for very popular apps; dlDesc retains values such as "58.8 亿次安装". */
internal fun OppoResource.resolvedDownloadCount(): Long =
    maxOf(downloadCount, parseOppoCount(downloadCountDescription))

internal fun parseOppoCount(raw: String): Long {
    val normalized = raw.trim().replace(",", "")
    val number = CountNumber.find(normalized)?.value?.toDoubleOrNull() ?: return 0L
    val multiplier = when {
        '亿' in normalized -> 100_000_000.0
        '万' in normalized -> 10_000.0
        normalized.contains("b", ignoreCase = true) -> 1_000_000_000.0
        normalized.contains("m", ignoreCase = true) -> 1_000_000.0
        normalized.contains("k", ignoreCase = true) -> 1_000.0
        else -> 1.0
    }
    return (number * multiplier).toLong().coerceAtLeast(0L)
}

private fun OppoProtoField.imageUrl(): String? = raw?.decodeToString()?.takeIf(::isOppoImageUrl)

/** BaseDetailDto v4 stores ScreenshotDto(width, height, url) inside field 107's list wrapper. */
private fun OppoProtoField.nestedHttpUrls(depth: Int = 0): List<String> {
    val bytes = raw ?: return emptyList()
    val direct = bytes.decodeToString().takeIf(::isOppoHttpUrl)
    if (direct != null) return listOf(direct)
    if (depth >= MAX_SCREENSHOT_DEPTH) return emptyList()
    val child = parseOppoProto(bytes)
    if (child.fields.isEmpty()) return emptyList()
    return child.fields.asSequence()
        .filter { it.wireType == 2 && it.raw != null }
        .flatMap { it.nestedHttpUrls(depth + 1).asSequence() }
        .distinct()
        .toList()
}

private fun isOppoImageUrl(value: String): Boolean =
    isOppoHttpUrl(value) &&
            ImageExtension.containsMatchIn(value.substringBefore('?'))

private fun isOppoHttpUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

private fun isOppoPromotionContainer(className: String): Boolean {
    val simpleName = className.substringAfterLast('.')
    return simpleName == "BannerCardDto" ||
            simpleName == "ListBannerCardDto" ||
            simpleName == "ListCarouselBannerCardDto" ||
            simpleName == "GlobalBannerCardDto" ||
            simpleName.startsWith("Ad")
}

private fun OppoResource.oppoSearchRelevance(query: String): Int = when {
    packageName.equals(query, ignoreCase = true) -> 0
    displayName.equals(query, ignoreCase = true) -> 0
    displayName.startsWith(query, ignoreCase = true) -> 1
    displayName.contains(query, ignoreCase = true) -> 2
    packageName.contains(query, ignoreCase = true) -> 3
    else -> UNRELATED_SEARCH_RESULT
}

private fun OppoProtoMessage.hasOppoAdMetadata(depth: Int = 0): Boolean {
    if (depth > MAX_METADATA_DEPTH) return false
    if (all(127).any { field ->
            field.raw?.decodeToString()
                ?.substringAfterLast('.')
                ?.startsWith("TransAd") == true
        }
    ) return true
    return fields.asSequence()
        .filter { it.wireType == 2 && it.raw != null }
        .mapNotNull { it.raw?.let(::parseOppoProto) }
        .filter { it.fields.isNotEmpty() }
        .any { it.hasOppoAdMetadata(depth + 1) }
}

private val CountNumber = Regex("[0-9]+(?:\\.[0-9]+)?")
private val ImageExtension = Regex("\\.(?:png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)
private val OPPO_IMAGE_COMPONENTS = setOf("ImageComponent", "ImageComponentV2", "SubjectImageComponent")
private const val BEAUTY_DETAIL_CLASS = "com.heytap.cdo.card.domain.dto.beautyapp.BeautyAppDetailDto"
private const val UNRELATED_SEARCH_RESULT = 4
private const val MAX_METADATA_DEPTH = 8
private const val MAX_SCREENSHOT_DEPTH = 5

internal fun encodeOppoVarint(value: Long): ByteArray {
    var current = value
    val output = ArrayList<Byte>(10)
    while (current and -0x80L != 0L) {
        output += ((current and 0x7fL) or 0x80L).toByte()
        current = current ushr 7
    }
    output += current.toByte()
    return output.toByteArray()
}

internal fun encodeOppoLongField(number: Int, value: Long): ByteArray =
    encodeOppoVarint((number.toLong() shl 3) or 0L) + encodeOppoVarint(value)

internal fun encodeOppoStringField(number: Int, value: String): ByteArray =
    encodeOppoBytesField(number, value.encodeToByteArray())

internal fun encodeOppoBytesField(number: Int, value: ByteArray): ByteArray =
    encodeOppoVarint((number.toLong() shl 3) or 2L) + encodeOppoVarint(value.size.toLong()) + value

internal fun encodeOppoMessage(vararg fields: ByteArray): ByteArray = fields.fold(ByteArray(0)) { acc, next -> acc + next }

private data class VarintResult(val value: Long, val nextOffset: Int)

private fun readVarint(bytes: ByteArray, start: Int): VarintResult? {
    var value = 0L
    var shift = 0
    var offset = start
    while (offset < bytes.size && shift <= 63) {
        val part = bytes[offset].toInt() and 0xff
        offset++
        value = value or ((part and 0x7f).toLong() shl shift)
        if ((part and 0x80) == 0) return VarintResult(value, offset)
        shift += 7
    }
    return null
}
