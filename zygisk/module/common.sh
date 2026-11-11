#!/system/bin/sh
# Sourced by action.sh and service.sh; the caller sets MODDIR first. Reads the module, the app
# and this boot's run record, and writes the one-line status the root manager shows under the
# module name (module.prop description), the way bindhosts does.
PKG=io.github.artifical0.fcmfix.coloros
RECORD=/data/system/fcmfix/run.properties
NEOZYGISK=/data/adb/modules/zygisksu

record_value() { sed -n "s/^$1=//p" "$RECORD" 2>/dev/null | sed -n 1p; }
prop_value() { sed -n "s/^$1=//p" "$MODDIR/module.prop" | sed -n 1p; }

# Sets MODULE (ok|off|nolib), APP (ok|mismatch|missing), APP_VERSION,
# RUN (ready|starting|none), ACTIVE, FAILED and LATE.
fcmfix_collect() {
  if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
    MODULE=off
  elif [ ! -f "$MODDIR/zygisk/arm64-v8a.so" ]; then
    MODULE=nolib
  else
    MODULE=ok
  fi

  APP=missing
  APP_VERSION=
  if pm path "$PKG" >/dev/null 2>&1; then
    APP_VERSION=$(dumpsys package "$PKG" 2>/dev/null | sed -n 's/^ *versionName=//p' | sed -n 1p)
    module_version=$(prop_value version)
    if [ -n "$APP_VERSION" ] && [ "${module_version%%+*}" = "$APP_VERSION" ]; then
      APP=ok
    else
      APP=mismatch
    fi
  fi

  # A record from an earlier system_server is not this boot's.
  RUN=none
  ACTIVE=0
  FAILED=0
  LATE=false
  server_pid=$(pidof system_server)
  if [ -n "$server_pid" ] && [ -f "$RECORD" ] && [ "$(record_value pid)" = "$server_pid" ]; then
    ACTIVE=$(record_value active)
    FAILED=$(record_value failedCount)
    LATE=$(record_value late)
    if [ "$(record_value ready)" = true ]; then RUN=ready; else RUN=starting; fi
  fi
}

fcmfix_describe() {
  case "$MODULE" in
    off) echo "状态：已停用 ❌"; return ;;
    nolib) echo "状态：缺少注入库 ❌"; return ;;
  esac
  case "$RUN" in
    none) text="状态：未加载 ❌" ;;
    starting) text="状态：初始化中 ⏳" ;;
    *)
      if [ "${FAILED:-0}" -gt 0 ]; then
        text="状态：运行中 ⚠️ | Hook ${ACTIVE} 项，${FAILED} 项失配"
      else
        text="状态：运行中 ✅ | Hook ${ACTIVE} 项"
      fi
      [ "$LATE" = true ] && text="$text | 晚加载"
      ;;
  esac
  case "$APP" in
    missing) text="$text | 应用未安装 ❌" ;;
    mismatch) text="$text | 应用版本不一致 ⚠️" ;;
  esac
  echo "$text"
}

# Rewrites only the description line, and only when it changed.
fcmfix_apply_description() {
  new="description=$(fcmfix_describe)"
  [ "$(grep '^description=' "$MODDIR/module.prop")" = "$new" ] && return
  tmp="$MODDIR/module.prop.tmp"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      description=*) printf '%s\n' "$new" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done < "$MODDIR/module.prop" > "$tmp" && mv -f "$tmp" "$MODDIR/module.prop"
}

# At boot: wait until system_server has finished initialising (or ten minutes pass), then write
# the status. Runs in the background from service.sh.
fcmfix_apply_when_ready() {
  waited=0
  while [ "$waited" -lt 600 ]; do
    pid=$(pidof system_server)
    if [ "$(getprop sys.boot_completed)" = 1 ] && [ -n "$pid" ] \
      && [ "$(record_value pid)" = "$pid" ] && [ "$(record_value ready)" = true ]; then
      break
    fi
    sleep 5
    waited=$((waited + 5))
  done
  fcmfix_collect
  fcmfix_apply_description
}
