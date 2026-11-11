#!/system/bin/sh
# Boot guard, part 2. Runs once per boot, not waited for. On a NeoZygisk late load the system
# is already up, and this returns at once.
MODDIR=${0%/*}
COUNT_FILE="$MODDIR/.boot_count"

# The status line the root manager shows under the module name, once this boot's record is in.
( . "$MODDIR/common.sh"; fcmfix_apply_when_ready ) &

# A system_server that keeps dying restarts zygote without rebooting the kernel, so
# post-fs-data.sh never runs again and its count never grows. Watch for that here.
last_pid=
restarts=0
waited=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  pid=$(pidof system_server)
  if [ -n "$pid" ] && [ "$pid" != "$last_pid" ]; then
    [ -n "$last_pid" ] && restarts=$((restarts + 1))
    last_pid=$pid
  fi
  # NeoZygisk hands zygote the module list it read at boot, so disabling takes a reboot.
  if [ "$restarts" -ge 3 ]; then
    touch "$MODDIR/disable"
    rm -f "$COUNT_FILE"
    log -p w -t fcmfix "boot guard: system_server restarted $restarts times, module disabled, rebooting"
    reboot
    exit 0
  fi
  # Stop watching after ten minutes: a first boot after an OTA can take that long.
  [ "$waited" -ge 600 ] && exit 0
  sleep 2
  waited=$((waited + 2))
done

rm -f "$COUNT_FILE"
