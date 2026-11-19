/* pepelauncher: config for libxcb 1.17.0 on Android (bionic) and Linux hosts. */
#pragma once
#define HAVE_SENDMSG 1
#define HAVE_ABSTRACT_SOCKETS 1
#define HAVE_GETADDRINFO 1
#define USE_POLL 1
#define XCB_QUEUE_BUFFER_SIZE 16384
#ifndef IOV_MAX
#define IOV_MAX 16
#endif
#define PACKAGE_STRING "libxcb 1.17.0"
