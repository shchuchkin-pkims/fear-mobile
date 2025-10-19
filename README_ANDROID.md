# FEAR Messenger - Android Version

Криптостойкий мессенджер для Android с поддержкой текстового чата и голосовых звонков.

## Основные возможности

- Зашифрованный текстовый чат (AES-256-GCM)
- Голосовые звонки с шифрованием
- Полная совместимость с ПК-версией FEAR
- Передача файлов (в разработке)
- Простой и понятный интерфейс

## Требования

- Android SDK 24+ (Android 7.0 Nougat или новее)
- Android Studio Hedgehog или новее
- Gradle 8.13+
- Kotlin 1.9+

## Настройка проекта

### 1. Установка Android SDK

Если у вас еще не установлен Android SDK:

1. Скачайте и установите [Android Studio](https://developer.android.com/studio)
2. Откройте Android Studio и перейдите в `Tools > SDK Manager`
3. Установите:
   - Android SDK Platform 34
   - Android SDK Build-Tools 34.0.0+
   - Android SDK Platform-Tools
   - Android Emulator (опционально, для тестирования)

### 2. Настройка local.properties

После установки SDK, обновите файл `local.properties`:

```properties
sdk.dir=/path/to/your/android-sdk
```

Например:
- Linux: `sdk.dir=/home/username/Android/Sdk`
- Windows: `sdk.dir=C\:\\Users\\Username\\AppData\\Local\\Android\\Sdk`
- macOS: `sdk.dir=/Users/username/Library/Android/sdk`

### 3. Сборка проекта

#### Через Android Studio:
1. Откройте проект в Android Studio
2. Дождитесь синхронизации Gradle
3. Выберите `Build > Make Project`
4. Для установки на устройство: `Run > Run 'app'`

#### Через командную строку:
```bash
# Сборка debug версии
./gradlew assembleDebug

# Сборка release версии
./gradlew assembleRelease

# Установка на подключенное устройство
./gradlew installDebug
```

APK файл будет находиться в:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Использование

### Подключение к серверу

1. Запустите FEAR server на компьютере (см. документацию ПК-версии)
2. В Android-приложении введите:
   - **Server Host**: IP адрес сервера (например, `192.168.1.100`)
   - **Port**: порт сервера (по умолчанию `8888`)
   - **Room**: название комнаты (например, `testroom`)
   - **Your Name**: ваше имя пользователя
   - **Room Key (Base64)**: ключ шифрования в формате Base64

### Генерация ключа шифрования

Ключ должен быть одинаковым на всех клиентах. Сгенерировать ключ можно с помощью ПК-версии:

```bash
cd /path/to/fear/build
./audio_call genkey
```

Или используйте Python:
```python
import base64
import secrets

key = secrets.token_bytes(32)
key_base64 = base64.urlsafe_b64encode(key).decode('utf-8').rstrip('=')
print(key_base64)
```

### Текстовый чат

После подключения вы можете отправлять текстовые сообщения. Все сообщения шифруются AES-256-GCM.

### Голосовые звонки

1. Нажмите кнопку "Audio Call"
2. Введите имя пользователя, которому хотите позвонить
3. Дождитесь подключения
4. Говорите! Аудио передается с шифрованием через UDP
5. Нажмите "End Call" для завершения звонка

## Архитектура

### Шифрование

- **Протокол**: AES-256-GCM (совместим с ПК-версией)
- **Длина ключа**: 32 байта (256 бит)
- **Nonce**: 12 байт (для GCM)
- **Authentication tag**: 16 байт

### Аудио кодек

- **Сэмплрейт**: 48000 Гц
- **Каналы**: Mono (1)
- **Размер фрейма**: 960 сэмплов (20 мс)
- **Кодек**: Opus (pass-through режим в текущей версии)
- **Битрейт**: 24 kbps

### Сетевой протокол

#### TCP (текст и сигнализация):
```
[2 room_len][room][2 name_len][name][2 nonce_len][nonce][1 type][4 clen][ciphertext]
```

#### UDP (аудио пакеты):
```
[1 version][8 sequence][encrypted_audio_data]
```

Nonce для аудио:
```
[4 bytes prefix][8 bytes sequence_number]
```

## Совместимость с ПК-версией

Android-версия полностью совместима с ПК-версией FEAR:
- ✅ Одинаковые алгоритмы шифрования (AES-256-GCM)
- ✅ Совместимый протокол обмена сообщениями
- ✅ Одинаковый формат аудио пакетов
- ✅ Взаимозаменяемые ключи шифрования

### Пример использования

1. **На сервере (Ubuntu/Linux)**:
```bash
cd /path/to/fear/build
./server 8888
```

2. **На ПК-клиенте (Windows)**:
```bash
cd /path/to/fear/build
fear-client.exe
# Введите данные подключения
```

3. **На Android-клиенте**:
- Откройте приложение FEAR
- Введите IP сервера, порт, комнату, имя и ключ
- Нажмите "Connect"

Теперь вы можете общаться между всеми клиентами!

## Разрешения приложения

Приложение требует следующие разрешения:

- `INTERNET` - для сетевого подключения
- `ACCESS_NETWORK_STATE` - для проверки состояния сети
- `RECORD_AUDIO` - для голосовых звонков
- `MODIFY_AUDIO_SETTINGS` - для настройки аудио
- `WRITE_EXTERNAL_STORAGE` (Android 9 и ниже) - для сохранения файлов
- `READ_EXTERNAL_STORAGE` (Android 12 и ниже) - для чтения файлов

## Известные ограничения

1. **Opus codec**: Текущая версия использует pass-through режим (передача PCM без сжатия). Для продакшн-версии рекомендуется интегрировать настоящий Opus codec через JNI.

2. **Передача файлов**: Функционал в разработке.

3. **UDP NAT traversal**: Для голосовых звонков через интернет может потребоваться настройка проброса портов или использование STUN/TURN серверов.

## Разработка

### Структура проекта

```
app/src/main/java/com/fear/
├── MainActivity.kt         # Основная активность
├── FearClient.kt          # TCP клиент для чата
├── AudioCallManager.kt    # Менеджер голосовых звонков
├── Crypto.kt              # AES-GCM шифрование
├── OpusCodec.kt          # Opus wrapper (stub)
├── Common.kt              # Общие константы и утилиты
└── Message.kt             # Data классы

app/src/main/res/
├── layout/
│   ├── activity_main.xml      # Основной layout
│   ├── item_message.xml       # Layout для сообщения
│   └── dialog_call.xml        # Диалог для звонка
└── ...
```

### Добавление настоящего Opus codec

Для интеграции настоящего Opus:

1. Добавьте opus библиотеку в `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.google.android.exoplayer:extension-opus:2.19.1")
}
```

2. Или используйте WebRTC (уже добавлено в проект):
```kotlin
import org.webrtc.audio.JavaAudioDeviceModule
```

3. Замените методы в `OpusCodec.kt` на реальную реализацию

## Безопасность

- Все сообщения шифруются AES-256-GCM
- Ключи не хранятся на устройстве после закрытия приложения
- Используются случайные nonce для каждого сообщения
- Аутентификация сообщений через GCM authentication tag

## Лицензия

См. LICENSE файл в корне проекта.

## Контакты и поддержка

Для вопросов и предложений создайте issue в репозитории проекта.

---

**Важно**: Это учебный проект. Для использования в production окружении необходим полный аудит безопасности.
