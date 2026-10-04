package top.app.market.ui.util

import top.app.market.domain.model.market.MarketAppInfo
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** 大小标签：不足 1000MB 显示 "12.3MB"，否则 "1.5GB"；非正数返回空串。 */
fun formatSize(size: Long): String {
    if (size <= 0) return ""
    val mb = size / 1024.0 / 1024.0
    val scaledMb = round(mb * 10).toLong()
    if (scaledMb < 10_000) return "${scaledMb / 10}.${scaledMb % 10}MB"
    val scaledGb = round(mb / 1024 * 10).toLong()
    return "${scaledGb / 10}.${scaledGb % 10}GB"
}

private const val WAN = 10_000L
private const val YI = 100_000_000L

/**
 * 人类可读的计数：低于一万显示原值，一万及以上用「万」，一亿及以上用「亿」，
 * 例如 50000 -> "5万"、123456 -> "12.3万"、250000000 -> "2.5亿"。非正数返回空串。
 */
fun formatCount(count: Long): String = when {
    count <= 0 -> ""
    count < WAN -> count.toString()
    count < YI -> oneDecimal(count, WAN) + "万"
    else -> oneDecimal(count, YI) + "亿"
}

/** 「今日」应用卡片副标题，优先展示商店分类和安装次数。 */
fun MarketAppInfo.todayAppSummary(): String = buildList {
    category.trim().takeIf(String::isNotBlank)?.let(::add)
    formatCount(downloadCount).takeIf(String::isNotBlank)?.let { add("${it}次安装") }
}.joinToString(" | ").ifBlank { publisherName.ifBlank { packageName } }

/** 今日奖项标签：标题已经是应用名时不重复显示应用名。 */
fun todayAwardLabel(awardName: String, title: String, appName: String?): String {
    val normalizedTitle = title.trim()
    return if (normalizedTitle.isBlank() || normalizedTitle == appName?.trim()) {
        awardName
    } else {
        "$awardName · $normalizedTitle"
    }
}

/** 以 [unit] 为单位缩放并保留一位小数，整数时省略小数部分（5.0 -> "5"）。 */
private fun oneDecimal(value: Long, unit: Long): String {
    val scaled = round(value.toDouble() / unit * 10).toLong()
    return if (scaled % 10 == 0L) "${scaled / 10}" else "${scaled / 10}.${scaled % 10}"
}

@OptIn(ExperimentalTime::class)
fun localDateOf(epochMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).date

@OptIn(ExperimentalTime::class)
fun currentLocalDate(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** "HH:mm" 时刻标签（系统时区）。 */
@OptIn(ExperimentalTime::class)
fun formatTimeHm(epochMillis: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()).time
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
