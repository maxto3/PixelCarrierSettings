# PixelCarrierSettings

Enable VoLTE and override carrier configurations for carriers in unsupported regions on Pixel devices.

The app started as a one-click carrier config override tool (5G SA and signal thresholds). Later, [Ims](https://github.com/vvb2060/Ims) discovered that `setImsProvisioningInt` can enable VoLTE persistently (surviving reboots, unlike `overrideConfig`), so the app was updated with a UI for both IMS provisioning and config overrides.

On Android 17 builds the platform rejects `overrideConfig(persistent=true)` for every non-system app, and the shell delegate used by Shizuku-based tools is rejected as well. This app avoids that restriction by writing the VoIMS opt-in flag (provisioning key 68) to the subscription database, which `ImsManager.isVolteEnabledByPlatform()` treats as a short-circuit condition. CarrierConfig overrides are still applied for the other features, with a boot-time replay service.

## Requirements

- A Pixel device with **Magisk root** (KernelSU/APatch may also work but are untested)
- **Android 14 or later** (SDK 34+)
- `READ_PHONE_STATE` permission to detect SIM cards
- On Android 13+, `POST_NOTIFICATIONS` permission (used by the foreground restoration service)
- Root access is detected via libsu; Magisk prompts for authorization on first launch

## Usage

### Main screen

The main screen groups everything into two cards.

**SIM card** (one per active SIM)

- **Config overrides** — opens the per-SIM override screen.
- **More actions** menu:
  - **Enable VoLTE (persistent)** — writes provisioning key 68 (`voims_opt_in_status`), the VoLTE user switch and VoNR. This survives reboots and system updates.
  - **Restore VoLTE default (key 68)** — writes key 68 back to `0` without touching the user switch, returning VoLTE to the carrier default decision.
  - **Reset IMS stack** — restarts the IMS service for that SIM. Does not change any configuration.

**Tools card**

- **APN editor** — lists carrier APNs, tap to edit the `type` field. A default APN must not contain `ims` when a dedicated IMS APN exists; the editor can split it automatically and creates a dedicated `apn=ims` row when missing.
- **Export logcat** — captures the last 3000 log lines and shares them as a file.
- **Backup configuration / Restore configuration** — exports or imports the saved overrides as a type-aware JSON file.
- **Fix captive portal check (China)** — switches the connectivity probe to Chinese endpoints (`connectivitycheck.gstatic.cn` / `www.google.cn`) so Wi-Fi validation succeeds in mainland China, or restores the defaults.

### Automatic IMS health check

On every launch and every boot the app silently verifies and repairs:

- VoIMS opt-in (key 68) is `1`.
- The VoLTE user switch and VoNR are explicitly enabled (with `voims=1` the user switch no longer falls back to the carrier default, so an unset `-1` value would read as disabled).
- APN type drift: every 5 minutes it re-checks whether SIM/OTA provisioning merged `ims` back into a general-purpose APN, splits it again, and creates a dedicated IMS APN when none exists.
- If Wi-Fi calling is set to **Wi-Fi preferred** but IMS is not registered, it falls back to **cellular preferred**. Android 17 clears the cellular voice domain while Wi-Fi is preferred, so without this guard choosing "Wi-Fi preferred" loses voice service entirely when the carrier ePDG tunnel is unavailable.

### Config overrides screen

Each overridden feature shows its real status after the screen is re-opened: **Applied** when the feature is present in the last applied snapshot, **Not enabled** otherwise. The status is no longer a permanent "Pending".

Since Android 16 QPR2 Beta 3, calling `overrideConfig` with `persistent=true` is blocked for non-system apps. To work around this, the app implements its own persistence layer:

- Toggle **Persist settings after reboot** to save your overrides. The switch automatically turns on if saved overrides already exist for that SIM.
- On every reboot, a foreground service automatically re-applies your saved overrides. The `CarrierConfigReceiver` listens for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, and `CARRIER_CONFIG_CHANGED` broadcasts.
  - For boot events: all SIMs with saved overrides are restored, with a 60-second delayed retry in case SystemUI / IMS is not yet ready.
  - For `CARRIER_CONFIG_CHANGED`: only the affected SIM is restored.
- After re-applying overrides, the service automatically runs **Reset IMS** to refresh the IMS registration state.
- Overrides are stored in device-protected storage (Direct Boot aware), so they can be accessed before the first unlock.
- The foreground service shows a low-priority notification ("Re-applying carrier config") while restoring, then dismisses itself when done.

#### Available overrides ("Enable all features" applies them in order)

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
| 9 | **UT supplementary services** | `carrier_supports_ss_over_ut_bool` → `true` |
| 10 | **TikTok network fix** | `sim_country_iso_override_string` → a random 3-digit numeric region |

- **Reset to system default** — clears all overrides for the current SIM, including both session, persisted state and the local status snapshot.

The screen also shows the live **IMS registration** state for the selected SIM.

## Architecture

The app runs an unprivileged UI process that communicates with a root-level service (`CarrierConfigRootService`) via AIDL IPC (libsu RootService). The root service uses `HiddenApiBypass` and reflection to access Android's hidden telephony APIs (`ITelephony`, `ICarrierConfigLoader`, `ISub`).

A few operations cannot use the normal framework APIs from the root process:

- The root service has no registered app record, so `ContentResolver` cannot reach the telephony provider. APN reads/writes go through the `content` CLI instead.
- `ImsManager` depends on `TelephonyFrameworkInitializer`, which is empty in the root process. Wi-Fi calling mode is read and written through `ISub` (`wfc_ims_mode`) directly.

Temporary session overrides are accumulated in-memory (`PersistableBundle`); persisted overrides go to device-protected `SharedPreferences` via `CarrierConfigPersistence` (type-aware serialization supporting booleans, ints, longs, doubles, strings, and their arrays). The applied-state snapshot is stored separately in `applied_overrides_snapshot`.

For custom/manual overrides, see [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch).

## Build

- JDK 17, Gradle 8.13 (wrapper), AGP 8.13.2, Kotlin 2.3.0
- Release signing is read from `local.properties` (`SIGN_KEY_STORE_FILE`, `SIGN_KEY_STORE_PASSWORD`, `SIGN_KEY_ALIAS`, `SIGN_KEY_PASSWORD`) or the identically named environment variables, so CI only needs secrets:

```bash
./gradlew :app:assembleRelease
```

## Localization

English resources live in `values/`, Simplified Chinese in `values-zh-rCN/`. The correct set is picked automatically from the system language; no in-app switch is required.

## References

- [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)
- [Ims](https://github.com/vvb2060/Ims)
- [类原生5G信号差的解决办法](https://www.irvingwu.blog/posts/aosp-5g-signal-strength)
