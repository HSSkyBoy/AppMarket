package top.app.market.domain.exception

class AppNotListedException(serverMessage: String = "") :
    RuntimeException(serverMessage.takeIf { it.isNotBlank() })
