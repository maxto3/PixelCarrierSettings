# PixelCarrierSettings

在 Pixel 设备上为不受支持地区的运营商启用 VoLTE，并覆盖运营商配置。

最初这个应用是为了能一键覆盖特定配置（5G SA 和信号阈值）而开发的。后来 [Ims](https://github.com/vvb2060/Ims) 发现 `setImsProvisioningInt` 可以持久化启用 VoLTE（能在重启后保持，这点与 `overrideConfig` 不同），因此本应用更新后同时提供了 IMS 配置与运营商配置覆盖的界面。

在 Android 17 上，平台拒绝所有非系统应用使用 `overrideConfig(persistent=true)`，基于 Shizuku 的工具所使用的 shell 身份同样会被拒绝。本应用改为把 VoIMS opt-in 标志（provisioning key 68）写进订阅数据库，`ImsManager.isVolteEnabledByPlatform()` 会把它当作短路条件，从而绕过上述限制。其余功能仍然使用 CarrierConfig 覆盖，并配合开机重放服务。

## 要求

- 一台已获取 **Magisk root** 的 Pixel 设备（KernelSU/APatch 可能也能用，但未经测试）
- **Android 14 或更高版本**（SDK 34+）
- 应用需要 `READ_PHONE_STATE` 权限以检测 SIM 卡
- Android 13+ 设备上还会请求 `POST_NOTIFICATIONS` 权限（供前台恢复服务使用）
- Root 权限通过 libsu 自动检测；首次启动时 Magisk 会弹出授权提示

## 使用方法

### 主界面

主界面分为两张卡片。

**SIM 卡卡片**（每张活跃 SIM 一张）

- **配置覆盖** — 打开该 SIM 卡独立的覆盖页面。
- **更多操作** 菜单：
  - **启用 VoLTE（持久化）** — 写入 provisioning key 68（`voims_opt_in_status`）、VoLTE 用户开关和 VoNR。重启和系统更新后仍然保持。
  - **恢复 VoLTE 默认行为（key 68）** — 把 key 68 写回 `0`，不改动系统设置里的 VoLTE 开关，让 VoLTE 回到由运营商配置决定的默认判定。
  - **重置 IMS 栈** — 重启该 SIM 卡的 IMS 服务，不修改任何配置。

**工具卡片**

- **APN 编辑器** — 列出运营商 APN，点击可编辑 `type`。若已存在独立的 IMS APN，默认 APN 不应包含 `ims`；编辑器可以自动拆分，并在缺少独立 IMS APN 时创建 `apn=ims` 条目。
- **导出日志** — 抓取最近 3000 行日志并以文件形式分享。
- **备份配置 / 恢复配置** — 把已保存的覆盖导出为类型感知的 JSON 文件，或从该文件恢复。
- **修复网络验证（中国）** — 把联网检测地址切换到中国端点（`connectivitycheck.gstatic.cn` / `www.google.cn`），避免在中国大陆出现"已连接但无法访问互联网"的误判；再次点击可恢复默认。

### 自动 IMS 健康检查

每次启动和每次开机，应用都会静默检查并修复：

- VoIMS opt-in（key 68）是否为 `1`。
- VoLTE 用户开关与 VoNR 是否显式开启（`voims=1` 后用户开关不再回落到运营商默认值，未设置 `-1` 会被判为关闭）。
- APN 类型漂移：每 5 分钟复查一次，若 SIM/OTA 制式配置把 `ims` 重新合并进通用 APN，则再次拆分，并在缺少独立 IMS APN 时自动创建。
- 若 WLAN 通话设置为「WLAN 优先」但 IMS 未注册，自动回退到「移动网络优先」。Android 17 在该模式下会清空蜂窝语音域，运营商 ePDG 隧道不可用时会导致完全无法通话。

### 配置覆盖页面

重新进入页面后，每项功能会显示真实状态：快照中存在该功能时显示 **已应用**，否则显示 **未启用**，不再固定显示「待处理」。

自 Android 16 QPR2 Beta 3 起，非系统应用无法再使用 `persistent=true` 调用 `overrideConfig`。作为替代方案，本应用实现了自己的持久化层：

- 打开 **重启后保持设置** 开关以保存覆盖项。若该 SIM 卡已有已保存的覆盖项，开关会自动开启。
- 每次重启时，前台服务会自动重新应用保存的覆盖项。`CarrierConfigReceiver` 监听 `BOOT_COMPLETED`、`LOCKED_BOOT_COMPLETED` 和 `CARRIER_CONFIG_CHANGED` 广播。
  - 启动事件：所有有已保存覆盖项的 SIM 卡都会被恢复，并在 60 秒后进行延迟重试，以应对 SystemUI / IMS 尚未就绪的情况。
  - `CARRIER_CONFIG_CHANGED` 事件：仅恢复受影响的 SIM 卡。
- 重新应用覆盖项后，服务会自动执行 **重置 IMS** 以刷新注册状态。
- 覆盖项存储在设备保护存储中（支持 Direct Boot），可以在首次解锁前访问。
- 前台服务在恢复过程中会显示低优先级通知（"重新应用运营商配置"），完成后自动关闭。

#### 可用覆盖项（"启用全部功能"按顺序依次应用）

| # | 功能 | 设置的键值 |
|---|------|-----------|
| 1 | **启用 VoLTE** | `carrier_volte_available_bool` → `true` |
| 2 | **启用 NR(5G) SA** | `carrier_nr_availabilities_int_array` → `[1, 2]`（同时启用 NSA 和 SA） |
| 3 | **启用 VoNR(Vo5G)** | `vonr_enabled_bool` → `true`；`vonr_setting_visibility_bool` → `true` |
| 4 | **启用 VoWiFi** | `carrier_wfc_ims_available_bool` → `true`；`carrier_wfc_supports_wifi_only_bool` → `true`；`carrier_default_wfc_ims_roaming_enabled_bool` → `true`；`editable_wfc_mode_bool` → `true`；`editable_wfc_roaming_mode_bool` → `true`；`wfc_spn_format_idx_int` → `4` |
| 5 | **覆盖 5G 信号阈值** | `5g_nr_ssrsrp_thresholds_int_array` → `[-115, -105, -95, -85]` |
| 6 | **禁用信号夸大** | `inflate_signal_strength_bool` → `false`（5 格变 4 格；双 SIM 卡时还能实现统一信号图标） |
| 7 | **显示 IMS 状态** | `show_ims_registration_status_bool` → `true`（在关于手机 → SIM 卡状态中添加"IMS 注册状态"） |
| 8 | **显示 4G 而不是 LTE** | `show_4g_for_lte_data_icon_bool` → `true` |
| 9 | **UT 补充业务** | `carrier_supports_ss_over_ut_bool` → `true` |
| 10 | **TikTok 网络修复** | `sim_country_iso_override_string` → 随机 3 位数字地区码 |

- **恢复系统默认** — 清除当前 SIM 卡的所有覆盖项，包括会话状态、持久化数据以及本地状态快照。

页面同时显示所选 SIM 的 **IMS 注册状态**。

## 架构

应用的非 root 界面进程通过 AIDL IPC（libsu RootService）与 root 级别的服务（`CarrierConfigRootService`）通信。Root 服务使用 `HiddenApiBypass` 和反射访问 Android 隐藏的通讯 API（`ITelephony`、`ICarrierConfigLoader`、`ISub`）。

部分操作无法在 root 进程中直接使用框架 API：

- root 服务进程没有注册的 app 记录，`ContentResolver` 无法访问 telephony provider，因此 APN 的读写通过 `content` 命令行工具完成。
- `ImsManager` 依赖 `TelephonyFrameworkInitializer`，该对象在 root 进程中为空，因此 WLAN 通话模式通过 `ISub`（`wfc_ims_mode`）直接读写。

临时会话覆盖项在内存中累积（`PersistableBundle`）；持久化覆盖项通过 `CarrierConfigPersistence`（类型感知序列化，支持布尔、整型、长整型、双精度浮点、字符串及其数组）存储到设备保护的 `SharedPreferences` 中。已应用状态快照单独保存在 `applied_overrides_snapshot` 中。

如需手动/自定义覆盖，请参见 [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)。

## 构建

- JDK 17、Gradle 8.13（wrapper）、AGP 8.13.2、Kotlin 2.3.0
- Release 签名从 `local.properties`（`SIGN_KEY_STORE_FILE`、`SIGN_KEY_STORE_PASSWORD`、`SIGN_KEY_ALIAS`、`SIGN_KEY_PASSWORD`）或同名环境变量读取，CI 只需配置 Secrets：

```bash
./gradlew :app:assembleRelease
```

## 多语言

英文资源位于 `values/`，简体中文位于 `values-zh-rCN/`。应用会根据系统语言自动选择，无需手动切换。

## 调试日志生成

```bash
adb logcat -v time CarrierConfigRootSvc:* RestorationService:* CarrierConfigReceiver:* *:S | Tee-Object -FilePath pixelutils.log
```

## 参考

- [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)
- [Ims](https://github.com/vvb2060/Ims)
- [类原生5G信号差的解决办法](https://www.irvingwu.blog/posts/aosp-5g-signal-strength)
