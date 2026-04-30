# PixelCarrierSettings

Enable VoLTE for carriers in unsupported regions on Pixel devices.

Originally this app was made because I want to override specific configurations in one click (5G SA and signal thresholds). As of writing this, using `setImsProvisioningInt` (from [Ims](https://github.com/vvb2060/Ims)'s version 1.0, it doesn't have an UI) seems to be the only way to set VoLTE enabled persistently (explained below in "Override configurations" section), so I updated my app and made an UI for it.

## Requirements

- A Pixel device with **Magisk root** (KernelSU/APatch may also work but are untested)
- The app will request root permissions on first launch
- The app requires `READ_PHONE_STATE` permission to detect your SIM cards

## Usage

### Enable VoLTE

1. Install this app, start it, and grant root permissions when prompted by Magisk
2. Tap on **Enable VoLTE** for the SIM you want to enable
3. Go to system settings - Network & internet - SIMs - select the one you have enabled VoLTE for - turn on VoLTE (or 4G calling, depending on the carrier config)

- You may need to turn on VoLTE in system settings again if you reboot the system after using this method for the first time. Then it should stay on and there is no need to do it again for the same SIM card. Unlike `overrideConfig`, this will not be reset after reboots or system updates.
- **Restore VoLTE default behavior**: Tap the button to disable IMS provisioning for a SIM. This restores the system's default behavior where VoLTE availability is determined by the carrier config.
- **Reset IMS**: Tap to restart the IMS service for a SIM. Useful for refreshing the IMS registration state after applying overrides.

### Override configurations

From "Config overrides" menu (per SIM), you can override carrier configurations (the same as what Pixel IMS does, but these are some presets). Each feature shows its status (✅ Applied / ❌ Rejected / ○ Pending) after you run **Enable all features**.

Since Android 16 QPR2 Beta 3, calling `overrideConfig` with `persistent=true` is no longer possible for non-system apps. To work around this, the app implements its own persistence layer:

- Toggle **Persist settings after reboot** to save your overrides. The switch is automatically turned on if saved overrides already exist for that SIM.
- On every reboot, a foreground service automatically re-applies your saved overrides. The service responds to `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, and `CARRIER_CONFIG_CHANGED` broadcasts.
- A 60-second delayed retry is scheduled after boot to handle cases where SystemUI / IMS isn't ready yet.
- After re-applying overrides, the service automatically runs **Reset IMS** to refresh the IMS registration state.
- Overrides are stored in device-protected storage (Direct Boot aware), so they can be accessed before the first unlock.
- The foreground service shows a low-priority notification ("Re-applying carrier config") while restoring, then dismisses itself when done.

- **Enable all features**: Applies all overrides below sequentially, showing progress and per-feature status.
- **Enable VoLTE**: Sets `carrier_volte_available_bool` to true, not needed if you use the Enable VoLTE option on the first screen.
- **Enable NR(5G) SA**: Sets `carrier_nr_availabilities_int_array` to `[1, 2]` which enables both NSA and SA.
- **Enable VoNR(Vo5G)**: Sets `vonr_enabled_bool` and `vonr_setting_visibility_bool` to true. VoNR lets the device stay connected to 5G when calling instead of switching to LTE.
- **Enable VoWiFi**: Sets the following options:
    * `carrier_wfc_ims_available_bool` → true
    * `carrier_wfc_supports_wifi_only_bool` → true
    * `carrier_default_wfc_ims_roaming_enabled_bool` → true
    * `editable_wfc_mode_bool` → true
    * `editable_wfc_roaming_mode_bool` → true
    * `wfc_spn_format_idx_int` → `4`
- **Override 5G signal threshold**: Sets `5g_nr_ssrsrp_thresholds_int_array` to `[-115, -105, -95, -85]`. This option exists because with AOSP's default values I only get 1 bar 5G signal in my area, but on other devices the signal are full or at least 2 bars (dBm values are the same).
- **Disable Signal Inflate (5 bars to 4)**: Sets `inflate_signal_strength_bool` to false. Changes signal icon from 5 bars to 4, also makes unified signal icon possible if using 2 SIMs.
- **Show IMS status in SIM status**: Sets `show_ims_registration_status_bool` to true. This adds `IMS registration state` to About phone - SIM status. Android 16 QPR3 Beta 1 has a bug which crashes `com.android.phone` when opening PhoneInformation or PhoneInformationV2 test menu. This option allows seeing IMS status without going to the menu.
- **Show 4G instead of LTE**: Sets `show_4g_for_lte_data_icon_bool` to true. Changes the status bar icon from "LTE" to "4G".
- **Reset to system default**: Clears all overrides for the current SIM, restoring default carrier behavior.

If you need manual/custom overrides, please check out [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch).

## References

- [Pixel IMS](https://github.com/kyujin-cho/pixel-volte-patch)
- [Ims](https://github.com/vvb2060/Ims)
- [类原生5G信号差的解决办法](https://www.irvingwu.blog/posts/aosp-5g-signal-strength)