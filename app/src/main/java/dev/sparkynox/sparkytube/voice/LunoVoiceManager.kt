package dev.sparkynox.sparkytube.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Offline voice-command engine for "Luno Voice (Beta)" -- wraps Vosk
 * (vosk-model-small-en-us-0.15, ~40MB, English only) and turns raw speech
 * into one of the fixed LunoCommand cases below. MainActivity owns the
 * actual command execution (search, navigate, download) -- this class
 * only does listening + parsing, same split as StreamExtractor (resolve)
 * vs MainActivity (play) elsewhere in the app.
 *
 * Two trigger modes, both wired through the same start()/stop() pair:
 * push-to-talk (caller starts listening on a button tap, one command,
 * then stops) and always-listening wake-word (caller keeps calling
 * start() again after each result, only actually parsing what came after
 * hearing "luno" in the transcript). Which mode is active is decided by
 * the caller (SettingsPrefs.isLunoVoiceWakeWordModeEnabled) -- this class
 * doesn't know or care, it just recognizes speech and hands back text.
 */
object LunoVoiceManager {

    private const val TAG = "LunoVoice"
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val SAMPLE_RATE = 16000.0f

    @Volatile
    private var model: Model? = null
    @Volatile
    private var speechService: SpeechService? = null
    @Volatile
    private var isModelReady = false
    @Volatile
    private var isModelLoading = false

    @Volatile
    var lastErrorMessage: String? = null
        private set

    /**
     * Unpacks the model from assets/vosk-model-small-en-us-0.15/ (must be
     * bundled into app/src/main/assets by whoever ships this -- the model
     * itself isn't in this file, it has to be downloaded once from
     * alphacephei.com/vosk/models and dropped into assets/ before building)
     * into app-private storage and loads it. Safe to call repeatedly --
     * only does real work once. Must be called before startListening().
     */
    fun ensureModelLoaded(context: Context, onReady: (success: Boolean) -> Unit) {
        if (isModelReady) {
            onReady(true)
            return
        }
        if (isModelLoading) {
            // Already unpacking from a previous call -- StorageService.unpack
            // has no built-in "wait for existing unpack" support, so just
            // tell this caller no for now rather than kicking off a second
            // unpack of the same model in parallel.
            onReady(false)
            return
        }
        isModelLoading = true
        StorageService.unpack(
            context, MODEL_NAME, "model",
            { unpackedModel ->
                model = unpackedModel
                isModelReady = true
                isModelLoading = false
                lastErrorMessage = null
                onReady(true)
            },
            { exception ->
                isModelLoading = false
                lastErrorMessage = "Model load failed: ${exception.message ?: exception.javaClass.simpleName}"
                Log.e(TAG, "failed to unpack Luno Voice model", exception)
                dev.sparkynox.sparkytube.logs.LogRecorder.e(TAG, "model unpack failed", exception)
                onReady(false)
            }
        )
    }

    /**
     * Starts listening on the mic and calls back once with the final
     * recognized phrase (lowercased, trimmed) once Vosk considers the
     * utterance done, then stops itself -- one call = one command, whether
     * this was triggered by a push-to-talk tap or by the wake-word service
     * hearing "luno" and re-arming. Caller must have RECORD_AUDIO granted
     * already; this doesn't request it.
     */
    fun startListening(onFinalResult: (text: String) -> Unit, onError: (String) -> Unit) {
        val loadedModel = model
        if (!isModelReady || loadedModel == null) {
            onError("Luno Voice model isn't loaded yet")
            return
        }
        // Stop any previous session first -- SpeechService isn't meant to
        // have two overlapping recognize() calls on the same instance.
        stopListening()

        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    // Not surfaced to the UI for now -- MainActivity only
                    // cares about the final phrase to match against
                    // LunoCommand. Kept as a no-op override rather than
                    // omitted so callers of this file can see at a glance
                    // that partials exist and could be wired up later
                    // (e.g. live captions on the mic overlay).
                }

