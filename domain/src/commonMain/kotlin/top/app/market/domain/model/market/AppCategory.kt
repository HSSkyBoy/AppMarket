package top.app.market.domain.model.market

/** Top-level catalogue sections exposed in the main navigation. */
enum class AppCategory { GAMES, APPS }

/** Sub-sections of [AppCategory.APPS]; [xiaomiCategoryId] is the official Xiaomi `level1CategoryId`. */
enum class AppSubCategory(val xiaomiCategoryId: Int) {
    TOOLS(5),
    MEDIA(27),
    SOCIAL(2),
    OFFICE(10),
    EDUCATION(12),
    SHOPPING(9),
    SPORTS(8),
}

/** Xiaomi's single top-level "game" category id. */
const val XIAOMI_GAME_CATEGORY_ID = 15

/** TapTap 游戏榜单；[tapTapType] 为官方 `app-top/v2/hits` 的 `type_name`。 */
enum class GameRanking(val tapTapType: String) {
    HOT("hot"),
    NEW("new"),
    SELL("sell"),
}

/** Xiaomi game sub-categories (`level1CategoryId`); [ALL] is the whole game category. */
enum class GameSubCategory(val xiaomiCategoryId: Int) {
    ALL(XIAOMI_GAME_CATEGORY_ID),
    STRATEGY(16),
    ACTION(17),
    RACING(18),
    RPG(19),
    CARD(20),
    FIGHTING(21),
    KIDS(22),
    CASUAL(23),
    FLIGHT(25),
    RUNNER(26),
}
