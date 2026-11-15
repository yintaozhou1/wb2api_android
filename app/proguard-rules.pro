# ============ wb2api R8 / ProGuard 规则 ============

# 保留注解与签名属性（serialization / 反射读取）
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*, AnnotationDefault, EnclosingMethod
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization ----
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.wb.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wb.api.**$$serializer { *; }
-keepclassmembers class com.wb.api.** {
    *** Companion;
}
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }

# ---- 数据模型与配置（序列化读写，字段名不可混淆） ----
-keep class com.wb.api.model.** { *; }
-keep class com.wb.api.core.GatewayConfig { *; }
-keep class com.wb.api.core.**Cfg { *; }
-keepclassmembers class com.wb.api.core.**Cfg { *; }

# ---- 应用入口 ----
# App / MainActivity / GatewayService 由 AndroidManifest 声明，AGP 会据此**自动生成** keep 规则
# （配合默认的 proguard-android-optimize.txt 里 Activity/Service/Application 的保底规则），
# 这里再写 `-keep class ... { *; }` 属于重复，且会把这几个类的成员全部钉死、挡住 R8 内联与裁剪。
# CrashHandler 是被 App.onCreate 直接引用的 object，属可达代码，无需 keep。

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class okhttp3.internal.** { *; }

# ---- 协程 ----
-dontwarn kotlinx.coroutines.**

# ---- Compose / Miuix ----
-dontwarn androidx.compose.**
-dontwarn top.yukonga.miuix.**
-keepclassmembers class top.yukonga.miuix.kmp.icon.** { *; }
-keep class top.yukonga.miuix.kmp.theme.** { *; }
