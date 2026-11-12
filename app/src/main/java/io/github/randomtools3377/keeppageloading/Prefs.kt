package io.github.randomtools3377.keeppageloading

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException

/** The little the app remembers: the open tabs, and whether it has asked its two questions. */
object Prefs {
    private fun prefs() = Tabs.app.getSharedPreferences("page", Context.MODE_PRIVATE)

    /** The tabs' addresses, in order, and which one was being looked at. */
    class SavedTabs(val addresses: List<String>, val current: Int)

    var tabs: SavedTabs
        get() {
            val prefs = prefs()
            val json = prefs.getString("tabs", null)
                // Version 0.1 kept a single page.
                ?: return SavedTabs(listOfNotNull(prefs.getString("lastAddress", null)), 0)
            val array = try {
                JSONArray(json)
            } catch (e: JSONException) {
                JSONArray()
            }
            val addresses = (0 until array.length())
                .map { array.optString(it) }
                .filter { Address.isWebAddress(it) }
            return SavedTabs(addresses, prefs.getInt("currentTab", 0))
        }
        set(value) = prefs().edit {
            putString("tabs", JSONArray(value.addresses).toString())
            putInt("currentTab", value.current)
            remove("lastAddress")
        }

    fun clearTabs() = prefs().edit {
        remove("tabs")
        remove("currentTab")
        remove("lastAddress")
    }

    var askedSetup: Boolean
        get() = prefs().getBoolean("askedSetup", false)
        set(value) = prefs().edit { putBoolean("askedSetup", value) }
}
