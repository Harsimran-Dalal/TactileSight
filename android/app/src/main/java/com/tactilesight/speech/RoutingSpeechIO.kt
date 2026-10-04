package com.tactilesight.speech

import android.util.Log
import com.tactilesight.core.Language
import com.tactilesight.core.SpeechIO

/**
 * Sends English to the phone's own engine and everything else to Sarvam.
 *
 * ADR-0012 put all speech on Sarvam because small VLMs are weak in Indic
 * languages — that reasoning is about *Indic*, and it never applied to English.
 * English through the cloud bought nothing and cost the one thing the ADR
 * recorded as an accepted risk: with the network down the app is **silent, not
 * degraded**, including the fast lane a user relies on to avoid walking into
 * things.
 *
 * So the split is not "cheaper" or "faster", though it is both. It is that
 * English survives the venue network and Indic keeps the quality that makes the
 * eleven-language demo worth showing.
 *
 * **Sarvam remains the fallback for English too.** If the handset has no
 * offline English voice, or its engine fails mid-utterance, the request goes to
 * Sarvam rather than to silence — hard rule #4, every press yields speech. The
 * failure is logged, because "it quietly went back to cloud" is exactly the
 * kind of thing that looks fine until the network drops.
 */
class RoutingSpeechIO(
    private val onDevice: AndroidSpeechIO,
    private val cloud: SpeechIO,
) : SpeechIO {

    override suspend fun speak(text: String, language: Language, translate: Boolean) {
        if (language != Language.ENGLISH || !onDevice.isAvailable()) {
            cloud.speak(text, language, translate)
            return
        }

        try {
            onDevice.speak(text, language, translate)
        } catch (e: Exception) {
            Log.w(TAG, "on-device English failed — falling back to Sarvam", e)
            cloud.speak(text, language, translate)
        }
    }

    /**
     * Speak in order, in **runs** of consecutive same-backend utterances.
     *
     * Naively looping [speak] would be correct but slow: [SarvamSpeechIO]
     * overrides `speakAll` to fetch its clips concurrently, and a per-utterance
     * loop throws that away, reinstating the dead gap between "Choose
     * language." and "भाषा चुनें।" that the override was written to remove.
     *
     * Handing each backend its longest contiguous run keeps both properties —
     * Sarvam still fetches its own clips in parallel, and playback order across
     * the whole list is unchanged. Order matters more than either: overlapping
     * or reordered speech is unintelligible, and the setup prompt is the first
     * thing a new user ever hears.
     */
    override suspend fun speakAll(utterances: List<SpeechIO.Utterance>) {
        if (utterances.isEmpty()) return

        val useOnDevice = onDevice.isAvailable()
        var index = 0
        while (index < utterances.size) {
            val local = useOnDevice && utterances[index].language == Language.ENGLISH
            val run = mutableListOf<SpeechIO.Utterance>()
            while (index < utterances.size &&
                (useOnDevice && utterances[index].language == Language.ENGLISH) == local
            ) {
                run += utterances[index++]
            }

            if (local) {
                // One at a time, so a single bad utterance cannot take the run
                // with it — `speak` already falls back to Sarvam per utterance.
                run.forEach { speak(it.text, it.language, it.translate) }
            } else {
                cloud.speakAll(run)
            }
        }
    }

    private companion object {
        const val TAG = "RoutingSpeechIO"
    }
}
