# R8 is disabled for now (see app/build.gradle.kts). Keep rules here for when it is enabled.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; }
