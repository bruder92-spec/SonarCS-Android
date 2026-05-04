# SonarCS Android

Офлайн-распознавание речи для Android на базе [GigaAM v3](https://github.com/salute-developers/GigaAM) от SberDevices.  
Android-форк настольного приложения [SonarCS](https://github.com/bruder92-spec/SonarCS).

## Скачать

**[⬇ SonarCS-Android-v1.0.0.apk](https://github.com/bruder92-spec/SonarCS-Android/releases/latest/download/app-debug.apk)** (287 МБ, модель встроена)

> Установка: Файлы → открыть APK → разрешить установку из неизвестных источников

## Как работает

Долгое нажатие кнопки **уменьшения громкости** (≥ 500 мс) → запись голоса → распознавание → текст вставляется в активное поле ввода.  
Короткое нажатие работает стандартно — регулирует громкость.

```
Долгий Volume Down ──► Запись ──► GigaAM v3 ──► Словарь замен ──► Вставка текста
```

## Возможности

- **Полностью офлайн** — модель встроена в APK, интернет не нужен
- **Русский язык** — GigaAM v3, WER ≈ 3.3%, автоматическая пунктуация
- **Работает везде** — Telegram, WhatsApp, Chrome, Gmail, любое поле ввода
- **Очередь фраз** — можно начать следующую запись пока идёт распознавание
- **Словари замен** — отраслевые термины (нефть/газ, юридический, экономика)
- **Цветное уведомление** — синий (готов) / красный (запись) / оранжевый (распознаёт)

## Требования

- Android 8.0+ (API 26)
- ~500 МБ свободного места (модель распаковывается при первом запуске)
- Разрешение на запись звука
- Включённая служба специальных возможностей SonarCS

## Сборка

```bash
# Клонировать репозиторий
git clone https://github.com/bruder92-spec/SonarCS-Android.git
cd SonarCS-Android

# Скопировать модель в assets (215 МБ, не входит в репозиторий)
cp /путь/до/giga-am-v3.onnx app/src/main/assets/

# Собрать APK
./gradlew :app:assembleDebug

# Установить на подключённое устройство
adb install app/build/outputs/apk/debug/app-debug.apk
```

> **Windows:** вместо `./gradlew` используйте `gradlew.bat`

### Где взять модель

Скачайте `giga-am-v3.onnx` со страницы [GigaAM на HuggingFace](https://huggingface.co/salute-developers/GigaAM) или из репозитория [SonarCS](https://github.com/bruder92-spec/SonarCS).

## Установка и настройка

1. Установите APK
2. Откройте приложение → пройдите визард:
   - Выдайте разрешение на микрофон
   - Включите **SonarCS** в `Настройки → Специальные возможности`
   - Запросите исключение из оптимизации батареи
3. Дождитесь синего уведомления «Готов» (~30 сек при первом запуске)
4. Нажмите «Тест (3 сек)» чтобы проверить распознавание

## Архитектура

```
SonarForegroundService   ← держит ONNX-сессию, показывает уведомление
SonarAccessibilityService ← перехватывает Volume Down, вставляет текст
        │
        ├── RecognitionStateMachine   ← управление состояниями + очередь фраз
        ├── AudioRecorder             ← AudioRecord, 16 кГц, PCM 16-bit
        ├── GigaAmEngine              ← MelSpectrogram → ONNX Runtime → CtcDecoder
        ├── DictionaryEngine          ← замены слов на границах слов
        └── TextInjector              ← ACTION_PASTE → ACTION_SET_TEXT → Toast
```

| Компонент | Описание |
|-----------|----------|
| `MelSpectrogram` | DFT вручную (N_FFT=320), HTK mel-фильтрбанк, нормализация per-feature |
| `CtcDecoder` | Жадное CTC-декодирование, фильтрация `<...>`-токенов |
| `SettingsRepository` | DataStore Preferences, `Flow<AppConfig>` |
| `SonarBus` | `SharedFlow` — шина событий между сервисами |

## Стек

- Kotlin + Jetpack Compose
- ONNX Runtime for Android 1.18.0
- DataStore Preferences, Lifecycle, Coroutines
- minSdk 26 / targetSdk 34

## Лицензия

MIT
