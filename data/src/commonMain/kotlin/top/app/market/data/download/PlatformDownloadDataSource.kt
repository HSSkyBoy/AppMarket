package top.app.market.data.download

import top.app.market.domain.repository.DownloadRepository

/** Platform download implementation before data-layer history recording is applied. */
internal interface PlatformDownloadDataSource : DownloadRepository
