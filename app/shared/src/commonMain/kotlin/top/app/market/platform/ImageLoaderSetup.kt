package top.app.market.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient

internal expect fun imageDiskCache(context: PlatformContext): DiskCache?

/**
 * Registers Coil's singleton loader with the Koin-managed data-layer HTTP client.
 */
fun setupImageLoader(httpClient: HttpClient) {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { httpClient })) }
            .apply { imageDiskCache(context)?.let(::diskCache) }
            .build()
    }
}
