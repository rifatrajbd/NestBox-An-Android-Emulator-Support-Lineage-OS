"""NestBox patch for Wayondroid's tawcroot (a45f80b): SELinux as Android 10 userspace expects it.

tawcroot already presents SELinux as permissive (fake /sys/fs/selinux/status and enforce), which
is enough for Android 13. Android 10's libselinux needs two more things:

1. /sys/fs/selinux/deny_unknown reads "0". Android 10 checks property permissions against
   classes it looks up in selinuxfs; an app cannot read the host's, so every class is "unknown"
   and, without deny_unknown, every property set by init was refused.
2. Writes to /proc/<task>/attr/{exec,fscreate,keycreate,sockcreate} are accepted and dropped.
   Android 10's init sets an exec label for its vendor_init helper and stops when the host
   refuses (it is an app); the guest's policy is not enforced by anything anyway.

Both apply only when the guest's root holds /.nestbox-selinux-compat (NestBox writes it for
Android 10 machines), so Android 13 guests behave exactly as with upstream tawcroot.

Usage: python3 scripts/patch_tawcroot_selinux.py <wayondroid-source>/tawcroot
"""
import os
import sys

root = sys.argv[1]


def edit(rel, pairs):
    path = os.path.join(root, rel)
    s = open(path, encoding="utf-8").read()
    if "SELINUX_DENY_UNKNOWN" in s or "nestbox: SELinux label" in s:
        raise SystemExit("already patched: start from a clean copy of " + rel)
    for a, b in pairs:
        if a not in s:
            raise SystemExit("%s: anchor not found: %s" % (rel, a[:60]))
        s = s.replace(a, b, 1)
    open(path, "w", encoding="utf-8").write(s)
    print("patched", path)


edit("include/proc_shadow.h", [
    ("#define TAWCROOT_PROC_SHADOW_KIND_MAX   12",
     "#define TAWCROOT_PROC_SHADOW_SELINUX_DENY_UNKNOWN 13  /* /sys/fs/selinux/deny_unknown (NestBox) */\n"
     "#define TAWCROOT_PROC_SHADOW_KIND_MAX   13"),
    ("long tawcroot_proc_shadow_open(int kind);",
     "long tawcroot_proc_shadow_open(int kind);\n\n"
     "/* NestBox: 1 when the guest root holds /.nestbox-selinux-compat (Android 10 guests). */\n"
     "int tawcroot_nestbox_selinux_compat(void);"),
])

edit("src/proc_shadow.c", [
    ("""		if (tawc_streq(path + 16, "enforce"))
			return TAWCROOT_PROC_SHADOW_SELINUX_ENFORCE;""",
     """		if (tawc_streq(path + 16, "enforce"))
			return TAWCROOT_PROC_SHADOW_SELINUX_ENFORCE;
		if (tawc_streq(path + 16, "deny_unknown") && tawcroot_nestbox_selinux_compat())
			return TAWCROOT_PROC_SHADOW_SELINUX_DENY_UNKNOWN;"""),
    ("""int tawcroot_proc_shadow_classify(const char *path)
{""",
     """int tawcroot_nestbox_selinux_compat(void)
{
	static int cached = -1;
	int c = __atomic_load_n(&cached, __ATOMIC_RELAXED);
	if (c >= 0) return c;
	c = tawcroot_rootfs_fd >= 0 &&
	    TAWC_RAW(TAWC_SYS_faccessat, tawcroot_rootfs_fd, (long)".nestbox-selinux-compat", 0, 0, 0, 0) == 0;
	__atomic_store_n(&cached, c, __ATOMIC_RELAXED);
	return c;
}

int tawcroot_proc_shadow_classify(const char *path)
{"""),
    ("""	case TAWCROOT_PROC_SHADOW_SELINUX_ENFORCE:
		return memfd_from_bytes("tawcroot-selinux-enforce", "0", 1);""",
     """	case TAWCROOT_PROC_SHADOW_SELINUX_ENFORCE:
		return memfd_from_bytes("tawcroot-selinux-enforce", "0", 1);
	case TAWCROOT_PROC_SHADOW_SELINUX_DENY_UNKNOWN:
		return memfd_from_bytes("tawcroot-selinux-deny-unknown", "0", 1);"""),
])

edit("src/syscalls_fs.c", [
    ("""	if ((flags & O_ACCMODE) == O_RDONLY &&
	    (flags & O_DIRECTORY) == 0 &&
	    (flags & O_PATH) == 0) {
		int kind = proc_shadow_classify_at(scratch, dirfd, path);""",
     """	/* nestbox: SELinux label requests (setexeccon, setfscreatecon...)
	 * written to /proc/<task>/attr/<name>. The host refuses an app's
	 * writes and nothing enforces the guest's policy, so they go to a
	 * throwaway memfd (Android 10's init stops when setexeccon fails). */
	if ((flags & O_ACCMODE) != O_RDONLY && (flags & O_PATH) == 0 && path &&
	    tawc_starts_with(path, "/proc/")) {
		static const char *const attr_tails[] = {
			"/attr/exec", "/attr/fscreate", "/attr/keycreate", "/attr/sockcreate",
		};
		const size_t plen = tawc_strlen(path);
		for (size_t ai = 0; ai < sizeof attr_tails / sizeof attr_tails[0]; ++ai) {
			const size_t tl = tawc_strlen(attr_tails[ai]);
			if (plen > tl && tawc_streq(path + plen - tl, attr_tails[ai]) &&
			    tawcroot_nestbox_selinux_compat())
				return tawc_memfd_create("tawcroot-selinux-attr",
							 (flags & O_CLOEXEC) ? 1U : 0U);
		}
	}

	if ((flags & O_ACCMODE) == O_RDONLY &&
	    (flags & O_DIRECTORY) == 0 &&
	    (flags & O_PATH) == 0) {
		int kind = proc_shadow_classify_at(scratch, dirfd, path);"""),
])
