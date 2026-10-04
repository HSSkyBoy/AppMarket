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
