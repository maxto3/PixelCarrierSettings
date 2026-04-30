# PixelCarrierSettings

在 Pixel 设备上为不受支持地区的运营商启用 VoLTE。

最初这个应用是为了能一键覆盖特定配置（5G SA 和信号阈值）而开发的。撰写本文时，使用 `setImsProvisioningInt`（来自 [Ims](https://github.com/vvb2060/Ims) 的 1.0 版本，它没有 UI）似乎是为 VoLTE 提供持久化启用的唯一方式（详见下方"覆盖配置"章节），因此我更新了应用并为其制作了界面。

## 要求

- 一台已获取 **Magisk root** 的 Pixel 设备（KernelSU/APatch 可能也能用，但未经测试）
- 应用首次启动时会请求 root 权限
- 应用需要 `READ_PHONE_STATE` 权限以检测你的 SIM 卡

## 使用方法

### 启用 VoLTE

1. 安装此应用，启动它，并在 Magisk 提示时授予 root 权限
2. 为你想要启用的 SIM 卡点击 **Enable VoLTE**
3. 前往系统设置 - 网络和互联网 - SIM 卡 - 选择你已启用 VoLTE 的那张卡 - 打开 VoLTE（或 4G 通话，取决于运营商配置）

- 如果你在首次使用此方法后重启系统，可能需要在系统设置中重新打开 VoLTE。之后它应该会保持开启状态，无需为同一张 SIM 卡重复操作。与 `overrideConfig` 不同，此方法不会在重启或系统更新后被重置。
- **恢复 VoLTE 默认行为**：点击按钮禁用某张 SIM 卡的 IMS 配置。这将恢复系统的默认行为，即 VoLTE 可用性由运营商配置决定。
- **重置 IMS**：点击以重启某张 SIM 卡的 IMS 服务。适用于在应用覆盖后刷新 IMS 注册状态。

### 覆盖配置

在"配置覆盖"菜单（每张 SIM 卡独立设置）中，你可以覆盖运营商配置（与 Pixel IMS 相同的功能，但这里提供了一些预设）。运行 **启用所有功能** 后，每项功能都会显示其状态（✅ 已应用 / ❌ 已拒绝 / ○ 待处理）。

自 Android 16 QPR2 Beta 3 起，非系统应用不再能使用 `persistent=true` 参数调用 `overrideConfig`。作为替代方案，本应用实现了自己的持久化层：

- 打开 **重启后保持设置** 开关以保存你的覆盖项。如果该 SIM 卡已有已保存的覆盖项，开关会自动开启。
- 每次重启时，前台服务会自动重新应用你保存的覆盖项。该服务会响应 `BOOT_COMPLETED`、`LOCKED_BOOT_COMPLETED` 和 `CARRIER_CONFIG_CHANGED` 广播。
- 启动后安排了 60 秒的延迟重试，以应对 SystemUI / IMS 尚未就绪的情况。
- 重新应用覆盖项后，该服务会自动执行 **重置 IMS** 以刷新 IMS 注册状态。
- 覆盖项存储在设备保护存储中（支持 Direct Boot），因此可以在首次解锁前访问。
- 前台服务在恢复过程中会显示低优先级通知（"重新应用运营商配置"），完成后自动关闭。

- **启用所有功能**：按顺序应用以下全部覆盖项，并显示进度和每项功能的状态。
- **启用 VoLTE**：将 `carrier_volte_available_bool` 设置为 true。如果你在首屏已使用了"启用 VoLTE"选项，则无需此项。
- **启用 NR(5G) SA**：将 `carrier_nr_availabilities_int_array` 设置为 `[1, 2]`，同时启用 NSA 和 SA。
- **启用 VoNR(Vo5G)**：将 `vonr_enabled_bool` 和 `vonr_setting_visibility_bool` 设置为 true。VoNR 让设备在通话时保持 5G 连接，而不是回落到 LTE。
- **启用 VoWiFi**：设置以下选项：
    * `carrier_wfc_ims_available_bool` → true
    * `carrier_wfc_supports_wifi_only_bool` → true
    * `carrier_default_wfc_ims_roaming_enabled_bool` → true
    * `editable_wfc_mode_bool` → true
    * `editable_wfc_roaming_mode_bool` → true
    * `wfc_spn_format_idx_int` → `4`
- **覆盖 5G 信号阈值**：将 `5g_nr_ssrsrp_thresholds_int_array` 设置为 `[-115, -105, -95, -85]`。这个选项存在的原因是在我的区域使用 AOSP 默认值时，5G 信号只能显示 1 格，但在其他设备上信号是满格或至少 2 格（dBm 值相同）。
- **禁用信号夸大（5 格变 4 格）**：将 `inflate_signal_strength_bool` 设置为 false。将信号图标从 5 格改为 4 格，在使用双 SIM 卡时还能实现统一信号图标。
- **在 SIM 状态中显示 IMS 状态**：将 `show_ims_registration_status_bool` 设置为 true。这会在"关于手机 - SIM 卡状态"中添加"IMS 注册状态"。Android 16 QPR3 Beta 1 存在一个错误，打开 PhoneInformation 或 PhoneInformationV2 测试菜单时会导致 `com.android.phone` 崩溃。此选项让你无需进入测试菜单即可查看 IMS 状态。
- **显示 4G 而不是 LTE**：将 `show_4g_for_lte_data_icon_bool` 设置为 true。将状态栏图标从"LTE"改为"4G"。
- **重置为系统默认**：清除当前 SIM 卡的所有覆盖项，恢复默认运营商行为。

如果你需要手动/自定义覆盖，请查看 [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)。

## 参考

- [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)
- [Ims](https://github.com/vvb2060/Ims)
- [类原生5G信号差的解决办法](https://www.irvingwu.blog/posts/aosp-5g-signal-strength)