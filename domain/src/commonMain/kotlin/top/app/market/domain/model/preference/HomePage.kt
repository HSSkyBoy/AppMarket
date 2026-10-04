package top.app.market.domain.model.preference

/** The tab opened on launch and returned to by Back. */
enum class HomePage(val token: String) {
    TODAY("today"),
    UPDATES("updates"),
    SEARCH("search");

    companion object {
        /** Resolve from a persisted [token]; unknown/null falls back to [TODAY]. */
        fun fromToken(token: String?): HomePage = entries.firstOrNull { it.token == token } ?: TODAY
    }
}
