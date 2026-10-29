"""NestBox patch for Wayondroid's rootless helper (a45f80b): app/src/main/cpp/rootless/rootless_prep.cpp.

Adds what Android 10 generic system images (GSIs, e.g. Pixel Experience Plus 10) need, plus two
hooks NestBox uses for every Modern machine:

1. Packed APEXes. Android 10 GSIs ship /system/apex/*.apex (a zip holding apex_payload.img, an
   ext4 image). The rootless engine can't loop-mount, so each payload is unpacked into
   /system/apex/<name>/ (the flattened layout Wayondroid already handles) and
   ro.apex.updatable=false is set so apexd bind-mounts the folders.
2. <instance>/nestbox.prop: system properties for the guest, written as a marked block at the end
   of /system/product/etc/build.prop on every start (product loads last, so it wins).
3. <instance>/nestbox.remove: guest paths to delete before boot (one per line), e.g. bloat apps.

Usage: python3 scripts/patch_helper.py <wayondroid-source>/app/src/main/cpp/rootless/rootless_prep.cpp
"""
import sys

path = sys.argv[1]
s = open(path, encoding="utf-8").read()
if "nb_unpack_apexes" in s:
    raise SystemExit("already patched: start from a clean copy of rootless_prep.cpp")

s = s.replace("#include <dirent.h>\n", "#include <dirent.h>\n#include <fcntl.h>\n#include <vector>\n", 1)

