package io.github.randomtools3377.keeppageloading

import android.content.Context
import androidx.core.content.edit

/** The little the app remembers: the last page, and whether it has asked its two questions. */
object Prefs {
    private fun prefs() = Page.app.getSharedPreferences("page", Context.MODE_PRIVATE)

    var lastAddress: String?
        get() = prefs().getString("lastAddress", null)
        set(value) = prefs().edit { putString("lastAddress", value) }

    var askedSetup: Boolean
        get() = prefs().getBoolean("askedSetup", false)
        set(value) = prefs().edit { putBoolean("askedSetup", value) }
}
