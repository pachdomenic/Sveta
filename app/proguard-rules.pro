# Paho loads its network modules and loggers reflectively.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# Jsoup optional re2j dependency.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
