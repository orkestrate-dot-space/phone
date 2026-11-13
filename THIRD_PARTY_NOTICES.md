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
