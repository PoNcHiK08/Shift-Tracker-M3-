# Room, Hilt, Navigation and kotlinx.serialization ship their own consumer rules.
# Keep the JSON backup DTOs' generated serializers explicit for safety.
-keepclassmembers @kotlinx.serialization.Serializable class com.vkurylo.shifttracker.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vkurylo.shifttracker.**$$serializer { *; }

# Type-safe navigation looks route serializers up reflectively (route::class.serializer()),
# including for `data object` routes. Keep routes and their annotations intact.
-keepattributes RuntimeVisibleAnnotations,InnerClasses,Signature,EnclosingMethod
-keep @kotlinx.serialization.Serializable class com.vkurylo.shifttracker.ui.navigation.** { *; }
-keep class com.vkurylo.shifttracker.ui.navigation.**$$serializer { *; }

# Shrinking stays on, but no optimisation/obfuscation for now:
#  - "open shift editor closes the app" happened only in the optimised release build (debug and an
#    unoptimised release behave correctly), so optimisation is off until the cause is pinned down;
#  - without obfuscation the in-app crash report (CrashReporter) shows readable class/method names.
-dontoptimize
-dontobfuscate
