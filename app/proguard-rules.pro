# Horizon's own code is kept as written (services, receivers, reflection-free but untested on
# device under heavy optimisation); R8 still shrinks and optimises the libraries (Compose etc.),
# which is where nearly all the size and speed gain comes from.
-keep class com.horizon.launcher.** { *; }

# Readable crash reports (the island's crash report shows real names and line numbers).
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
