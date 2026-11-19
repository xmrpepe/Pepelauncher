/*
 * pepelauncher: replaces libxcb's xcb_auth.c. pepelauncher only connects to Xwayland through a
 * socketpair it created itself (xcb_connect_to_fd), so no X authority file is ever consulted and
 * libXau is not needed. xcb_connect() would simply connect without credentials.
 */
#include "xcb.h"
#include "xcbint.h"

int _xcb_get_auth_info(int fd, xcb_auth_info_t *info, int display)
{
    (void) fd;
    (void) display;
    info->namelen = 0;
    info->name = 0;
    info->datalen = 0;
    info->data = 0;
    return 0;
}
