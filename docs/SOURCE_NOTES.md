# mako source notes

What pepelauncher is forked from, read before any code was changed.

- **Upstream:** https://github.com/rama-io/mako, tag `2026.59` (commit `8aa9f27`, "59 (#238)").
- **Submodule:** `bohio` → https://github.com/rama-io/bohio at `f722ffa` ("Update strings.xml").
- This repository is based on mako's real git history (187 commits), not on an unpacked tarball.
  The `upstream` remote points at rama-io/mako, so a future tag can be merged with
  `git fetch upstream tag 2026.60 && git merge 2026.60` instead of being re-applied by hand.
  The GitHub tag tarball does **not** contain `bohio/` (GitHub archives skip submodules), so
  always clone with `--recurse-submodules` or run `git submodule update --init`.

## Language, toolkit, architecture

| Topic | What mako does |
|---|---|
| Language | 100 % Kotlin. No Java and no native code in `:app`. |
| UI toolkit | **Android Views + XML layouts.** No Jetpack Compose and no AppCompat themes in `:app`; the activities extend `androidx.activity.ComponentActivity` through `bohio`'s `BohioActivity`. |
| DI | None. Objects are created by hand in `onCreate` (`AppsProvider(this)`, `AppListManager(...)`), and preferences are a process singleton (`PrefsManager.getInstance(context)`). |
| Structure | `activities/` (one Activity per screen), `activities/settings/` (one `SettingsXController(activity).setup()` per settings section), `managers/` (logic: apps, icons, groups, prefs, clock, battery), `widgets/`, `receivers/`, `services/`. |
| Threading | Almost all on the main thread. `AppUninstallReceiver` uses `goAsync()` with a raw `Thread`. No coroutines in use (core-ktx only). |
| Prefs | `SharedPreferences("settings")` via `bohio.managers.PrefsManager`, subclassed by `com.rama.mako.managers.PrefsManager`. Keys are colon-namespaced (`apps:icons`, `group:<id>:label`, `app:<pkg>[:profile_<n>][:shortcut_<id>]:group_id`). The PIN lives in `EncryptedSharedPreferences("secure_settings")`. |
| Theming | `bohio.managers.ThemeManager` remaps colours of a whole view tree at runtime (`ThemeManager.applyTheme(context, view)`), from named palettes (`rama`, `mako`, `puma`, `tui`, `txori`, …). The font is Jersey 25 (pixel font). Icons are vector drawables named `px_*` in bohio. |
| Strings | `app/src/main/res/values*/strings.xml` in 12 locales. The product name comes from bohio (`@string/product_mako`), and `app_name` points to it. The debug build type overrides `app_name` to "Mako Dev". |
| Code style | Kotlin "official" style, 4-space indent, trailing commas used sparingly, `private fun` helpers at the bottom, `runCatching { … }.getOrDefault(…)` for defensive platform calls, `Build.VERSION.SDK_INT` guards inline. |

## Build system

- Gradle wrapper **9.4.1**, Android Gradle Plugin **9.2.0** (it has built-in Kotlin support; the
  `kotlin-android` plugin is only declared `apply false` at the root), Kotlin **2.3.20**, JVM target 17.
- Version catalog in `gradle/libs.versions.toml`, though `:app` also declares some dependencies
  inline (`androidx.activity:activity-ktx:1.9.3`, `recyclerview:1.4.0`, `security-crypto:1.1.0-alpha06`).
