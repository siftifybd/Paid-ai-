# Proguard Rules for JARVIS Assistant

# Keep Gson model classes
-keep class com.jarvis.assistant.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (HUD, Settings, & Floating Orbs)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Vosk offline wake word and JNA native libraries
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }

# Keep Room database entities & DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class com.jarvis.assistant.data.memory.** { *; }

# Remote control (Gson models + Java-WebSocket)
-keep class com.jarvis.assistant.remote.** { *; }
-keep class org.java_websocket.** { *; }
-dontwarn org.slf4j.**

# License & Entitlement models
-keep class com.jarvis.assistant.license.model.** { *; }

# Nexus models & Firebase PropertyName keep rules (H2)
-keep class com.jarvis.assistant.nexus.model.** { *; }
-keep class com.jarvis.assistant.nexus.data.** { *; }
-keep class com.jarvis.assistant.data.repository.NetworkRemoteCommand { *; }
-keep class com.jarvis.assistant.data.repository.DeviceNetworkStatus { *; }
-keepclassmembers class * { @com.google.firebase.firestore.PropertyName <fields>; }
