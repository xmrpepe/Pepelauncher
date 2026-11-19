# pepelauncher implementation plan

Deliverable 1 of the brief. Read together with `SOURCE_NOTES.md`. Nothing below has been
implemented yet. This is the plan to approve before upstream files are touched.

## 1. Feature flag: a product flavor, not just a constant

`BuildConfig.LINUX_ENABLED` alone can't satisfy "builds and runs as plain mako with the flag
off". mako's main manifest strips `INTERNET`, `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS`, and
a Kotlin constant can't change the merged manifest or drop native libraries from the APK. So the
flag is a **flavor dimension** that sets the constant:

| Flavor | `BuildConfig.LINUX_ENABLED` | applicationId | Contents |
|---|---|---|---|
| `linux` (default) | `true` | `io.pepe.launcher` | `:linux`, `:terminal`, `:compositor`, `:proot`; extra permissions |
| `plain` | `false` | `io.pepe.launcher.plain` | Exactly mako's code and manifest; no network permission; no native code |

- Variants become `linuxDebug`, `linuxRelease`, `plainDebug`, … (for example `sh gradlew assembleLinuxDebug`).
- `app/src/linux/AndroidManifest.xml` is a flavor manifest, which has higher merge priority than
  `main`. It re-adds the permissions with `tools:node="replace"` and declares no components itself;
  the components come from the library manifests. If the merger refuses to override `main`'s
  `remove` markers (to be verified in step 2), the fallback is a documented two-line edit of the main
  manifest that moves those `remove` lines into `app/src/plain/AndroidManifest.xml`.
- Upstream code reaches Linux features only through `com.rama.mako.pepe.LinuxHooks`. That object
  exists twice with the same signature: `app/src/linux/java/…` (the real one, delegating to `:linux`)
  and `app/src/plain/java/…` (no-ops). The plain flavor never depends on the Linux modules.
- **Naming:** the Kotlin `namespace` stays `com.rama.mako`, so upstream R and BuildConfig references
  don't change. Only `applicationId` changes. The app name "pepelauncher" comes from a flavor-level
  `app_name` resource override, so `values/strings.xml` and the 12 translations stay untouched.
  New code lives under `io.pepe.launcher.{linux,terminal,compositor}`.

## 2. New modules

```
:linux        Kotlin, Android library. Package io.pepe.launcher.linux
  runtime/    ProotRunner (pure command builder + process launcher), ProotEnv,
              FakeProc (fake /proc/{stat,loadavg,uptime,version,vmstat} like proot-distro)
  setup/      RootfsInstaller (resumable HTTP Range download, md5 + optional PGP check,
              Commons-Compress tar.gz extraction: symlinks kept, hardlinks → symlinks,
              modes kept), FirstBootConfigurator (resolv.conf, hosts, locale-gen,
              pacman-key, pacman.conf DisableSandbox/DownloadUser, remove kernel+firmware,
              pacman hook install, pacman -Syu), SetupActivity (progress UI, retry)
  service/    LinuxService (foreground, specialUse; owns proot processes, terminal sessions,
              the compositor), PhantomKillerGuideActivity
  apps/       DesktopEntryParser, DesktopEntryRepository (FileObserver on the hook stamp +
              rescan on resume), IconResolver (hicolor/Adwaita lookup, PNG preferred, SVG →
              PNG cache), PacmanActions (pacman -Qi / -Rns / -Ss / -S)
  settings/   LinuxSettingsActivity (bohio widgets): mirror, reinstall/reset, storage used,
              /sdcard bind, shell, scale, touch mode, auto keyboard, dialog policy,
              export/import (tar.zst via proot `bsdtar --zstd`, so no zstd-jni needed)
:proot        NDK/CMake only. Builds termux/proot + talloc from vendored source
              (git submodules) into libproot.so, libproot-loader.so, libproot-loader32.so
              for arm64-v8a, armeabi-v7a and x86_64 (x86_64 only for emulator tests).
              Building from source keeps the app F-Droid-compatible (no prebuilt blobs).
:terminal     Vendored Termux terminal-emulator + terminal-view (Apache-2.0, with JNI pty
              helper) + TerminalActivity (tabs, extra-keys row, pinch zoom, copy/paste,
              URL detection, immersive, cutout-aware). Sessions are owned by LinuxService.
:compositor   NDK C/C++: vendored libwayland-server + libffi + xkbcommon, pre-generated
              protocol code (wayland-scanner output is committed so the build host needs no
              wayland-scanner), and a minimal XWM. Kotlin side: LinuxWindowActivity,
              WindowRegistry, input mapping (touch/pointer, hardware keys, IME →
              xkb keysyms, text-input-v3), clipboard bridge.
```

