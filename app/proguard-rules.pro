# Движок OpenVPN for Android: JNI-методы, AIDL, Parcelable и сериализация профилей
# ищутся по именам, поэтому оставляем его целиком без переименования.
-keep class de.blinkt.openvpn.** { *; }
-keep interface de.blinkt.openvpn.** { *; }
-keep class net.openvpn.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn de.blinkt.openvpn.**
-dontwarn net.openvpn.**

# SSTP-движок (Open SSTP Client): оставляем как есть
-keep class kittoku.osc.** { *; }
