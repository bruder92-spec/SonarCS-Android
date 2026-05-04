package com.sonar.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.sonar.android.config.AppConfig
import com.sonar.android.config.SettingsRepository
import com.sonar.android.service.SonarAccessibilityService
import com.sonar.android.service.SonarForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : ComponentActivity() {

    private val repo by lazy { SettingsRepository(this) }
    private var boundService: SonarForegroundService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            boundService = (service as SonarForegroundService.LocalBinder).service
            engineReady = boundService?.isEngineReady ?: false
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
            engineReady = false
        }
    }

    private var config              by mutableStateOf(AppConfig())
    private var copyProgress        by mutableStateOf<Float?>(null)
    private var testResult          by mutableStateOf<String?>(null)
    private var accessibilityOn     by mutableStateOf(false)
    private var batteryExempt       by mutableStateOf(false)
    private var engineReady         by mutableStateOf(false)

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch { importModel(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            config = repo.config.first()
            val svcIntent = Intent(this@SettingsActivity, SonarForegroundService::class.java)
            startForegroundService(svcIntent)
            bindService(svcIntent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        config          = config,
                        copyProgress    = copyProgress,
                        testResult      = testResult,
                        accessibilityOn = accessibilityOn,
                        batteryExempt   = batteryExempt,
                        engineReady     = engineReady,
                        onPickModel     = { pickModel.launch(arrayOf("*/*")) },
                        onDictToggle    = { applyConfig(it) },
                        onTest          = { runTest() },
                        onDismissResult = { testResult = null },
                        onOpenAccessibility  = { openAccessibilitySettings() },
                        onRequestBattery     = { requestBatteryExemption() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityOn = isAccessibilityEnabled()
        batteryExempt   = isBatteryOptExempt()
        engineReady     = boundService?.isEngineReady ?: false
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }

    private fun applyConfig(updated: AppConfig) {
        config = updated
        lifecycleScope.launch { repo.save(updated) }
    }

    private suspend fun importModel(uri: Uri) {
        copyProgress = 0f
        val dest = File(filesDir, "giga-am-v3.onnx")
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { input ->
                val total = contentResolver.openFileDescriptor(uri, "r")?.statSize ?: -1L
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L; var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) copyProgress = written.toFloat() / total
                    }
                }
            }
        }
        copyProgress = null
        applyConfig(config.copy(modelPath = dest.absolutePath))
    }

    private fun runTest() {
        val svc = boundService ?: run { testResult = "Сервис не запущен"; return }
        if (!svc.isEngineReady) { testResult = "Модель не загружена"; return }
        testResult = "Запись 3 сек…"
        lifecycleScope.launch(Dispatchers.IO) {
            val recorder = com.sonar.android.audio.AudioRecorder()
            recorder.startRecording()
            kotlinx.coroutines.delay(3_000)
            val pcm  = recorder.stopRecording()
            val text = svc.recognizeOnce(pcm).ifBlank { "(тишина)" }
            withContext(Dispatchers.Main) { testResult = text }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, SonarAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(cn, ignoreCase = true) }
    }

    private fun isBatteryOptExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun openAccessibilitySettings() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun requestBatteryExemption() =
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        })
}

// ── Compose ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsScreen(
    config: AppConfig,
    copyProgress: Float?,
    testResult: String?,
    accessibilityOn: Boolean,
    batteryExempt: Boolean,
    engineReady: Boolean,
    onPickModel: () -> Unit,
    onDictToggle: (AppConfig) -> Unit,
    onTest: () -> Unit,
    onDismissResult: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestBattery: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Настройки SonarCS", style = MaterialTheme.typography.headlineSmall)

        // Status card
        StatusCard(
            accessibilityOn = accessibilityOn,
            batteryExempt   = batteryExempt,
            engineReady     = engineReady,
            modelSet        = config.modelPath.isNotEmpty(),
            onOpenAccessibility = onOpenAccessibility,
            onRequestBattery    = onRequestBattery
        )

        // Model section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Модель GigaAM v3", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (config.modelPath.isEmpty()) "Файл не выбран"
                    else "Загружен: ${File(config.modelPath).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (copyProgress != null) {
                    LinearProgressIndicator(progress = { copyProgress }, modifier = Modifier.fillMaxWidth())
                    Text("Копирование: ${(copyProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onPickModel) { Text("Выбрать файл модели…") }
                }
            }
        }

        // Dictionaries section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Словари замен", style = MaterialTheme.typography.titleMedium)
                DictToggle("Нефть и газ", config.dictOilGas) { onDictToggle(config.copy(dictOilGas = it)) }
                DictToggle("Юридический", config.dictLegal)  { onDictToggle(config.copy(dictLegal = it)) }
                DictToggle("Экономика",   config.dictEconomy) { onDictToggle(config.copy(dictEconomy = it)) }
            }
        }

        // Test section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Тест микрофона", style = MaterialTheme.typography.titleMedium)
                Text("Записывает 3 секунды и показывает результат.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick  = onTest,
                    enabled  = config.modelPath.isNotEmpty() && copyProgress == null
                ) { Text("Тест (3 сек)") }
            }
        }
    }

    if (testResult != null) {
        AlertDialog(
            onDismissRequest = onDismissResult,
            title   = { Text("Результат") },
            text    = { Text(testResult) },
            confirmButton = { TextButton(onClick = onDismissResult) { Text("OK") } }
        )
    }
}

@Composable
private fun StatusCard(
    accessibilityOn: Boolean,
    batteryExempt: Boolean,
    engineReady: Boolean,
    modelSet: Boolean,
    onOpenAccessibility: () -> Unit,
    onRequestBattery: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Статус", style = MaterialTheme.typography.titleMedium)

            StatusRow(
                label = "Специальные возможности",
                ok    = accessibilityOn,
                okText   = "Включено",
                failText = "Выключено"
            ) {
                if (!accessibilityOn) TextButton(onClick = onOpenAccessibility) { Text("Открыть настройки") }
            }

            StatusRow(
                label = "Движок ASR",
                ok    = engineReady,
                okText   = "Загружен",
                failText = if (modelSet) "Загружается…" else "Модель не выбрана"
            ) {}

            StatusRow(
                label = "Оптимизация батареи",
                ok    = batteryExempt,
                okText   = "Исключено",
                failText = "Активна (сервис может отключаться)"
            ) {
                if (!batteryExempt) TextButton(onClick = onRequestBattery) { Text("Запросить исключение") }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    okText: String,
    failText: String,
    action: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (ok) okText else failText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
        action()
    }
}

@Composable
private fun DictToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.align(Alignment.CenterVertically))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
