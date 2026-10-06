# AppMarket

基于 **Kotlin Multiplatform** 的现代化跨平台应用商店，支持 **Android** 与 **Desktop (JVM)**。采用 **miuix** 设计语言，拥有流畅优雅的视觉交互，聚合多家主流应用源。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.0-blue?logo=jetpackcompose)](https://github.com/JetBrains/compose-multiplatform)
[![miuix](https://img.shields.io/badge/UI-miuix-FF6900.svg)](https://github.com/compose-miuix-ui/miuix)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Desktop-green.svg)](https://github.com/YuKongA/AppMarket)
[![Android Min SDK](<https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-brightgreen.svg?logo=android>)](https://android.com)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg?logo=openjdk)](https://openjdk.org)

---

> [!IMPORTANT]
>
> ### 免责与分发来源声明
>
> 1. **零中转服务器**：本应用是一个**纯客户端工具**，本项目**不设立、不运营、不持有任何用于存储、托管或分发第三方应用安装包的服务器**。
> 2. **数据源直连**：应用内展示的所有应用信息、元数据、图标截图、评论以及 APK 安装包 / 增量差分补丁，**全部直接源自对应应用源**。
> 3. **原包与安全**：客户端不对提供的任何应用进行二次打包、注入或篡改；增量更新（差分补丁）均在用户本地与已安装的原版 APK 完成合成。
> 4. **版权与合规**：所有应用、图标、商标等的版权均归各自原厂商/开发者所有。用户需自行判断应用的合法性，并遵守应用源的服务条款与版权规范。

---

## 目录

- [AppMarket](#appmarket)
  - [目录](#目录)
  - [特性概览](#特性概览)
  - [支持的应用源](#支持的应用源)
  - [商店链接识别](#商店链接识别)
  - [安装方式与安装器](#安装方式与安装器)
  - [编译与开发](#编译与开发)
    - [环境要求](#环境要求)
    - [常用命令](#常用命令)
      - [Desktop 端](#desktop-端)
      - [Android 端](#android-端)
  - [运行要求](#运行要求)
  - [开源引用](#开源引用)
  - [免责声明](#免责声明)

---

## 特性概览

- **多源聚合与自由定制**：支持独立为「搜索来源」、「更新来源」、「今日来源」以及「分区来源」指定不同的商店源，各取所长。
- **游戏 / 应用分区浏览**：小米官方细分类、TapTap 榜单（热门 / 新品 / 热卖）、华为应用榜与游戏榜、OPPO 服务端动态分类，支持下拉刷新。
- **主题自定义**：莫奈取色（自定义主色 / 调色盘风格 / Material 3 2021·2025 规格）、深浅色模式、AMOLED 纯黑、设置页搜索。
- **纯客户端增量更新**：内置多套差分算法还原引擎，可用时下载补丁包并于本地合成为完整 APK，节省 70%~90% 流量。
- **全场景安装器支持**：支持系统标准 PackageInstaller、Shizuku 免 Root 静默安装、Root 极速静默安装以及第三方安装器交接。
- **纯净体验与结果净化**：
  - **去除推广应用**：自动隐藏搜索结果中的广告推广。
  - **过滤快应用**：一键隐藏免安装快应用结果。
  - **过滤预约应用**：过滤尚未上线的预注册应用。
  - **优化应用名称**：智能裁剪应用名称后携带的营销副标题与推广宣传语。
- **商店链接识别**：认得各家应用商店的链接（含分享文案中的链接），进入应用时可识别剪贴板并询问是否打开；Android 上可被选为这些链接的打开方式，详见 [商店链接识别](#商店链接识别)。
- **历史版本回退**：接入海量应用历史库，支持按版本追溯并下载历史版本 APK。
- **设备指纹与机型模拟**：内置预设及自定义 `MarketProfile`（机型、Android 版本、SDK、分辨率、区域等），解决特定厂商或生态专属应用不可见问题。
- **系统生态深度优化**：支持小米 HyperOS 超级岛优化、焦点通知优化等。
- **多语言**：简体中文、繁体中文（台湾 / 香港）、English，应用内可独立切换语言，不受系统语言限制。
- **跨平台一致体验**：基于 Compose Multiplatform 打造，同时支持 Android 移动端与桌面端（Windows / macOS / Linux）。

---

## 支持的应用源

| 应用源                     |    标识     | 搜索 / 详情 | 更新检查 | 增量更新 | 历史版本 |  今日精选   | 分区浏览  | 真实评论 | 过滤净化 |
| :------------------------- | :---------: | :---------: | :------: | :------: | :------: | :---------: | :-------: | :------: | :------: |
| **小米应用商店** (Xiaomi)  |  `xiaomi`   |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (金米奖) | ✅ (细分类) |    ✅    |    ✅    |
| **vivo 应用商店** (vivo)   |   `vivo`    |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (极光奖) |    ❌     |    ❌    |    ✅    |
| **OPPO 软件商店** (OPPO)   |   `oppo`    |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (至美奖) | ✅ (动态大类) |    ✅    |    ✅    |
| **TapTap**                 |  `taptap`   |     ✅      |    ✅    |    ✅    |    ❌    | ✅ (推荐流) | ✅ (仅游戏榜) |    ❌    |    —     |
| **华为应用市场** (Huawei)  |  `huawei`   |     ✅      |    ✅    |    ❌    |    ❌    |     ❌      | ✅ (应用/游戏榜) |    ❌    |    ✅    |
| **荣耀应用市场** (Honor)   |   `honor`   |     ✅      |    ✅    |    ✅    |    ❌    |     ❌      |    ❌     |    ❌    |    ✅    |
| **三星应用商店** (Samsung) |  `samsung`  |     ✅      |    ✅    |    ❌    |    ❌    |     ❌      |    ❌     |    ❌    |    —     |
| **豌豆荚** (Wandoujia)     | `wandoujia` |     ✅      |   ✅\*   |    ❌    |    ✅    |     ❌      |    ❌     |    ❌    |    —     |

> _\* 注：豌豆荚无原生批量更新元数据协议，更新检查时目前转发到小米源处理。_
> _分区浏览（「游戏」「应用」页）无原生接口的来源会自动回退到小米官方分类。_

---

## 商店链接识别

AppMarket 能解析各家应用商店的应用详情链接，并在应用内打开对应详情页，有三个入口：

- **系统链接**（Android）：在浏览器、聊天软件中点击商店链接，可选择用 AppMarket 打开。
- **剪贴板识别**：进入应用（窗口获得焦点）时读取剪贴板，识别到商店链接就弹窗询问是否打开；同一链接只询问一次，可在 设置 中关闭「识别剪贴板中的商店链接」。Android 12 及以上读取剪贴板内容时系统会提示，因此仅在剪贴板内容变化时才读取。
- **分享文案**：剪贴板里的整段文字（如「【微信】快来下载吧！https://app.mi.com/details?id=…」）也会被识别，取第一个可识别的链接。
- **短链接**：华为应用市场「复制链接」得到的短链接（`url.cloud.huawei.com/xxxx`）会联网展开（跟随 302 跳转）后再识别。只会展开这个已知短链域名，不会去访问剪贴板里的其他网址。
- **分享**：应用详情页右上角 ⋮ 菜单的「分享」，内容为「应用名 + 统一格式链接」；Android 拉起系统分享面板，桌面端复制到剪贴板。

**统一格式**：`appmarket://details?id=<包名>&source=<来源标识>`，来源标识见上表。华为应用市场本身使用 `appmarket://details?id=`，因此不带 `source` 时按华为处理，两者相容。

### 可识别的链接

包名取自查询参数（`id`、`packageName`、`pkgName`、`pname`、`package`、`pkg` 等，含 `#` 之后的参数）或路径段（如三星 `/detail/<包名>`、豌豆荚 `/apps/<包名>`）。TapTap 只带站内 id 的链接（`/app/<id>`）也可打开。

| 来源              | scheme                                    | 网页域名                                                  |
| :---------------- | :---------------------------------------- | :-------------------------------------------------------- |
| 统一格式          | `appmarket://`                            | —                                                         |
| 小米              | `mimarket://`                             | `app.mi.com`、`app.xiaomi.com`                            |
| 华为              | `hiapp://`、`hwmarket://`                 | `appgallery.huawei.com`、`appgallery.cloud.huawei.com`、`appstore.huawei.com` |
| vivo              | `vivomarket://`                           | `vivo.com.cn`、`vivo.com`                                 |
| OPPO              | `oppomarket://`、`heytapmarket://`、`oaps://` | `heytap.com`、`oppomobile.com`、`heytapmobi.com`      |
| 荣耀              | `honormarket://`、`hnappmarket://`        | `hihonor.com`、`honor.com`                                |
| 三星              | `samsungapps://`                          | `galaxystore.samsung.com`、`apps.samsung.com`             |
| TapTap            | `taptap://`                               | `taptap.cn`、`taptap.com`、`taptap.io`                    |
| 豌豆荚            | `wandoujia://`                            | `wandoujia.com`                                           |
| 其他（无对应来源） | `market://`                               | `play.google.com`、`market.android.com`、`coolapk.com`    |

华为分享链接常只带 `C` 开头的应用 id（如 `appgallery.huawei.com/app/C100404489`），同样可以识别，打开时通过华为网页版的应用信息接口换出包名再加载详情。只有域名匹配、但链接里既没有包名也取不到站内 id 的链接不会被识别。

### 打开到哪个来源

- 小米、华为、荣耀、TapTap：仅凭包名即可加载详情，在链接指明的来源中打开；华为、TapTap 只带站内 id 时也可打开。
- vivo、OPPO、三星、豌豆荚：仅凭包名查不到详情，改由小米打开（TapTap 带站内 id 时除外）。
- Google Play、酷安、通用 `market://`：无对应来源，由小米打开。

### 系统打开方式（Android）

`AndroidManifest.xml` 声明的范围是上表的子集：

- **scheme**：`market`、`mimarket`（详情 / 搜索）；`appmarket`、`hiapp`、`hwmarket`、`vivomarket`、`oppomarket`、`heytapmarket`、`oaps`、`honormarket`、`hnappmarket`、`samsungapps`、`taptap`、`wandoujia`（不限 host）。
- **网页**（按路径前缀限定，不接管整站）：

  | 域名                                                   | 路径前缀              |
  | :----------------------------------------------------- | :-------------------- |
  | `m.app.mi.com`                                         | `/details`            |
  | `app.xiaomi.com`                                       | `/`                   |
  | `appgallery.huawei.com`、`appgallery.cloud.huawei.com` | `/appDetail`          |
  | `h5.appstore.vivo.com.cn`                              | `/`                   |
  | `galaxystore.samsung.com`                              | `/detail`             |
  | `www.wandoujia.com`                                    | `/apps`               |
  | `www.taptap.cn`、`www.taptap.com`                      | `/app`                |
  | `play.google.com`                                      | `/store/apps/details` |
  | `www.coolapk.com`                                      | `/apk`                |

- 解析器认得、但 Manifest 未声明的网页链接（OPPO、荣耀的全部网页，以及 `appstore.huawei.com`、`apps.samsung.com`、`taptap.io`、`market.android.com`）：复制到剪贴板仍会被识别，但在浏览器中点击不会跳转到 AppMarket。
- 网页链接未做域名验证，系统通常会弹出选择器，而不是直接用 AppMarket 打开。

---

## 安装方式与安装器

1. **标准安装 (Standard)**：
   - 调用系统原生 `PackageInstaller` 确认界面。
   - 兼容性最佳，无需任何特殊权限，开箱即用。
2. **Shizuku 静默安装 (Shizuku)**：
   - 借助 [Shizuku](https://shizuku.rikka.app/) 服务在免 Root 情况下获取系统级安装权限。
   - 无需手动点击系统安装弹窗，后台静默无感完成更新与安装。
3. **Root 静默安装 (Root)**：
   - 适用于已获取 Root 权限（KernelSU / Magisk / APatch）的设备。
   - 直接通过底层特权指令高速完成静默安装。
4. **第三方包安装器 (Third-party)**：
   - 支持自定义交接给系统内其他第三方安装器或分发应用处理。

---

## 编译与开发

### 环境要求

- **JDK 21** 或更高版本
- **Android SDK**（编译 Android 客户端需要，`compileSdk = 37`, `minSdk = 26`）
- **Gradle**（推荐使用项目自带的 Gradle Wrapper）

### 常用命令

#### Desktop 端

```bash
# 快速编译检查桌面端与公共共享模块
./gradlew :app:shared:compileKotlinDesktop

# 本地直接运行桌面端客户端
./gradlew :app:desktop:run

# 打包当前操作系统格式的桌面安装包（dmg / deb / msi）
./gradlew :app:desktop:packageDistributionForCurrentOS
```

#### Android 端

```bash
# 编译 Android 共享模块
./gradlew :app:shared:compileAndroidMain

# 构建 Android Debug APK
./gradlew :app:android:assembleDebug

# 构建 Android Release APK
./gradlew :app:android:assembleRelease
```

---

## 运行要求

| 平台        | 最低要求                                   | 推荐配置                                     |
| :---------- | :----------------------------------------- | :------------------------------------------- |
| **Android** | Android 8.0 (API 26) 以上                  | Android 10+，已授权 Shizuku 或具备 Root 权限 |
| **Windows** | Windows 10 x64 / ARM64                     | 安装 JRE/JDK 21+                             |
| **macOS**   | macOS 12 Monterey 以上                     | Apple Silicon (M系列) 或 Intel x86_64        |
| **Linux**   | 主流现代发行版（Ubuntu 20.04+、Fedora 等） | 带有 X11 或 Wayland 环境                     |

---

## 开源引用

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) - JetBrains 推出的声明式跨平台 UI 框架
- [miuix](https://github.com/compose-miuix-ui/miuix) - 遵循 MIUI / HyperOS 设计风格的 Compose Multiplatform UI 库
- [Ktor](https://github.com/ktorio/ktor) - 现代化异步多平台 HTTP 客户端
- [Koin](https://github.com/InsertKoinIO/koin) - 轻量级依赖注入框架
- [Coil](https://github.com/coil-kt/coil) - Kotlin Multiplatform 异步图片加载库
- [Shizuku](https://github.com/RikkaApps/Shizuku) - Android 系统级 API 免 Root 调用框架
- [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) - Android 非公开 API 限制绕过方案
- [HDiffPatch](https://github.com/sisong/HDiffPatch) - 高性能差分与补丁还原库
- [HyperNotification](https://github.com/xzakota/HyperNotification) - 小米澎湃焦点通知与超级岛适配支持

---

## 免责声明

1. **用途限制**：本项目仅供技术研究学习及交流使用，严禁用于任何商业用途或非法牟利行为。
2. **商标与版权**：文中提及的 Xiaomi、vivo、OPPO、Honor、Huawei、Samsung、TapTap、豌豆荚等商标及产品名称，其商标权与著作权均归其合法所有者所有。
