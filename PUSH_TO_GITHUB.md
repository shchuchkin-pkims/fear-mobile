# Инструкция по отправке проекта на GitHub

Проект **полностью подготовлен** для отправки на GitHub!

## Что уже сделано

✅ Создан `.gitignore` файл
✅ Созданы документы: `README.md`, `BUILD.md`, `LICENSE`
✅ Инициализирован Git репозиторий
✅ Создан начальный коммит с полным кодом проекта
✅ Настроен remote для GitHub: `git@github.com:shchuchkin-pkims/fear-mobile.git`
✅ Включены все Opus библиотеки для всех архитектур

## Что нужно сделать

### Вариант 1: Через веб-интерфейс GitHub (самый простой)

1. Откройте https://github.com/shchuchkin-pkims
2. Нажмите **New repository**
3. Заполните поля:
   - **Repository name**: `fear-mobile`
   - **Description**: `F.E.A.R. Messenger - Encrypted messenger with audio calls for Android`
   - **Public** или **Private** (на ваш выбор)
   - **НЕ СТАВЬТЕ ГАЛОЧКИ** на "Add README" и "Add .gitignore" (они уже есть!)
4. Нажмите **Create repository**

### Вариант 2: Через GitHub CLI (если установлен)

```bash
# Установите gh CLI если нужно
# Ubuntu/Debian: sudo apt install gh
# macOS: brew install gh

# Авторизуйтесь
gh auth login

# Создайте репозиторий
gh repo create fear-mobile --public --source=. --remote=origin --push
```

### Вариант 3: Вручную через командную строку

После создания репозитория на GitHub (Вариант 1):

```bash
cd /home/user/Documents/AndroidStudioProjects/FEAR

# Если нужно, переустановите remote на SSH
git remote set-url origin git@github.com:shchuchkin-pkims/fear-mobile.git

# Отправьте код на GitHub
git push -u origin main
```

### Если возникают проблемы с SSH

Если `git push` выдает ошибку про SSH ключ:

```bash
# Добавьте SSH ключ в агент
eval "$(ssh-agent -s)"
ssh-add ~/.ssh/github

# Проверьте соединение с GitHub
ssh -T git@github.com

# Если все ОК, попробуйте push снова
git push -u origin main
```

Альтернативно, используйте HTTPS (потребуется Personal Access Token):

```bash
git remote set-url origin https://github.com/shchuchkin-pkims/fear-mobile.git
git push -u origin main
# Введите ваш GitHub username
# Введите Personal Access Token вместо пароля
```

## Создание Personal Access Token (если используете HTTPS)

1. GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Generate new token (classic)
3. Выберите scopes: `repo` (full control of private repositories)
4. Сохраните токен в безопасном месте!

## Что будет отправлено на GitHub

```
fear-mobile/
├── .gitignore                          # Git ignore rules
├── .idea/                              # Android Studio config (minimal)
├── BUILD.md                            # Detailed build instructions
├── LICENSE                             # MIT License
├── README.md                           # Project documentation
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/fear/         # Kotlin source code
│   │   │   ├── cpp/                    # Native C++ code
│   │   │   │   ├── opus_jni.cpp        # JNI wrapper
│   │   │   │   ├── CMakeLists.txt      # CMake config
│   │   │   │   └── opus/               # Opus libraries
│   │   │   │       ├── include/        # Headers (6 files)
│   │   │   │       └── libs/           # .so files (5 files, ~2.5 MB total)
│   │   │   ├── res/                    # Android resources
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
├── gradle/                             # Gradle wrapper
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

**Общий размер репозитория**: ~10 MB (включая Opus библиотеки)

## После успешной отправки

1. Проверьте что все файлы на месте: https://github.com/shchuchkin-pkims/fear-mobile
2. Создайте Release:
   - Перейдите в Releases → Create a new release
   - Tag version: `v0.4.2`
   - Release title: `F.E.A.R. v0.4.2`
   - Прикрепите release APK файл
3. Обновите README.md если нужно (добавьте скриншоты, ссылки)

## Коммит который будет отправлен

```
commit db4cc36
Author: Your Name <your@email.com>
Date:   [current date]

    F.E.A.R. Messenger v0.4.2 for Android

    Features:
    - E2E encrypted messaging (AES-256-GCM)
    - ECDH key exchange (X25519 + crypto_box)
    - Ed25519 identity verification (TOFU)
    - Encrypted audio and video calls (TCP media relay)
    - File transfer with CRC32 verification
    - In-app updates from GitHub releases
    - Light/dark theme, push notifications

    [... полное описание в коммите ...]
```

## Проверка перед push

```bash
cd /home/user/Documents/AndroidStudioProjects/FEAR

# Проверить статус
git status

# Посмотреть что будет отправлено
git log --stat

# Проверить remote
git remote -v

# Убедиться что на ветке main
git branch
```

Все готово! После создания репозитория на GitHub, просто выполните `git push -u origin main`

---

**Важно**: После первой отправки, не забудьте создать Release и прикрепить готовый APK файл!
