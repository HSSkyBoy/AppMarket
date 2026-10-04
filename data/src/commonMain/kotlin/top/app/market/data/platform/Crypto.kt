package top.app.market.data.platform

/**
 * Minimal cryptographic primitives needed by external market request signers.
 * JVM actuals (Android + desktop) both delegate to `java.security` / `javax.crypto`.
 *
 * Used by external market protocol adapters for request signing.
 */

/** MD5 digest of [data]. */
expect fun md5(data: ByteArray): ByteArray

/** SHA-1 digest of [data]. */
expect fun sha1(data: ByteArray): ByteArray

/** SHA-256 digest of [data]. */
expect fun sha256(data: ByteArray): ByteArray

/**
 * HMAC of [data] with [key] using the JCA [algorithm] name
 * (one of "HmacSHA1", "HmacSHA256", "HmacSHA384").
 */
expect fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray
