# Regles par defaut suffisantes : Compose, Room et coroutines embarquent leurs regles consumer.
# SQLCipher : classes appelees depuis le code natif JNI, a ne pas renommer.
-keep class net.zetetic.database.** { *; }
# MediaPipe : idem, pont JNI vers le runtime tflite.
-keep class com.google.mediapipe.** { *; }
# Protos du profiler MediaPipe non embarques, references morts pour nous.
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.protobuf.**
