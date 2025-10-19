# F.E.A.R. Messenger - Android

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**F.E.A.R. Messenger** - кроссплатформенный зашифрованный мессенджер с поддержкой аудиозвонков в реальном времени. Мобильная версия для Android, совместимая с PC версией.

## Возможности

- ✅ **Зашифрованные аудиозвонки** - AES-GCM шифрование для всех аудиопакетов
- ✅ **Высокое качество звука** - Opus codec (48kHz, mono, 24kbps)
- ✅ **Низкая задержка** - Оптимизирован для real-time коммуникации
- ✅ **Работа в фоне** - Foreground Service для непрерывной связи даже при заблокированном экране
- ✅ **Кроссплатформенность** - Совместимость с PC версией (Windows/Linux/macOS)
- ✅ **Текстовые сообщения** - Обмен зашифрованными сообщениями
- ✅ **Передача файлов** - Отправка файлов между участниками

## Скриншоты

[TODO: Добавьте скриншоты приложения]

## Технические характеристики

### Аудио
- **Codec**: Opus 1.3.1
- **Sample Rate**: 48000 Hz
- **Channels**: Mono
- **Bitrate**: 24 kbps
- **Frame Size**: 960 samples (~20ms)
- **Latency**: < 100ms (зависит от сети)

### Безопасность
- **Шифрование**: AES-GCM
- **Nonce**: 12 bytes (4-byte prefix + 8-byte sequence)
- **Протокол**: UDP с custom binary protocol

### Требования
- **Минимальная версия Android**: 7.0 (API 24)
- **Целевая версия Android**: 34
- **Разрешения**:
  - `INTERNET` - для сетевого соединения
  - `RECORD_AUDIO` - для записи звука с микрофона
  - `ACCESS_NETWORK_STATE` - для проверки состояния сети
  - `WAKE_LOCK` - для работы в фоне
  - `FOREGROUND_SERVICE` - для Foreground Service
  - `FOREGROUND_SERVICE_MICROPHONE` - для использования микрофона в фоне
  - `POST_NOTIFICATIONS` - для уведомлений (Android 13+)

## Быстрый старт

### Установка готового APK

1. Скачайте последний релиз APK из [Releases](https://github.com/shchuchkin-pkims/fear-mobile/releases)
2. Установите APK на устройство
3. Предоставьте необходимые разрешения

### Сборка из исходников

Подробная инструкция по сборке находится в [BUILD.md](BUILD.md)

**Краткая версия:**

```bash
# Клонировать репозиторий
git clone https://github.com/shchuchkin-pkims/fear-mobile.git
cd fear-mobile

# Собрать Debug версию
./gradlew assembleDebug

# Собрать Release версию
./gradlew assembleRelease

# APK будет в app/build/outputs/apk/
```

## Использование

1. Запустите приложение
2. Введите данные подключения:
   - **Server Host**: IP адрес сервера
   - **Port**: Порт сервера
   - **Room**: Название комнаты
   - **Your Name**: Ваше имя
   - **Room Key**: Ключ комнаты (Base64)
3. Нажмите **Connect** для подключения к комнате
4. Нажмите **Audio Call** для начала аудиозвонка
5. Используйте **End Call** для завершения звонка

### Обмен сообщениями

- Введите текст в поле ввода и нажмите **Send**
- Для отправки файла используйте команду: `/sendfile filename`

## Архитектура

```
fear-mobile/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/fear/
│   │   │   │   ├── MainActivity.kt           # Главная активность
│   │   │   │   ├── FearClient.kt             # Клиент для текстовых сообщений
│   │   │   │   ├── AudioCallManager.kt       # Менеджер аудиозвонков
│   │   │   │   ├── AudioCallService.kt       # Foreground Service
│   │   │   │   └── OpusCodec.kt              # JNI обертка для Opus
│   │   │   ├── cpp/
│   │   │   │   ├── opus_jni.cpp              # JNI реализация Opus
│   │   │   │   ├── CMakeLists.txt            # CMake конфигурация
│   │   │   │   └── opus/                     # Prebuilt Opus библиотеки
│   │   │   │       ├── include/              # Заголовочные файлы
│   │   │   │       └── libs/                 # .so библиотеки
│   │   │   ├── res/                          # Ресурсы приложения
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── gradle/
├── .gitignore
├── BUILD.md                                  # Инструкция по сборке
├── README.md                                 # Этот файл
└── settings.gradle.kts
```

## Протокол связи

### Типы пакетов

- `0x01` - HELLO (обмен nonce prefix)
- `0x02` - AUDIO (аудио пакет)

### Формат HELLO пакета

```
[1 byte: version 0x01][4 bytes: nonce prefix]
```

### Формат AUDIO пакета

```
[1 byte: version 0x02][12 bytes: nonce][N bytes: encrypted audio]
```

### Nonce структура

```
[4 bytes: random prefix][8 bytes: sequence number (big-endian)]
```

## Известные проблемы

- Групповые звонки (3+ участников) работают нестабильно - в текущей реализации используется только один remote nonce prefix
- При первом запуске требуется предоставить разрешения вручную
- Возможно эхо при использовании без наушников

## Roadmap

- [ ] Полноценная поддержка групповых звонков
- [ ] Улучшение качества звука при плохом интернете (PLC - Packet Loss Concealment)
- [ ] Видеозвонки
- [ ] End-to-end шифрование для текстовых сообщений
- [ ] Push уведомления
- [ ] Контакты и история звонков

## Разработка

### Требования для разработки

- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17+
- Android SDK 34
- NDK 25.1.8937393 или новее
- CMake 3.22.1+

### Отладка

```bash
# Просмотр логов
adb logcat -s "ACM_DEBUG" "FC_DEBUG" "MainActivity" "OpusJNI"

# Установка Debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Очистка данных приложения
adb shell pm clear com.fear
```

## Вклад в проект

Приветствуются Pull Requests! Пожалуйста, следуйте:

1. Fork проекта
2. Создайте feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit изменения (`git commit -m 'Add some AmazingFeature'`)
4. Push в branch (`git push origin feature/AmazingFeature`)
5. Откройте Pull Request

## Лицензия

Этот проект лицензирован под MIT License - см. файл [LICENSE](LICENSE) для деталей.

## Авторы

- **Разработка** - [shchuchkin-pkims](https://github.com/shchuchkin-pkims)

## Благодарности

- [Opus Audio Codec](https://opus-codec.org/) - за отличный аудио кодек
- [Kotlin](https://kotlinlang.org/) - за прекрасный язык программирования
- Всем, кто тестирует и предлагает улучшения

## Связанные проекты

- [F.E.A.R. Desktop](https://github.com/shchuchkin-pkims/fear) - Десктопная версия мессенджера

## Контакты

Для вопросов и предложений свяжитесь через GitHub Issues.

---

**⚠️ Disclaimer**: Этот проект создан в образовательных целях. Используйте на свой риск.