- `settings.gradle.kts` uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS` with google() and
  mavenCentral() only. **No JitPack.**
- `:app`: `namespace = applicationId = "com.rama.mako"`, `compileSdk 37`, `minSdk 21`, `targetSdk 37`,
  `versionCode 59`, `versionName "2026.59"`.
  - Build types: `debug` (`.debug` suffix, "-dev"), `beta` (`.beta` suffix, minified), `release`
    (minified, signed with the debug key so F-Droid can re-sign).
  - No product flavors and no `buildConfig` feature enabled (AGP 9 turns `BuildConfig` off by default).
  - `packaging.jniLibs.useLegacyPackaging = true` is **already set**. Native libraries are stored
    compressed and extracted at install, which is what `extractNativeLibs=true` asks for.
  - The APK file name is set to `mako_<versionName>.apk` in `androidComponents.onVariants`.
- `:bohio`: Groovy `build.gradle`, `com.android.library`, `namespace com.rama.bohio`, minSdk 21,
  `api 'androidx.appcompat:appcompat:1.6.1'`.
- Tests: JUnit 4 + Robolectric 4.14 + MockK + Truth (`GroupsManagerTest`, `IconManagerTest` in
  `:app`, and four tests in bohio). There is **no `androidTest` source set** and no CI workflow
  (`.github/` holds only issue templates).
- `gradlew` is committed **without the executable bit** (mode 100644). Run it as `sh gradlew …`.

## Module layout

```
settings.gradle.kts      rootProject "Mako"; include(":app", ":bohio")
app/                     the launcher (com.rama.mako)
  src/main/java/com/rama/mako/
    CsActivity.kt        base Activity: prefs + window background on top of BohioActivity
    activities/          MainActivity (HOME), SettingsActivity, AboutActivity, LockActivity,
                         ConfirmPinShortcutActivity
    activities/settings/ one controller per settings section
    managers/            AppsProvider, AppListManager, IconManager, GroupManager(GroupsManager),
                         PrefsManager, ClockManager, BatteryManager, HomeBackgroundManager
    receivers/           AppUninstallReceiver (+ unused ScreenLockAdminReceiver)
    services/            ScreenLockAccessibilityService (unused, not in the manifest)
    widgets/             WdPinField
  src/debug/res/         app_name override "Mako Dev"
  src/test/              Robolectric unit tests
bohio/                   shared UI library (git submodule): BohioActivity, ThemeManager,
                         FontManager, base PrefsManager, Wd* widgets (WdCheckbox,
                         WdCollapsibleSection, WdRange, WdRadioGroup, WdColorPicker, …)
