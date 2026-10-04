package top.app.market.data.platform

expect fun gzip(data: ByteArray): ByteArray
expect fun gunzip(data: ByteArray): ByteArray
