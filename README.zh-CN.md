# PixelCarrierSettings

在 Pixel 设备上为不受支持地区的运营商启用 VoLTE 并覆盖运营商配置。

最初这个应用是为了能一键覆盖特定配置（5G SA 和信号阈值）而开发的。后来 [Ims](https://github.com/vvb2060/Ims) 发现 `setImsProvisioningInt` 可以持久化启用 VoLTE（能在重启后保持，这点与 `overrideConfig` 不同），因此本应用更新后同时提供了 IMS 配置与运营商配置覆盖的界面。

## 要求

- 一台已获取 **Magisk root** 的 Pixel 设备（KernelSU/APatch 可能也能用，但未经测试）
- **Android 14 或更高版本**（SDK 34+）
- 应用需要 `READ_PHONE_STATE` 权限以检测你的 SIM 卡
- Android 13+ 设备上应用还会请求 `POST_NOTIFICATIONS` 权限（供前台恢复服务使用）
- Root 权限通过 libsu 自动检测；如果 Magisk 尚未授权，会弹出授权提示

## 使用方法

### 主界面 — 启用 VoLTE

1. 安装此应用，启动它，并在 Magisk 提示时授予 root 权限
2. 主界面最多显示两张 SIM 卡，每张卡各四个按钮：
   - **启用 VoLTE** — 调用 `setImsProvisioningInt` 持久化启用 VoLTE。此设置自身即可在重启后保持。
   - **恢复 VoLTE 默认行为** — 禁用 IMS 配置，恢复由运营商配置决定 VoLTE 可用性的系统默认行为。
   - **重置 IMS** — 重启该 SIM 卡的 IMS 服务，适用于在应用覆盖后刷新 IMS 注册状态。
   - **配置覆盖** — 打开该 SIM 卡独立的运营商配置覆盖页面。
3. 启用 VoLTE 后，前往系统设置 → 网络和互联网 → SIM 卡 → 选择对应 SIM 卡 → 打开 VoLTE（或 4G 通话，取决于运营商配置）。
4. 首次重启后可能需要在系统设置中再次打开 VoLTE。之后该 SIM 卡的 VoLTE 应永久保持。

### 配置覆盖页面 — 覆盖运营商配置

在主界面点击"配置覆盖"进入每张 SIM 卡独立的覆盖页面。运行 **"启用所有功能"** 后，每项功能会显示其状态（已应用 / 已拒绝 / 待处理）。

自 Android 16 QPR2 Beta 3 起，非系统应用无法再使用 `persistent=true` 参数调用 `overrideConfig`。作为替代方案，本应用实现了自己的持久化层：

- 打开 **"重启后保持设置"** 开关以保存你的覆盖项。如果该 SIM 卡已有已保存的覆盖项，开关会自动开启。
- 每次重启时，前台服务会自动重新应用你保存的覆盖项。`CarrierConfigReceiver` 监听 `BOOT_COMPLETED`、`LOCKED_BOOT_COMPLETED` 和 `CARRIER_CONFIG_CHANGED` 广播。
  - 启动事件：所有有已保存覆盖项的 SIM 卡均会被恢复，并在 60 秒后进行延迟重试以应对 SystemUI / IMS 尚未就绪的情况。
  - `CARRIER_CONFIG_CHANGED` 事件：仅恢复受影响的 SIM 卡。
- 重新应用覆盖项后，该服务会自动执行 **"重置 IMS"** 以刷新 IMS 注册状态。
- 覆盖项存储在设备保护存储中（支持 Direct Boot），因此可以在首次解锁前访问。
- 前台服务在恢复过程中会显示低优先级通知（"重新应用运营商配置"），完成后自动关闭。

#### 可用覆盖项（"启用所有功能"按顺序依次应用）

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

- **重置为系统默认** — 清除当前 SIM 卡的所有覆盖项，包括会话状态和持久化数据。

## 架构

应用的非 root 界面进程通过 AIDL IPC（libsu RootService）与 root 级别的服务（`CarrierConfigRootService`）通信。Root 服务使用 `HiddenApiBypass` 和反射来访问 Android 隐藏的通讯 API（`ITelephony`、`ICarrierConfigLoader`、`ISub`）。

临时会话覆盖项在内存中累积（`PersistableBundle`）；持久化覆盖项通过 `CarrierConfigPersistence`（类型感知序列化，支持布尔、整型、长整型、双精度浮点、字符串及其数组）存储到设备保护的 `SharedPreferences` 中。

如需手动/自定义覆盖，请参见 [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)。

## 参考

- [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)
- [Ims](https://github.com/vvb2060/Ims)
- [类原生5G信号差的解决办法](https://www.irvingwu.blog/posts/aosp-5g-signal-strength)
