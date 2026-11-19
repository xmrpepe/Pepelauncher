# Building pepelauncher

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 or newer (21 tested) |
| Android SDK platform | `platforms;android-37.0`, `build-tools;37.0.0` |
| Android NDK | **r28c** (`ndk;28.2.13676358`), pinned in `proot/`, `terminal/` |
| CMake | 3.22.1 (installed automatically by the Android Gradle plugin) |
| Build host tools | POSIX `sh`, GNU `make` (used to build proot) |

Clone with submodules (mako's `bohio` and termux's `proot`):

```sh
git clone --recurse-submodules https://github.com/xmrpepe/Pepelauncher
# or, in an existing clone:
git submodule update --init
```

`gradlew` is committed without the executable bit by upstream mako, so call it through `sh`:

```sh
sh gradlew assembleLinuxDebug      # pepelauncher with Linux (io.pepe.launcher.debug)
sh gradlew assemblePlainDebug      # plain mako-equivalent build (io.pepe.launcher.plain.debug)
sh gradlew test                    # all JVM unit tests
```

## Build variants

The `runtime` flavor dimension is the single feature flag (`BuildConfig.LINUX_ENABLED`):

- `linux`: adds the `:linux`, `:proot` and `:terminal` modules and the permissions they need
  (`INTERNET`, foreground service, notifications, optional storage access).
- `plain`: exactly mako's code and manifest. No network permission and no native code.

## proot

`:proot` builds [termux/proot](https://github.com/termux/proot) (submodule
`proot/third_party/proot`) and a vendored talloc (`proot/third_party/talloc`) with the NDK via
`proot/build-proot.sh`. Gradle runs this automatically (`:proot:buildProot`). The outputs are
packaged as native libraries, so Android extracts them to `nativeLibraryDir`, the only
app-writable place that allows `execve()` for targetSdk ≥ 29:

| ABI | files |
|---|---|
| arm64-v8a | `libproot.so`, `libproot-loader.so`, `libproot-loader32.so` (32-bit guests) |
| armeabi-v7a | `libproot.so`, `libproot-loader.so` |
| x86_64 | `libproot.so`, `libproot-loader.so` (emulator testing only) |

To rebuild proot by hand, for example after moving the submodule to a newer commit:

```sh
git -C proot/third_party/proot fetch && git -C proot/third_party/proot checkout <commit>
sh proot/build-proot.sh "$ANDROID_HOME/ndk/28.2.13676358" /tmp/proot-work /tmp/proot-out arm64-v8a armeabi-v7a
```

The script works around two upstream build quirks without patching the submodule:
`loader-info.c` is generated with `llvm-readelf`, because upstream's script needs gawk; and
`<string.h>` is force-included, which armv7 needs.

## Testing Linux setup on a desktop

`HostSetupIntegrationTest` runs the real setup code (download, md5 check, extraction, first-boot
configuration, `pacman -Syu`) through a **host** build of the same proot sources, against
Arch Linux's x86_64 bootstrap tarball. It is skipped unless configured:

```sh
# 1. host proot (glibc), from the same sources
cp -R proot/third_party/proot/src /tmp/hp && mkdir -p /tmp/tl
cp proot/talloc-config.h /tmp/tl/config.h && cp proot/third_party/talloc/talloc.h /tmp/tl/
gcc -O2 -I/tmp/tl -Iproot/third_party/talloc/lib/replace -c proot/third_party/talloc/talloc.c -o /tmp/tl/talloc.o
ar rcs /tmp/tl/libtalloc.a /tmp/tl/talloc.o
for t in loader/loader proot; do
  CFLAGS=-I/tmp/tl LDFLAGS=-L/tmp/tl make -C /tmp/hp PROOT_UNBUNDLE_LOADER=/x HAS_LOADER_32BIT= $t
done
mkdir -p /tmp/hostproot && cp /tmp/hp/proot /tmp/hp/loader/loader /tmp/hostproot/

# 2. Arch bootstrap as .tar.gz (pepelauncher extracts gzip tarballs, like Arch Linux ARM's)
curl -LO https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst
zstd -dc archlinux-bootstrap-x86_64.tar.zst | gzip -1 > /tmp/boot.tar.gz

# 3. run
PEPE_HOST_PROOT_DIR=/tmp/hostproot PEPE_HOST_ROOTFS_TGZ=/tmp/boot.tar.gz \
  sh gradlew :linux:testDebugUnitTest --tests '*HostSetupIntegrationTest*'
```

Behind a TLS-intercepting proxy, also set `PEPE_HOST_EXTRA_CA=<PEM file or bundle with the proxy CA>`. The test then
installs that CA in the guest before `pacman -Syu`.

## Testing the compositor on a desktop

The compositor core (`compositor/src/main/cpp/pepe/`) is platform independent. A desktop test
backend renders each window into a PNG and takes input commands from a FIFO:

```sh
cmake -S compositor/host -B /tmp/pepe-host -G Ninja && ninja -C /tmp/pepe-host
# in the rootfs from HostSetupIntegrationTest:
#   pacman -S foot mousepad xorg-xwayland xorg-xeyes ttf-dejavu
PROOT_DIR=/tmp/hostproot WORK=<work dir with arch/> HOST_BIN=/tmp/pepe-host/pepe-host \
  compositor/host/smoke-test.sh
```

It checks the step-5 milestones (foot, two mousepad windows, xeyes through Xwayland, closing)
and leaves `window-<id>.png` snapshots in `$OUT` (default `/tmp/pepe-smoke`). See
`compositor/README.pepelauncher.md` for the vendored libraries and how generated files are made.
