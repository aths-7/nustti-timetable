# 本工程使用 Gson 解析服务端 JSON，保留其字段名与泛型签名
-keep class edu.nustti.timetable.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
