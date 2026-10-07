package top.app.market.domain.model.installer

enum class InstallerAttributionMode(val key: String) {
    /** 依应用来源自动伪装：厂商源对应官方商店，非厂商源/第三方源走 Google Play */
    AUTO_BY_SOURCE("auto_by_source"),

    /** 固定伪装为 Google Play (com.android.vending) */
    GOOGLE_PLAY("google_play"),

    /** 自定义伪装包名 */
    CUSTOM("custom"),

    /** 不伪装（使用本应用包名） */
    NONE("none");

    companion object {
        fun fromKey(key: String?): InstallerAttributionMode = when (key) {
            GOOGLE_PLAY.key -> GOOGLE_PLAY
            CUSTOM.key -> CUSTOM
            NONE.key -> NONE
            else -> AUTO_BY_SOURCE
        }
    }
}
