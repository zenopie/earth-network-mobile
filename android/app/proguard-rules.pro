# R8 rules for the release build.
#
# Play measures obfuscation coverage, and app code is what it counts, so
# network.erth.wallet.** is deliberately left to be renamed. Nothing in this
# app's own code looks a class up by name — no Class.forName, no loadLibrary,
# no Gson (the JSON is parsed with org.json, which is reflection-free) — so
# renaming it is safe. The rules below exist for the libraries that do.

# --- BouncyCastle -----------------------------------------------------------
# BC resolves algorithm implementations by building class names as strings and
# reflecting on them, so a renamed provider class becomes NoSuchAlgorithmException
# at runtime rather than a build error. The passport's CMS/SOD verification runs
# through here, so this is kept whole rather than narrowed.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- JMRTD + scuba ----------------------------------------------------------
# ePassport read path. JMRTD picks card-service and file-parser implementations
# reflectively, and scuba does the same for the smartcard transport.
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**

# --- Protobuf (javalite) ----------------------------------------------------
# Generated messages carry a DEFAULT_INSTANCE field and dispatch through
# dynamicMethod(), both reached reflectively by the lite runtime. Losing either
# breaks transaction encoding, which is every signed message on the chain.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- Noir prover ------------------------------------------------------------
# com.noirandroid.lib bridges to native barretenberg over JNI. JNI binds by
# fully-qualified name, so renaming anything here unbinds the prover.
-keep class com.noirandroid.lib.** { *; }
-keep class noir.** { *; }
-dontwarn com.noirandroid.lib.**

# --- bitcoinj ---------------------------------------------------------------
# Used for BIP-32/39 key derivation; carries its own protobuf wallet format.
-keep class org.bitcoinj.** { *; }
-dontwarn org.bitcoinj.**

# --- Misc third-party -------------------------------------------------------
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn javax.swing.**

# --- Attributes -------------------------------------------------------------
# Signature/InnerClasses/EnclosingMethod are needed wherever generic types are
# read back at runtime; the annotation attributes keep protobuf and BC metadata
# intact. SourceFile is renamed rather than dropped so crash reports still map
# through the mapping.txt R8 writes to app/build/outputs/mapping/release/.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
