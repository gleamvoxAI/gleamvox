// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech recognition by the Android system service. Prefers recognition on the device (Android 12+, needs the
 * language pack), otherwise the default recognition service chosen in the Android settings. We never choose a
 * service ourselves and never receive or keep audio: [RecognitionListener.onBufferReceived] is ignored.
 * Must be used on the main thread.
 */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechEngine.Listener? = null
    private var languageTag = ""
    private var onDevice = false
    private var gotSomething = false
    private var stopRequested = false

    override fun start(languageTag: String, listener: SpeechEngine.Listener) {
        this.languageTag = languageTag
        this.listener = listener
        stopRequested = false
        if (isOnDeviceAvailable(context)) startRecognizer(true)
        else startRecognizer(false)
    }

    private fun startRecognizer(useOnDevice: Boolean) {
        recognizer?.destroy()
        onDevice = useOnDevice
        gotSomething = false
        val r = try {
            if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            listener?.onError(VoiceError.OTHER)
            return
        }
        recognizer = r
        r.setRecognitionListener(Callback())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (useOnDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            addFormatting(this)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listener?.onError(VoiceError.OTHER)
        }
    }

    override fun stop() {
        stopRequested = true
        recognizer?.stopListening()
    }

    override fun cancel() {
        listener = null
        recognizer?.cancel()
    }

    override fun destroy() {
        listener = null
        recognizer?.destroy()
        recognizer = null
    }

    private inner class Callback : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onTrace(VoiceTrace.RecordingStarted("system"))
            listener?.onListening()
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) { listener?.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit // audio is never kept
        override fun onEndOfSpeech() { listener?.onEndOfSpeech() }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            gotSomething = true
            listener?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            listener?.onFinal(firstResult(results) ?: "")
        }

        override fun onError(error: Int) {
            // language pack for on-device recognition missing or service problem: try the normal service once
            if (onDevice && !gotSomething && !stopRequested && error in ON_DEVICE_RETRY_ERRORS && SpeechRecognizer.isRecognitionAvailable(context)) {
                startRecognizer(false)
                return
            }
            listener?.onError(mapError(error))
        }
    }

    companion object {
        // numbers of SpeechRecognizer.ERROR_* (some exist only on newer Android versions)
        private val ON_DEVICE_RETRY_ERRORS = setOf(4, 5, 11, 12, 13, 14)

        fun mapError(error: Int): VoiceError = when (error) {
            6, 7 -> VoiceError.NO_SPEECH // speech timeout, no match
            1, 2, 4, 10, 11 -> VoiceError.NETWORK // network timeout, network, server, too many requests, server disconnected
            3 -> VoiceError.AUDIO
            8 -> VoiceError.BUSY
            9 -> VoiceError.PERMISSION
            12, 13 -> VoiceError.LANGUAGE_NOT_SUPPORTED
            else -> VoiceError.OTHER
        }

        /**
         * Ask the recognizer for punctuation and capital letters (docs/DESIGN.md, stage 3g, point 1; Android 13+):
         * the first result is then the formatted text. Partial results without a jumping trailing punctuation mark.
         */
        fun addFormatting(intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            intent.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
        }

        private fun firstResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        private fun isOnDeviceAvailable(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && try { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } catch (_: Exception) { false }

        /** true if there is any speech recognition we can use */
        @JvmStatic
        fun isAvailable(context: Context): Boolean =
            try { SpeechRecognizer.isRecognitionAvailable(context) } catch (_: Exception) { false } || isOnDeviceAvailable(context)
    }
}
