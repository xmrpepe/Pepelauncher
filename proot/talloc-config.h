/*
 * Hand-written config.h for building talloc.c alone against Android's bionic libc.
 * talloc's waf build would normally generate this; proot only needs the core allocator.
 */
#ifndef PEPE_TALLOC_CONFIG_H
#define PEPE_TALLOC_CONFIG_H

#define __STDC_WANT_LIB_EXT1__ 1

#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3

#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDARG_H 1
#define HAVE_ERRNO_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STDINT_H 1
#define HAVE_MALLOC_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_SYSMACROS_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_BOOL 1
#define HAVE__Bool 1
#define HAVE_LIMITS_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_LINUX_TYPES_H 1
#define HAVE_DLFCN_H 1

#define HAVE_VA_COPY 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_STRLCPY 1
#define HAVE_STRLCAT 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMMEM 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_SETEUID 1
#define HAVE_SETEGID 1
#define HAVE_MKDTEMP 1
#define HAVE_STRTOLL 1
#define HAVE_STRTOULL 1
#define HAVE_STRTOK_R 1
#define HAVE_STRERROR_R 1
#define HAVE_FTRUNCATE 1
#define HAVE_PREAD 1
#define HAVE_PWRITE 1
#define HAVE_CHOWN 1
#define HAVE_LCHOWN 1
#define HAVE_CHROOT 1
#define HAVE_LINK 1
#define HAVE_READLINK 1
#define HAVE_SYMLINK 1
#define HAVE_REALPATH 1
#define HAVE_INITGROUPS 1
#define HAVE_DLOPEN 1
#define HAVE_DLSYM 1
#define HAVE_DLERROR 1
#define HAVE_DLCLOSE 1
#define HAVE_TIMEGM 1
#define HAVE_GETPAGESIZE 1
#define HAVE_USLEEP 1
#define HAVE_SIG_ATOMIC_T_TYPE 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_SOCKLEN_T 1
#define HAVE_OFF_T 1
#define HAVE_INO_T 1
#define HAVE_SSIZE_T 1
#define HAVE_SIZE_T 1
#define HAVE_MODE_T 1
#define HAVE_INT8_T 1
#define HAVE_UINT8_T 1
#define HAVE_INT16_T 1
#define HAVE_UINT16_T 1
#define HAVE_INT32_T 1
#define HAVE_UINT32_T 1
#define HAVE_INT64_T 1
#define HAVE_UINT64_T 1
#define HAVE_UINT_T 1
#define HAVE_UINTMAX_T 1
#define HAVE_INTMAX_T 1
#define HAVE_COMPARISON_FN_T 1
#define HAVE_DECL_STRERROR_R 1
#define HAVE_LIBREPLACE 1

#define HAVE_FUNCTION_MACRO 1
#define HAVE___ATTRIBUTE__ 1
#define HAVE_VISIBILITY_ATTR 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE___BUILTIN_EXPECT 1

#define TALLOC_BUILD 1
#define _PUBLIC_ __attribute__((visibility("default")))

#endif
