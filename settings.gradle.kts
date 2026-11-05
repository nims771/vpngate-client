pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VpnGateClient"
// Windows-версия (Compose Desktop)
include(":desktop")

// Android-модули. На сборщике Windows (DESKTOP_ONLY=1) их не подключаем:
// там нет нужды в Android SDK и исходниках OpenVPN.
if (System.getenv("DESKTOP_ONLY") == null) {
    include(":app")
    include(":openvpn")
    include(":sstp")
}
