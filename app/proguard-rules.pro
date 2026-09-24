# 本工程 release 未开启混淆（isMinifyEnabled = false），此文件留空占位。
# 若将来开启 R8，需要保留 MediaPipe 的 JNI 桥接类：
#   -keep class com.google.mediapipe.** { *; }
#   -keep class com.google.protobuf.** { *; }
