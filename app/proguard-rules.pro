# Bouncy Castle is pulled in for org.bouncycastle.crypto.generators.SCrypt
# alone. The rest of the provider registers algorithms reflectively and
# references optional JCE classes that do not exist on Android; none of it is
# reachable from this app, so let R8 remove it and stay quiet about the
# dangling references.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.jce.provider.**
-dontwarn org.bouncycastle.x509.**

# Guava carries annotations and @GwtIncompatible references that are absent
# on Android.
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**

# kotlinx.serialization generates serializer() companions that R8 cannot see
# are used.
-keepclassmembers class nu.staldal.pw.** {
    *** Companion;
}
-keepclasseswithmembers class nu.staldal.pw.** {
    kotlinx.serialization.KSerializer serializer(...);
}
