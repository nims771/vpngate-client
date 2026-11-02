// Встроенный движок OpenVPN.
// Исходники берутся из подмодуля ics-openvpn (OpenVPN for Android, v0.7.63)
// без изменений: этот модуль только собирает их как библиотеку
// (вариант «skeleton» без интерфейса, OpenVPN 2.x).

plugins {
    id("com.android.library")
}

val ics = rootProject.file("ics-openvpn/main")

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        // Используем классический OpenVPN 2 (лучше всего совместим с VPN Gate)
        buildConfigField("boolean", "openvpn3", "false")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = File(ics, "src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf(File(ics, "src/main/java"), File(ics, "src/skeleton/java")))
            aidl.setSrcDirs(listOf(File(ics, "src/main/aidl")))
            res.setSrcDirs(listOf(File(ics, "src/main/res"), File(ics, "src/skeleton/res")))
            assets.setSrcDirs(listOf(File(ics, "src/main/assets"), File(ics, "build/ovpnassets")))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core:1.15.0")
}
