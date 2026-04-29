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

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefKey(subId: Int, key: String): String = "${PREFIX}${subId}_$key"
    private fun typeKey(subId: Int, key: String): String = "${prefKey(subId, key)}__type"
    private fun keysKey(subId: Int): String = "${PREFIX}${subId}_keys" // stores comma-separated key names

    /**
     * Save a [PersistableBundle] to SharedPreferences for the given [subId].
     * Passing `null` clears all saved overrides for this subId.
     */
    fun saveOverrides(context: Context, subId: Int, overrides: PersistableBundle?) {
        val prefs = getPrefs(context)
        val edit = prefs.edit()

        // Clear any previously stored overrides for this subId
        val prevKeys = prefs.getString(keysKey(subId), "")?.split(",")?.filter { it.isNotEmpty() }.orEmpty()
        for (k in prevKeys) {
            edit.remove(prefKey(subId, k))
            edit.remove(typeKey(subId, k))
        }

        if (overrides == null || overrides.isEmpty) {
            edit.remove(keysKey(subId))
            edit.apply()
            return
        }

        val keySet = mutableSetOf<String>()
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
                    edit.putFloat(pk, obj.toFloat()) // SharedPrefs has no putDouble
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
                    if (obj.all { it is String }) {
                        edit.putString(tk, TYPE_STRING_ARRAY)
                        edit.putString(pk, obj.joinToString(ARRAY_SEP))
                    }
                }
            }
            keySet.add(key)
        }
        edit.putString(keysKey(subId), keySet.joinToString(","))
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
                    TYPE_DOUBLE -> bundle.putDouble(key, prefs.getFloat(pk, 0f).toDouble())
                    TYPE_STRING -> bundle.putString(key, prefs.getString(pk, null))
                    TYPE_BOOLEAN_ARRAY -> {
                        val arr = prefs.getString(pk, "")?.split(ARRAY_SEP)?.map { it.toBooleanStrict() }?.toBooleanArray()
                        if (arr != null) bundle.putBooleanArray(key, arr)
                    }
                    TYPE_INT_ARRAY -> {
                        val arr = prefs.getString(pk, "")?.split(ARRAY_SEP)?.map { it.toInt() }?.toIntArray()
                        if (arr != null) bundle.putIntArray(key, arr)
                    }
                    TYPE_LONG_ARRAY -> {
                        val arr = prefs.getString(pk, "")?.split(ARRAY_SEP)?.map { it.toLong() }?.toLongArray()
                        if (arr != null) bundle.putLongArray(key, arr)
                    }
                    TYPE_DOUBLE_ARRAY -> {
                        val arr = prefs.getString(pk, "")?.split(ARRAY_SEP)?.map { it.toDouble() }?.toDoubleArray()
                        if (arr != null) bundle.putDoubleArray(key, arr)
                    }
                    TYPE_STRING_ARRAY -> {
                        val arr = prefs.getString(pk, "")?.split(ARRAY_SEP)?.toTypedArray()
                        if (arr != null && arr.isNotEmpty()) bundle.putStringArray(key, arr)
                    }
                }
            } catch (_: Exception) {
                // Skip corrupted entries
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
}