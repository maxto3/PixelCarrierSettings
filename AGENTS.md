# AGENTS.md

## 项目

单模块 Android 应用，用于在具备 Magisk root 的 Pixel 设备上开启 VoLTE 并覆盖运营商配置。

- **根项目名称**：`PixelUtils`（见于 `settings.gradle.kts`），但应用/构件名为 `PixelCarrierSettings`
- **包名**：`com.github.maxto3.pixelims`
- **minSdk** 34，**targetSdk/compileSdk** 36
- **Kotlin 2.3.0**，**AGP 8.13.2**，Java 8 目标

## 命令

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew assembleRelease        # 构建 release APK（启用 R8）
./gradlew build                  # 完整构建
```

无测试套件，无 lint 规则，无 CI。

## 架构

```
app/src/main/java/me/ikirby/pixelutils/
  MainActivity.kt              # 入口：每张 SIM 卡的 VoLTE 启用/禁用/重置
  ConfigOverridesActivity.kt   # 每张 SIM 卡的运营商配置覆盖 + 功能启用
  CarrierConfigRootService.kt  # RootService (libsu) — AIDL IPC；使用反射 + HiddenApiBypass 调用 Android 内部 API
  RestorationService.kt        # 前台服务，用于重启后重新应用配置
  CarrierConfigReceiver.kt     # 开机/运营商配置变更的广播接收器
  CarrierConfigPersistence.kt  # 基于 SharedPreferences 的 PersistableBundle 持久化（类型感知，支持 Direct Boot）
  RootShell.kt                 # libsu Shell 的轻量封装

app/src/main/aidl/me/ikirby/pixelutils/
  ICarrierConfigRootService.aidl  # 应用 ↔ root 服务 IPC 的 AIDL 接口
```

## 关键注意事项

- **版本号规则**：`versionCode` 和 `versionName` 使用当前日期格式，如 `20260504`。

- **需要 root (Magisk)**。应用使用 `libsu:core` + `libsu:service` 实现 root shell/RootService IPC。使用 `HiddenApiBypass` 通过反射访问 `@hide` 的内部 Android API（Telephony、CarrierConfig、ISub）。
- **AIDL 为生成代码**：`CarrierConfigRootService` 暴露 AIDL 接口。`aidl = true` 构建特性已启用。`CarrierConfigRootService.onBind()` 返回匿名 `ICarrierConfigRootService.Stub` 实现。
- **persistent=true 已被封堵**：自 Android 16 QPR2 起，非系统应用无法调用 `overrideConfig(persistent=true)`。应用通过实现自己的持久化层（`CarrierConfigPersistence`）和开机恢复服务来绕过此限制。
- **持久化发生在两处**：`ConfigOverridesActivity` 中的 `sessionOverrides` PersistableBundle 累积每个会话的覆盖状态，而 `CarrierConfigPersistence` 将完整合并后的状态存储到设备保护的 SharedPreferences（感知 Direct Boot，因此可在首次解锁前访问）。
- **R8/proguard 在 release 构建时启用**，但 `proguard-rules.pro` 为空——仅应用默认的 `proguard-android-optimize.txt` 规则。如果新增通过反射访问的类（如现有的 AIDL Stub），请确保它们不会被移除。
- **无测试基础设施。** 应用在搭载 Magisk 的实体 Pixel 设备上手动测试。没有单元测试、插桩测试或截图测试。
