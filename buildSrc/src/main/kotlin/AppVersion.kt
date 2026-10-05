import org.gradle.api.Project

/** 基础版本名，取自 gradle.properties 的 `APP_VERSION_NAME`。 */
private fun Project.baseVersionName(): String =
    providers.gradleProperty("APP_VERSION_NAME").get()

/** 版本号，取自 gradle.properties 的 `APP_VERSION_CODE`；发版时与版本名一起改。 */
fun Project.resolveVersionCode(): Int =
    providers.gradleProperty("APP_VERSION_CODE").get().toInt()

/**
 * 展示用版本名：CI 非发版构建（设置了 `COMMIT_ID`）追加 `-<7 位提交>`，如 `2.3.3-e54a42c`；
 * 发版构建和本机构建为纯 `APP_VERSION_NAME`。
 */
fun Project.resolveVersionName(): String {
    val commit = providers.environmentVariable("COMMIT_ID").orNull?.trim()?.take(7)
    return if (commit.isNullOrEmpty()) baseVersionName() else "${baseVersionName()}-$commit"
}

/** 桌面安装包要求严格的 `x.y.z`，不带提交后缀。 */
fun Project.resolvePackageVersion(): String = baseVersionName()
