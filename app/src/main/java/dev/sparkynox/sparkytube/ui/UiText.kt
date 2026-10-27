package dev.sparkynox.sparkytube.ui

import androidx.compose.runtime.mutableStateMapOf

// text that plugins can replace. screens read through get(), so a change shows up right away.
// keys: home_greeting
object UiText {
    private val map = mutableStateMapOf<String, String>()

    fun get(key: String, default: String): String = map[key] ?: default

    fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    fun clearAll() = map.clear()
}
