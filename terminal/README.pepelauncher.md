# Vendored Termux terminal libraries

- Source: https://github.com/termux/termux-app, commit `8629e632fcb95da272221be327db653fb24befe9`
  - `terminal-emulator/src/main/java` → `src/main/java/com/termux/terminal`
  - `terminal-emulator/src/main/jni/termux.c` → `src/main/cpp/termux.c` (built with CMake instead of ndk-build)
  - `terminal-emulator/src/test/java` → `src/test/java`
  - `terminal-view/src/main/java` → `src/main/java/com/termux/view`
  - `terminal-view/src/main/res` → `src/main/res`
- License: **Apache License 2.0** (see `LICENSE-APACHE-2.0.txt`). termux-app itself is GPLv3, but its
  `LICENSE.md` exempts these two libraries, which derive from jackpal's Android-Terminal-Emulator.
  Apache-2.0 is compatible with pepelauncher's GPL-3.0-or-later.
- Source files are unmodified, so updating means copying the same directories again.
  The only differences are the build files (`build.gradle.kts`, `CMakeLists.txt`) and one merged
  Android library (namespace `com.termux.view`) instead of two.
