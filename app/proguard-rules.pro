# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.yichen.kandu.** {
    *** Companion;
}
-keepclasseswithmembers class com.yichen.kandu.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# pdfbox
-dontwarn com.gemalto.jp2.**
