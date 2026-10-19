# 🎙️ Диктофон для совещаний (Meeting Recorder)

Android-приложение для записи совещаний, лекций и встреч. Пишет звук в фоне через `Foreground Service`, сохраняет записи в системное хранилище (MediaStore) и показывает список записей с встроенным плеером.

## Возможности
- ⏺️ Запись звука в фоновом режиме (устойчива к сворачиванию приложения)
- 🔔 Уведомление о ходе записи с таймером
- 📁 Автосохранение файлов в папку `Recordings` (MediaStore, доступны другим приложениям)
- 📋 Список всех записей: название, дата, длительность, размер
- ▶️ Встроенный плеер с перемоткой
- 🗑️ Удаление записей
- 🌙 Material 3 интерфейс на Jetpack Compose

## Технологии
| Слой | Что используется |
|------|------------------|
| UI | Jetpack Compose, Material 3 |
| Архитектура | MVVM, ViewModel, StateFlow |
| Запись | AudioRecord / MediaCodec (AAC), Foreground Service |
| Хранение | MediaStore Files (Scoped Storage) |
| Язык | Kotlin 2.x, Gradle (Kotlin DSL) |

## Требования
- Android Studio Ladybug (2024.2) или новее
- JDK 17
- Минимальный Android: **API 26 (Android 8.0)**, target: **API 35**

## Сборка
```bash
git clone <URL_репозитория> meeting-recorder
cd meeting-recorder
./gradlew assembleDebug
```
APK появится в `app/build/outputs/apk/debug/`.

Или просто откройте папку в Android Studio и нажмите **Run ▶**.

## Установка на устройство
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Разрешения
| Разрешение | Зачем |
|------------|-------|
| `RECORD_AUDIO` | Доступ к микрофону |
| `FOREGROUND_SERVICE_MICROPHONE` | Фоновая запись (Android 14+) |
| `POST_NOTIFICATIONS` | Уведомление о записи (Android 13+) |

## Публикация на GitHub
В репозитории есть скрипт `publish-to-github.sh`:
```bash
GITHUB_TOKEN=<ваш_токен> REPO=<логин/имя-репо> ./publish-to-github.sh
```

## Лицензия
MIT
