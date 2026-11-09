package dev.sparkynox.sparkytube.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** For work that must finish even after the menu or dialog that started it has closed. */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}
