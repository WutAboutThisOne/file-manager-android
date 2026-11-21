# File Manager release rules
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.coroutines.**
# libsu: the root service / shell classes are loaded reflectively
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# 0.3.0 archives: Commons Compress references optional codecs the app doesn't ship (Brotli, Zstd, ASM for
# Pack200, OSGi). They're only loaded on demand for formats the app never opens.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.compress.harmony.**
