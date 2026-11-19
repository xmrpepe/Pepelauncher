/*
 * pepelauncher: hand-written fficonfig.h for libffi 3.8.1 on Android (bionic) and Linux hosts,
 * aarch64 / arm / x86_64. libwayland-server only uses ffi_call, never closures, so the closure
 * allocator keeps its simple mmap path (no static trampolines, no SELinux/PaX probing).
 */
#ifndef PEPE_FFICONFIG_H
#define PEPE_FFICONFIG_H

#define HAVE_ALLOCA_H 1
#define HAVE_MEMCPY 1
#define HAVE_MMAP 1
#define HAVE_MMAP_ANON 1
#define HAVE_MMAP_DEV_ZERO 1
#define HAVE_MKOSTEMP 1
#define HAVE_GETPAGESIZE 1
#define HAVE_AS_CFI_PSEUDO_OP 1
#define HAVE_HIDDEN_VISIBILITY_ATTRIBUTE 1
#define STDC_HEADERS 1
#define EH_FRAME_FLAGS "a"
#define FFI_MMAP_EXEC_WRIT 0

#if defined(__x86_64__)
#define HAVE_AS_X86_PCREL 1
#define HAVE_AS_X86_64_UNWIND_SECTION_TYPE 1
#endif

#if defined(__aarch64__) || defined(__x86_64__)
#define SIZEOF_LONG_DOUBLE 16
#define HAVE_LONG_DOUBLE 1
#define SIZEOF_SIZE_T 8
#else
#define SIZEOF_LONG_DOUBLE 8
#define SIZEOF_SIZE_T 4
#endif
#define SIZEOF_DOUBLE 8

#ifdef HAVE_HIDDEN_VISIBILITY_ATTRIBUTE
#ifdef LIBFFI_ASM
#define FFI_HIDDEN(name) .hidden name
#else
#define FFI_HIDDEN __attribute__ ((visibility ("hidden")))
#endif
#else
#ifdef LIBFFI_ASM
#define FFI_HIDDEN(name)
#else
#define FFI_HIDDEN
#endif
#endif

#endif
