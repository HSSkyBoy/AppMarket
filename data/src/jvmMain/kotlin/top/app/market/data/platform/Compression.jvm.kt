package top.app.market.data.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

actual fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
    GZIPOutputStream(output).use { it.write(data) }
    output.toByteArray()
}

actual fun gunzip(data: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
