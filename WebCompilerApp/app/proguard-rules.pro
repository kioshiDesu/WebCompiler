-keep class com.webcompiler.app.** { *; }
-keep class org.bouncycastle.** { *; }
-keepattributes SourceFile,LineNumberTable

-dontwarn javax.naming.**
-dontwarn org.bouncycastle.cert.dane.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.pqc.**
