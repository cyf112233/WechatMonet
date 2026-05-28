# WechatMonet

Pure Zygisk module for recoloring WeChat with Android Monet palette tones.

## Scope

- No app
- No Xposed
- No LSPosed
- No NPatch fallback

## Requirements

- Android 12 or newer
- Magisk with Zygisk enabled
- WeChat (`com.tencent.mm`)
- JDK 21 for building

## Build

1. Ensure `local.properties` points to your Android SDK.
2. Run `.\gradlew.bat packageMagiskModule`
3. Output zip: `build/outputs/magisk/wechatmonet-zygisk.zip`

## Layout

- `src/main/cpp/`: native Zygisk entry
- `src/main/java/`: Java injector compiled to dex and packed into the module
- `module/`: Magisk module template

## Notes

- The current implementation is pure Zygisk and uses a dex bridge loaded inside the target process.
- The recoloring path is heuristic view-tree recoloring, not legacy Xposed method hooking.
