# R8-Regeln für alle Advantly-Android-Apps (Phone + Wear), eingebunden vom Convention Plugin
# advantly.android.application zusätzlich zu proguard-android-optimize.txt.
# Modulspezifische Regeln: <app>/proguard-rules.pro.
#
# Grundsatz: so wenig wie möglich behalten. Die meisten Bibliotheken bringen ihre Regeln selbst mit
# (Consumer Rules im AAR/JAR) – hier steht nur, was darüber hinaus nötig ist, plus die Begründung,
# warum für die übrigen Bibliotheken nichts nötig ist (Stand Oktober 2026, geprüft beim Release-Build W1-G).

# --- Lesbare Stacktraces in der Play Console -----------------------------------------------------
# Die mapping.txt landet automatisch im AAB (BUNDLE-METADATA) und wird von Play zur Deobfuskierung
# genutzt. Zeilennummern behalten, den Quelldateinamen aber vereinheitlichen.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- kotlinx-serialization ----------------------------------------------------------------------
# kotlinx-serialization-core bringt R8-Regeln mit (META-INF/com.android.tools/r8/*.pro). Die
# serialName-Strings erzeugt das Compiler-Plugin als Konstanten – Obfuskierung ändert also weder das
# JSON-Format noch polymorphe Diskriminatoren. Zusätzlich abgesichert: Serializer unserer eigenen
# @Serializable-Klassen, falls sie zur Laufzeit über serializer<T>()/Companion gesucht werden.
-if @kotlinx.serialization.Serializable class eu.advantly.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class eu.advantly.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class eu.advantly.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- SQLDelight (shared/data) -------------------------------------------------------------------
# Generierter Code ohne Reflection; Enum-Spalten laufen über name()/enumValueOf, die Namen bleiben
# erhalten (proguard-android-optimize.txt behält values()/valueOf()). Keine Regeln nötig.

# --- Wear Data Layer (play-services-wearable) ---------------------------------------------------
# Consumer Rules im AAR (Proto-Felder). WearableListenerService-Unterklassen sind im Manifest
# deklariert und werden über die von AAPT2 erzeugten Regeln behalten. Die Capability-Ressource
# (res/values/wear.xml) schützt tools:keep vor dem Resource-Shrinker.

# --- Health Services (nur wearApp) --------------------------------------------------------------
# health-services-client bringt Regeln für seine Proto-Nachrichten mit; Parcelable-CREATOR-Felder
# behält proguard-android-optimize.txt.
