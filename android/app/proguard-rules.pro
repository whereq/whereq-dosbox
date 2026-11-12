# Keep rules for a release build with minification enabled.
#
# minifyEnabled is false in build.gradle, because the native side looks up
# Java methods by name and an over-eager shrinker breaks that. These rules
# make it safe to turn minification on - should you do that, check the
# resulting APK on a real device before shipping it.

# Any class with native methods: the C code registers them by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# SDL2's Android glue: the native code calls these by name.
-keep class org.libsdl.app.SDLActivity { *; }
-keep class org.libsdl.app.SDLAudioManager { *; }
-keep class org.libsdl.app.SDLControllerManager { *; }
-keep class org.libsdl.app.HIDDeviceManager { *; }

# Our JNI entry points (Java_com_whereq_dosbox_DosboxNative_*).
-keep class com.whereq.dosbox.DosboxNative { *; }

# The activity is referenced from the manifest, but keep it explicit.
-keep class com.whereq.dosbox.WhereqDosboxActivity { *; }
