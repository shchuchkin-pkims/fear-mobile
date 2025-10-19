plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fear"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // ВАРИАНТ 1: LiveKit WebRTC (реально работает)
    //implementation("io.livekit:webrtc-android:1.0.1")

    // ВАРИАНТ 2: Используйте стандартный WebRTC через Google
    implementation("com.google.oboe:oboe:1.7.0") // у вас уже есть

    // ВАРИАНТ 3: Если нужен именно аудио кодек Opus - используйте отдельную библиотеку
    // TODO: Добавить реальную библиотеку Opus (пока используется pass-through для тестирования)
    //implementation("com.github.louisyonge:opus_android:master-SNAPSHOT")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}