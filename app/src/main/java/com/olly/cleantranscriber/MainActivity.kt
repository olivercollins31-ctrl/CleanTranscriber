package com.olly.cleantranscriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var micStatus: TextView
    private lateinit var kbStatus: TextView

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        micStatus = findViewById(R.id.micStatus)
        kbStatus = findViewById(R.id.kbEnabledStatus)

        findViewById<Button>(R.id.btnMic).setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: Exception) {
                toast("Open Settings > Keyboard list and enable Clean Transcriber")
            }
        }
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e: Exception) {
                toast("Could not open the keyboard picker")
            }
        }

        val cbFill = findViewById<CheckBox>(R.id.cbFill)
        cbFill.isChecked = prefs.fillers
        cbFill.setOnCheckedChangeListener { _, c -> prefs.fillers = c }

        val cbPunc = findViewById<CheckBox>(R.id.cbPunc)
        cbPunc.isChecked = prefs.punctuation
        cbPunc.setOnCheckedChangeListener { _, c -> prefs.punctuation = c }

        val rg = findViewById<RadioGroup>(R.id.langGroup)
        rg.check(if (prefs.lang == "en-US") R.id.langUs else R.id.langUk)
        rg.setOnCheckedChangeListener { _, id ->
            prefs.lang = if (id == R.id.langUs) "en-US" else "en-GB"
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (granted) "Microphone: allowed" else "Microphone: not allowed yet"

        val on = try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (e: Exception) {
            false
        }
        kbStatus.text = if (on) "Keyboard: enabled" else "Keyboard: not enabled yet"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
