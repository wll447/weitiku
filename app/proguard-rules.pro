
# R8 的 optimize 配置默认会删掉所有 Log 调用（-assumenosideeffects），真机排障全靠它，必须保留
-keep class android.util.Log { *; }
# ONNX Runtime 走 JNI，方法名不能被混淆
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.microsoft.onnxruntime.**

# AIDL 跨进程接口（:ocr 服务）
-keep class com.weizh.tiku.ocr.** { *; }
-keep interface com.weizh.tiku.ocr.** { *; }

# 数据实体
-keep class com.weizh.tiku.data.** { *; }

# jxl 读老式 .xls
-dontwarn jxl.**
-keep class jxl.** { *; }
