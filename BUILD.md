# Инструкция по сборке F.E.A.R. Messenger для Android

Подробное руководство по сборке приложения из исходников.

## Содержание

- [Требования](#требования)
- [Подготовка окружения](#подготовка-окружения)
- [Сборка Debug версии](#сборка-debug-версии)
- [Сборка Release версии](#сборка-release-версии)
- [Работа с Opus библиотекой](#работа-с-opus-библиотекой)
- [Решение проблем](#решение-проблем)

---

## Требования

### Системные требования

- **OS**: Windows 10/11, macOS 11+, или Linux (Ubuntu 20.04+)
- **RAM**: Минимум 8 GB (рекомендуется 16 GB)
- **Дисковое пространство**: Минимум 10 GB свободного места

### Программное обеспечение

1. **JDK (Java Development Kit)**
   - Версия: JDK 17 или новее
   - Скачать: [Oracle JDK](https://www.oracle.com/java/technologies/downloads/) или [OpenJDK](https://adoptium.net/)

2. **Android Studio**
   - Версия: Hedgehog (2023.1.1) или новее
   - Скачать: [Android Studio](https://developer.android.com/studio)

3. **Android SDK**
   - Минимальная версия: API 24 (Android 7.0)
   - Целевая версия: API 34 (Android 14)
   - Build Tools: 34.0.0

4. **Android NDK (Native Development Kit)**
   - Версия: 25.1.8937393 или новее
   - Устанавливается через Android Studio SDK Manager

5. **CMake**
   - Версия: 3.22.1 или новее
   - Устанавливается через Android Studio SDK Manager

6. **Git**
   - Для клонирования репозитория
   - Скачать: [Git](https://git-scm.com/)

---

## Подготовка окружения

### 1. Установка JDK

#### Windows:
```bash
# Скачайте установщик с сайта Oracle или Adoptium
# После установки проверьте:
java -version
javac -version
```

#### macOS:
```bash
# Используя Homebrew:
brew install openjdk@17

# Добавьте в PATH:
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Проверка:
java -version
```

#### Linux (Ubuntu/Debian):
```bash
sudo apt update
sudo apt install openjdk-17-jdk

# Проверка:
java -version
```

### 2. Установка Android Studio

1. Скачайте Android Studio с [официального сайта](https://developer.android.com/studio)
2. Запустите установщик и следуйте инструкциям
3. При первом запуске Android Studio установит Android SDK

### 3. Установка Android SDK компонентов

Откройте Android Studio → Settings/Preferences → Appearance & Behavior → System Settings → Android SDK

Установите следующие компоненты:

**SDK Platforms:**
- ✅ Android 14.0 (API 34)
- ✅ Android 7.0 (API 24)

**SDK Tools:**
- ✅ Android SDK Build-Tools 34.0.0
- ✅ NDK (Side by side) - версия 25.1.8937393 или новее
- ✅ CMake - версия 3.22.1 или новее
- ✅ Android SDK Command-line Tools
- ✅ Android Emulator (опционально, для тестирования)

### 4. Настройка переменных окружения

#### Windows:
```bash
# Добавьте в системные переменные:
ANDROID_HOME = C:\Users\YourUsername\AppData\Local\Android\Sdk
JAVA_HOME = C:\Program Files\Java\jdk-17

# Добавьте в PATH:
%ANDROID_HOME%\platform-tools
%ANDROID_HOME%\tools
%JAVA_HOME%\bin
```

#### macOS/Linux:
```bash
# Добавьте в ~/.bashrc или ~/.zshrc:
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  # Путь может отличаться
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/tools
export PATH=$PATH:$JAVA_HOME/bin

# Примените изменения:
source ~/.bashrc  # или source ~/.zshrc
```

### 5. Проверка установки

```bash
# Проверьте все инструменты:
java -version      # Должна быть версия 17 или новее
./gradlew --version  # После клонирования репозитория
adb version        # Android Debug Bridge
cmake --version    # CMake
```

---

## Клонирование репозитория

```bash
# Клонировать репозиторий
git clone https://github.com/shchuchkin-pkims/fear-mobile.git

# Перейти в директорию проекта
cd fear-mobile

# Проверить структуру
ls -la
```

---

## Сборка Debug версии

Debug версия используется для разработки и отладки. Она содержит отладочную информацию и не требует подписи ключом.

### Через Android Studio

1. Откройте Android Studio
2. File → Open → выберите папку `fear-mobile`
3. Дождитесь синхронизации Gradle
4. Build → Make Project (Ctrl+F9 / Cmd+F9)
5. Build → Build Bundle(s) / APK(s) → Build APK(s)

APK будет в: `app/build/outputs/apk/debug/app-debug.apk`

### Через командную строку

```bash
# Перейдите в директорию проекта
cd fear-mobile

# Сборка Debug APK
./gradlew assembleDebug

# Для Windows используйте:
# gradlew.bat assembleDebug

# APK будет в: app/build/outputs/apk/debug/app-debug.apk
```

### Установка Debug APK на устройство

```bash
# Подключите устройство по USB или запустите эмулятор

# Проверьте подключение
adb devices

# Установите APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Флаг -r заменяет существующее приложение
```

---

## Сборка Release версии

Release версия оптимизирована и подписана для распространения.

### Шаг 1: Создание keystore (если еще не создан)

```bash
# Создайте keystore для подписи APK
keytool -genkey -v -keystore fear-app.keystore \
  -alias fearapp \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass yourpassword \
  -keypass yourpassword \
  -dname "CN=F.E.A.R. Messenger, OU=Development, O=FEAR Project, L=Unknown, ST=Unknown, C=RU"

# ВАЖНО: Сохраните keystore и пароли в безопасном месте!
# Без keystore вы не сможете обновлять приложение в будущем!
```

### Шаг 2: Сборка unsigned APK

```bash
# Очистка предыдущих сборок
./gradlew clean

# Сборка Release APK (без подписи)
./gradlew assembleRelease

# APK будет в: app/build/outputs/apk/release/app-release-unsigned.apk
```

### Шаг 3: Выравнивание APK (zipalign)

```bash
# Путь к zipalign (измените версию build-tools если нужно)
ZIPALIGN=$ANDROID_HOME/build-tools/34.0.0/zipalign

# Выравнивание APK
$ZIPALIGN -v -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-aligned.apk
```

### Шаг 4: Подпись APK (apksigner)

```bash
# Путь к apksigner
APKSIGNER=$ANDROID_HOME/build-tools/34.0.0/apksigner

# Подписать APK
$APKSIGNER sign \
  --ks fear-app.keystore \
  --ks-pass pass:yourpassword \
  --key-pass pass:yourpassword \
  --ks-key-alias fearapp \
  --out app/build/outputs/apk/release/F.E.A.R.Messenger-release.apk \
  app/build/outputs/apk/release/app-release-aligned.apk
```

### Шаг 5: Проверка подписи

```bash
# Проверить подпись APK
$APKSIGNER verify --print-certs \
  app/build/outputs/apk/release/F.E.A.R.Messenger-release.apk

# Вычислить контрольную сумму
sha256sum app/build/outputs/apk/release/F.E.A.R.Messenger-release.apk
```

### Автоматическая сборка Release

Создайте скрипт `build-release.sh`:

```bash
#!/bin/bash

# Конфигурация
KEYSTORE_PATH="fear-app.keystore"
KEYSTORE_PASS="yourpassword"
KEY_ALIAS="fearapp"
VERSION="0.3.1"

# Пути
ZIPALIGN="$ANDROID_HOME/build-tools/34.0.0/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"

echo "=== Building F.E.A.R. Messenger v$VERSION ==="

# Очистка
echo "1. Cleaning..."
./gradlew clean

# Сборка
echo "2. Building release APK..."
./gradlew assembleRelease

# Выравнивание
echo "3. Aligning APK..."
$ZIPALIGN -v -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

# Подпись
echo "4. Signing APK..."
$APKSIGNER sign \
  --ks $KEYSTORE_PATH \
  --ks-pass pass:$KEYSTORE_PASS \
  --key-pass pass:$KEYSTORE_PASS \
  --ks-key-alias $KEY_ALIAS \
  --out release/F.E.A.R.Messenger-v$VERSION-release.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

# Проверка
echo "5. Verifying signature..."
$APKSIGNER verify --print-certs release/F.E.A.R.Messenger-v$VERSION-release.apk

# Контрольная сумма
echo "6. Calculating checksum..."
sha256sum release/F.E.A.R.Messenger-v$VERSION-release.apk

echo "=== Build complete! ==="
echo "APK: release/F.E.A.R.Messenger-v$VERSION-release.apk"
```

Запуск:
```bash
chmod +x build-release.sh
./build-release.sh
```

---

## Работа с Opus библиотекой

### Структура Opus в проекте

Проект уже содержит prebuilt Opus библиотеки для всех архитектур:

```
app/src/main/cpp/opus/
├── include/
│   └── opus/
│       ├── opus.h
│       ├── opus_defines.h
│       ├── opus_types.h
│       └── opus_multistream.h
└── libs/
    ├── arm64-v8a/
    │   └── libopus.so
    ├── armeabi-v7a/
    │   └── libopus.so
    ├── x86/
    │   └── libopus.so
    └── x86_64/
        └── libopus.so
```

### Если нужно пересобрать Opus (опционально)

#### Требования:
- CMake 3.22.1+
- NDK 25.1.8937393+
- Python 3.7+ (для скриптов сборки)

#### Сборка Opus из исходников:

```bash
# 1. Скачайте исходники Opus
wget https://archive.mozilla.org/pub/opus/opus-1.3.1.tar.gz
tar -xzf opus-1.3.1.tar.gz
cd opus-1.3.1

# 2. Создайте скрипт сборки для Android
cat > build-android.sh << 'EOF'
#!/bin/bash

NDK_PATH=$ANDROID_HOME/ndk/25.1.8937393
TOOLCHAIN=$NDK_PATH/build/cmake/android.toolchain.cmake
BUILD_DIR=build-android

for ARCH in arm64-v8a armeabi-v7a x86 x86_64; do
  echo "Building for $ARCH..."
  mkdir -p $BUILD_DIR/$ARCH
  cd $BUILD_DIR/$ARCH

  cmake ../.. \
    -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN \
    -DANDROID_ABI=$ARCH \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DOPUS_BUILD_SHARED_LIBRARY=ON

  make -j$(nproc)
  cd ../..
done
EOF

# 3. Запустите сборку
chmod +x build-android.sh
./build-android.sh

# 4. Скопируйте библиотеки в проект
cp build-android/arm64-v8a/libopus.so     /path/to/fear-mobile/app/src/main/cpp/opus/libs/arm64-v8a/
cp build-android/armeabi-v7a/libopus.so   /path/to/fear-mobile/app/src/main/cpp/opus/libs/armeabi-v7a/
cp build-android/x86/libopus.so           /path/to/fear-mobile/app/src/main/cpp/opus/libs/x86/
cp build-android/x86_64/libopus.so        /path/to/fear-mobile/app/src/main/cpp/opus/libs/x86_64/

# 5. Скопируйте заголовочные файлы
cp -r include/opus /path/to/fear-mobile/app/src/main/cpp/opus/include/
```

### JNI обертка

Файл `app/src/main/cpp/opus_jni.cpp` содержит JNI интерфейс для работы с Opus из Kotlin.

Основные функции:
- `Java_com_fear_OpusCodec_nativeCreateEncoder` - создание энкодера
- `Java_com_fear_OpusCodec_nativeCreateDecoder` - создание декодера
- `Java_com_fear_OpusCodec_nativeEncode` - кодирование PCM → Opus
- `Java_com_fear_OpusCodec_nativeDecode` - декодирование Opus → PCM
- `Java_com_fear_OpusCodec_nativeDestroyEncoder` - освобождение энкодера
- `Java_com_fear_OpusCodec_nativeDestroyDecoder` - освобождение декодера

---

## Решение проблем

### Проблема: "SDK location not found"

**Решение:**
```bash
# Создайте файл local.properties в корне проекта
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Например:
# Windows: sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
# macOS:   sdk.dir=/Users/YourName/Library/Android/sdk
# Linux:   sdk.dir=/home/yourname/Android/Sdk
```

### Проблема: "NDK not configured"

**Решение:**
1. Откройте Android Studio → SDK Manager
2. Перейдите в SDK Tools
3. Установите "NDK (Side by side)"
4. В `local.properties` добавьте:
```properties
ndk.dir=/path/to/Android/Sdk/ndk/25.1.8937393
```

### Проблема: "CMake not found"

**Решение:**
1. Откройте Android Studio → SDK Manager
2. Перейдите в SDK Tools
3. Установите "CMake"

### Проблема: Ошибки при сборке native кода

**Решение:**
```bash
# Очистите native build
./gradlew clean
rm -rf app/.cxx
rm -rf app/build

# Пересоберите
./gradlew assembleDebug
```

### Проблема: "Execution failed for task ':app:mergeDebugNativeLibs'"

**Решение:**
Убедитесь что все `.so` файлы присутствуют в `app/src/main/cpp/opus/libs/`

```bash
# Проверьте наличие библиотек
ls -R app/src/main/cpp/opus/libs/
```

### Проблема: Gradle sync failed

**Решение:**
```bash
# Очистите Gradle кэш
rm -rf ~/.gradle/caches

# В Android Studio:
# File → Invalidate Caches → Invalidate and Restart
```

### Проблема: "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

**Решение:**
```bash
# Удалите старую версию приложения
adb uninstall com.fear

# Установите заново
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Дополнительные команды

### Просмотр логов

```bash
# Все логи приложения
adb logcat -s "ACM_DEBUG" "FC_DEBUG" "MainActivity" "OpusJNI"

# Только аудио логи
adb logcat -s "ACM_DEBUG"

# Очистка логов
adb logcat -c
```

### Анализ APK

```bash
# Размер APK
ls -lh app/build/outputs/apk/release/*.apk

# Содержимое APK
unzip -l app/build/outputs/apk/release/app-release.apk

# Проверка архитектур
unzip -l app/build/outputs/apk/release/app-release.apk | grep libopus.so
```

### Запуск тестов

```bash
# Unit тесты
./gradlew test

# Instrumented тесты (требуется устройство/эмулятор)
./gradlew connectedAndroidTest
```

---

## Дополнительные ресурсы

- [Android Developer Documentation](https://developer.android.com/docs)
- [Opus Codec Documentation](https://opus-codec.org/docs/)
- [Gradle Build Tool](https://gradle.org/guides/)
- [CMake Documentation](https://cmake.org/documentation/)

---

## Поддержка

Если у вас возникли проблемы:
1. Проверьте [Issues](https://github.com/shchuchkin-pkims/fear-mobile/issues)
2. Создайте новый Issue с описанием проблемы
3. Приложите логи и версии используемых инструментов

---

**Успешной сборки! 🚀**
