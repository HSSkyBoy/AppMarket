package top.app.market.data.install

import top.app.market.domain.model.install.InstallFailureCode

internal class InstallPipelineException(
    val code: InstallFailureCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun Throwable.toInstallFailure(): InstallPipelineException =
    generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<InstallPipelineException>()
        .firstOrNull()
        ?: toDirectInstallFailure()

private fun Throwable.toDirectInstallFailure(): InstallPipelineException = when (this) {
    is InstallPipelineException -> this
    is kotlinx.coroutines.CancellationException -> InstallPipelineException(
        InstallFailureCode.CANCELLED,
        message ?: "Installation cancelled",
        this,
    )

    is java.net.SocketTimeoutException,
    is java.net.UnknownHostException,
    is java.net.ConnectException -> InstallPipelineException(
        InstallFailureCode.NETWORK,
        message ?: "Network request failed",
        this,
    )

    is java.io.IOException -> InstallPipelineException(
        InstallFailureCode.STORAGE,
        message ?: "I/O operation failed",
        this,
    )

    else -> InstallPipelineException(
        InstallFailureCode.UNKNOWN,
        message ?: this::class.simpleName.orEmpty(),
        this,
    )
}
