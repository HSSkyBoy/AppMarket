import org.gradle.api.Project

const val VERSION_CODE_BASE = 220
const val GitVersionCodeFallback = 0

/** 提交总数（等价于 `git rev-list --count HEAD`）。失败时返回回退值。 */
fun Project.getGitCommitCount(): Int =
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(GitVersionCodeFallback)

/**
 * 220 + 当前分支 commit 数。
 * 本地构建还会把常量回写到 [ProjectConfig]；失败或小于现有值时沿用 VERSION_CODE。
 */
fun Project.resolveVersionCode(): Int {
    val gitCount = getGitCommitCount()
    val gitCode = if (gitCount > 0) VERSION_CODE_BASE + gitCount else GitVersionCodeFallback
    val baseline = ProjectConfig.VERSION_CODE
    if (gitCode == GitVersionCodeFallback || gitCode < baseline) return baseline
    if (gitCode > baseline && !isCiBuild()) {
        updateProjectVersionCode(gitCode)
    }
    return gitCode
}

private fun Project.isCiBuild(): Boolean =
    providers.environmentVariable("CI").orNull.toBoolean() ||
            providers.environmentVariable("GITHUB_ACTIONS").orNull.toBoolean()

private fun Project.updateProjectVersionCode(versionCode: Int) {
    val file = rootProject.file("buildSrc/src/main/kotlin/ProjectConfig.kt")
    val current = file.readText()
    val updated = current.replace(
        Regex("""const val VERSION_CODE = \d+"""),
        "const val VERSION_CODE = $versionCode",
    )
    if (updated != current) file.writeText(updated)
}

private const val VersionNameFallback = "0.0.0"
private val SemVerPattern = Regex("""\d+\.\d+\.\d+""")

/**
 * 版本名取自最近的 `v*` tag（如 `v2.3.2` → `2.3.2`），发版只需打 tag，无需改源码。
 * 取不到 git / tag 时返回 [VersionNameFallback]。
 */
fun Project.resolveVersionName(): String =
    runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
        }.standardOutput.asText.get().trim()
    }.getOrNull()
        ?.let { SemVerPattern.find(it)?.value }
        ?: VersionNameFallback
