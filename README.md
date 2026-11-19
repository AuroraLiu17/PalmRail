# 掌上轨道-试玩版

此工程在 `AuroraLiu17/PalmRail` 的独立 `trial` 分支维护。该分支的仓库根目录就是试玩版 Android 工程；主应用在其独立分支维护。不要将试玩版根目录直接合并进主应用。

当前版本：`1.9.7-trial`（versionCode 29）。包名：`com.palmrail.singlerail`。使用 Kotlin、PICO Spatial SDK 6.4.0，要求 Android API 35 及相匹配的 PICO OS/Spatial 运行时。

## 玩法

启动后进入 Mixed 透视中的三板轨道。按下或注视起点红色按钮释放动态钢球，再抓握并摇动摇柄，将球送入终点。球完整进入终点轨道并实际撞击末端金属片后，才响铃并完成本轮。

本版本保留两行操作提示、20%应用补光、手托轨道银球图标及终点落地音。1.9.7 增加本地反射环境补偿、背景资源优化和接触回调优化；最终佩戴观感及性能不由构建、自动测试替代验收。

## 本地构建

安装 JDK 21、Android SDK Platform 35，并设置 `JAVA_HOME` 和 `ANDROID_HOME`，或在未纳入 Git 的 `local.properties` 中配置 SDK 路径。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleRelease
```

没有本地签名配置时，Release 产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`。签名密钥及 `.signing/release.properties` 由应用所有者在本机管理，不属于仓库内容；云端签名须由发布服务提供。

## 云端构建状态

本次按用户授权上传 1.9.7 源码。原工程仍声明开发内网 Maven 源，Spatial SDK 6.4.0 在当前配置的公开 Volcengine Maven 仓库尚不可匿名获取。因此，本机缓存构建通过不代表 VibeLab 能在空缓存环境构建。

在确认官方公开 SDK 6.4.0 依赖来源之前，保持云端构建待验证；不要上传本机缓存或 SDK 私有二进制，也不要自动降级 SDK。后续发布配置和宣传素材在独立审核后补齐。

## 维护边界

仅此分支维护试玩版源码和运行时资源。APK、构建缓存、SDK、本机配置、PICO 工具状态、签名材料和宣传上传暂存目录均排除在 Git 外。素材生成工具属于离线工具；Gradle 构建直接使用仓库中已有资源，不调用 Blender、Python 或 Spatial Editor 重新生成。
