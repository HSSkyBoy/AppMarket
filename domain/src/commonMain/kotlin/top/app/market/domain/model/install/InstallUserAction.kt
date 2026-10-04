package top.app.market.domain.model.install

sealed interface InstallUserAction {
    data object GrantUnknownSourcesPermission : InstallUserAction
    data class InstallationFailed(val message: String) : InstallUserAction
}
