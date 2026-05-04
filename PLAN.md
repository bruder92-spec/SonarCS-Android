# SonarCS Android — План реализации

Форк проекта SonarCS (распознавание речи на ПК) для Android.
Исходный проект: `C:\Users\Zver\Documents\Claude\SonarCS`

---

## Обзор

Приложение для офлайн-ввода текста голосом на Android. Пользователь долго нажимает Volume Down — идёт запись, отпускает — текст вставляется в активное поле. Короткое нажатие Volume Down работает стандартно (убавляет громкость).

---

## Фаза 1 — ASR ядро (JVM, без Android)

**Цель:** Kotlin-порт `GigaAmEngine` с unit-тестами, которые дают побайтово идентичный результат с C#-версией.

**Почему первым:** Самый рискованный компонент. Любое отклонение в Mel-спектрограмме или DFT даёт мусор на выходе. Лучше обнаружить это до написания Android-кода.

**Файлы:**
- `app/src/main/kotlin/.../asr/Vocab.kt` — парсинг `giga-am-v3-vocab.txt`
- `app/src/main/kotlin/.../asr/MelSpectrogram.kt` — DFT 320 точек, 64-канальный Mel HTK, нормализация per-feature
- `app/src/main/kotlin/.../asr/CtcDecoder.kt` — жадное CTC-декодирование
- `app/src/main/kotlin/.../asr/GigaAmEngine.kt` — оркестрация: PCM → Mel → ONNX → текст
- `app/src/test/.../asr/MelSpectrogramTest.kt` — сравнение с эталонным дампом из C#
- `app/src/test/.../asr/GigaAmEngineTest.kt` — эталонные WAV → эталонные транскрипты

