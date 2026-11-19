# Upstream patches

Every file inherited from mako (tag `2026.59`) that pepelauncher changes is listed here.
Everything else that pepelauncher adds lives in new files and new modules.

To move to a new mako tag:

```sh
git fetch upstream tag <tag>
git merge <tag>            # conflicts can only be in the files below
git submodule update --init
```

Then re-check every hook below against the new upstream code.

| File | Why | What changed |
|---|---|---|
| `settings.gradle.kts` | Rename and new modules | `rootProject.name = "pepelauncher"`; `include(":linux")`, `":proot"`, `":terminal"` (and later modules) under a `// pepelauncher modules` comment. |
| `app/build.gradle.kts` | Rename and feature flag | `applicationId = "io.pepe.launcher"` (namespace stays `com.rama.mako`); `buildFeatures.buildConfig = true`; flavor dimension `runtime` with flavors `linux` (default, `LINUX_ENABLED = true`) and `plain` (`.plain` suffix, `LINUX_ENABLED = false`); `"linuxImplementation"(project(":linux"))`; APK name `pepelauncher_<version>.apk`. |
| `.gitmodules` | proot sources | Adds the `proot/third_party/proot` submodule (termux/proot). |
| `app/src/main/java/com/rama/mako/activities/MainActivity.kt` | Swipe-right terminal, Linux section refresh | `dispatchTouchEvent` calls `LinuxHooks.onHomeTouch(this, ev)` before `super` (it never consumes the event); `onCreate` calls `LinuxHooks.onHomeCreated(this) { appListManager.refresh() }` after `appListManager.setup()` (FileObserver on the pacman hook stamp); one import. |
| `app/src/main/java/com/rama/mako/activities/SettingsActivity.kt` | Linux settings entry | `onCreate` ends with `LinuxHooks.onSettingsCreated(this)`, which inserts a "Linux settings" button (bohio `BTN2`) under the title programmatically, so `view_settings.xml` stays untouched; one import. |
| `app/src/main/java/com/rama/mako/managers/AppsProvider.kt` | Linux section | `getAll()` appends `LinuxHooks.entries(context)`; `launch()`, `getIcon()`, `getAppSizeBytes()` and `appCacheKey()` get a `LinuxEntry` branch in their `when`; `getIcon()` returns Linux icons before mako's per-instance icon cache (they render asynchronously); one import. Marked `// pepelauncher`. |
| `app/src/main/java/com/rama/mako/managers/AppListManager.kt` | Linux long-press actions | `openAppSettings()` gets `is LinuxEntry -> LinuxHooks.showActions(...)`; `renderAppInfo()` treats `LinuxEntry` like `ShortcutEntry` (no API/size row); one import. |
| `readme.md` | Project description | A pepelauncher header (features, build variants, links) prepended; mako's text is unchanged below it. |
| `app/src/debug/res/values/strings.xml` | Rename | Debug `app_name` "Mako Dev" → "pepelauncher Dev" (a build-type resource outranks the flavor's `app_name`). |

## New files inside upstream packages

- `app/src/main/java/com/rama/mako/managers/LinuxEntry.kt`: the `LinuxEntry` subclass of mako's
  sealed `AppsProvider.AppEntry`. Kotlin requires sealed subclasses in the same package and module.
  It lives in its own file so that `AppsProvider.kt` only needs one-line hooks.
- `app/src/{linux,plain}/java/com/rama/mako/pepe/LinuxHooks.kt`: the per-flavor bridge.

## Files that are deliberately *not* changed

- `PrefsManager.kt`: Linux entries use `packageName = "linux.<desktop id>"`, so `appKey()`,
  group assignment and `healData()` work unchanged. The "Linux" group (`pepe-linux`) is created
  through `PrefsManager`'s public API.

- `app/src/main/AndroidManifest.xml`: the `linux` flavor's own manifest
  (`app/src/linux/AndroidManifest.xml`) re-adds the permissions mako strips, using
  `tools:node="replace"`. This was checked with `aapt2 dump badging`: the `linux` APK requests
  `INTERNET`, `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS`, and the `plain` APK requests only
  mako's `ACCESS_HIDDEN_PROFILES`.
- `app/src/main/res/values*/strings.xml`: `app_name` is overridden per flavor in
  `app/src/{linux,plain}/res/values/strings.xml`.
- `LICENSE`, `bohio/`.
