/*
 * NestBox su for Modern (rootless) guests. Built as jniLibs/arm64-v8a/libnbsu.so and copied into
 * the guest by the machine overlay:
 *   /system/xbin/su       mode 04755, only while "Root" is on
 *   /system/bin/nb-runas  mode 0755, NestBox's own commands (run as root) switching to a user
 *
 * The guest runs under tawcroot, which emulates users: a set-uid ELF starts with euid 0 inside
 * the guest only. The phone's own user and permissions never change.
 *
 * Accepts the usual forms: su, su -c "cmd", su 0 cmd args, su shell -c cmd, su -, su --mount-master.
 */
#include <ctype.h>
#include <errno.h>
#include <grp.h>
#include <pwd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int is_number(const char *s) {
    if (!*s) return 0;
    for (; *s; s++) if (!isdigit((unsigned char)*s)) return 0;
    return 1;
}

int main(int argc, char **argv) {
    uid_t uid = 0;
    const char *cmd = NULL;
    const char *shell = "/system/bin/sh";
    int login = 0, i = 1, have_user = 0;
    for (; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "-c") || !strcmp(a, "--command")) {
            if (i + 1 < argc) cmd = argv[++i];
        } else if (!strncmp(a, "--command=", 10)) {
            cmd = a + 10;
        } else if (!strcmp(a, "-") || !strcmp(a, "-l") || !strcmp(a, "--login")) {
            login = 1;
        } else if (!strcmp(a, "-s") || !strcmp(a, "--shell")) {
            if (i + 1 < argc) shell = argv[++i];
        } else if (!strcmp(a, "-v") || !strcmp(a, "--version")) {
            puts("1.0:NESTBOX");
            return 0;
        } else if (!strcmp(a, "-V")) {
            puts("1");
            return 0;
        } else if (!strcmp(a, "-h") || !strcmp(a, "--help")) {
            puts("usage: su [-c CMD] [-] [USER|UID [COMMAND ARGS...]]");
            return 0;
        } else if (a[0] == '-' && a[1]) {
            /* -mm, --mount-master, -p, --preserve-environment, -Z ...: nothing to do here */
        } else if (!have_user) {
            have_user = 1;
            if (is_number(a)) {
                uid = (uid_t)strtoul(a, NULL, 10);
            } else {
                struct passwd *pw = getpwnam(a);
                if (!pw) {
                    fprintf(stderr, "su: unknown user %s\n", a);
                    return 1;
                }
                uid = pw->pw_uid;
            }
        } else {
            break; /* the rest is the command */
        }
    }
    gid_t gid = (gid_t)uid;
    if (setgroups(0, NULL) != 0 && uid != getuid()) { /* not fatal for root -> root */ }
    if (setresgid(gid, gid, gid) != 0 || setresuid(uid, uid, uid) != 0) {
        fprintf(stderr, "su: permission denied\n");
        return 1;
    }
    if (uid == 0) {
        setenv("HOME", "/data", 1);
        setenv("USER", "root", 1);
        setenv("LOGNAME", "root", 1);
    }
    setenv("SHELL", shell, 1);
    if (cmd) {
        execl(shell, "sh", "-c", cmd, (char *)NULL);
    } else if (i < argc) {
        execvp(argv[i], argv + i);
    } else {
        execl(shell, login ? "-sh" : "sh", (char *)NULL);
    }
    fprintf(stderr, "su: %s\n", strerror(errno));
    return 127;
}
