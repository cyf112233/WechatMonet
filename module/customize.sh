SKIPUNZIP=1

ui_print "*******************************"
ui_print " WechatMonet Zygisk Installer "
ui_print "*******************************"

if [ -z "$BOOTMODE" ]; then
  abort "! Install from Magisk app only"
fi

if [ "$API" -lt 31 ]; then
  abort "! Android 12 (API 31) or newer is required"
fi

if [ "$ARCH" != "arm64" ] && [ "$ARCH" != "arm" ] && [ "$ARCH" != "x64" ]; then
  abort "! Unsupported architecture: $ARCH"
fi

ui_print "- Extracting module files"
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'service.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'framework/*' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'zygisk/*' -d "$MODPATH" >&2

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Done"