                override fun onResult(hypothesis: String?) {
                    val text = extractText(hypothesis)
                    if (text.isNotBlank()) {
                        onFinalResult(text)
                    }
                    stopListening()
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = extractText(hypothesis)
                    if (text.isNotBlank()) {
                        onFinalResult(text)
                    }
                    stopListening()
                }

                override fun onError(exception: Exception?) {
                    lastErrorMessage = exception?.message ?: "Unknown recognition error"
                    onError(lastErrorMessage ?: "Unknown recognition error")
                    stopListening()
                }

                override fun onTimeout() {
                    // Vosk's own silence-timeout -- treat the same as "no
                    // command heard", not a hard error worth a dialog.
                    stopListening()
                }
            })
        } catch (e: IOException) {
            lastErrorMessage = "Couldn't start recognizer: ${e.message}"
            Log.e(TAG, "startListening failed", e)
            onError(lastErrorMessage ?: "Couldn't start recognizer")
        }
    }

    fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    /** Vosk's onResult/onFinalResult hand back {"text": "..."} JSON, not plain text. */
    private fun extractText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return try {
            JSONObject(hypothesis).optString("text", "").trim().lowercase()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Matches a recognized phrase against Luno's fixed command set. Every
     * command must start with the "luno" wake word (push-to-talk mode
     * still says it, just for consistency -- keeps both trigger modes
     * feeling like the same feature). Returns null for anything that
     * doesn't match a known pattern, which the caller shows as "Didn't
     * catch that" rather than silently doing nothing.
     */
    fun parseCommand(phrase: String): LunoCommand? {
        val normalized = phrase.trim().lowercase()
        if (!normalized.startsWith("luno")) return null
        val rest = normalized.removePrefix("luno").trim()

        return when {
            rest.isEmpty() -> null

            rest.startsWith("search") -> {
                val query = rest.removePrefix("search").trim()
                if (query.isEmpty()) null else LunoCommand.Search(query)
            }

            rest == "open library" || rest == "go to library" || rest == "library" ->
                LunoCommand.OpenLibrary

            rest == "open subs" || rest == "open subscriptions" ||
                rest == "go to subs" || rest == "go to subscriptions" || rest == "subscriptions" ->
                LunoCommand.OpenSubscriptions

            rest == "go to home" || rest == "open home" || rest == "home" ->
                LunoCommand.OpenHome

            rest == "open shorts" || rest == "go to shorts" || rest == "shorts" ->
                LunoCommand.OpenShorts

            rest.startsWith("open search bar and search") -> {
                val query = rest.removePrefix("open search bar and search").trim()
                if (query.isEmpty()) LunoCommand.OpenSearchBar else LunoCommand.Search(query)
            }
            rest == "open search bar" || rest == "open search" ->
                LunoCommand.OpenSearchBar

            rest.startsWith("download the currently playing video") ||
                rest.startsWith("download currently playing video") ||
                rest.startsWith("download this video") ||
                rest.startsWith("download current video") -> {
                // Optional trailing quality, e.g. "...video in 1080p" /
                // "...video at 720p" -- default handled by the caller
                // (MainActivity) when qualityLabel comes back null.
                val qualityMatch = Regex("""(\d{3,4})p""").find(rest)
                LunoCommand.DownloadCurrentVideo(qualityMatch?.groupValues?.get(1)?.let { "${it}p" })
            }

            else -> null
        }
    }
}

/** Every recognizable Luno Voice command, matched by LunoVoiceManager.parseCommand. */
sealed class LunoCommand {
    data class Search(val query: String) : LunoCommand()
    object OpenLibrary : LunoCommand()
    object OpenSubscriptions : LunoCommand()
    object OpenHome : LunoCommand()
    object OpenShorts : LunoCommand()
    object OpenSearchBar : LunoCommand()
    // qualityLabel e.g. "1080p" if the user said one, null -> caller's default
    data class DownloadCurrentVideo(val qualityLabel: String?) : LunoCommand()
}
