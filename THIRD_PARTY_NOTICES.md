# Third-party notices

## LADB (Tyler Nijmeh) — ADB server binaries + pairing mechanism
- Source: https://github.com/tytydraco/LADB
- License: BSD-3-Clause with an additional no-Play-Store-uploads condition.
  Copyright (c) 2025, Tyler Nijmeh. Full text in `third_party/ladb/LICENSE`.
- What we use: the prebuilt `adb` server binaries under
  `app/src/main/jniLibs/<abi>/libadb.so` (AOSP code, built by the LADB
  project), and the pairing/loopback mechanism (own implementation in
  `AdbShell.kt`). No LADB application code is copied.
- `phone` is distributed outside the Play Store, satisfying the upload condition.

## Geist Mono (Vercel) — `app/app/src/main/res/font/geist_mono.ttf`

SIL Open Font License 1.1. Source: npm `geist@1.3.1`, `dist/fonts/geist-mono/GeistMono-Regular.ttf`.
