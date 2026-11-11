#!/system/bin/sh
# Run by the root manager's Action button. Read-only, except that it refreshes the status line
# under the module name.
MODDIR=${0%/*}
. "$MODDIR/common.sh"

ok() { echo "✅ $1"; }
warn() { echo "⚠️ $1"; }
bad() { echo "❌ $1"; }

fcmfix_collect
echo "FCMFix-Zygisk $(prop_value version)"

case "$MODULE" in
  off) bad "模块已停用" ;;
  nolib) bad "缺少注入库，请重新刷入" ;;
  *) ok "模块已启用" ;;
esac

# Only NeoZygisk is verified; another implementation is reported as such and is not an error.
name=
if [ -f "$NEOZYGISK/module.prop" ] && [ ! -f "$NEOZYGISK/disable" ]; then
  name=$(sed -n 's/^name=//p' "$NEOZYGISK/module.prop" | sed -n 1p)
fi
if [ "$name" = NeoZygisk ]; then
  ok "Zygisk：NeoZygisk $(sed -n 's/^version=//p' "$NEOZYGISK/module.prop" | sed -n 1p)"
elif [ -n "$name" ]; then
  warn "Zygisk：$name 未验证"
else
  warn "Zygisk：非 NeoZygisk，未验证"
fi

case "$APP" in
  ok) ok "应用 $APP_VERSION" ;;
  mismatch) warn "应用 $APP_VERSION 与模块不一致，请重新刷入" ;;
  missing)
    bad "应用未安装"
    [ -f "$MODDIR/fcmfix.apk" ] && echo "   pm install -r $MODDIR/fcmfix.apk"
    ;;
esac

case "$RUN" in
  none) bad "本次开机未加载" ;;
  starting) warn "已注入，等待初始化完成" ;;
  ready)
    ok "已注入 system_server，Hook ${ACTIVE} 项生效"
    [ "$LATE" = true ] && warn "晚加载：电池进程需重启后才会注入"
    if [ "${FAILED:-0}" -gt 0 ]; then
      bad "${FAILED} 项 Hook 失配："
      sed -n 's/^failed=/   - /p' "$RECORD"
    fi
    ;;
esac

fcmfix_apply_description
exit 0