helpers = r'''
// ---- NestBox additions (see scripts/patch_helper.py) ----
namespace {

uint32_t nb_rd32(const std::string& b, size_t at) {
    return (uint32_t)(uint8_t)b[at] | (uint32_t)(uint8_t)b[at + 1] << 8 | (uint32_t)(uint8_t)b[at + 2] << 16 |
           (uint32_t)(uint8_t)b[at + 3] << 24;
}
uint16_t nb_rd16(const std::string& b, size_t at) { return (uint16_t)((uint8_t)b[at] | (uint8_t)b[at + 1] << 8); }

bool nb_pread_all(int fd, std::string& out, size_t len, off_t at) {
    out.assign(len, '\0');
    size_t done = 0;
    while (done < len) {
        ssize_t n = pread(fd, &out[done], len - done, at + (off_t)done);
        if (n <= 0) return false;
        done += (size_t)n;
    }
    return true;
}

// Offset and size of a STORED (uncompressed) zip entry. APEX payloads are stored so they can be
// mounted in place.
bool nb_zip_stored(int fd, const char* want, off_t* off, uint64_t* size) {
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < 22) return false;
    const size_t tail = st.st_size < 70000 ? (size_t)st.st_size : 70000;
    std::string b;
    if (!nb_pread_all(fd, b, tail, st.st_size - (off_t)tail)) return false;
    long eocd = -1;
    for (long i = (long)tail - 22; i >= 0; i--) {
        if (memcmp(&b[(size_t)i], "PK\x05\x06", 4) == 0) { eocd = i; break; }
    }
    if (eocd < 0) return false;
    const uint32_t cd_size = nb_rd32(b, (size_t)eocd + 12), cd_off = nb_rd32(b, (size_t)eocd + 16);
    std::string cd;
    if (!nb_pread_all(fd, cd, cd_size, cd_off)) return false;
    for (size_t i = 0; i + 46 <= cd.size();) {
        if (memcmp(&cd[i], "PK\x01\x02", 4) != 0) return false;
        const uint16_t method = nb_rd16(cd, i + 10), nl = nb_rd16(cd, i + 28), el = nb_rd16(cd, i + 30), cl = nb_rd16(cd, i + 32);
        const uint32_t usize = nb_rd32(cd, i + 24), lho = nb_rd32(cd, i + 42);
        if (i + 46 + nl > cd.size()) return false;
        if (cd.compare(i + 46, nl, want) == 0 && strlen(want) == nl) {
            if (method != 0) return false;
            std::string lh;
            if (!nb_pread_all(fd, lh, 30, lho) || memcmp(lh.data(), "PK\x03\x04", 4) != 0) return false;
            *off = (off_t)lho + 30 + nb_rd16(lh, 26) + nb_rd16(lh, 28);
            *size = usize;
            return true;
        }
        i += 46 + nl + el + cl;
    }
    return false;
}

bool nb_copy_range(int in, off_t off, uint64_t size, const std::string& dst) {
    const int out = open(dst.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (out < 0) return false;
    std::vector<char> buf(1 << 20);
    uint64_t left = size;
    bool ok = true;
    while (left > 0 && ok) {
        const size_t want = left < buf.size() ? (size_t)left : buf.size();
        const ssize_t n = pread(in, buf.data(), want, off);
        ok = n > 0 && write(out, buf.data(), (size_t)n) == n;
        if (ok) { off += n; left -= (uint64_t)n; }
    }
    close(out);
    return ok;
}

// "com.android.runtime.release.apex" -> "com.android.runtime"
std::string nb_apex_name(std::string file) {
    file = file.substr(0, file.size() - 5);
    for (const char* sfx : {".release", ".debug", ".current"}) {
        const size_t n = strlen(sfx);
        if (file.size() > n && file.compare(file.size() - n, n, sfx) == 0) file.resize(file.size() - n);
    }
    return file;
}

bool nb_unpack_apexes(const Layout& l, std::string* error) {
    const std::string dir = l.rootfs + "/system/apex";
    std::vector<std::string> packed;
    if (DIR* d = opendir(dir.c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string n = e->d_name;
            if (n.size() > 5 && n.compare(n.size() - 5, 5, ".apex") == 0) packed.push_back(n);
        }
        closedir(d);
    }
    if (packed.empty()) return true;
    for (const auto& n : packed) {
        const std::string apex = dir + "/" + n;
        const std::string dest = dir + "/" + nb_apex_name(n);
        const std::string tmp = dir + "/.nestbox-payload.img";
        const int fd = open(apex.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return fail_errno(error, "open " + apex);
        off_t off = 0;
        uint64_t size = 0;
        const bool found = nb_zip_stored(fd, "apex_payload.img", &off, &size);
        const bool copied = found && nb_copy_range(fd, off, size, tmp);
        close(fd);
        if (!found) return fail(error, n + ": no stored apex_payload.img");
        if (!copied) { unlink(tmp.c_str()); return fail(error, n + ": could not read the payload"); }
        if (!remove_if_exists(dest, error) || !mkdirs_or_fail(dest, error)) return false;
        char err[512];
        const std::string owners = l.store + "/owners";
        const int rc = vine_ext4_extract(tmp.c_str(), dest.c_str(), owners.c_str(), err, sizeof err);
        unlink(tmp.c_str());
        if (rc != 0) return fail(error, n + ": " + err);
        unlink(apex.c_str());
    }
    // apexd must use the folders instead of loop-mounting packages
    const std::string flag = l.instance + "/nestbox.apex-flattened";
    return write_file(flag, "1\n") || fail(error, "could not write " + flag);
}

// Android 10 GSIs (system-as-root, 64_32): the zygote rc lives at the image root, flattened APEX
// folders carry a suffix (com.android.runtime.release) their manifest drops, and prop.default
// still declares the 32-bit ABIs.
bool nb_prepare_q(const Layout& l, const PrepOptions& o, std::string* error) {
    const std::string R = l.rootfs;
    // /apex/<manifest name> for folders named differently
    const std::string adir = R + "/system/apex";
    if (DIR* d = opendir(adir.c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string n = e->d_name;
            if (n == "." || n == "..") continue;
            const auto manifest = read_file(adir + "/" + n + "/apex_manifest.json");
            if (!manifest) continue;
            const size_t k = manifest->find("\"name\"");
            if (k == std::string::npos) continue;
            const size_t q1 = manifest->find('"', manifest->find(':', k) + 1);
            const size_t q2 = q1 == std::string::npos ? q1 : manifest->find('"', q1 + 1);
            if (q2 == std::string::npos) continue;
            const std::string name = manifest->substr(q1 + 1, q2 - q1 - 1);
            if (name.empty() || name == n || name.find('/') != std::string::npos) continue;
            struct stat st{};
            const std::string link = R + "/apex/" + name;
            if (lstat(link.c_str(), &st) != 0 && symlink(("/system/apex/" + n).c_str(), link.c_str()) != 0) {
                closedir(d);
                return fail_errno(error, "symlink " + link);
            }
        }
        closedir(d);
    }
    if (o.host_has_32bit) return true;
    // Root-level zygote rc (imported by init.rc as /init.${ro.zygote}.rc)
    if (!is_regular_file(R + "/init.zygote64.rc")) {
        if (const auto z = read_file(R + "/init.zygote64_32.rc")) {
            if (!write_mode(R + "/init.zygote64.rc", zygote64_rc(*z), 0644, error)) return false;
        }
    }
    // Wayondroid's 64-bit conversion looks for it in /system/etc/init/hw
    const std::string hw = R + "/system/etc/init/hw";
    if (!is_regular_file(hw + "/init.zygote64.rc") && !is_regular_file(hw + "/init.zygote64_32.rc") &&
        is_regular_file(R + "/init.zygote64.rc")) {
        if (!mkdirs_or_fail(hw, error) || !copy_mode(R + "/init.zygote64.rc", hw + "/init.zygote64.rc", 0644, error)) return false;
    }
    for (const char* f : {"/system/etc/prop.default", "/default.prop", "/system/product/build.prop", "/system/product_services/build.prop"}) {
        struct stat st{};
        if (lstat((R + f).c_str(), &st) != 0 || !S_ISREG(st.st_mode)) continue;
        const auto text = read_file(R + f);
        if (!text) continue;
        const std::string edited = props_64bit_only(*text);
        if (edited != *text && !write_mode(R + f, edited, 0644, error)) return false;
    }
    return true;
}

// nestbox.prop into a marked block of /system/product/etc/build.prop, nestbox.remove deleted
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
    std::string props = read_file(l.instance + "/nestbox.prop").value_or("");
    if (is_regular_file(l.instance + "/nestbox.apex-flattened")) props += "\nro.apex.updatable=false\n";
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
    return mkdirs_or_fail(R + "/system/product/etc", error) && write_mode(prop, text, 0644, error);
}

} // namespace
'''

anchor = "bool prepare_rootfs(const Layout& l, const PrepOptions& o, std::string* error) {\n    const std::string& R = l.rootfs;\n"
if anchor not in s:
    raise SystemExit("prepare_rootfs not found")
s = s.replace(anchor, helpers + "\n" + anchor +
              "\n    if (!nb_unpack_apexes(l, error) || !nb_prepare_q(l, o, error)) return false;\n", 1)

tail = "    return mkdirs_or_fail(l.run + \"/pulse\", error);\n}"
if tail not in s:
    raise SystemExit("prepare_rootfs end not found")
s = s.replace(tail, "    if (!mkdirs_or_fail(l.run + \"/pulse\", error)) return false;\n    return nb_apply_overlay(l, error);\n}", 1)

open(path, "w", encoding="utf-8").write(s)
print("patched", path)
