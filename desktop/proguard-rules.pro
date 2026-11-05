# Код приложения не трогаем (рефлексии нет, но так надёжнее)
-keep class com.example.vpngateclient.** { *; }
-keep class org.json.** { *; }

# Swing-диспетчер корутин находится через ServiceLoader
-keep class kotlinx.coroutines.swing.** { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.** { *; }

-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontnote **
# Необязательные зависимости библиотек (сериализация не используется)
-dontwarn kotlinx.datetime.**
-dontwarn kotlinx.serialization.**
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.skiko.**
-ignorewarnings
