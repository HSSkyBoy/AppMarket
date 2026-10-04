package top.app.market.domain.repository

/** Account credentials used by authenticated market requests. */
fun interface AccountRepository {
    fun cookie(): String

    fun security(): String = ""
}
