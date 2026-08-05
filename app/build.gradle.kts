import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.27"
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.fear"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/${localProps["STORE_FILE"] ?: "fear-app.keystore"}")
            storePassword = localProps["STORE_PASSWORD"]?.toString() ?: ""
            keyAlias = localProps["KEY_ALIAS"]?.toString() ?: ""
            keyPassword = localProps["KEY_PASSWORD"]?.toString() ?: ""
        }
    }

    defaultConfig {
        applicationId = "com.fear"
        minSdk = 24
        targetSdk = 34
        versionCode = 50
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /*
         * Схема базы выкладывается в файл и хранится в репозитории.
         *
         * Без неё Room нечем сверять миграцию: он узнаёт о расхождении уже
         * на устройстве пользователя, и до этой правки отвечал на расхождение
         * стиранием. Выложенная схема даёт две вещи - сверку при сборке и
         * возможность прогнать миграцию тестом на старой базе, а не на чужой
         * переписке.
         */
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    sourceSets {
        // Схемы нужны инструментальному тесту миграций как ресурс.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    buildTypes {
        debug {
            /* A separate application id so a development build installs
             * alongside the release one rather than demanding an uninstall.
             * Replacing the release build would take the identity key and the
             * message history with it - they live in app-private storage, and
             * uninstalling is the only way Android lets a differently-signed
             * build take over a package. The FileProvider authority already
             * derives from the application id, and the launcher name comes
             * from app/src/debug/res, so nothing else needs to change. */
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildToolsVersion = "36.0.0"
    //ndkVersion = "29.0.14033849 rc4"
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.4")

    implementation("androidx.core:core:1.12.0")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("com.google.oboe:oboe:1.7.0")

    // Lazysodium for Ed25519 identity + BLAKE2b KDF
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // EncryptedFile for at-rest encryption of identity_sk (Phase 0 security baseline)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room — local message history (Phase A §9a + §17 search)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // Прогон миграций на настоящей старой базе, а не на догадке о ней.
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    // На голой JVM org.json - заглушка из android.jar, бросающая "not mocked".
    // Тесту миграции нужно читать выложенную схему, поэтому берём настоящую.
    testImplementation("org.json:json:20240303")

    // ZXing for QR code generation (identity backup) and scanning (import).
    // Need transitive deps now: CameraView + the embedded CaptureActivity used
    // by ScanContract for QR import.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // CameraX for video calls
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ─── Jetpack Compose (new Telegram-style UI) ───
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    // BLAKE2b for JVM unit tests: lazysodium-android needs a device, and an
    // independent implementation is what makes the frozen vectors meaningful.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}