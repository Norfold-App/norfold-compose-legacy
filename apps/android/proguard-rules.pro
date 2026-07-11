# Room entities and generated implementations are referenced through generated code.
-keep class * extends androidx.room.RoomDatabase { *; }

# Supabase/Ktor models use Kotlin serialization metadata and may be reached through plugins.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.norfold.app.domain.** { *; }

# Firebase discovers the messaging service from the manifest.
-keep class com.norfold.app.cloud.NorfoldMessagingService { *; }
