package com.olly.cleantranscriber

import android.Manifest
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Shows a floating mic button whenever a normal text box is focused.
 * Tap it, talk, and cleaned text is typed into the box. Your normal keyboard is untouched.
 */
class DictationService : AccessibilityService() {
    private var wm: WindowManager? = null
    private var root: View? = null
    private var bubble: TextView? = null
    private var caption: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var recognizer: SpeechRecognizer? = null
    private var wantOn = false
    private val handler = Handler(Looper.getMainLooper())

    private val evalRunnable = Runnable { evaluateFocus() }
    private val restartRunnable = Runnable { beginSession() }
    private val destroyRunnable = Runnable { destroyRecognizer() }
    private val clearCaptionRunnable = Runnable { say("") }

    // ---------- Service lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            buildOverlay()
        } catch (e: Exception) {
            toast("Could not create the mic button: ${e.message}")
        }
        registerAccessibilityButton()
        evaluateFocus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        handler.removeCallbacks(evalRunnable)
        handler.postDelayed(evalRunnable, 150)
    }

    override fun onInterrupt() {
        shutdown()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        try {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        } catch (e: Exception) {
            // ignore
        }
        detachBubble()
        return super.onUnbind(intent)
    }

    // ---------- Floating mic button ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = LayoutInflater.from(this).inflate(R.layout.bubble_view, null)
        root = v
        bubble = v.findViewById(R.id.bubble)
        caption = v.findViewById(R.id.caption)

        val prefs = Prefs(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so the text box keeps focus and your keyboard stays put
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.END
        p.x = if (prefs.bubbleX >= 0) prefs.bubbleX else dp(8)
        p.y = if (prefs.bubbleY >= 0) prefs.bubbleY
        else (resources.displayMetrics.heightPixels * 0.4).toInt()
        params = p

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        bubble?.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        p.x = (startX - dx).toInt().coerceAtLeast(0)
                        p.y = (startY + dy).toInt().coerceAtLeast(0)
                        try {
                            wm?.updateViewLayout(root, p)
                        } catch (ex: Exception) {
                            // ignore
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs(this).bubbleX = p.x
                        Prefs(this).bubbleY = p.y
                    } else {
                        toggle()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachBubble() {
        if (attached || root == null) return
        try {
            wm?.addView(root, params)
            attached = true
        } catch (e: Exception) {
            toast("Could not show the mic button: ${e.message}")
        }
    }

    private fun detachBubble() {
        if (!attached) return
        try {
            wm?.removeView(root)
        } catch (e: Exception) {
            // ignore
        }
        attached = false
    }

    private fun setBubbleActive(on: Boolean) {
        bubble?.setBackgroundResource(if (on) R.drawable.mic_bg_on else R.drawable.mic_bg)
    }

    private fun say(msg: String) {
        caption?.text = msg
        caption?.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    private fun toast(msg: String) =
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()

    // ---------- Focus tracking ----------

    private fun findInputNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
    }

    private fun evaluateFocus() {
        val node = findInputNode()
        val usable = node != null && node.isEditable && !node.isPassword
        if (usable) {
            attachBubble()
        } else if (!wantOn) {
            detachBubble()
        }
    }

    // Optional shortcut: the system accessibility button / floating menu also toggles dictation
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            toggle()
        }
    }

    private fun registerAccessibilityButton() {
        try {
            val c = accessibilityButtonController
            if (c.isAccessibilityButtonAvailable) {
                c.registerAccessibilityButtonCallback(buttonCallback)
            }
        } catch (e: Exception) {
            // not available on this phone, the floating button still works
        }
    }

    // ---------- Dictation control ----------

    private fun toggle() {
        if (wantOn) stopDictation() else startDictation()
    }

    private fun startDictation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Allow the microphone in the Clean Transcriber app first")
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // toast above is enough
            }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("No speech recognition service found on this phone")
            return
        }
        val node = findInputNode()
        if (node == null || !node.isEditable || node.isPassword) {
            toast("Tap a text box first")
            return
        }
        wantOn = true
        setBubbleActive(true)
        handler.removeCallbacks(clearCaptionRunnable)
        beginSession()
    }

    /** Graceful stop: let the recogniser hand over what it has heard, then clean up. */
    private fun stopDictation() {
        wantOn = false
        setBubbleActive(false)
        handler.removeCallbacks(restartRunnable)
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            // ignore
        }
        handler.postDelayed(destroyRunnable, 2500)
    }

    private fun shutdown() {
        wantOn = false
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(destroyRunnable)
        destroyRecognizer()
        setBubbleActive(false)
        say("")
    }

    private fun halt(message: String) {
        wantOn = false
        handler.removeCallbacks(restartRunnable)
        handler.post { destroyRecognizer() }
        setBubbleActive(false)
        say(message)
        handler.removeCallbacks(clearCaptionRunnable)
        handler.postDelayed(clearCaptionRunnable, 4000)
    }

    private fun beginSession() {
        if (!wantOn) return
        try {
            destroyRecognizer()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            r.startListening(buildIntent())
            recognizer = r
        } catch (e: Exception) {
            halt("Could not start listening: ${e.message}")
        }
    }

    private fun restartSoon(ms: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, ms)
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

    // ---------- Typing into the focused box ----------

    private data class Field(val text: String, val start: Int, val end: Int)

    private fun readField(node: AccessibilityNodeInfo): Field {
        if (node.isShowingHintText) return Field("", 0, 0)
        val cur = node.text?.toString() ?: ""
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0) {
            s = cur.length
            e = cur.length
        }
        if (s > e) {
            val t = s
            s = e
            e = t
        }
        return Field(cur, s.coerceIn(0, cur.length), e.coerceIn(0, cur.length))
    }

    /** Fit the cleaned text in: spacing and first-letter case depend on what comes before it. */
    private fun shape(raw: String, cleaned: String, before: String): String {
        if (cleaned.isEmpty()) return cleaned
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

    private fun insertText(raw: String) {
        val prefs = Prefs(this)
        val cleaned = TextCleaner.clean(raw, prefs.fillers, prefs.punctuation, final = true)
        if (cleaned.isEmpty()) return

        val node = findInputNode()
        if (node == null || !node.isEditable || node.isPassword) {
            toast("Lost the text box. Tap it and try again.")
            return
        }
        val f = readField(node)
        val before = f.text.substring(0, f.start)
        val after = f.text.substring(f.end)
        val shaped = shape(raw, cleaned, before)
        val trailing = if (after.isNotEmpty() && !after.first().isWhitespace()) " " else ""

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before + shaped + trailing + after
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val pos = before.length + shaped.length
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        } else {
            pasteFallback(node, shaped + trailing)
        }
    }

    /** Some apps refuse direct text changes. Fall back to paste (this replaces your clipboard). */
    private fun pasteFallback(node: AccessibilityNodeInfo, text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                toast("This box won't take dictated text. It's on your clipboard, so paste it.")
            }
        } catch (e: Exception) {
            toast("Could not type into this box: ${e.message}")
        }
    }

    // ---------- Recogniser callbacks ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            say("Listening...")
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
            if (p.isNotBlank()) {
                val prefs = Prefs(this@DictationService)
                say(TextCleaner.clean(p, prefs.fillers, prefs.punctuation, final = false))
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) insertText(text)
            if (wantOn) {
                say("Listening...")
                restartSoon(100)
            } else {
                say("")
                handler.post { destroyRecognizer() }
            }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    halt("Microphone permission missing. Open the app to allow it.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    halt("That language is not available on this phone.")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    say("Connection problem, retrying...")
                    if (wantOn) restartSoon(1500) else handler.post { destroyRecognizer() }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    if (wantOn) restartSoon(700) else handler.post { destroyRecognizer() }
                else ->
                    // no match or speech timeout: just listen again
                    if (wantOn) restartSoon(150) else {
                        say("")
                        handler.post { destroyRecognizer() }
                    }
            }
        }
    }
}
