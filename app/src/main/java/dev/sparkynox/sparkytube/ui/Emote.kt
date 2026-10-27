package dev.sparkynox.sparkytube.ui

import androidx.annotation.DrawableRes
import dev.sparkynox.sparkytube.R

// the artwork for popups and empty/error screens
enum class Emote(@DrawableRes val res: Int) {
    NO_INTERNET(R.drawable.emote_no_internet),
    ERROR(R.drawable.emote_error),
    CRASH(R.drawable.emote_crash),
    FEATURE_DISABLED(R.drawable.emote_disabled),   // disabled / banned by admin
    PLUGIN_ERROR(R.drawable.emote_plugin_error),
    VPN(R.drawable.emote_vpn),
    EMPTY(R.drawable.emote_empty),
    SUCCESS(R.drawable.emote_success),
}
