/* pepelauncher: hand-written config for libwayland-server 1.26.0 on Android (bionic) and Linux hosts. */
#pragma once

#define HAVE_ACCEPT4
#define HAVE_BROKEN_MSG_CMSG_CLOEXEC 0
#define HAVE_GETTID
#define HAVE_MKOSTEMP
#define HAVE_MREMAP
#define HAVE_POSIX_FALLOCATE
#define HAVE_PRCTL
#define HAVE_STRNDUP
#define HAVE_SYS_PRCTL_H
#undef HAVE_SYS_PROCCTL_H
#undef HAVE_SYS_UCRED_H
#define HAVE_XUCRED_CR_PID 0
/* memfd_create(2) is in bionic only from API 30; the server does not need it. */
#if !defined(__ANDROID__) || __ANDROID_API__ >= 30
#define HAVE_MEMFD_CREATE
#endif
#define PACKAGE "wayland"
#define PACKAGE_VERSION "1.26.0"
