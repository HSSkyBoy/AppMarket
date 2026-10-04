package top.app.market.data.install.patch

import top.app.market.data.install.InstallPipelineException
import top.app.market.domain.model.install.InstallFailureCode
import top.app.market.domain.model.install.InstallSource
import top.app.market.patch.JojoPatchEngine
import top.app.market.patch.HDiffCore
import top.app.market.patch.OppoDeltaPatchEngine
import top.app.market.patch.VcdiffPatchEngine
import kotlinx.coroutines.CancellationException
import java.io.File

internal object DeltaPatchEngine {
    fun synthesize(
        source: InstallSource.Delta,
        patchFile: File,
        outputFile: File,
    ) {
        if (source.patchVersion !in SUPPORTED_PATCH_VERSIONS) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Unsupported delta patch version: ${source.patchVersion}",
            )
        }
        try {
            when (source.patchVersion) {
                JOJO_PATCH_VERSION -> JojoPatchEngine.applyFiles(patchFile, File(source.baseApkPath), outputFile)
                HDIFF_PATCH_VERSION -> HDiffCore.applyFiles(patchFile, File(source.baseApkPath), outputFile)
                OPPO_FILE_BY_FILE_VERSION -> OppoDeltaPatchEngine.applyFiles(
                    patchFile,
                    File(source.baseApkPath),
                    outputFile,
                )

                VCDIFF_PATCH_VERSION -> VcdiffPatchEngine.applyFiles(
                    patchFile,
                    File(source.baseApkPath),
                    outputFile,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                error.message ?: "Delta synthesis failed",
                error,
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            throw InstallPipelineException(
                InstallFailureCode.INTEGRITY,
                "Delta synthesis produced an empty APK",
            )
        }
    }

    private const val JOJO_PATCH_VERSION = 7
    private const val HDIFF_PATCH_VERSION = 3
    private const val OPPO_FILE_BY_FILE_VERSION = 5
    private const val VCDIFF_PATCH_VERSION = 6
    private val SUPPORTED_PATCH_VERSIONS = setOf(
        JOJO_PATCH_VERSION,
        HDIFF_PATCH_VERSION,
        OPPO_FILE_BY_FILE_VERSION,
        VCDIFF_PATCH_VERSION,
    )
}
