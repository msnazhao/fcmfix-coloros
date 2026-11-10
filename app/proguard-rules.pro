# The hook code no longer runs from this APK: it is compiled into the module's hook.dex by
# zygisk/build.sh. The app only uses the shared protocol classes, which R8 can treat normally.

# Readable line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
