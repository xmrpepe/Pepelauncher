/* pepelauncher: config for libxkbcommon 1.13.2 (core library only) on Android and Linux hosts. */
#pragma once
#define DEFAULT_XKB_LAYOUT "us"
#define DEFAULT_XKB_MODEL "pc105"
#define DEFAULT_XKB_OPTIONS NULL
#define DEFAULT_XKB_RULES "evdev"
#define DEFAULT_XKB_VARIANT NULL
/* pepelauncher compiles its keymap from a string, so these paths are never searched. */
#define DFLT_XKB_CONFIG_EXTRA_PATH "/nonexistent"
#define DFLT_XKB_CONFIG_ROOT "/nonexistent"
#define DFLT_XKB_CONFIG_UNVERSIONED_EXTENSIONS_PATH "/nonexistent"
#define DFLT_XKB_CONFIG_VERSIONED_EXTENSIONS_PATH "/nonexistent"
#define DFLT_XKB_LEGACY_ROOT "/nonexistent"
#define HAVE_ASPRINTF 1
#define HAVE_DIRENT_H 1
#define HAVE_MKOSTEMP 1
#define HAVE_MMAP 1
#define HAVE_STRNDUP 1
#define HAVE_UNISTD_H 1
#define HAVE_REAL_PATH 1
#define HAVE___BUILTIN_EXPECT 1
#if !defined(__ANDROID__)
#define HAVE_SECURE_GETENV 1
#define HAVE_EUIDACCESS 1
#define HAVE_EACCESS 1
#define HAVE_NEWLOCALE 1
#define HAVE_OPEN_MEMSTREAM 1
#define HAVE_POSIX_FALLOCATE 1
#endif
#define LIBXKBCOMMON_VERSION "1.13.2"
#define LIBXKBCOMMON_TOOL_PATH "/nonexistent"
#define XLOCALEDIR "/nonexistent"
#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
