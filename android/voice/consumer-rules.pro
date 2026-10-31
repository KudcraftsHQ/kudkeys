# Kud Keys voice client: R8 rules shipped to the app that uses this module.
# The module is small and uses no reflection, but keep its classes whole so a release build can never strip or
# merge something the keyboard reaches only through the VoiceEngine interface.
-keep class com.kudcrafts.kudkeys.voice.** { *; }

# OkHttp 4 ships its own rules; these silence optional TLS providers it probes for at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines ships its own rules; this covers the debug agent classes it references.
-dontwarn kotlinx.coroutines.debug.**
