#!/system/bin/sh
# Run by the root manager's Action button: is the module installed, is the app installed, and
# did system_server load the hooks this boot. Read-only: it changes nothing.
MODDIR=${0%/*}
PKG=io.github.artifical0.fcmfix.coloros
RECORD=/data/system/fcmfix/run.properties
NEOZYGISK=/data/adb/modules/zygisksu

problems=0
ok() { echo "[正常] $1"; }
bad() { echo "[异常] $1"; problems=$((problems + 1)); }
note() { echo "       $1"; }

prop() { sed -n "s/^$1=//p" "$MODDIR/module.prop" | sed -n 1p; }

echo "FCMFix-Zygisk $(prop version)"
echo

echo "== 模块"
if [ -f "$MODDIR/disable" ]; then
  bad "模块已被停用（手动停用，或启动保护连续检测到开机失败后自动停用）"
  note "在 root 管理器里重新启用，然后重启手机"
elif [ -f "$MODDIR/remove" ]; then
  bad "模块已标记为卸载，重启后会被删除"
else
  ok "模块已安装并启用"
fi
if [ -f "$MODDIR/zygisk/arm64-v8a.so" ]; then
  ok "注入库在位"
else
  bad "缺少 zygisk/arm64-v8a.so，请重新刷入模块"
fi
count=$(cat "$MODDIR/.boot_count" 2>/dev/null)
case "$count" in '' | 0) ;; *) note "启动保护计数：$count（开机完成后会清零；达到 2 会自动停用模块）" ;; esac

echo
echo "== NeoZygisk"
if [ ! -d "$NEOZYGISK" ]; then
  bad "没有找到 NeoZygisk（$NEOZYGISK），本模块需要它来注入"
elif [ -f "$NEOZYGISK/disable" ]; then
  bad "NeoZygisk 已被停用"
else
  ok "NeoZygisk 已安装并启用：$(sed -n 's/^version=//p' "$NEOZYGISK/module.prop" | sed -n 1p)"
fi

echo
echo "== 应用"
if pm path "$PKG" >/dev/null 2>&1; then
  app_version=$(dumpsys package "$PKG" 2>/dev/null | sed -n 's/^ *versionName=//p' | sed -n 1p)
  ok "应用已安装：$app_version"
  module_version=$(prop version)
  case "$module_version" in
    "$app_version"*) ;;
    *) bad "应用版本 $app_version 与模块 $module_version 不一致，请重新刷入同一个压缩包"
       note "应用随模块安装；模块目录里还有 fcmfix.apk 时，说明安装应用失败了" ;;
  esac
else
  bad "应用没有安装"
  if [ -f "$MODDIR/fcmfix.apk" ]; then
    note "手动安装：pm install -r $MODDIR/fcmfix.apk"
  else
    note "重新刷入模块压缩包即可"
  fi
fi

echo
echo "== 运行状态（system_server）"
server_pid=$(pidof system_server)
if [ ! -f "$RECORD" ]; then
  bad "system_server 没有留下加载记录：本次开机模块没有加载"
  note "确认 NeoZygisk 和本模块都已启用后重启；已经开机的系统可在 root 管理器里执行 NeoZygisk 的晚加载"
else
  record_pid=$(sed -n 's/^pid=//p' "$RECORD" | sed -n 1p)
  if [ "$record_pid" != "$server_pid" ]; then
    bad "加载记录属于另一个 system_server（记录 pid $record_pid，当前 $server_pid）：本次启动模块没有加载"
    note "确认 NeoZygisk 和本模块都已启用后重启"
  else
    version=$(sed -n 's/^version=//p' "$RECORD" | sed -n 1p)
    commit=$(sed -n 's/^commit=//p' "$RECORD" | sed -n 1p)
    active=$(sed -n 's/^active=//p' "$RECORD" | sed -n 1p)
    failed=$(sed -n 's/^failedCount=//p' "$RECORD" | sed -n 1p)
    skipped=$(sed -n 's/^skippedCount=//p' "$RECORD" | sed -n 1p)
    ready=$(sed -n 's/^ready=//p' "$RECORD" | sed -n 1p)
    late=$(sed -n 's/^late=//p' "$RECORD" | sed -n 1p)
    time=$(sed -n 's/^time=//p' "$RECORD" | sed -n 1p)
    ok "已注入 system_server（pid $server_pid），模块 $version+$commit"
    if [ "$late" = true ]; then
      note "这是晚加载：开机时已经发生的限制不会被追溯修正；电池进程要重启后才会被注入"
    fi
    if [ "$ready" = true ]; then
      ok "已完成初始化，$active 项 Hook 生效，$skipped 项不适用"
    else
      bad "Hook 已安装但还没有完成初始化（等待用户解锁，或初始化出错），$active 项生效"
    fi
    if [ "${failed:-0}" -gt 0 ]; then
      bad "$failed 项 Hook 与当前固件不匹配："
      sed -n 's/^failed=/         - /p' "$RECORD"
      note "请在应用里导出完整报告反馈；其余 Hook 不受影响"
    fi
    [ -n "$time" ] && note "记录时间：$(date -d "@$time" '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$time")"
  fi
fi

echo
if [ "$problems" -eq 0 ]; then
  echo "结论：一切正常。电池进程、配置同步和每个应用的状态请在应用的“状态”页查看。"
else
  echo "结论：发现 $problems 项异常，见上。"
fi
exit 0
