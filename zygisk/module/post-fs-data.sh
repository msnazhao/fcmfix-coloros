#!/system/bin/sh
# Boot guard, part 1. This module runs inside system_server, where a fault means the phone
# does not finish booting. Count the boots that started; service.sh clears the count once a
# boot completes. Two boots in a row that did not complete: stand down.
MODDIR=${0%/*}
COUNT_FILE="$MODDIR/.boot_count"

count=$(cat "$COUNT_FILE" 2>/dev/null)
case "$count" in
  '' | *[!0-9]*) count=0 ;;
esac

if [ "$count" -ge 2 ]; then
  touch "$MODDIR/disable"
  rm -f "$COUNT_FILE"
  log -p w -t fcmfix "boot guard: $count boots did not complete, module disabled"
else
  echo $((count + 1)) > "$COUNT_FILE"
fi