New modules declare their dependencies inline, as `:app` already does, so
`gradle/libs.versions.toml` stays untouched.

## 3. Upstream files to touch (estimate)

Each touch is a hook that is as small as possible. Everything is listed in `UPSTREAM_PATCHES.md`
as it lands.

| File | Change | Size |
|---|---|---|
| `settings.gradle.kts` | `rootProject.name`; `include(":linux", ":terminal", ":compositor", ":proot")` | ~5 lines |
| `app/build.gradle.kts` | applicationId; `buildFeatures.buildConfig = true`; flavor dimension and the two flavors with `LINUX_ENABLED`; `linuxImplementation(project(":linux"))`; APK name `pepelauncher_…` | ~25 lines |
| `app/src/debug/res/values/strings.xml` | "Mako Dev" → "pepelauncher Dev" (the debug build type outranks flavor resources) | 1 line |
| `managers/AppsProvider.kt` | new sealed subclass `LinuxEntry` (in a **new file** in the same package, as Kotlin allows); `getAll()` appends `LinuxHooks.entries(context)`; one branch each in `launch`, `getIcon`, `getAppSizeBytes`, `appCacheKey` | ~8 lines |
| `managers/AppListManager.kt` | one branch each in `openAppSettings` (Linux long-press sheet: Add to Favorites / Add to home screen (pinned shortcut) / Package info / Uninstall) and `renderAppInfo` (hide API row) | ~6 lines |
| `activities/MainActivity.kt` | `dispatchTouchEvent`: `LinuxHooks.onHomeTouch(this, ev)` for the swipe-right fling; `onCreate`/`onResume`: `LinuxHooks.onHomeCreated(this) { appListManager.refresh() }` for FileObserver-driven refresh | ~4 lines |
| `activities/SettingsActivity.kt` | `LinuxHooks.onSettingsCreated(this)` injects a "Linux" section built from bohio widgets programmatically, so `view_settings.xml` is not edited | 1 line |
| `readme.md` | short pepelauncher header + link to `docs/BUILDING.md`; mako text kept below | ~15 lines |

**Not touched:** `AndroidManifest.xml` (main, unless the merger fallback is needed),
`PrefsManager.kt` (Linux entries get `packageName = "linux.<desktop-id>"`, so the existing
`appKey()` works and `healData()` keeps their prefs), `GroupManager.kt` (the Linux group is
created through public `PrefsManager` APIs), layouts, translations, `LICENSE`, bohio.

**New files at the root:** `NOTICE` (credits mako/Pombo; lists Termux terminal libs (Apache-2.0),
PRoot (GPL-2.0+), talloc (LGPL-3.0+), Wayland/libffi/xkbcommon (MIT), Apache Commons Compress
(Apache-2.0)), `docs/UPSTREAM_PATCHES.md`, `docs/BUILDING.md`.

## 4. Key technical decisions

- **No root, Android 10–15, W^X:** only `libproot.so` and `libproot-loader.so` are ever `execve`d,
  both from `nativeLibraryDir`. Guest binaries are mapped by the proot loader, never `execve`d from
  `filesDir`. This stays within the SELinux `execute_no_trans` ban for targetSdk ≥ 29.
  targetSdk stays 37.
- **16 KB pages:** NDK r28 links with 16 KB max page size by default, which Android 15 devices need.
- **SysV shared memory:** Android kernels have no SysV IPC. Xwayland and X clients use MIT-SHM, so
  proot runs with the Termux fork's `--sysvipc` emulation. Without it, many X11 apps crash.
- **Process model (proposal):** run `LinuxService`, the compositor, `TerminalActivity` and
  `LinuxWindowActivity` in a separate process `:linux` of the same app. Then a launcher crash or
  `recreate()` (mako recreates on theme or language change) doesn't kill every Linux window, and a
  compositor crash doesn't take down the home screen. The brief says "in the app process", so
  see question Q3.
