package me.ikirby.pixelutils

import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle

/**
 * Persists carrier config overrides to SharedPreferences so they can be
 * re-applied after a device reboot.
 *
 * Each PersistableBundle key is stored as a separate SharedPreferences entry
 * with a prefix that encodes the subId, using type-aware serialization.
 */
object CarrierConfigPersistence {

    private const val PREFS_NAME = "carrier_config_persistence"
    private const val PREFIX = "override_"

    // Type tags stored alongside values to preserve the exact PersistableBundle type
    private const val TYPE_BOOLEAN = "Z"
    private const val TYPE_INT = "I"
    private const val TYPE_LONG = "L"
    private const val TYPE_DOUBLE = "D"
    private const val TYPE_STRING = "S"
    private const val TYPE_BOOLEAN_ARRAY = "[Z"
    private const val TYPE_INT_ARRAY = "[I"
    private const val TYPE_LONG_ARRAY = "[L"
    private const val TYPE_DOUBLE_ARRAY = "[D"
    private const val TYPE_STRING_ARRAY = "[S"

    // Separator for array elements
    private const val ARRAY_SEP = "\u001E" // ASCII Record Separator

    private fun getPrefs(context: Context): SharedPreferences {
        // Use device-protected storage to ensure access before first unlock (Direct Boot)
        val deContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return deContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun prefKey(subId: Int, key: String): String = "${PREFIX}${subId}_$key"
    private fun typeKey(subId: Int, key: String): String = "${prefKey(subId, key)}__type"
    private fun keysKey(subId: Int): String = "${PREFIX}${subId}_keys" // stores comma-separated key names

    /**
     * Save a [PersistableBundle] to SharedPreferences for the given [subId].
     * If [merge] is true, new keys are added to existing ones.
     * Passing `null` overrides with [merge]=false clears all saved overrides for this subId.
     */
    fun saveOverrides(context: Context, subId: Int, overrides: PersistableBundle?, merge: Boolean = true) {
        val prefs = getPrefs(context)
        val edit = prefs.edit()

        val existingKeys = prefs.getString(keysKey(subId), "")?.split(",")?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()

        if (!merge || overrides == null) {
            // Clear previous keys if not merging or if resetting
            for (k in existingKeys) {
                edit.remove(prefKey(subId, k))
                edit.remove(typeKey(subId, k))
            }
            existingKeys.clear()
        }

        if (overrides == null || overrides.isEmpty) {
            if (!merge) {
                edit.remove(keysKey(subId))
                edit.apply()
            }
            return
        }

        for (key in overrides.keySet()) {
            @Suppress("DEPRECATION")
            val obj = overrides.get(key) ?: continue
            val pk = prefKey(subId, key)
            val tk = typeKey(subId, key)

            when (obj) {
                is Boolean -> {
                    edit.putString(tk, TYPE_BOOLEAN)
                    edit.putBoolean(pk, obj)
                }
                is Int -> {
                    edit.putString(tk, TYPE_INT)
                    edit.putInt(pk, obj)
                }
                is Long -> {
                    edit.putString(tk, TYPE_LONG)
                    edit.putLong(pk, obj)
                }
                is Double -> {
                    edit.putString(tk, TYPE_DOUBLE)
                    // PersistableBundle uses Double, SharedPreferences only has Float.
                    // Store as string to preserve precision.
                    edit.putString(pk, obj.toString())
                }
                is String -> {
                    edit.putString(tk, TYPE_STRING)
                    edit.putString(pk, obj)
                }
                is BooleanArray -> {
                    edit.putString(tk, TYPE_BOOLEAN_ARRAY)
                    edit.putString(pk, obj.joinToString(ARRAY_SEP))
                }
                is IntArray -> {
                    edit.putString(tk, TYPE_INT_ARRAY)
                    edit.putString(pk, obj.joinToString(ARRAY_SEP))
                }
                is LongArray -> {
                    edit.putString(tk, TYPE_LONG_ARRAY)
                    edit.putString(pk, obj.joinToString(ARRAY_SEP))
                }
                is DoubleArray -> {
                    edit.putString(tk, TYPE_DOUBLE_ARRAY)
                    edit.putString(pk, obj.joinToString(ARRAY_SEP))
                }
                is Array<*> -> {
                    // String array
                    if (obj.all { it is String? }) {
                        edit.putString(tk, TYPE_STRING_ARRAY)
                        edit.putString(pk, obj.joinToString(ARRAY_SEP))
                    }
                }
            }
            existingKeys.add(key)
        }
        edit.putString(keysKey(subId), existingKeys.joinToString(","))
        edit.apply()
    }

    /**
     * Load a previously saved [PersistableBundle] for the given [subId].
     * Returns null if nothing was saved.
     */
    fun loadOverrides(context: Context, subId: Int): PersistableBundle? {
        val prefs = getPrefs(context)
        val keys = prefs.getString(keysKey(subId), "")?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
        if (keys.isEmpty()) return null

        val bundle = PersistableBundle()
        for (key in keys) {
            val pk = prefKey(subId, key)
            val tk = typeKey(subId, key)
            val type = prefs.getString(tk, null) ?: continue

            try {
                when (type) {
                    TYPE_BOOLEAN -> bundle.putBoolean(key, prefs.getBoolean(pk, false))
                    TYPE_INT -> bundle.putInt(key, prefs.getInt(pk, 0))
                    TYPE_LONG -> bundle.putLong(key, prefs.getLong(pk, 0))
                    TYPE_DOUBLE -> {
                        val s = prefs.getString(pk, null)
                        if (s != null) bundle.putDouble(key, s.toDouble())
                    }
                    TYPE_STRING -> bundle.putString(key, prefs.getString(pk, null))
                    TYPE_BOOLEAN_ARRAY -> {
                        val s = prefs.getString(pk, "")
                        if (!s.isNullOrEmpty()) {
                            bundle.putBooleanArray(key, s.split(ARRAY_SEP).map { it.toBoolean() }.toBooleanArray())
                        } else {
                            bundle.putBooleanArray(key, booleanArrayOf())
                        }
                    }
                    TYPE_INT_ARRAY -> {
                        val s = prefs.getString(pk, "")
                        if (!s.isNullOrEmpty()) {
                            bundle.putIntArray(key, s.split(ARRAY_SEP).map { it.toInt() }.toIntArray())
                        } else {
                            bundle.putIntArray(key, intArrayOf())
                        }
                    }
                    TYPE_LONG_ARRAY -> {
                        val s = prefs.getString(pk, "")
                        if (!s.isNullOrEmpty()) {
                            bundle.putLongArray(key, s.split(ARRAY_SEP).map { it.toLong() }.toLongArray())
                        } else {
                            bundle.putLongArray(key, longArrayOf())
                        }
                    }
                    TYPE_DOUBLE_ARRAY -> {
                        val s = prefs.getString(pk, "")
                        if (!s.isNullOrEmpty()) {
                            bundle.putDoubleArray(key, s.split(ARRAY_SEP).map { it.toDouble() }.toDoubleArray())
                        } else {
                            bundle.putDoubleArray(key, doubleArrayOf())
                        }
                    }
                    TYPE_STRING_ARRAY -> {
                        val s = prefs.getString(pk, "")
                        if (!s.isNullOrEmpty()) {
                            bundle.putStringArray(key, s.split(ARRAY_SEP).toTypedArray())
                        } else {
                            bundle.putStringArray(key, arrayOf())
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip corrupted entries
                android.util.Log.w("CarrierConfigPersistence", "Failed to load key $key", e)
            }
        }
        return if (bundle.isEmpty) null else bundle
    }
    /**
     * Clear all saved overrides for a given [subId].
     */
    fun clearOverrides(context: Context, subId: Int) {
        val prefs = getPrefs(context)
        val keys = prefs.getString(keysKey(subId), "")?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
        val edit = prefs.edit()
        for (k in keys) {
            edit.remove(prefKey(subId, k))
            edit.remove(typeKey(subId, k))
        }
        edit.remove(keysKey(subId))
        edit.apply()
    }

    /**
     * Check whether a given [subId] has any saved overrides.
     */
    fun hasSavedOverrides(context: Context, subId: Int): Boolean {
        val prefs = getPrefs(context)
        val keys = prefs.getString(keysKey(subId), "")?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
        return keys.isNotEmpty()
    }

    /**
     * Scans SharedPreferences for all subIds that have persisted overrides.
     */
    fun getSubIdsWithOverrides(context: Context): List<Int> {
        val prefs = getPrefs(context)
        val subIds = mutableSetOf<Int>()
        val prefix = "override_"
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix) && key.endsWith("_keys") && key != "${prefix}keys") {
                // Key format: override_{subId}_keys
                val subIdStr = key.removePrefix(prefix).removeSuffix("_keys")
                val subId = subIdStr.toIntOrNull()
                if (subId != null) {
                    subIds.add(subId)
                }
            }
        }
        return subIds.toList().sorted()
    }
}
