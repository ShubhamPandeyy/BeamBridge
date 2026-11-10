# NanoHTTPD + kotlinx.serialization
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class app.beambridge.core.** { *; }
-dontwarn org.slf4j.**
-dontwarn javax.servlet.**
# BouncyCastle (iPhone remote crypto): only the lightweight API is used; its JNDI/LDAP and JSSE parts are never loaded.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
