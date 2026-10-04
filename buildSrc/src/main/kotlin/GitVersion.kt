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
