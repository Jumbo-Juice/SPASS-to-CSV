# The app has no reflection and no serialization library, so no keep rules are needed
# for its own code. These rules exist so that turning on `isMinifyEnabled` in
# build.gradle.kts is a one-line change.

# Keep the conversion engine's public API readable in stack traces.
-keepnames class com.jumbojuice.spasstocsv.core.** { *; }

# Line numbers make crash reports from users useful; the source file name is not needed.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
