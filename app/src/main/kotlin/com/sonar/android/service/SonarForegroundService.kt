package com.sonar.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sonar.android.SonarApp
import com.sonar.android.asr.GigaAmEngine
import com.sonar.android.config.SettingsRepository
import com.sonar.android.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SonarForegroundService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service get() = this@SonarForegroundService
    }

    private val binder = LocalBinder()
    private val repo by lazy { SettingsRepository(this) }
    private val nm   by lazy { getSystemService(NotificationManager::class.java) }

    @Volatile private var engine: GigaAmEngine? = null
    @Volatile private var loadedPath: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIF_ID, buildNotification("Загрузка…", CLR_LOADING))

        lifecycleScope.launch {
            // Auto-extract bundled model on first launch
            val cfg = repo.config.first()
            if (cfg.modelPath.isEmpty() || !File(cfg.modelPath).exists()) {
                withContext(Dispatchers.IO) { extractBundledModel() }
            }

            // Watch settings — reload engine when model path changes
            repo.config.collect { c ->
                if (c.modelPath != loadedPath) {
                    updateNotification("Загрузка модели…", CLR_LOADING)
                    withContext(Dispatchers.IO) { reloadEngine(c.modelPath) }
                }
            }
        }

        // Mirror AccessibilityService events to the notification
        lifecycleScope.launch {
            SonarBus.events.collect { event ->
                when (event) {
                    is SonarBus.Event.RecordingStarted -> updateNotification("Запись…",             CLR_RECORDING)
                    is SonarBus.Event.Recognizing      -> updateNotification("Распознавание…",      CLR_RECOGNIZING)
                    is SonarBus.Event.Idle             -> updateNotification("Готов",                CLR_READY)
                    is SonarBus.Event.TextReady        -> updateNotification("Готов: «${event.text.take(40)}»", CLR_READY)
                    is SonarBus.Event.EngineReady      -> updateNotification("Готов",                CLR_READY)
                    is SonarBus.Event.EngineUnloaded   -> updateNotification("Модель не загружена",  CLR_LOADING)
                    is SonarBus.Event.Error            -> updateNotification("Ошибка: ${event.msg.take(40)}", CLR_ERROR)
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        engine?.close()
        engine = null
        super.onDestroy()
    }

    fun recognizeOnce(pcm: ByteArray): String = engine?.transcribe(pcm) ?: ""

    val isEngineReady get() = engine != null

    // Copies giga-am-v3.onnx from APK assets to internal storage on first launch.
    // Runs on IO thread; updates notification with progress.
    private fun extractBundledModel() {
        val dest = File(filesDir, BUNDLED_MODEL_NAME)
        try {
            updateNotification("Распаковка модели…", CLR_LOADING)
            assets.open(BUNDLED_MODEL_NAME).use { input ->
                val total = assets.openFd(BUNDLED_MODEL_NAME).length
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var written = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            updateNotification("Распаковка модели… $pct%", CLR_LOADING)
                        }
                    }
                }
            }
            // Save extracted path so SettingsRepository picks it up
            kotlinx.coroutines.runBlocking {
                val cfg = repo.config.first()
                repo.save(cfg.copy(modelPath = dest.absolutePath))
            }
            android.util.Log.i(TAG, "Bundled model extracted to ${dest.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Bundled model extraction failed: ${e.message}")
            dest.delete()
            SonarBus.post(SonarBus.Event.Error("Ошибка распаковки модели"))
        }
    }

    private fun reloadEngine(path: String) {
        engine?.close()
        engine = null
        loadedPath = path
        if (path.isEmpty()) {
            SonarBus.post(SonarBus.Event.EngineUnloaded)
            return
        }
        if (!File(path).exists()) {
            android.util.Log.w(TAG, "Model file not found: $path — re-extracting")
            extractBundledModel()
            return
        }
        try {
            val vocab = assets.open("giga-am-v3-vocab.txt").reader()
            engine = GigaAmEngine(path, vocab)
            SonarBus.post(SonarBus.Event.EngineReady)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Engine load failed: ${e.message}")
            SonarBus.post(SonarBus.Event.Error("Не удалось загрузить модель"))
        }
    }

    private fun updateNotification(status: String, color: Int) {
        nm.notify(NOTIF_ID, buildNotification(status, color))
    }

    private fun buildNotification(status: String, color: Int): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SonarApp.CHANNEL_ID)
            .setContentTitle("SonarCS")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(color)
            .setColorized(true)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG               = "SonarForegroundService"
        private const val BUNDLED_MODEL_NAME = "giga-am-v3.onnx"
        const val NOTIF_ID = 1

        val CLR_LOADING     = Color.GRAY
        val CLR_READY       = Color.rgb( 30, 120, 255)
        val CLR_RECORDING   = Color.rgb(220,  40,  40)
        val CLR_RECOGNIZING = Color.rgb(220, 120,   0)
        val CLR_ERROR       = Color.rgb(180,   0, 180)

        @Volatile var instance: SonarForegroundService? = null
    }
}
