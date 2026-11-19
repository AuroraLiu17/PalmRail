# 掌上轨道-试玩版

此工程在 `AuroraLiu17/PalmRail` 的独立 `trial` 分支维护。该分支的仓库根目录就是试玩版 Android 工程；主应用在其独立分支维护。不要将试玩版根目录直接合并进主应用。

当前版本：`1.9.7-trial`（versionCode 29）。包名：`com.palmrail.singlerail`。使用 Kotlin、PICO Spatial SDK 6.2.8，要求 Android API 35 及相匹配的 PICO OS/Spatial 运行时。

## 玩法

启动后进入 Mixed 透视中的三板轨道。按下或注视起点红色按钮释放动态钢球，再抓握并摇动摇柄，将球送入终点。球完整进入终点轨道并实际撞击末端金属片后，才响铃并完成本轮。

本版本保留两行操作提示、20%应用补光、手托轨道银球图标及终点落地音。1.9.7 增加本地反射环境补偿、背景资源优化和接触回调优化；最终佩戴观感及性能不由构建、自动测试替代验收。

## 本地构建

安装 JDK 21、Android SDK Platform 35，并设置 `JAVA_HOME` 和 `ANDROID_HOME`，或在未纳入 Git 的 `local.properties` 中配置 SDK 路径。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease
```

没有本地签名配置时，Release 产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`。签名密钥及 `.signing/release.properties` 由应用所有者在本机管理，不属于仓库内容；云端签名须由发布服务提供。

## 公开依赖与发布构建

当前版本已适配公开可下载的 Spatial SDK 6.2.8，移除开发内网 Maven 源。依赖仅来自 Google Maven、Maven Central、Gradle Plugin Portal 和 PICO 的公开 Volcengine Maven 仓库。

已用全新 Gradle 缓存、空 Maven local、无凭据和无 Node 的环境完成 Debug/Release 构建与 21 项单元测试。发布前还会对最终提交输入重复验证。运行时资源、120Hz物理、完整入轨及终点撞片规则、真实眼手输入、Mixed透视、20%应用补光和音效保持原实现；提示面板补齐系统可读的语义名称，未新增代理控件或玩法动作。

在Swan上使用独立包名的Debug验证包进行动态回归，避免覆盖开发中的配色预览。模拟握持回归与真实佩戴手感、眼动完成率、实际听感和不同环境下的金属观感分别记录，不由编译通过推定。

## 维护边界

仅此分支维护试玩版源码和运行时资源。APK、构建缓存、SDK、本机配置、PICO 工具状态、签名材料和宣传上传暂存目录均排除在 Git 外。素材生成工具属于离线工具；Gradle 构建直接使用仓库中已有资源，不调用 Blender、Python 或 Spatial Editor 重新生成。
