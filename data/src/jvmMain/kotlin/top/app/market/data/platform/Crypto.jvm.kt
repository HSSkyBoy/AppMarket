package top.app.market.data.platform

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun md5(data: ByteArray): ByteArray =
    MessageDigest.getInstance("MD5").digest(data)

actual fun sha1(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-1").digest(data)

actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key, algorithm))
    return mac.doFinal(data)
}
