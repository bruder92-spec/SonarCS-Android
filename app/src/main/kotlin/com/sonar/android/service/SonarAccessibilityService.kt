package com.sonar.android.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.sonar.android.audio.AudioRecorder
import com.sonar.android.config.SettingsRepository
import com.sonar.android.dict.DictionaryEngine
import com.sonar.android.inject.TextInjector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SonarAccessibilityService : AccessibilityService() {

    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler   = Handler(Looper.getMainLooper())
    private val sm        = RecognitionStateMachine()
    private val recorder  = AudioRecorder()
    private val repo      by lazy { SettingsRepository(this) }

    @Volatile private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private var dict = DictionaryEngine.fromReaders()

    override fun onServiceConnected() {
        scope.launch {
            repo.config.collect { cfg ->
                dict = DictionaryEngine.fromAssets(assets, cfg.dictOilGas, cfg.dictLegal, cfg.dictEconomy)
                if (cfg.modelPath.isNotEmpty()) {
                    startForegroundService(Intent(this@SonarAccessibilityService, SonarForegroundService::class.java))
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown()
            KeyEvent.ACTION_UP   -> handleUp()
            else                 -> false
        }
    }

    private fun handleDown(): Boolean {
        // Cancel any pending timer from a rapid double-press
        longPressRunnable?.let { handler.removeCallbacks(it) }

        val r = Runnable {
            longPressFired = true
            sm.onLongPressDown()?.let { executeCmd(it) }
        }
        longPressRunnable = r
        handler.postDelayed(r, LONG_PRESS_MS)
        return true  // always consume ACTION_DOWN
    }

    private fun handleUp(): Boolean {
        longPressRunnable?.let { handler.removeCallbacks(it); longPressRunnable = null }

        if (!longPressFired) {
            // Short press: pass through as manual volume adjustment
            (getSystemService(AUDIO_SERVICE) as AudioManager)
                .adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return true
        }
        longPressFired = false

        val pcm = recorder.stopRecording()
        sm.onKeyUp(pcm)?.let { executeCmd(it) }
        return true
    }

    private fun executeCmd(cmd: RecognitionStateMachine.Cmd) {
        when (cmd) {
            is RecognitionStateMachine.Cmd.StartRecording -> {
                try {
                    recorder.startRecording()
                    SonarBus.post(SonarBus.Event.RecordingStarted)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Recorder start failed", e)
                    sm.reset()
                }
            }
            is RecognitionStateMachine.Cmd.Recognize -> recognize(cmd.pcm)
            is RecognitionStateMachine.Cmd.GoIdle    -> SonarBus.post(SonarBus.Event.Idle)
        }
    }

    private fun recognize(pcm: ByteArray) {
        SonarBus.post(SonarBus.Event.Recognizing)
        scope.launch(Dispatchers.IO) {
            try {
                val svc = SonarForegroundService.instance
                val raw = svc?.recognizeOnce(pcm) ?: run {
                    android.util.Log.w(TAG, "ForegroundService not available")
                    ""
                }
                val cfg = repo.config.first()
                val localDict = DictionaryEngine.fromAssets(assets, cfg.dictOilGas, cfg.dictLegal, cfg.dictEconomy)
                val text = localDict.apply(raw)

                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        TextInjector.paste(this@SonarAccessibilityService, "$text ")
                        SonarBus.post(SonarBus.Event.TextReady(text))
                    }
                    val next = sm.onRecognitionDone()
                    next?.let { executeCmd(it) }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Recognition failed", e)
                withContext(Dispatchers.Main) {
                    sm.reset()
                    SonarBus.post(SonarBus.Event.Error(e.message ?: "Recognition error"))
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { sm.reset() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG          = "SonarAccessibility"
        private const val LONG_PRESS_MS = 500L
    }
}
