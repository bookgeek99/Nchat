#!/system/bin/sh
# Nchat daemon 开机启动脚本
# 由 Magisk 在 late_start service 阶段执行（root 身份，systemless）
MODDIR=${0%/*}
JAR="$MODDIR/files/nchatd.jar"
WORK=/data/local/tmp/nchatd

# App 已卸载则跳过，避免留下孤儿进程
if ! pm list packages com.nchat 2>/dev/null | grep -q "package:com.nchat"; then
  exit 0
fi

# jar 不存在则跳过
[ -f "$JAR" ] || exit 0

# 等系统启动完成（最多 60 秒）
i=0
while [ $i -lt 60 ]; do
  if [ "$(getprop sys.boot_completed)" = "1" ]; then break; fi
  sleep 1
  i=$((i+1))
done
sleep 5

mkdir -p $WORK
cp "$JAR" $WORK/nchatd.jar
chmod 644 $WORK/nchatd.jar

# 已在运行则直接退出（幂等）
for d in /proc/[0-9]*; do
  if grep -qa NchatDaemon $d/cmdline 2>/dev/null; then
    exit 0
  fi
done

cd $WORK || exit 0
export CLASSPATH=$WORK/nchatd.jar
# setsid：让 daemon 脱离当前会话，脚本退出后不被连带清理
setsid /system/bin/app_process /system/bin NchatDaemon </dev/null >>$WORK/stdout.txt 2>&1 &
echo "service.sh launched daemon at $(date)" >> $WORK/stdout.txt
exit 0