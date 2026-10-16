# WI-12: R8 rules for the on-device test APK of the r8check build (never shipped).
# com.google.errorprone annotations (pulled in by the androidx.test libraries) reference
# javax.lang.model, a compile-time-only API that is not on Android. They are never loaded at run time.
-dontwarn javax.lang.model.**
