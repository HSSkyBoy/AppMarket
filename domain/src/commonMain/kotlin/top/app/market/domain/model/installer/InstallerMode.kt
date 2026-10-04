package top.app.market.domain.model.installer

enum class InstallerMode(val key: String) {
    STANDARD("standard"),
    ROOT("root"),
    SHIZUKU("shizuku"),
    THIRD_PARTY("third_party");

    companion object {
        fun fromKey(key: String?): InstallerMode = when (key) {
            ROOT.key -> ROOT
            SHIZUKU.key -> SHIZUKU
            THIRD_PARTY.key, "custom" -> THIRD_PARTY
            else -> STANDARD
        }
    }
}
