# Sourced by the root manager's module installer after the files are extracted.

if [ "$ARCH" != "arm64" ]; then
  abort "! FCMFix-Zygisk only supports arm64 (this device: $ARCH)"
fi
if [ "$API" -lt 36 ]; then
  abort "! FCMFix-Zygisk needs Android 16 or newer (this device: API $API)"
fi

rm -f "$MODPATH/.boot_count"
set_perm "$MODPATH/action.sh" 0 0 0755

# The companion app: allow list, options and the status page. Installed from here so that it
# always matches the hook code in this module.
APK="$MODPATH/fcmfix.apk"
if [ -f "$APK" ]; then
  if [ "$BOOTMODE" = true ]; then
    # The package manager cannot read /data/adb.
    TMP_APK=/data/local/tmp/fcmfix-coloros.apk
    cp -f "$APK" "$TMP_APK" && chmod 644 "$TMP_APK"
    ui_print "- Installing the FCMFix app"
    if pm install -r "$TMP_APK" >/dev/null 2>&1; then
      rm -f "$APK"
    else
      ui_print "! Could not install the app. Install it by hand:"
      ui_print "!   $APK"
    fi
    rm -f "$TMP_APK"
  else
    ui_print "- Recovery install: install $APK by hand after booting"
  fi
fi

ui_print "- Verified with NeoZygisk. Reboot to activate."
ui_print "- Action button: check the module, the app and the running hooks"
ui_print "- Config button: opens the FCMFix-Zygisk app"
