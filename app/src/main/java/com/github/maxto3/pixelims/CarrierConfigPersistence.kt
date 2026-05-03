package com.github.maxto3.pixelims

import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle

/**
 * Centrally suppress the DEPRECATION warning for [PersistableBundle.get].
 * The typed replacement `get(key, defaultValue)` requires knowing the type
 * at the call site, which is not possible in generic bundle processing.
 */
@Suppress("DEPRECATION")
internal fun PersistableBundle.getAny(key: String): Any? = this.get(key)

/**
 * Deep hash code for bundle values, correctly handling primitive arrays
 * (whose default [Any.hashCode] is identity-based, not content-based).
 */
internal fun Any?.deepHashCode(): Int = when (this) {
    null -> 0
    is BooleanArray -> contentHashCode()
    is IntArray -> contentHashCode()
    is LongArray -> contentHashCode()
    is DoubleArray -> contentHashCode()
    is Array<*> -> contentHashCode()
    else -> hashCode()
}

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
    // Separator for key-name lists (same character, distinct constant for clarity)
    private const val KEYS_SEP = "\u001E"

    // Track the checksum of successfully applied overrides per subId to prevent
    // redundant re-application that would trigger another CARRIER_CONFIG_CHANGED broadcast.
    private val lastAppliedChecksums = mutableMapOf<Int, Int>()

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
    @Synchronized
    fun saveOverrides(context: Context, subId: Int, overrides: PersistableBundle?, merge: Boolean = true) {
        val prefs = getPrefs(context)
        val edit = prefs.edit()

        val existingKeys = prefs.getString(keysKey(subId), "")?.split(KEYS_SEP)?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()

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
            val obj = overrides.getAny(key) ?: continue
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
                    // SharedPreferences has no putDouble; store raw bits as Long for exact precision
                    edit.putLong(pk, obj.toRawBits())
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
                    // Store each double's raw bits as a long string for exact precision
                    edit.putString(pk, obj.map { it.toRawBits().toString() }.joinToString(ARRAY_SEP))
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
        edit.putString(keysKey(subId), existingKeys.joinToString(KEYS_SEP))
        edit.apply()
    }

    /**
     * Load a previously saved [PersistableBundle] for the given [subId].
     * Returns null if nothing was saved.
     */
    fun loadOverrides(context: Context, subId: Int): PersistableBundle? {
        val prefs = getPrefs(context)
        val keys = prefs.getString(keysKey(subId), "")?.split(KEYS_SEP)?.filter { it.isNotEmpty() }.orEmpty()
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
                        bundle.putDouble(key, Double.fromBits(prefs.getLong(pk, 0L)))
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
                            bundle.putDoubleArray(key, s.split(ARRAY_SEP).map { Double.fromBits(it.toLong()) }.toDoubleArray())
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
        return if (bundle.isEmpty) {
            if (keys.isNotEmpty()) {
                android.util.Log.w("CarrierConfigPersistence", "Keys exist but all values failed to load for subId=$subId — possible data corruption")
            }
            null
        } else bundle
    }
    /**
     * Clear all saved overrides for a given [subId].
     */
    @Synchronized
    fun clearOverrides(context: Context, subId: Int) {
        val prefs = getPrefs(context)
        val keys = prefs.getString(keysKey(subId), "")?.split(KEYS_SEP)?.filter { it.isNotEmpty() }.orEmpty()
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
        val keys = prefs.getString(keysKey(subId), "")?.split(KEYS_SEP)?.filter { it.isNotEmpty() }.orEmpty()
        return keys.isNotEmpty()
    }

    /**
     * Compare a single key's value between two PersistableBundles.
     * Returns true if the value for [key] exists and is equal in both bundles.
     * Handles primitive types and their array counterparts.
     */
    fun bundleValuesEqual(left: PersistableBundle, right: PersistableBundle, key: String): Boolean {
        val lv = left.getAny(key) ?: return false
        val rv = right.getAny(key) ?: return false
        return when (lv) {
            is Boolean -> rv is Boolean && lv == rv
            is Int -> rv is Int && lv == rv
            is Long -> rv is Long && lv == rv
            is Double -> rv is Double && lv == rv
            is String -> rv is String && lv == rv
            is BooleanArray -> rv is BooleanArray && lv.contentEquals(rv)
            is IntArray -> rv is IntArray && lv.contentEquals(rv)
            is LongArray -> rv is LongArray && lv.contentEquals(rv)
            is DoubleArray -> rv is DoubleArray && lv.contentEquals(rv)
            is Array<*> -> rv is Array<*> && lv.contentEquals(rv)
            else -> false
        }
    }

    /**
     * Returns true if overrides for [subId] should be re-applied (either
     * never applied before, or the persisted content has changed since the
     * last successful application).
     */
    fun shouldRestore(subId: Int, overrides: PersistableBundle): Boolean {
        val checksum = computeChecksum(overrides)
        val last = lastAppliedChecksums[subId]
        return last == null || last != checksum
    }

    /**
     * Mark the given [overrides] as successfully applied for [subId].
     */
    fun markAsRestored(subId: Int, overrides: PersistableBundle) {
        lastAppliedChecksums[subId] = computeChecksum(overrides)
    }

    /**
     * Clear the tracked checksum for [subId] so that the next
     * RestorationService pass will re-apply (used on reset).
     */
    fun clearRestored(subId: Int) {
        lastAppliedChecksums.remove(subId)
    }

    /**
     * Compute a content-based checksum for a PersistableBundle.
     * Uses key-sorted iteration for deterministic ordering and handles
     * arrays via deep-hash extensions.
     */
    private fun computeChecksum(bundle: PersistableBundle): Int {
        var result = 1
        for (key in bundle.keySet().sorted()) {
            result = 31 * result + key.hashCode()
            result = 31 * result + bundle.getAny(key).deepHashCode()
        }
        return result
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
                if (subId != null && subId >= 0) {
                    subIds.add(subId)
                }
            }
        }
        return subIds.toList().sorted()
    }
}
