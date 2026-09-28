package com.baystudio.droide.core






internal object FoundryUbuntuBaseManifest {
    const val ROOTFS_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    const val ROOTFS_BYTES = 29_936_675L
    const val EXPECTED_PACKAGE_COUNT = 91
    const val PACKAGE_SET_SHA256 = "5c63840b017fb52485cb502cc7ffc1e727f82ffd86e232727704880f78285dec"
    const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"

    val packages = listOf(
        "apt", "base-files", "base-passwd", "bash", "bsdutils", "coreutils", "dash",
        "debconf", "debianutils", "diffutils", "dpkg", "e2fsprogs", "findutils",
        "gcc-14-base", "gpgv", "grep", "gzip", "hostname", "init-system-helpers",
        "libacl1", "libapt-pkg6.0t64", "libassuan0", "libattr1", "libaudit-common",
        "libaudit1", "libblkid1", "libbz2-1.0", "libc-bin", "libc6", "libcap-ng0",
        "libcap2", "libcom-err2", "libcrypt1", "libdb5.3t64", "libdebconfclient0",
        "libext2fs2t64", "libffi8", "libgcc-s1", "libgcrypt20", "libgmp10",
        "libgnutls30t64", "libgpg-error0", "libhogweed6t64", "libidn2-0",
        "liblz4-1", "liblzma5", "libmd0", "libmount1", "libncursesw6",
        "libnettle8t64", "libnpth0t64", "libp11-kit0", "libpam-modules",
        "libpam-modules-bin", "libpam-runtime", "libpam0g", "libpcre2-8-0",
        "libproc2-0", "libseccomp2", "libselinux1", "libsemanage-common",
        "libsemanage2", "libsepol2", "libsmartcols1", "libss2", "libssl3t64",
        "libstdc++6", "libsystemd0", "libtasn1-6", "libtinfo6", "libudev1",
        "libunistring5", "libuuid1", "libxxhash0", "libzstd1", "login", "logsave",
        "mawk", "mount", "ncurses-base", "ncurses-bin", "passwd", "perl-base",
        "procps", "sed", "sensible-utils", "sysvinit-utils", "tar", "ubuntu-keyring",
        "util-linux", "zlib1g",
    ).also { manifest ->
        check(manifest.size == EXPECTED_PACKAGE_COUNT)
        check(manifest.distinct().size == manifest.size)
    }
}
