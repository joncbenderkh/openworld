package dev.joncbender.openworld

import com.badlogic.gdx.Preferences

/** In-memory [Preferences] for tests - no libGDX application backend required. */
class FakePreferences : Preferences {
    private val values = mutableMapOf<String, Any?>()

    override fun putBoolean(key: String, `val`: Boolean): Preferences {
        values[key] = `val`
        return this
    }

    override fun putInteger(key: String, `val`: Int): Preferences {
        values[key] = `val`
        return this
    }

    override fun putLong(key: String, `val`: Long): Preferences {
        values[key] = `val`
        return this
    }

    override fun putFloat(key: String, `val`: Float): Preferences {
        values[key] = `val`
        return this
    }

    override fun putString(key: String, `val`: String): Preferences {
        values[key] = `val`
        return this
    }

    override fun put(vals: MutableMap<String, *>): Preferences {
        values.putAll(vals)
        return this
    }

    override fun getBoolean(key: String) = getBoolean(key, false)
    override fun getInteger(key: String) = getInteger(key, 0)
    override fun getLong(key: String) = getLong(key, 0L)
    override fun getFloat(key: String) = getFloat(key, 0f)
    override fun getString(key: String) = getString(key, "")

    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun getInteger(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getString(key: String, defValue: String) = values[key] as? String ?: defValue

    override fun get(): MutableMap<String, *> = values
    override fun contains(key: String) = values.containsKey(key)
    override fun clear() = values.clear()
    override fun remove(key: String) {
        values.remove(key)
    }
    override fun flush() {}
}
