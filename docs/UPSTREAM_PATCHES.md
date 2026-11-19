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
| `app/src/main/java/com/rama/mako/activities/MainActivity.kt` | Swipe-right terminal | `dispatchTouchEvent` calls `LinuxHooks.onHomeTouch(this, ev)` before `super` (it never consumes the event); one import. |
| `app/src/debug/res/values/strings.xml` | Rename | Debug `app_name` "Mako Dev" → "pepelauncher Dev" (a build-type resource outranks the flavor's `app_name`). |

## Files that are deliberately *not* changed

- `app/src/main/AndroidManifest.xml`: the `linux` flavor's own manifest
  (`app/src/linux/AndroidManifest.xml`) re-adds the permissions mako strips, using
  `tools:node="replace"`. This was checked with `aapt2 dump badging`: the `linux` APK requests
  `INTERNET`, `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS`, and the `plain` APK requests only
  mako's `ACCESS_HIDDEN_PROFILES`.
- `app/src/main/res/values*/strings.xml`: `app_name` is overridden per flavor in
  `app/src/{linux,plain}/res/values/strings.xml`.
- `LICENSE`, `bohio/`.