**Ключевые константы (из C#, нельзя менять):**
- `N_FFT = 320`, `HOP = 160`, `N_MEL = 64`, `SR = 16000`
- Окно Ханна: периодическое (`2π·i/n`, не `n-1`)
- Mel-фильтрбанк: HTK-формулы (не Slaney)
- `center = false`
- Нормализация: `ln(max(e, 1e-9))`, затем per-feature mean/std (`1e-5` floor)

**Верификация:** `./gradlew :app:test` зелёный, Mel совпадает с C# дампом до `< 1e-4` на ячейку.

---

## Фаза 2 — Скелет проекта

**Цель:** Приложение устанавливается, запрашивает разрешения, запускает `ForegroundService` с уведомлением, регистрируется как `AccessibilityService`.

**Файлы:**
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `app/src/main/kotlin/.../SonarApp.kt` — Application, создаёт notification channel
- `app/src/main/kotlin/.../service/SonarAccessibilityService.kt` — заглушка с логированием
- `app/src/main/kotlin/.../service/SonarForegroundService.kt` — заглушка с уведомлением
- `app/src/main/kotlin/.../ui/FirstRunActivity.kt` — визард: разрешения + accessibility

**Разрешения:**
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (API 34+)
- `POST_NOTIFICATIONS` (API 33+)
- `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` (старше)
- `BIND_ACCESSIBILITY_SERVICE`

**Верификация:** `adb shell dumpsys accessibility` показывает `SonarAccessibilityService` как bound; `onKeyEvent` логирует KEYCODE_VOLUME_DOWN.

---

## Фаза 3 — Аудио + распознавание (без триггера)

**Цель:** Кнопка "Тест" в настройках записывает 3 сек, распознаёт, показывает результат в Toast.

**Файлы:**
- `app/src/main/kotlin/.../audio/AudioRecorder.kt` — `AudioRecord`, PCM 16-bit 16kHz mono
- `app/src/main/kotlin/.../dict/DictionaryEngine.kt` — порт C# `DictionaryEngine.cs`
- `app/src/main/kotlin/.../config/AppConfig.kt` — data class конфига
- `app/src/main/kotlin/.../config/SettingsRepository.kt` — DataStore Preferences
- `app/src/main/kotlin/.../ui/SettingsActivity.kt` — Jetpack Compose, пикер модели, тоглы словарей, кнопка теста
- Расширяем `SonarForegroundService.kt` — загрузка ONNX модели, метод `recognizeOnce(pcm)`

**Замечание по микрофону:** Android не поддерживает выбор устройства микрофона как на PC — `AudioRecord` использует `VOICE_RECOGNITION` source, система выбирает устройство сама. Настройка девайса убрана.

**Верификация:** Toast показывает корректный русский транскрипт.

---

## Фаза 4 — Триггер Volume Down + вставка текста

**Цель:** Полный цикл: долгое нажатие Volume Down → запись → распознавание → вставка текста в активное поле.

**Файлы:**
- `app/src/main/kotlin/.../service/RecognitionStateMachine.kt` — порт логики очереди из `TrayApp.cs` (чистый Kotlin, без Android)
- `app/src/main/kotlin/.../service/SonarBus.kt` — `MutableSharedFlow<Event>` для связи AccessibilityService ↔ ForegroundService
- `app/src/main/kotlin/.../inject/TextInjector.kt` — вставка через `AccessibilityNodeInfo.ACTION_PASTE`
- Расширяем `SonarAccessibilityService.kt` — long-press логика + вставка текста
- Расширяем `SonarForegroundService.kt` — подписка на SonarBus, оркестрация

**Логика long-press в `onKeyEvent`:**
```
ACTION_DOWN → consume, запустить таймер 500ms
  таймер сработал → longPressFired=true, начать запись
ACTION_UP:
  если NOT longPress → adjustVolume(ADJUST_LOWER) вручную
  если longPress → стоп записи → распознавание → вставка
```

**Очередь фраз:** Если пользователь нажал Volume Down пока идёт распознавание — записать следующую фразу, обработать после завершения текущей (аналог `_pendingPcm` из C#).

**Вставка текста — цепочка fallback:**
1. `AccessibilityNodeInfo.ACTION_PASTE` (основной)
2. `ACTION_SET_TEXT` (если paste не сработал)
3. Toast с текстом (последний резерв)

Клипборд сохраняется и восстанавливается (как в C# `TextTyper.cs`).

**Верификация:** Short press — громкость меняется; long press — текст вставляется в Telegram, Chrome, WhatsApp, Gmail.

---

## Фаза 5 — Полировка

**Цель:** Приложение готово к реальному использованию. Обработка всех edge cases.

**Что входит:**
- Battery optimization exemption flow в Settings
- Восстановление после: отзыв разрешений, удаление модели, поворот экрана
- ProGuard keep rules для `ai.onnxruntime.**`
- Notification с цветовой индикацией состояния (5 состояний как на PC)
- "Открыть Accessibility Settings" shortcut в настройках
- Пользовательский словарь (редактор в настройках)
- Тест триггера в настройках (для диагностики проблем OEM)

---

## Тестовая стратегия

**Unit (JVM):**
- `MelSpectrogramTest` — сравнение с C# дампом
- `GigaAmEngineTest` — эталонные WAV → транскрипты
- `CtcDecoderTest` — синтетические logits
- `DictionaryEngineTest` — граничные случаи, перекрытия, longest-match
- `RecognitionStateMachineTest` — все переходы состояний + очередь

**Instrumented:**
- `AudioRecorderTest` — запись 1 сек, проверка размера буфера
- `TextInjectorTest` — UiAutomator + тестовый EditText

**Manual E2E:**
- Telegram, WhatsApp, Chrome, Gmail, Samsung Notes
- Очередь фраз (hold-release-hold-release быстро)
- Short press не ломает громкость
- Выживание после screen-off/on и 30+ мин idle

---

## Риски

| Риск | Вероятность | Митигация |
|------|-------------|-----------|
| Mel/DFT расхождение с C# | Высокая | Фаза 1: fixture-тесты с бинарным дампом |
| `onKeyEvent` не срабатывает на некоторых OEM | Средняя | Кнопка "тест триггера" в настройках |
| `ACTION_PASTE` не работает в некоторых приложениях | Средняя | Fallback цепочка |
| ForegroundService убивается battery saver | Средняя | Battery optimization exemption в Settings |
| 215 MB модель не влезает в APK | — | Пользователь копирует модель сам (как на PC) |
