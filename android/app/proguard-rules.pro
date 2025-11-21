 # gRPC / Protobuf lite keep rules
 -dontwarn io.grpc.**
 -dontwarn javax.annotation.**
 -dontwarn com.google.errorprone.annotations.**
 -dontwarn org.codehaus.mojo.animal_sniffer.*
 
 -keep class io.grpc.** { *; }
 -keep class com.google.protobuf.** { *; }
 
 # Keep generated lite messages & service stubs
 -keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
   <fields>;
   <methods>;
 }
 -keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
 -keep class **/*Grpc { *; }
 
 # (Optional) if you slim logs, keep names used at runtime reflection
 -keepattributes *Annotation*
 

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
