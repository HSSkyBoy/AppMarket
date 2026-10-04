package top.app.market.data.remote.xiaomi.platform

import java.util.Locale

/** Desktop has no real device fingerprint; use a stable, plausible HyperOS-ish profile. */
internal class DesktopDeviceDefaultsDataSource : DeviceDefaultsDataSource {
    override fun current(): DeviceDefaults = DeviceDefaults(
        cpuArchitecture = "arm64-v8a",
        device = "haotian",
        model = "2410DPN6CC",
        androidVersion = "16",
        sdk = "36",
        language = Locale.getDefault().language.ifBlank { "zh" },
    )
}
