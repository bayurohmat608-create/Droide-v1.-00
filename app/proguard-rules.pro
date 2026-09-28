

-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.slf4j.**

-keep class com.termux.terminal.JNI { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
