-dontwarn org.slf4j.**
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class cx.miruro.app.**$$serializer { *; }
-keepclassmembers class cx.miruro.app.** {
    *** Companion;
}
-keepclasseswithmembers class cx.miruro.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
