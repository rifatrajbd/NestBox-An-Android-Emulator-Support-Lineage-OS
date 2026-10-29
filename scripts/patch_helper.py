"""NestBox patch for Wayondroid's rootless helper (a45f80b), app/src/main/cpp/rootless.

Adds the machine overlay NestBox applies before every boot (the first one included):

1. <instance>/nestbox.prop: system properties for the guest, written as a marked block at the end
   of /system/product/etc/build.prop on every start (product loads last, so it wins).
2. <instance>/nestbox.remove: guest paths to delete before boot (one per line), e.g. bloat apps,
   /system/xbin/su when root is off.
3. <instance>/nestbox.files/: a tree copied over the guest's root with its file modes (NestBox's su
   with the set-uid bit, nb-runas, files a ROM ships in assets/guest/<rom id>/).
4. <instance>/nestbox.shared: a phone folder (one path) bound at the guest's /nestbox_host and
   mounted at /data/media/0/Host, which apps see as /sdcard/Host. The mount runs on post-fs-data
   from /product/etc/init/nestbox-shared.rc, which init parses before vine-rootless.rc, so the
   recursive /data/media binds that make /storage carry it along.

Usage: python3 scripts/patch_helper.py <wayondroid-source>/app/src/main/cpp/rootless
       (patches rootless_prep.cpp and rootless_instance.cpp in that folder)
"""
import os
import sys

folder = sys.argv[1]
path = os.path.join(folder, "rootless_prep.cpp")
s = open(path, encoding="utf-8").read()
if "nb_apply_overlay" in s:
    raise SystemExit("already patched: start from a clean copy of rootless_prep.cpp")

s = s.replace("#include <dirent.h>\n", "#include <dirent.h>\n#include <fcntl.h>\n#include <vector>\n", 1)

helpers = r'''
// ---- NestBox additions (see scripts/patch_helper.py) ----
namespace {

// nestbox.files: copies a tree over the rootfs, keeping each file's mode (set-uid included)
bool nb_copy_tree(const std::string& src, const std::string& dst, std::string* error) {
    DIR* d = opendir(src.c_str());
    if (!d) return true;
    bool ok = true;
    while (dirent* e = readdir(d)) {
        const std::string n = e->d_name;
        if (n == "." || n == "..") continue;
        const std::string from = src + "/" + n, to = dst + "/" + n;
        struct stat st{};
        if (lstat(from.c_str(), &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            ok = mkdirs_or_fail(to, error) && nb_copy_tree(from, to, error);
        } else if (S_ISREG(st.st_mode)) {
            struct stat old{};
            if (lstat(to.c_str(), &old) == 0 && !S_ISREG(old.st_mode)) ok = remove_if_exists(to, error);
            ok = ok && copy_mode(from, to, st.st_mode & 07777, error);
        }
        if (!ok) break;
    }
    closedir(d);
    return ok;
}

// nestbox.remove deleted, nestbox.prop into a marked block of /system/product/etc/build.prop,
// nestbox.files copied, the shared folder mount
bool nb_apply_overlay(const Layout& l, std::string* error) {
    const std::string R = l.rootfs;
    if (const auto list = read_file(l.instance + "/nestbox.remove")) {
        size_t at = 0;
        while (at < list->size()) {
            size_t nl = list->find('\n', at);
            std::string p = list->substr(at, nl == std::string::npos ? std::string::npos : nl - at);
            at = nl == std::string::npos ? list->size() : nl + 1;
            while (!p.empty() && (p.back() == '\r' || p.back() == ' ')) p.pop_back();
            if (p.size() < 2 || p[0] != '/' || p.find("..") != std::string::npos) continue;
            if (!remove_if_exists(R + p, error)) return false;
        }
    }
    const std::string props = read_file(l.instance + "/nestbox.prop").value_or("");
    const std::string prop = R + "/system/product/etc/build.prop";
    std::string text = read_file(prop).value_or("");
    const size_t b = text.find("\n# nestbox-begin");
    if (b != std::string::npos) {
        const size_t e = text.find("# nestbox-end", b);
        text.erase(b, e == std::string::npos ? std::string::npos : e + 14 - b);
    }
    while (!text.empty() && text.back() == '\n') text.pop_back();
    if (!props.empty()) text += "\n# nestbox-begin\n" + props + "\n# nestbox-end\n";
    else text += "\n";
    if (!mkdirs_or_fail(R + "/system/product/etc", error) || !write_mode(prop, text, 0644, error)) return false;
    if (!nb_copy_tree(l.instance + "/nestbox.files", R, error)) return false;
    const std::string init_dir = R + "/system/product/etc/init";
    // left by NestBox 0.6-0.7 (Android 10 port)
    if (!remove_if_exists(init_dir + "/nestbox-storage-q.rc", error)) return false;
    const std::string shared_rc = init_dir + "/nestbox-shared.rc";
    if (!is_regular_file(l.instance + "/nestbox.shared")) return remove_if_exists(shared_rc, error);
    return mkdirs_or_fail(R + "/nestbox_host", error) && mkdirs_or_fail(init_dir, error) &&
           write_mode(shared_rc,
                      "# NestBox shared folder: the phone's folder (bound at /nestbox_host) as /sdcard/Host\n"
                      "on post-fs-data\n"
                      "    mkdir /data/media/0 0770 media_rw media_rw\n"
                      "    mkdir /data/media/0/Host 0770 media_rw media_rw\n"
                      "    mount none /nestbox_host /data/media/0/Host bind\n",
                      0644, error);
}

} // namespace
'''

anchor = "bool prepare_rootfs(const Layout& l, const PrepOptions& o, std::string* error) {\n"
if anchor not in s:
    raise SystemExit("prepare_rootfs not found")
s = s.replace(anchor, helpers + "\n" + anchor, 1)

tail = "    return mkdirs_or_fail(l.run + \"/pulse\", error);\n}"
if tail not in s:
    raise SystemExit("prepare_rootfs end not found")
s = s.replace(tail, "    if (!mkdirs_or_fail(l.run + \"/pulse\", error)) return false;\n    return nb_apply_overlay(l, error);\n}", 1)

open(path, "w", encoding="utf-8").write(s)
print("patched", path)

# rootless_instance.cpp: the shared folder joins the runtime's binds (init and exec alike)
ipath = os.path.join(folder, "rootless_instance.cpp")
t = open(ipath, encoding="utf-8").read()
if "nestbox.shared" in t:
    raise SystemExit("already patched: start from a clean copy of rootless_instance.cpp")
old = """             "/dev/pts:/dev/pts", l.run + ":/run/user/1000"}) {
        out.push_back("-b");
        out.push_back(b);
    }
    return out;
}"""
new = """             "/dev/pts:/dev/pts", l.run + ":/run/user/1000"}) {
        out.push_back("-b");
        out.push_back(b);
    }
    // NestBox: the phone folder shared with the guest (see patch_helper.py)
    if (const auto shared = read_file(l.instance + "/nestbox.shared")) {
        std::string host = *shared;
        while (!host.empty() && (host.back() == '\\n' || host.back() == '\\r' || host.back() == ' ')) host.pop_back();
        struct stat st{};
        if (!host.empty() && host[0] == '/' && host.find(':') == std::string::npos && stat(host.c_str(), &st) == 0 &&
            S_ISDIR(st.st_mode)) {
            out.push_back("-b");
            out.push_back(host + ":/nestbox_host");
        }
    }
    return out;
}"""
if old not in t:
    raise SystemExit("binds() not found in rootless_instance.cpp")
t = t.replace(old, new, 1)
open(ipath, "w", encoding="utf-8").write(t)
print("patched", ipath)
