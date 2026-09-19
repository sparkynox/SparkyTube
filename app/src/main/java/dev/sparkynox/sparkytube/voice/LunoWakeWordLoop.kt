package dev.sparkynox.sparkytube.voice

/**
 * Drives the "always listening" trigger mode from SettingsPrefs -- keeps
 * re-arming LunoVoiceManager.startListening() as long as the app is in
 * the foreground, so the user can just say "Luno ..." any time without
 * tapping a button first.
 *
 * Deliberately NOT a bound/foreground Android Service with its own
 * lifecycle -- that would keep the mic running even after the user backs
 * out of the app, which nobody asked for and would be a nasty privacy
 * surprise. Instead this is a plain object whose start()/stop() are
 * called from MainActivity's onResume/onPause, so listening only ever
 * happens while SparkyTube is the thing on screen.
 *
 * Each heard phrase already requires the "luno" prefix (parseCommand
 * enforces that), so this isn't doing separate wake-word detection before
 * a "real" listen -- every utterance IS the wake-word check, matching the
 * always-listening UX the user asked for without needing a second, always
 * running lightweight model just to detect "luno" before switching to
 * full recognition.
 */
object LunoWakeWordLoop {

    @Volatile
    private var isActive = false

    /**
     * @param onCommand called on the main thread with whatever LunoCommand
     *                   was parsed from a heard phrase (caller executes it)
     * @param onHeardButUnrecognized called when speech was heard and started
     *                   with "luno" but didn't match any known command --
     *                   lets the caller show a small "Didn't catch that"
     *                   toast without spamming one for every silence timeout
     */
    fun start(
        onCommand: (LunoCommand) -> Unit,
        onHeardButUnrecognized: () -> Unit
    ) {
        if (isActive) return
        isActive = true
        listenOnce(onCommand, onHeardButUnrecognized)
    }

    fun stop() {
        isActive = false
        LunoVoiceManager.stopListening()
    }

    private fun listenOnce(
        onCommand: (LunoCommand) -> Unit,
        onHeardButUnrecognized: () -> Unit
    ) {
        if (!isActive) return
        LunoVoiceManager.startListening(
            onFinalResult = { text ->
                if (text.startsWith("luno")) {
                    val command = LunoVoiceManager.parseCommand(text)
                    if (command != null) {
                        onCommand(command)
                    } else {
                        onHeardButUnrecognized()
                    }
                }
                // Re-arm regardless of whether "luno" was heard -- most
                // ambient speech in a video's audio won't start with it
                // and gets silently ignored, which is the whole point of
                // requiring the wake word.
                listenOnce(onCommand, onHeardButUnrecognized)
            },
            onError = {
                // Recognition hiccup (e.g. audio focus lost) -- back off
                // briefly and try again rather than giving up the loop
                // entirely, since the user never explicitly turned voice
                // off, something just went wrong for one cycle.
                if (isActive) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        listenOnce(onCommand, onHeardButUnrecognized)
                    }, 1000)
                }
            }
        )
    }
}
