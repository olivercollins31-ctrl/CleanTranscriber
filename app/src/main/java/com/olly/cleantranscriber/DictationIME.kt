package com.olly.cleantranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

class DictationIME : InputMethodService() {
    private var recognizer: SpeechRecognizer? = null
    private var wantOn = false
    private var secureField = false
    private var sessionBefore = ""
    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var micBtn: Button? = null

    // ---------- Keyboard UI ----------

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusView = v.findViewById(R.id.kbStatus)
        micBtn = v.findViewById(R.id.kbMic)

        micBtn?.setOnClickListener { if (wantOn) stopDictation() else startDictation() }
        v.findViewById<View>(R.id.kbSwitch).setOnClickListener {
            try {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            } catch (e: Exception) {
                setStatus("Could not open the keyboard picker")
            }
        }
        v.findViewById<View>(R.id.kbDelete).setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        v.findViewById<View>(R.id.kbSpace).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        v.findViewById<View>(R.id.kbEnter).setOnClickListener { pressEnter() }
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        secureField = isSecure(info)
        setStatus(if (secureField) "Dictation is off in password fields" else "Tap the mic and talk")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shutdown()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun isSecure(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    // ---------- Dictation control ----------

    private fun startDictation() {
        if (secureField) {
            setStatus("Dictation is off in password fields")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Allow the microphone in the Clean Transcriber app first")
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // status message above is enough
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("No speech recognition service found on this phone")
            return
        }
        wantOn = true
        micBtn?.setBackgroundResource(R.drawable.mic_bg_on)
        beginSession()
    }

    /** Graceful stop: let the recogniser deliver what it has heard, then clean up. */
    private fun stopDictation() {
        wantOn = false
        resetMic()
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            // ignore
        }
        handler.postDelayed({ destroyRecognizer() }, 2500)
        setStatus("Tap the mic and talk")
    }

    /** Hard stop: keyboard is closing. Keep any live preview text as typed. */
    private fun shutdown() {
        wantOn = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        resetMic()
        currentInputConnection?.finishComposingText()
    }

    private fun halt(message: String) {
        wantOn = false
        handler.post { destroyRecognizer() }
        resetMic()
        setStatus(message)
    }

    private fun beginSession() {
        if (!wantOn) return
        try {
            destroyRecognizer()
            sessionBefore = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString().orEmpty()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            r.startListening(buildIntent())
            recognizer = r
        } catch (e: Exception) {
            halt("Could not start listening: ${e.message}")
        }
    }

    private fun restartSoon(ms: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ beginSession() }, ms)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        recognizer = null
    }

    private fun buildIntent(): Intent {
        val prefs = Prefs(this)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.lang)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            // Android 13+ hint to auto-format; ignored on older versions
            putExtra("android.speech.extra.ENABLE_FORMATTING", "quality")
        }
    }

    // ---------- Text handling ----------

    /** Fit the cleaned text into the field: spacing and capital letter depend on what is already there. */
    private fun shape(raw: String, cleaned: String): String {
        if (cleaned.isEmpty()) return cleaned
        val before = sessionBefore
        val last = before.trimEnd().lastOrNull()
        val startsSentence =
            last == null || last == '.' || last == '?' || last == '!' || before.endsWith("\n")
        var t = cleaned
        val rawStartsLower = raw.trim().firstOrNull()?.isLowerCase() == true
        if (!startsSentence && rawStartsLower && !Regex("^I\\b").containsMatchIn(t)) {
            t = t.replaceFirstChar { it.lowercase() }
        }
        val needSpace = before.isNotEmpty() && !before.last().isWhitespace()
        return (if (needSpace) " " else "") + t
    }

    private fun commitFinal(raw: String) {
        val ic = currentInputConnection ?: return
        val prefs = Prefs(this)
        val cleaned = TextCleaner.clean(raw, prefs.fillers, prefs.punctuation, final = true)
        ic.commitText(shape(raw, cleaned), 1)
    }

    private fun showPreview(raw: String) {
        val ic = currentInputConnection ?: return
        val prefs = Prefs(this)
        val cleaned = TextCleaner.clean(raw, prefs.fillers, prefs.punctuation, final = false)
        ic.setComposingText(shape(raw, cleaned), 1)
    }

    private fun setStatus(msg: String) {
        statusView?.text = msg
    }

    private fun resetMic() {
        micBtn?.setBackgroundResource(R.drawable.mic_bg)
    }

    // ---------- Recogniser callbacks ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            setStatus("Listening...")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (p.isNotBlank()) showPreview(p)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            commitFinal(text)
            if (wantOn) restartSoon(100) else handler.post { destroyRecognizer() }
        }

        override fun onError(error: Int) {
            // Keep any live preview text rather than losing it
            currentInputConnection?.finishComposingText()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    halt("Microphone permission missing. Open the app to allow it.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    halt("That language is not available on this phone.")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    setStatus("Connection problem, retrying...")
                    if (wantOn) restartSoon(1500) else handler.post { destroyRecognizer() }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    if (wantOn) restartSoon(700) else handler.post { destroyRecognizer() }
                else ->
                    // no match or speech timeout: just listen again
                    if (wantOn) restartSoon(150) else handler.post { destroyRecognizer() }
            }
        }
    }
}
