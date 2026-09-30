-keepattributes Signature,InnerClasses,EnclosingMethod

# JGit contains optional Java SE integrations that are not part of the Android
# runtime. Droide does not enable JMX cache monitoring or SPNEGO/GSS auth.
# Keep these suppressions narrow so any unrelated missing class still fails R8.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**

-keep class com.termux.terminal.JNI { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
