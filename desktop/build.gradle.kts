// VPN Gate Client для Windows (Compose Desktop).
// Экраны, загрузка списка, пинг и автовыбор — общий код из app/ (без Android-зависимостей),
// здесь только окно, хранилище настроек и VPN-движок Windows.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val appVersion = "3.4.1"
val shared = rootProject.file("app/src/main/java")

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets {
        named("main") {
            kotlin.srcDir(shared)
            // из Android-приложения берём только платформенно-независимые файлы
            kotlin.include(
                "com/example/vpngateclient/data/VpnServer.kt",
                "com/example/vpngateclient/data/VpnGateParser.kt",
                "com/example/vpngateclient/data/Storage.kt",
                "com/example/vpngateclient/vpn/OvpnConfig.kt",
                "com/example/vpngateclient/vpn/VpnModels.kt",
                "com/example/vpngateclient/net/PingChecker.kt",
                "com/example/vpngateclient/net/SpeedTest.kt",
                "com/example/vpngateclient/ui/AppCore.kt",
                "com/example/vpngateclient/ui/Format.kt",
                "com/example/vpngateclient/ui/Components.kt",
                "com/example/vpngateclient/ui/HomeScreen.kt",
                "com/example/vpngateclient/ui/CountriesScreen.kt",
                "com/example/vpngateclient/ui/SettingsScreen.kt",
                "com/example/vpngateclient/ui/Dialogs.kt",
                "com/example/vpngateclient/ui/theme/Theme.kt",
                // собственный код Windows-версии
                "com/example/vpngateclient/desktop/**",
            )
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.example.vpngateclient.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")

        // ProGuard выкидывает неиспользуемый код (в основном тысячи иконок Material) — установщик меньше
        buildTypes.release.proguard {
            version.set("7.6.1")
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "VPN Gate Client"
            packageVersion = appVersion
            description = "Client for free VPN Gate servers"
            vendor = "VPN Gate Client"
            copyright = "GPL v2"
            // только нужные модули JDK: установщик в ~2 раза меньше, чем со всеми
            modules(
                "java.desktop", "java.logging", "java.naming", "java.net.http", "java.prefs",
                "java.management", "java.instrument", "jdk.crypto.ec", "jdk.crypto.cryptoki",
                "jdk.charsets", "jdk.localedata", "jdk.unsupported", "jdk.zipfs", "jdk.accessibility",
            )

            windows {
                menu = true
                menuGroup = "VPN Gate Client"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "8a4f2c1e-6b7d-4e3a-9c5f-2d1b0e7a9f34"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}
