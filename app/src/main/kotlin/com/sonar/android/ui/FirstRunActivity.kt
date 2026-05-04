package com.sonar.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sonar.android.service.SonarAccessibilityService

class FirstRunActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) refreshState()
    }

    private var permissionsGranted  by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var batteryExempt        by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()

        // Skip wizard if everything is already configured
        if (permissionsGranted && accessibilityEnabled) {
            goToSettings(); return
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FirstRunScreen(
                        permissionsGranted   = permissionsGranted,
                        accessibilityEnabled = accessibilityEnabled,
                        batteryExempt        = batteryExempt,
                        onRequestPermissions = { requestRequiredPermissions() },
                        onOpenAccessibility  = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onRequestBattery     = { requestBatteryExemption() },
                        onDone               = { goToSettings() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        permissionsGranted   = hasRecordPermission()
        accessibilityEnabled = isAccessibilityEnabled()
        batteryExempt        = isBatteryOptExempt()
    }

    private fun hasRecordPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, SonarAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(cn, ignoreCase = true) }
    }

    private fun isBatteryOptExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestRequiredPermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        requestPermissions.launch(perms)
    }

    private fun requestBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun goToSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}

@Composable
private fun FirstRunScreen(
    permissionsGranted: Boolean,
    accessibilityEnabled: Boolean,
    batteryExempt: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestBattery: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Настройка SonarCS", style = MaterialTheme.typography.headlineMedium)

        StepCard(
            number      = "1",
            title       = "Разрешения",
            description = "Доступ к микрофону обязателен.",
            done        = permissionsGranted,
            action      = if (permissionsGranted) null else "Разрешить",
            onAction    = onRequestPermissions
        )

        StepCard(
            number      = "2",
            title       = "Специальные возможности",
            description = "Включите SonarCS в настройках → Специальные возможности для перехвата кнопки громкости.",
            done        = accessibilityEnabled,
            action      = if (accessibilityEnabled) null else "Открыть настройки",
            onAction    = onOpenAccessibility
        )

        StepCard(
            number      = "3",
            title       = "Оптимизация батареи",
            description = "Исключите приложение из оптимизации, чтобы сервис не отключался в фоне.",
            done        = batteryExempt,
            action      = if (batteryExempt) null else "Запросить исключение",
            onAction    = onRequestBattery
        )

        StepCard(
            number      = "4",
            title       = "Модель GigaAM v3",
            description = "Скопируйте giga-am-v3.onnx на телефон — выберите файл в Настройках после завершения визарда.",
            done        = false,
            action      = null,
            onAction    = {}
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick  = onDone,
            enabled  = permissionsGranted && accessibilityEnabled,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Далее")
        }
    }
}

@Composable
private fun StepCard(
    number: String,
    title: String,
    description: String,
    done: Boolean,
    action: String?,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier             = Modifier.padding(16.dp),
            verticalAlignment    = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape    = MaterialTheme.shapes.small,
                color    = if (done) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (done) "✓" else number,
                        color = if (done) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title,       style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
                if (action != null) {
                    TextButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
