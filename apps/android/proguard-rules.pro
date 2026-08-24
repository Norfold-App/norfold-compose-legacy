# Room entities and generated implementations are referenced through generated code.
-keep class * extends androidx.room.RoomDatabase { *; }

# Supabase/Ktor models use Kotlin serialization metadata and may be reached through plugins.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.norfold.app.domain.** { *; }

# Firebase discovers the messaging service from the manifest.
-keep class com.norfold.app.cloud.NorfoldMessagingService { *; }

# Crashlytics: keep the two attributes a stack trace is made of.
#
# R8 strips SourceFile and LineNumberTable by default, and a crash report without them is a list of
# method names with no line numbers — technically deobfuscated by the uploaded mapping file, and still
# not enough to find the statement that threw. These two are the difference between "somewhere in
# DocsViewModel" and a line. -renamesourcefileattribute is Google's documented companion: it replaces
# the real file name with a fixed string, so nothing is leaked by keeping the attribute, while the
# mapping file still resolves the original.
#
# Note this only matters for a release build, which is exactly where crash reporting earns its place,
# and it is invisible to every unit test — hence the CrashReportingGateTest assertion that these lines
# are present, rather than trusting that nobody tidies the file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
