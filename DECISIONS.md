# SonarCS Android — Архитектурные решения

Зафиксированные решения, принятые в процессе планирования.

---

## Триггер записи

**Решение:** Долгое нажатие Volume Down через `AccessibilityService.onKeyEvent()`

**Альтернативы которые отклонили:**
- Floating overlay button — требует отдельного разрешения `SYSTEM_ALERT_WINDOW`, менее удобно
- Notification action — нужно тянуть шторку, неудобно в процессе работы

**Детали реализации:**
- Короткое нажатие (`< 500ms`) — вручную вызвать `AudioManager.adjustVolume(ADJUST_LOWER)`, громкость меняется штатно
- Долгое нажатие (`≥ 500ms`) — начало записи; `onKeyEvent` возвращает `true` (event consumed), громкость не меняется
- Порог: `ViewConfiguration.getLongPressTimeout()` (~500ms) — системное значение, ощущается нативно
- `ACTION_DOWN` с `repeatCount > 0` игнорировать (нет повторного старта таймера)

---

## Вставка текста

**Решение:** `AccessibilityService` — `AccessibilityNodeInfo.ACTION_PASTE` с fallback на `ACTION_SET_TEXT`

**Почему не InputMethodService (IME):**
- IME работает только когда пользователь переключился на нашу клавиатуру
- Пользователь теряет свою привычную клавиатуру
- Требует от пользователя смены системной клавиатуры

**Цепочка fallback:**
1. `ACTION_PASTE` на focused node
2. `ACTION_SET_TEXT` (если paste вернул false)
3. Toast с текстом (если нода недоступна)

**Клипборд:** сохранить предыдущее содержимое, восстановить через 200ms после вставки (аналог `TextTyper.cs`).

---

## Одна служба = два назначения

**Решение:** `SonarAccessibilityService` отвечает и за перехват кнопки, и за вставку текста.

**Почему:** AccessibilityService нужен для обоих — одно разрешение вместо двух (не нужен отдельный `SYSTEM_ALERT_WINDOW`). Меньше разрешений → меньше трений при onboarding.

**Связь между службами:** `SonarBus` (singleton `MutableSharedFlow`) — AccessibilityService эмитит события кнопок, ForegroundService их собирает. Нет прямых ссылок между службами.

---

## ONNX модель

**Решение:** Пользователь копирует `giga-am-v3.onnx` (~215 MB) на устройство сам, выбирает файл через SAF picker в настройках. Приложение копирует в `filesDir`.

**Почему не бандлить в APK:**
- 215 MB делает APK неприемлемым для распространения через Play Store
- Аналогично поведению PC-версии (модель лежит рядом с .exe)

**ONNX Runtime:** `com.microsoft.onnxruntime:onnxruntime-android` — прямой перенос без конвертации модели.
- `abiFilters`: `arm64-v8a`, `x86_64`
- `setIntraOpNumThreads(2)`, `setInterOpNumThreads(1)` для mid-range устройств

---

## Язык и UI

**Решение:** Kotlin + Jetpack Compose (только для Settings Activity)

**Min SDK:** API 26 (Android 8.0) — охватывает ~95%+ активных устройств

---

## Хранение настроек

**Решение:** DataStore Preferences (замена SharedPreferences)

**Конфиг (аналог `sonar.json` на PC):**
```
modelPath: String?
dictOilGas: Boolean
dictLegal: Boolean
dictEconomy: Boolean
```

*Примечание:* `MicrophoneDevice` и `HotkeyVk` из PC-версии убраны — на Android не применимы.

---

## Микрофон

**Решение:** `AudioRecord` с `MediaRecorder.AudioSource.VOICE_RECOGNITION` — система сама выбирает устройство.

**Отличие от PC:** В C#-версии есть `DeviceNumber` для выбора конкретного микрофона. На Android API не позволяет выбирать физическое устройство через `AudioRecord` — фича убрана.

---

## Архитектура потоков

```
AccessibilityService (main thread)
    ↓ emits to SonarBus
ForegroundService (coroutine scope)
    ├── AudioRecorder (Dispatchers.IO)
    ├── GigaAmEngine.transcribe (Dispatchers.Default)
    └── TextInjector → AccessibilityService.instance (main thread)
```

**Правило:** `onKeyEvent` никогда не блокируется — всё через `SonarBus`.

---

## Очередь фраз

**Решение:** Порт логики `_pendingPcm` / `_capturingForQueue` из `TrayApp.cs`.

Если пользователь нажал Volume Down пока идёт распознавание:
- Записать следующую фразу (`_capturingForQueue = true`)
- Сохранить PCM в `_pendingPcm`
- После завершения текущего распознавания — автоматически запустить следующее

Реализовано в `RecognitionStateMachine.kt` — чистый Kotlin, без Android-зависимостей, полностью тестируемый.
