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

# No obfuscation: the in-app crash report (CrashReporter) then shows readable class and method names,
# which is what pinned down the container-transform crash. Shrinking and optimisation stay on.
-dontobfuscate