docs/, assets/, fastlane/  branding, F-Droid metadata
```

## Manifest and permissions (important for pepelauncher)

mako is deliberately **network-free**. `app/src/main/AndroidManifest.xml` strips permissions with
`tools:node="remove"` under a comment "PREVENT THIS PERMISSIONS TO EVER BE ADDED". The list includes
`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`,
`READ/WRITE_EXTERNAL_STORAGE` and `SYSTEM_ALERT_WINDOW`. The README advertises "No network access is
required".

The Linux feature needs at least `INTERNET` (rootfs download, pacman), `FOREGROUND_SERVICE` (plus a
type permission) and `POST_NOTIFICATIONS`. Shared storage needs `MANAGE_EXTERNAL_STORAGE`. These
can't be switched with a `BuildConfig` constant, because the manifest is merged at build time.
See `docs/PLAN.md` § "Feature flag" for how the plan handles this: a Linux-only product flavor
whose higher-priority manifest re-adds the permissions, while the plain build stays network-free.

Components declared: `MainActivity` (HOME + LAUNCHER, `singleTask`, `excludeFromRecents`,
`adjustResize`), `SettingsActivity`, `AboutActivity`, `LockActivity`,
`ConfirmPinShortcutActivity` (handles `CONFIRM_PIN_SHORTCUT`, own empty task affinity) and
`AppUninstallReceiver` (`PACKAGE_REMOVED`, which calls `GroupsManager.healData()`).

## Home screen = the app list (there is no separate drawer)

mako has **no home/drawer split**. `MainActivity` inflates `res/layout/view_home.xml`:

1. `home_list_header.xml`: clock (tap opens the configured clock app), date (tap opens the
   configured date app), battery, private-space lock button, search icon. A long-press opens Settings.
2. `RecyclerView @id/app_list`: the whole app list, driven by `AppListManager`.
3. The search bar (`EditText`, 300 ms debounce, fuzzy, accent-folded matching via `AppListManager.filter`).
4. `@id/menu_bar`: a multi-select action bar (rename, move to group, app settings / remove shortcut).

**`AppsProvider`** is the data source. It has a sealed `AppEntry` hierarchy:

- `ActivityEntry` (from `LauncherApps.getActivityList` for every user profile, including work and
  private space),
- `DisabledEntry` (disabled packages, shown dimmed),
- `ShortcutEntry` (pinned shortcuts, plus dynamic or manifest shortcuts when "show shortcuts" is on).

`getAll()`, `launch(entry)`, `getIcon(entry)` and `getAppSizeBytes(entry)` each switch exhaustively
over the sealed class with `when`.

**`AppListManager`** builds `ListItem.Header` / `ListItem.App` / `ListItem.Empty` rows:

- Apps are grouped by `PrefsManager.getAppGroupId(entry)`. The default groups are
  `SystemIds.UNGROUPED` ("------ Default") and `SystemIds.FAVORITES` ("------ Favorites"). Users
  add groups in Settings. Groups can be collapsible, hidden, reordered, or "keep expanded".
- Optional multi-column layout via `GridLayoutManager`.
- **Tap** launches the app.
- **Long-press** on a row enters multi-select mode, whose bar has the context actions. Long-press on
  the trailing empty space opens Settings.
- Tapping the icon opens app settings by default (`apps:icons:open_settings`).
- `openAppSettings(entry)` and `renderAppInfo(view, entry)` also switch exhaustively over `AppEntry`.

**`PrefsManager.healData()`** deletes per-app prefs (group, custom label) for any key not
returned by `AppsProvider.getAll()`. Any new entry type must be part of `getAll()`, or its group
assignment is wiped the next time a package is uninstalled.

"Pinning" in mako means assigning an app to the Favorites group. Pinned launcher shortcuts
(`ShortcutManager.requestPinShortcut`) also work: mako is the default launcher, so it receives
`CONFIRM_PIN_SHORTCUT` itself and lists the result as a `ShortcutEntry`.

## Gesture handling

There is **no swipe or fling handling anywhere** in mako:

- `MainActivity` has a `GestureDetector` used only for `onLongPress` on empty RecyclerView space
  (it opens Settings). It is attached via `RecyclerView.addOnItemTouchListener` and always returns
  `false` from `onInterceptTouchEvent`.
- The header has an `OnLongClickListener` that opens Settings.
- `MainActivity.dispatchTouchEvent` is overridden but only calls `super`. It is a ready-made hook point.
- Keys: `KEYCODE_MENU` / `KEYCODE_F10` open Settings. Back exits multi-select, collapses search, then
  collapses groups.

Swipe-right is therefore **free**. Nothing upstream needs to move behind a setting, but pepelauncher
still adds a toggle "Swipe right opens terminal".

## Settings

`SettingsActivity` inflates `res/layout/view_settings.xml` (596 lines). It is a `ScrollView`
containing `WdCollapsibleSection` blocks (System, Groups, Themes, Appearance, Icons, Clock, Date,
Battery, Language, Security, About…). Each block is wired by a `Settings*Controller`. Checkboxes
are `bohio.widgets.WdCheckbox` bound to pref keys with `bindWdCheckbox(id, key, default, dependents)`.
The PIN lock gates `onResume`.

## License

- mako: **GPL-3.0-or-later** (`LICENSE` is the GPLv3 text, and the README says "v3 or later").
  Original author Pombo, with contributors listed in `docs/attributions.md`. The bundled font is
  Jersey 25 (OFL-1.1) and the icons are pixel-icon-library (CC-BY-4.0).
- bohio: **no LICENSE file in the repository.** It is published by the same organisation
  alongside GPL-3.0 projects and is distributed inside mako, so it is treated as GPL-3.0 here.
  This should be confirmed with upstream (see open questions in `PLAN.md`).
- Implication: pepelauncher as a whole is distributed under GPL-3.0-or-later. Anything added must be
  GPLv3-compatible. Apache-2.0, MIT, BSD, LGPL and GPL-2.0-or-later are fine. **GPL-2.0-only code
  must not be linked in**, but it may ship as a separate executable that pepelauncher only runs
  (mere aggregation). That covers proot.

## Observations that shape the plan

1. `AppEntry` is a **sealed class** with ~7 exhaustive `when`s across `AppsProvider`,
   `AppListManager` and `PrefsManager.FileKeys.appKey`. A Linux entry type is the cleanest
   integration, at the cost of one-line hooks in each `when`. The alternative is publishing every
   `.desktop` file as a dynamic launcher shortcut. That is capped at about 15 per app, needs the
   "show shortcuts" pref, and gives no control over labels or group, so it is rejected.
2. Grouping is mako's "section" concept. The **Linux section** is a system group ("------ Linux")
   that Linux entries default to, rendered by the existing header and row code.
3. Main manifest strips network and FGS permissions; see "Manifest and permissions" above.
4. `useLegacyPackaging = true` already gives `extractNativeLibs=true` behaviour.
5. `minSdk 21` must stay for the plain build. The Linux runtime only activates on API 29+
   (`Build.VERSION.SDK_INT` check), matching the Android 10–15 target.
6. `bohio` already provides every widget the Linux settings screen needs (`WdCollapsibleSection`,
   `WdCheckbox`, `WdRadioGroup`, `WdRange`, `BTN2` buttons) and the base `BohioActivity` (edge-to-edge,
   cutout mode, theme, UI scale), so new screens should extend `CsActivity`/`BohioActivity` for a
   consistent look.
