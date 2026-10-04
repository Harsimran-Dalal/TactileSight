package com.tactilesight.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.tactilesight.core.Language
import com.tactilesight.core.SpeechIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speech out through Android's own on-device engine — **English only**.
 *
 * This is the mitigation ADR-0012 recorded and deferred: with Sarvam as the
 * only voice, losing the network leaves the app *silent, not degraded*,
 * including the safety-relevant fast lane. The venue network already measured
 * 0.3–15 MB/s with repeated drops. English no longer depends on it.
 *
 * **Indic stays on Sarvam** and that is not a temporary state. Android's
 * offline voices for Indian languages are absent on most handsets and poor
 * where present, while `bulbul:v3` is the reason the eleven-language demo works
 * at all. [RoutingSpeechIO] is what keeps the two apart.
 *
 * Two things are load-bearing here:
 *
 * 1. **The voice is forced offline.** `TextToSpeech` will happily pick a
 *    network voice — Google's engine prefers them, they sound better, and they
 *    reintroduce the exact dependency this class exists to remove. A voice is
 *    only accepted if `isNetworkConnectionRequired == false`, so "on-device"
 *    is checked rather than hoped for.
 * 2. **Unavailability is loud and early.** If no offline English voice is
 *    installed, [speak] throws instead of silently doing nothing, so the router
 *    can fall back to Sarvam. A TTS engine that accepts an utterance and never
 *    speaks it is indistinguishable from a dead app to a blind user.
 */
class AndroidSpeechIO(context: Context) : SpeechIO {

    private val appContext = context.applicationContext

    /**
     * The engine's own init result — **nothing more**.
     *
     * Voice selection deliberately does *not* happen in this callback. It fires
     * on a binder thread and can arrive before the `engine` property below has
     * been assigned, so a callback that touched `engine` would occasionally
     * dereference null during construction. Configuration is therefore done
     * lazily in [isAvailable], by which point construction has certainly
     * finished.
     */
    private val initStatus = CompletableDeferred<Int>()

    /**
     * One utterance at a time. `TextToSpeech` has a queue of its own, but its
     * progress callbacks are keyed by utterance id and interleaving two
     * suspending [speak] calls would let one resume the other's continuation.
     */
    private val turnstile = Mutex()

    /** Guards the one-time voice selection in [isAvailable]. */
    private val initLock = Mutex()
    private var available: Boolean? = null

    private var utteranceCount = 0L

    private val engine = TextToSpeech(appContext) { status -> initStatus.complete(status) }

    /**
     * Pick an offline English voice, preferring Indian English.
     *
     * `en-IN` first because that is the demo's audience and Sarvam's own
     * English code; `en-GB`/`en-US` are accepted rather than refused, since a
     * different English accent is a far smaller problem than no speech.
     */
    private fun configure(): Boolean {
        val offline = runCatching {
            val local = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
            // Every offline locale, not just English: this is how to find out
            // what a given handset can actually speak without a network, which
            // is the question every "can we drop the cloud for X" conversation
            // turns on.
            Log.i(TAG, "offline locales: ${local.map { it.locale }.distinct().sortedBy { it.toString() }}")
            local.filter { it.locale.language == "en" }
        }.getOrElse {
            // Some engines throw from getVoices() before they are fully up.
            Log.w(TAG, "could not enumerate voices", it)
            emptyList()
        }

        if (offline.isEmpty()) {
            Log.w(TAG, "no offline English voice installed — English stays on Sarvam")
            return false
        }

        val preferred = PREFERRED_REGIONS.firstNotNullOfOrNull { region ->
            offline.firstOrNull { it.locale.country.equals(region, ignoreCase = true) }
        } ?: offline.maxByOrNull { it.quality }!!

        engine.voice = preferred
        val result = engine.setLanguage(preferred.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "engine rejected ${preferred.locale}")
            return false
        }

        Log.i(TAG, "offline English voice: ${preferred.name} (${preferred.locale})")
        return true
    }

    /**
     * True once the engine is up and has an offline English voice.
     *
     * Selects the voice on first call and remembers the answer, so the router
     * can ask before every utterance without re-enumerating voices.
     */
    suspend fun isAvailable(): Boolean = initLock.withLock {
        available ?: run {
            val ok = initStatus.await() == TextToSpeech.SUCCESS && configure()
            available = ok
            ok
        }
    }

    /**
     * Speak [text]. Suspends until the utterance finishes.
     *
     * [language] must be English — the router owns that decision and anything
     * else arriving here is a wiring bug worth failing on rather than speaking
     * Tamil text in an English voice, which is audible nonsense rather than an
     * error. [translate] is ignored: the VLM already answers in English, so
     * English out needs no translation step (ADR-0012).
     */
    override suspend fun speak(text: String, language: Language, translate: Boolean) {
        require(language == Language.ENGLISH) {
            "AndroidSpeechIO speaks English only, got ${language.sarvamCode}"
        }
        if (text.isBlank()) return
        check(isAvailable()) { "no offline English voice available" }

        turnstile.withLock {
            val id = "tactilesight-${utteranceCount++}"
            suspendCancellableCoroutine { continuation ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && continuation.isActive) continuation.resume(Unit)
                    }

                    @Deprecated("Kept because the platform still calls it on older engines.")
                    override fun onError(utteranceId: String?) = onError(utteranceId, -1)

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Android TTS failed (code $errorCode)")
                            )
                        }
                    }
                })

                continuation.invokeOnCancellation { engine.stop() }

                val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (queued != TextToSpeech.SUCCESS && continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Android TTS refused the utterance")
                    )
                }
            }
        }
    }

    /** Releases the engine. The activity owns the lifetime; leaking it leaks audio. */
    fun shutdown() {
        runCatching { engine.stop(); engine.shutdown() }
            .onFailure { Log.w(TAG, "shutdown failed", it) }
    }

    private companion object {
        const val TAG = "AndroidSpeechIO"

        /** Indian English first — the demo's audience, and Sarvam's `en-IN`. */
        val PREFERRED_REGIONS = listOf("IN", "GB", "US")
    }
}
