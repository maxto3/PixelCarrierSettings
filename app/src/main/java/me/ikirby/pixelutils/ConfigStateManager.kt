package me.ikirby.pixelutils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages persistent state of which carrier config overrides have been applied per SIM,
 * plus the global auto-apply-on-boot toggle.
 *
 * Each SIM is keyed by ICCID so configs survive subId changes across reboots.
 */
object ConfigStateManager {

    private const val TAG = "PixelCS.ConfigState"
    private const val PREFS_NAME = "config_state"
    private const val KEY_AUTO_APPLY = "auto_apply_enabled"
    private const val PREFIX_CONFIG = "cfg_"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            Log.d(TAG, "Initialized, autoApply=${isAutoApplyEnabled}")
        }
    }

    private fun requirePrefs(): SharedPreferences = prefs
        ?: throw IllegalStateException("ConfigStateManager not initialized. Call init() first.")

    /** Global toggle: should saved configs be automatically applied after boot? */
    var isAutoApplyEnabled: Boolean
        get() = requirePrefs().getBoolean(KEY_AUTO_APPLY, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_AUTO_APPLY, value).apply()

    /** Mark a specific config key as applied for the given ICCID. */
    fun markConfigApplied(iccid: String, key: ConfigKey) {
        val current = getAppliedConfigNames(iccid).toMutableSet()
        current.add(key.name)
        requirePrefs().edit().putStringSet(configKey(iccid), current).apply()
    }

    /** Get the set of config keys saved for the given ICCID. */
    fun getAppliedConfigs(iccid: String): Set<ConfigKey> {
        return getAppliedConfigNames(iccid).mapNotNull { name ->
            try { ConfigKey.valueOf(name) } catch (_: IllegalArgumentException) { null }
        }.toSet()
    }

    /** Returns all saved configs grouped by ICCID. */
    fun getAllConfigs(): Map<String, Set<ConfigKey>> {
        val all = requirePrefs().all
        val result = mutableMapOf<String, Set<ConfigKey>>()
        for ((key, _) in all) {
            if (key.startsWith(PREFIX_CONFIG)) {
                val iccid = key.removePrefix(PREFIX_CONFIG)
                val configs = getAppliedConfigs(iccid)
                if (configs.isNotEmpty()) {
                    result[iccid] = configs
                }
            }
        }
        return result
    }

    /** Clear all saved configs for the given ICCID (e.g. after reset). */
    fun clearConfigs(iccid: String) {
        requirePrefs().edit().remove(configKey(iccid)).apply()
    }

    private fun getAppliedConfigNames(iccid: String): Set<String> {
        return requirePrefs().getStringSet(configKey(iccid), emptySet()) ?: emptySet()
    }

    private fun configKey(iccid: String) = "$PREFIX_CONFIG$iccid"

    enum class ConfigKey {
        VOLTE,
        VONR,
        NR_MODE,
        WFC,
        SIGNAL_THRESHOLD,
        SIGNAL_INFLATE,
        SHOW_IMS,
        SHOW_4G
    }
}
