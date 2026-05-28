#!/system/bin/sh
MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done

chmod 0755 "$MODDIR"/zygisk/* 2>/dev/null
