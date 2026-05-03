# PixelCarrierSettings

Enable VoLTE and override carrier configurations for carriers in unsupported regions on Pixel devices.

Originally this app was made for one-click carrier config overrides (5G SA and signal thresholds). Later, [Ims](https://github.com/vvb2060/Ims) discovered that `setImsProvisioningInt` can enable VoLTE persistently (surviving reboots, unlike `overrideConfig`), so this app was updated with a UI for both IMS provisioning and config overrides.

## Requirements

- A Pixel device with **Magisk root** (KernelSU/APatch may also work but are untested)
- **Android 14 or later** (SDK 34+)
- The app requires `READ_PHONE_STATE` permission to detect your SIM cards
- On Android 13+, the app will also request `POST_NOTIFICATIONS` permission (used by the foreground restoration service)
- Root access is automatically detected via libsu; you will be prompted to grant root in Magisk if not already authorized

## Usage

### Main screen — Enable VoLTE

1. Install this app, launch it, and grant root permissions when prompted by Magisk
2. The main screen shows up to two SIM cards with four buttons each:
   - **Enable VoLTE** — calls `setImsProvisioningInt` to persistently enable VoLTE. This survives reboots on its own.
   - **Restore VoLTE default behavior** — disables IMS provisioning, restoring the system default where VoLTE availability is determined by the carrier config.
   - **Reset IMS** — restarts the IMS service for the SIM. Useful for refreshing the IMS registration state after applying overrides.
   - **Config overrides** — opens the per-SIM configuration override screen.
3. After enabling VoLTE, go to system settings → Network & internet → SIMs → select the SIM → turn on VoLTE (or 4G calling, depending on the carrier config).
4. You may need to turn on VoLTE in system settings again after the first reboot. After that it should stay on permanently for that SIM.

### Config overrides screen — Override carrier configurations

From the "Config overrides" screen (per SIM, accessed from the main screen), you can override carrier configurations. Each feature shows its status (Applied / Rejected / Pending) after running **Enable all features**.

Since Android 16 QPR2 Beta 3, calling `overrideConfig` with `persistent=true` is blocked for non-system apps. To work around this, the app implements its own persistence layer:

- Toggle **Persist settings after reboot** to save your overrides. The switch automatically turns on if saved overrides already exist for that SIM.
- On every reboot, a foreground service automatically re-applies your saved overrides. The `CarrierConfigReceiver` listens for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, and `CARRIER_CONFIG_CHANGED` broadcasts.
  - For boot events: all SIMs with saved overrides are restored, with a 60-second delayed retry in case SystemUI / IMS is not yet ready.
  - For `CARRIER_CONFIG_CHANGED`: only the affected SIM is restored.
- After re-applying overrides, the service automatically runs **Reset IMS** to refresh the IMS registration state.
- Overrides are stored in device-protected storage (Direct Boot aware), so they can be accessed before the first unlock.
- The foreground service shows a low-priority notification ("Re-applying carrier config") while restoring, then dismisses itself when done.

#### Available overrides (applied sequentially by "Enable all features")

| # | Feature | Key(s) set |
|---|---------|------------|
| 1 | **Enable VoLTE** | `carrier_volte_available_bool` → `true` |
| 2 | **Enable NR(5G) SA** | `carrier_nr_availabilities_int_array` → `[1, 2]` (NSA + SA) |
| 3 | **Enable VoNR(Vo5G)** | `vonr_enabled_bool` → `true`; `vonr_setting_visibility_bool` → `true` |
| 4 | **Enable VoWiFi** | `carrier_wfc_ims_available_bool` → `true`; `carrier_wfc_supports_wifi_only_bool` → `true`; `carrier_default_wfc_ims_roaming_enabled_bool` → `true`; `editable_wfc_mode_bool` → `true`; `editable_wfc_roaming_mode_bool` → `true`; `wfc_spn_format_idx_int` → `4` |
| 5 | **Override 5G signal threshold** | `5g_nr_ssrsrp_thresholds_int_array` → `[-115, -105, -95, -85]` |
| 6 | **Disable Signal Inflate** | `inflate_signal_strength_bool` → `false` (5 bars → 4 bars; enables unified signal icon with dual SIM) |
| 7 | **Show IMS status** | `show_ims_registration_status_bool` → `true` (adds "IMS registration state" to About phone → SIM status) |
| 8 | **Show 4G instead of LTE** | `show_4g_for_lte_data_icon_bool` → `true` |

- **Reset to system default** — clears all overrides for the current SIM, including both session and persisted state.

## Architecture

The app runs an unprivileged UI process that communicates with a root-level service (`CarrierConfigRootService`) via AIDL IPC (libsu RootService). The root service uses `HiddenApiBypass` and reflection to access Android's hidden telephony APIs (`ITelephony`, `ICarrierConfigLoader`, `ISub`).

Temporary session overrides are accumulated in-memory (`PersistableBundle`); persisted overrides go to device-protected `SharedPreferences` via `CarrierConfigPersistence` (type-aware serialization supporting booleans, ints, longs, doubles, strings, and their arrays).

For custom/manual overrides, see [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch).

## References

- [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)
- [Ims](https://github.com/vvb2060/Ims)
- [类原生5G信号差的解决办法](https://www.irvingwu.blog/posts/aosp-5g-signal-strength)