- **Window → task:** `LinuxWindowActivity` uses `documentLaunchMode="always"`,
  `autoRemoveFromRecents="true"` and its own `taskAffinity`. The window id goes in
  `data = pepe-window://<id>`.
  - A Recents swipe is detected as `onDestroy` with `isFinishing` and no `finish()` from us. That
    sends `xdg_toplevel.close`, then SIGTERM after 5 s and SIGKILL after 10 s.
  - Android 10+ blocks background activity starts. If a Linux app maps a window while
    pepelauncher is not in the foreground, the window is announced by a notification
    ("Mousepad opened a window — tap to show") instead of appearing on its own.
- **Rendering stage 1:** wl_shm → `ANativeWindow_lock` + NEON BGRA→RGBA swizzle, with
  damage-limited copies. Stage 2 (optional, later): linux-dmabuf via `AHardwareBuffer`.
- **Rootfs download:** `os.archlinuxarm.org` serves an HTTPS certificate that doesn't match its
  host name; I checked this from the build machine. It redirects to mirrors over plain HTTP. The
  default mirror is therefore an HTTPS mirror (`fl.us.mirror.archlinuxarm.org`) and the mirror is
  configurable. Integrity is checked against the published `.md5`; see question Q2 for PGP signature
  checks.
- **"Add to home screen":** mako's home *is* the list, so the native equivalent is
  "Add to Favorites". A real pinned shortcut (`ShortcutManager.requestPinShortcut`) is offered too;
  because pepelauncher is the default launcher, mako's own `ConfirmPinShortcutActivity` accepts it.

## 5. Order of work and milestones (from the brief)

1. ✅ `SOURCE_NOTES.md` + this plan. **Stop for review.**
2. Flavors and flag, rename, NOTICE, `UPSTREAM_PATCHES.md`. Gate: `assemblePlainDebug`,
   `assembleLinuxDebug` and `testPlainDebugUnitTest` pass, and the plain APK's merged manifest has
   no `INTERNET` permission.
3. `:proot` + `:linux` setup + `:terminal`. Milestone: `pacman -Syu` from the swipe-right terminal.
4. Linux section, pacman hook, `.desktop` parsing, icons. Milestone: `pacman -S mousepad` → entry appears.
5. `:compositor`, one Activity per toplevel, XWayland and the XWM. Milestones: `foot` or
   `weston-terminal` gets its own card; two mousepad windows give two cards; `xeyes` works.
6. Settings, polish, `BUILDING.md`, unit tests (`DesktopEntryParser`, `ProotRunner`), instrumented
   setup smoke test. The smoke test uses a tiny fixture tarball served from the test APK, so CI needs
   no network.

**What this container can verify:** Gradle builds, unit tests and the native NDK builds. It
**cannot** verify on-device milestones: no ARM device, no emulator with KVM, and no Arch ARM
userland. Those need a phone or a `adb`-attached device. I will mark each milestone "built" vs
"verified on device" honestly.

## 6. Open questions (answers change what gets built)

- **Q1 – Extra native dependencies.** The compositor needs **libffi** (libwayland-server's
  dispatch depends on it) and, for the XWM, **libxcb** (+ libXau, with pre-generated xcb-proto
  C). Neither is in the brief's list. Both are MIT and vendored as source. OK to add?
- **Q2 – Rootfs authenticity.** The `.md5` comes from the same mirror as the tarball, so it only
  protects against corruption. Real authenticity needs a check of the PGP `.sig` against the Arch
  Linux ARM build key, which in Kotlin means **BouncyCastle** (`bcpg`, ~1.5 MB, MIT). Add it, or
  ship md5 + a pinned SHA-256 that is updated per release?
- **Q3 – Process.** Run the Linux side in a separate `:linux` process (recommended, see §4), or in
  the launcher's main process as the brief literally says?
- **Q4 – SVG icons.** `rsvg-convert` inside proot needs `librsvg` and its dependencies installed in
  Arch, which is large and not present on a fresh rootfs. A bundled **AndroidSVG** (Apache-2.0,
  ~250 KB, no native code) renders most Adwaita/hicolor SVGs. Use AndroidSVG with
  `rsvg-convert` as a fallback when it is installed?
- **Q5 – Repository setup.** The GitHub repo was empty. If you allow it, I'll push the untouched
  mako `2026.59` history to `main`, so the pull request from this branch shows only pepelauncher's
  changes. Otherwise this branch becomes the default branch and no PR can be opened.
- **Q6 – bohio license.** bohio has no LICENSE file. I'll treat it as GPL-3.0 (same organisation,
  distributed inside mako). Flag it upstream?
