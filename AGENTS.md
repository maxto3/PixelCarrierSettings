# AGENTS.md — PixelCarrierSettings

Project overview: [README.md](README.md)

## Build & Run

```bash
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # ProGuard-optimized release APK
./gradlew build              # full build with checks
```

- AGP 8.13.2, Kotlin 2.3.0, JVM target 1.8
- compileSdk/targetSdk = 36, minSdk = 34

## Architecture

**Package**: `me.ikirby.pixelutils` — single flat package, ~350 LOC total.

| Component | File | Role |
|-----------|------|------|
| MainActivity | [MainActivity.kt](app/src/main/java/me/ikirby/pixelutils/MainActivity.kt) | Dual-SIM VoLTE provisioning via Shizuku |
| ConfigOverridesActivity | [ConfigOverridesActivity.kt](app/src/main/java/me/ikirby/pixelutils/ConfigOverridesActivity.kt) | Carrier config override presets |
| InstrumentationHelper | [InstrumentationHelper.kt](app/src/main/java/me/ikirby/pixelutils/InstrumentationHelper.kt) | Instrumentation-based privilege elevation for `overrideConfig()` |

## Key Patterns

### Shizuku Permission Flow
Always check `Shizuku.checkSelfPermission()` in `onStart()`. Call `init()` only after permission is granted. The app has no fallback — if Shizuku isn't running, show an error dialog and finish.

### Hidden API Access
```
HiddenApiBypass.setHiddenApiExemptions("")
```
Accesses hidden APIs via `TelephonyFrameworkInitializer` + `ShizukuBinderWrapper`:
- `ITelephony` → `setImsProvisioningInt()`, `resetIms()`
- `ISub` → `getActiveSubscriptionInfoList()`, `getSlotIndex()`

### Instrumentation-Based Config Override
`ConfigOverridesActivity` starts `InstrumentationHelper` via `startInstrumentation()` to call `CarrierConfigManager.overrideConfig()` with shell-level permissions. The helper uses `startDelegateShellPermissionIdentity()` / `stopDelegateShellPermissionIdentity()` to temporarily elevate.

### UI Binding
ViewBinding everywhere — no `findViewById`. Layouts: `activity_main.xml` (two SIM sections, visibility toggled), `activity_config_overrides.xml` (button grid).

## Conventions

- Minimal, single-package structure — keep it flat
- Toast + AlertDialog for user feedback
- Prefer `PersistableBundle` for typed carrier config values
- Version code = simple integer increment (current: 12)
- Theme: `Theme.DeviceDefault.Settings`

## Pitfalls

1. **Shizuku is mandatory** — app crashes without it; always check permission before any privileged operation
2. **Android 16 QPR2 Beta 3+**: `overrideConfig(persistent=true)` is blocked for non-system apps → overrides reset on reboot
3. **No tests exist** — manual testing only; add tests before refactoring
4. **Dual-SIM only** — UI hardcodes max 2 SIM slots
5. **System theme dependency** — `Theme.DeviceDefault.Settings` may break on custom ROMs
6. **Empty ProGuard rules** — relies entirely on AGP defaults; monitor release builds for missing keep rules
7. **`init()` failure is fatal** — if `ITelephony`/`ISub` initialization fails, app shows error and exits
