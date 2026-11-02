import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Скриншоты экранов в unit-тестах (для проверки дизайна в CI)
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.example.vpngateclient"
    compileSdk = 36
    // Та же версия NDK, что и у модуля :openvpn — нужна, чтобы вырезать отладочные символы из .so
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.example.vpngateclient"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "3.3"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // R8 убирает неиспользуемый код (в основном иконки Compose).
            // Классы движка OpenVPN (JNI, AIDL) защищены правилами в proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Для личного использования release подписывается debug-ключом.
            // Перед публикацией в Google Play замените на свой ключ.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // OpenVPN запускается как отдельный бинарник из папки нативных библиотек,
            // поэтому они должны распаковываться при установке
            useLegacyPackaging = true
            // OpenVPN 3 и тест скорости OpenSSL не используются (работаем на OpenVPN 2)
            excludes += listOf("**/libovpn3.so", "**/libosslspeedtest.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":openvpn"))
    implementation(project(":sstp"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
