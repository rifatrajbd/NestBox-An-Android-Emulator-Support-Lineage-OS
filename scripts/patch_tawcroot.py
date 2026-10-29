"""Nestbox patch for tawcroot (Wayondroid a45f80b): hide dead entries from a process's own fd listing.

On HyperOS 3 (Redmi Note 14 Pro+) every guest process lists an fd 4 in /proc/self/fd that
neither fstat nor readlink can reach. Zygote's FileDescriptorTable aborts on it
("JNI FatalError called: (system_server) Unable to stat 4"), so system_server never starts.
An fd listing should only name fds the caller can use, so entries of the caller's own fd
directory that fstat rejects are dropped, the same way tawcroot already hides its reserved fds.
"""
import sys

path = sys.argv[1]
s = open(path, encoding="utf-8").read()

helpers = r'''
/* Nestbox: entries of the caller's OWN fd directory that fstat cannot
 * reach are dropped from the listing. HyperOS 3 shows every guest a
 * phantom fd 4 that fstat and readlink both reject; zygote's fd table
 * check aborts on it ("Unable to stat 4") before system_server forks.
 * Only own-process listings are touched: another process's fd numbers
 * mean nothing to our fstat. */
static int nb_is_own_fd_dir(const char *link, long n)
{
	if (n == 13 && tawc_starts_with(link, "/proc/self/fd")) return 1;
	if (n < 10 || !tawc_starts_with(link, "/proc/")) return 0;
	long pid = 0;
	long i = 6;
	while (i < n && link[i] >= '0' && link[i] <= '9') {
		pid = pid * 10 + (link[i] - '0');
		i++;
	}
	if (i == 6 || i + 3 != n) return 0;
	if (link[i] != '/' || link[i + 1] != 'f' || link[i + 2] != 'd') return 0;
	return pid == TAWC_RAW(TAWC_SYS_getpid, 0, 0, 0, 0, 0, 0);
}

static long nb_drop_dead_fds(void *buf, long n)
{
	unsigned char *p = (unsigned char *)buf;
	long out = 0, in = 0;
	while (in < n) {
		unsigned short reclen;
		__builtin_memcpy(&reclen, p + in + 16, 2);
		if (reclen < 20 || in + (long)reclen > n)
			return out == in ? n : out;
		int has_nul = 0;
		for (long k = 19; k < (long)reclen; k++) {
			if (p[in + k] == 0) { has_nul = 1; break; }
		}
		if (!has_nul) return out == in ? n : out;
		int keep = 1;
		int fd;
		if (tawcroot_dirent_filter_dname_to_fd((const char *)(p + in + 19), &fd)) {
			struct stat st;
			if (TAWC_RAW(TAWC_SYS_fstat, fd, (long)&st, 0, 0, 0, 0) < 0)
				keep = 0;
		}
		if (keep) {
			if (out != in) __builtin_memmove(p + out, p + in, reclen);
			out += reclen;
		}
		in += reclen;
	}
	return out;
}
'''

old_loop = '''	for (;;) {
		long compacted = tawcroot_dirent_filter_compact(
			buf, n, tawcroot_reserved_fds,
			tawcroot_n_reserved_fds);
		if (compacted > 0) return compacted;'''
new_loop = '''	int nb_own = nb_is_own_fd_dir(proc_link, ln);
	for (;;) {
		long compacted = tawcroot_dirent_filter_compact(
			buf, n, tawcroot_reserved_fds,
			tawcroot_n_reserved_fds);
		if (nb_own && compacted > 0) compacted = nb_drop_dead_fds(buf, compacted);
		if (compacted > 0) return compacted;'''
if old_loop not in s:
    raise SystemExit("getdents loop not found")
s = s.replace(old_loop, new_loop)

# Insert the helpers before the getdents64 handler (the function holding the loop).
idx = s.index(new_loop)
func_start = s.rfind("\nstatic long ", 0, idx)
if func_start < 0:
    raise SystemExit("handler start not found")
s = s[:func_start] + "\n" + helpers + s[func_start:]
open(path, "w", encoding="utf-8").write(s)
print("patched", path)
