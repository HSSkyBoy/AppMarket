package top.app.market.data.remote.googleplay

/** 匿名会话使用的设备资料：Pixel 10 Pro（arm64，Android 16）。 */
internal object GooglePlayDevice {
    const val Sdk = "36"
    const val VendingVersionCode = "84582130"
    const val VendingVersionString = "45.8.21-31 [0] [PR] 747433787"
    const val Mccmnc = "310260"
    const val Abi = "arm64-v8a"

    val properties: Map<String, String> = mapOf(
        "UserReadableName" to "Google Pixel 10 Pro (API 36)",
        "Build.HARDWARE" to "blazer",
        "Build.RADIO" to "g5400b-250801-250731-B-13800000",
        "Build.BOOTLOADER" to "blazer-1.0-13800000",
        "Build.FINGERPRINT" to "google/blazer/blazer:16/BP4A.251205.006/14401865:user/release-keys",
        "Build.BRAND" to "google",
        "Build.DEVICE" to "blazer",
        "Build.VERSION.SDK_INT" to Sdk,
        "Build.VERSION.RELEASE" to "16",
        "Build.MODEL" to "Pixel 10 Pro",
        "Build.MANUFACTURER" to "Google",
        "Build.PRODUCT" to "blazer",
        "Build.ID" to "BP4A.251205.006",
        "Build.SUPPORTED_ABIS" to Abi,
        "Platforms" to Abi,
        "Features" to listOf(
            "android.hardware.camera", "android.hardware.camera.autofocus", "android.hardware.camera.flash",
            "android.hardware.camera.front", "android.hardware.faketouch", "android.hardware.location",
            "android.hardware.location.gps", "android.hardware.location.network", "android.hardware.microphone",
            "android.hardware.nfc", "android.hardware.screen.landscape", "android.hardware.screen.portrait",
            "android.hardware.sensor.accelerometer", "android.hardware.sensor.compass",
            "android.hardware.sensor.gyroscope", "android.hardware.telephony", "android.hardware.touchscreen",
            "android.hardware.touchscreen.multitouch", "android.hardware.touchscreen.multitouch.distinct",
            "android.hardware.touchscreen.multitouch.jazzhand", "android.hardware.usb.accessory",
            "android.hardware.usb.host", "android.hardware.wifi", "android.hardware.wifi.direct",
            "android.software.live_wallpaper", "android.software.webview",
        ).joinToString(","),
        "Locales" to "en,en_US,zh,zh_CN,zh_TW",
        "SharedLibraries" to listOf(
            "android.test.base", "android.test.mock", "android.test.runner", "com.android.location.provider",
            "com.android.media.remotedisplay", "com.android.mediadrm.signer", "javax.obex", "org.apache.http.legacy",
        ).joinToString(","),
        "GL.Version" to "196610",
        "GL.Extensions" to "GL_OES_EGL_image,GL_OES_EGL_image_external,GL_OES_depth24,GL_OES_texture_float",
        "Screen.Density" to "480",
        "Screen.Width" to "1280",
        "Screen.Height" to "2856",
        "Touchscreen" to "3",
        "Keyboard" to "1",
        "Navigation" to "1",
        "ScreenLayout" to "2",
        "HasHardKeyboard" to "false",
        "HasFiveWayNavigation" to "false",
        "Client" to "android-google",
        "GSF.version" to "251333035",
        "Vending.version" to VendingVersionCode,
        "Vending.versionString" to VendingVersionString,
        "CellOperator" to Mccmnc,
        "SimOperator" to Mccmnc,
        "TimeZone" to "America/New_York",
        "Roaming" to "mobile-notroaming",
    )
}
