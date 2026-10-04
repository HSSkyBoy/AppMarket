package top.app.market.data.remote.honor

/**
 * 荣耀应用市场协议兼容常量。requestKeyBase64 提取自官方客户端
 * (com.hihonor.appmarket 16.1.7.301)，是随 APK 分发的固定值，与设备无关。
 * 若荣耀服务端轮换密钥导致签名失效，需从新版官方 APK 重新提取。
 */
internal object HonorCompatibility {
    const val requestKeyBase64: String = "OTNZNTk0a0JPVGtDNkEyOWdlcWRTQkxTNXUxU1RwQVE="
}
