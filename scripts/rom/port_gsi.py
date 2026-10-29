"""Prepare a new Modern-mode port: a generic system image (GSI) on a Waydroid vendor.

Downloads both files (or uses local copies), checks them, unpacks the system image, looks
inside it and prints:
  - the Catalog.kt entry (Source.Port with sizes and SHA-256),
  - the bloat it found that NestBox usually removes,
  - anything that needs a code change (32-bit only image, packed APEXes, missing zygote rc...),
and records the ROM in roms/catalog.json.

Needs Python 3.9+ and `pip install ext4` (pure Python ext4 reader).

Example (Pixel Experience Plus 10):
  python -I scripts/rom/port_gsi.py --id pe10 --name "Pixel Experience Plus 10" --sdk 29 \
    --system https://sourceforge.net/projects/androidgsi/files/PixelExperiencePlus/PixelExperience_Plus-10.0-20201105-GSI-ARM64-AB.img.xz/download \
    --vendor https://sourceforge.net/projects/waydroid/files/images/vendor/waydroid_arm64/lineage-17.1-20210925-MAINLINE-waydroid_arm64-vendor.zip/download

See docs/PORTING.md for the whole procedure.
"""
import argparse
import hashlib
import json
import lzma
import os
import struct
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
CACHE = os.path.join(REPO, "build", "rom-cache")

# Apps that do nothing in a VM (no SIM, camera, NFC or sensors) or slow the first boot.
BLOAT = [
    "FaceUnlock", "StitchImage", "NfcNci", "Stk", "Camera2", "GoogleCamera", "Photos", "Bugle", "Messaging",
    "GoogleTTS", "talkback", "PixelSetupWizard", "SetupWizard", "SetupWizardPrebuilt", "GoogleDialer", "Dialer",
    "CarrierServices", "CarrierSetup", "ConnMetrics", "Matchmaker", "Turbo", "Velvet", "WellbeingPrebuilt",
    "Wellbeing", "PixelLiveWallpaperPrebuilt", "WallpapersBReel2019", "SoundPickerPrebuilt", "Tycho",
    "OemDmTrigger", "AppDirectedSMSService", "CallLogBackup", "EmergencyInfo", "SafetyHubPrebuilt",
    "ScribePrebuilt", "DevicePersonalizationPrebuiltPixel2020", "MarkupGoogle", "RecorderPrebuilt",
    "YouTube", "YouTubeMusicPrebuilt", "Maps", "Drive", "Gmail2", "Chrome", "CalendarGooglePrebuilt",
]
APP_DIRS = ["app", "priv-app", "product/app", "product/priv-app", "product_services/app",
            "product_services/priv-app", "system_ext/app", "system_ext/priv-app"]


def fetch(url, dest):
    if os.path.isfile(url):
        return url
    if os.path.isfile(dest):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.5.0"})  # SourceForge: no HTML page
    print("downloading", url)
    with urllib.request.urlopen(req) as r, open(dest + ".part", "wb") as f:
        done = 0
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
            done += len(b)
            print("\r  %d MB" % (done >> 20), end="", flush=True)
    print()
    os.replace(dest + ".part", dest)
    return dest


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def unpack_system(xz, out):
    """.img.xz (Android sparse or raw ext4) -> raw ext4, the same conversion NestBox does on the phone."""
    if os.path.isfile(out):
        return out
    f = lzma.open(xz, "rb")
    hdr = f.read(28)
    with open(out + ".part", "wb") as o:
        magic = struct.unpack("<I", hdr[:4])[0]
        if magic != 0xED26FF3A:
            o.write(hdr)
            for b in iter(lambda: f.read(1 << 20), b""):
                o.write(b)
        else:
            _, _, _, fhs, chs, bs, tb, tc, _ = struct.unpack("<IHHHHIIII", hdr)
            f.read(fhs - 28)
            pos = 0
            for _ in range(tc):
                t, _, blocks, total = struct.unpack("<HHII", f.read(12))
                f.read(chs - 12)
                n = blocks * bs
                if t == 0xCAC1:
                    o.seek(pos)
                    left = n
                    while left:
                        b = f.read(min(1 << 20, left))
                        o.write(b)
                        left -= len(b)
                elif t == 0xCAC2:
                    v = f.read(4)
                    if v != b"\0\0\0\0":
                        o.seek(pos)
                        o.write(v * (n // 4))
                elif t == 0xCAC4:
                    f.read(total - chs)
                    n = 0
                pos += n
            o.truncate(bs * tb)
    os.replace(out + ".part", out)
    return out


def inspect(img):
    try:
        from ext4 import Volume
    except ImportError:
        sys.exit("pip install ext4  (needed to look inside the image)")
    report = {"apps": {}, "notes": []}
    with open(img, "rb") as fh:
        vol = Volume(fh, offset=0)

        def ls(path):
            try:
                return sorted(e.name_str for e, _ in vol.inode_at(path).opendir() if e.name_str not in (".", ".."))
            except Exception:
                return None

        def cat(path):
            try:
                return vol.inode_at(path).open().read().decode(errors="replace")
            except Exception:
                return None

        sar = ls("/system") is not None  # system-as-root image
        root = "/system" if sar else ""
        props = {}
        for line in (cat(root + "/build.prop") or "").splitlines():
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                props[k] = v
        report["sdk"] = props.get("ro.build.version.sdk")
        report["release"] = props.get("ro.build.version.release")
        report["display"] = props.get("ro.build.display.id")
        report["abilist"] = props.get("ro.product.cpu.abilist") or props.get("ro.system.product.cpu.abilist")
        report["zygote"] = props.get("ro.zygote")
        if report["abilist"] and "arm64-v8a" not in report["abilist"]:
            report["notes"].append("32-bit only image: the Modern engine needs arm64 (pick an ARM64 GSI)")
        apex = ls(root + "/apex") or []
        if any(a.endswith(".apex") for a in apex):
            report["notes"].append("packed APEXes: handled by NestBox's helper (nb_unpack_apexes)")
        if any(a.endswith((".release", ".debug", ".current")) for a in apex):
            report["notes"].append("suffixed APEX folders: handled (nb_prepare_q links /apex/<name>)")
        if sar and cat("/init.zygote64_32.rc") and not cat("/init.zygote64.rc"):
            report["notes"].append("zygote rc at the image root: handled (nb_prepare_q writes init.zygote64.rc)")
        if not any(cat(root + "/lib64/" + n) is not None for n in ("libEGL.so",)):
            report["notes"].append("no /system/lib64/libEGL.so: unusual image, check it boots")
        for d in APP_DIRS:
            items = ls(root + "/" + d)
            if items:
                report["apps"]["/system/" + d] = items
    remove = []
    for d, items in report["apps"].items():
        for a in items:
            if a in BLOAT:
                remove.append(d + "/" + a)
    report["remove"] = remove
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--id", required=True, help="short id, e.g. pe10 (letters and digits)")
    ap.add_argument("--name", required=True, help="name shown in NestBox")
    ap.add_argument("--sdk", type=int, required=True, help="guest Android API level (29 = 10, 30 = 11, 33 = 13)")
    ap.add_argument("--system", required=True, help="URL or path of the GSI .img.xz (ARM64, A/B)")
    ap.add_argument("--vendor", required=True, help="URL or path of Waydroid's vendor zip for the same Android version")
    ap.add_argument("--gms", action="store_true", help="the image includes Google apps")
    ap.add_argument("--system-url", help="download URL to record when --system is a local file")
    ap.add_argument("--vendor-url", help="download URL to record when --vendor is a local file")
    a = ap.parse_args()

    surl = a.system_url or a.system
    vurl = a.vendor_url or a.vendor
    for u in (surl, vurl):
        if not u.startswith("https://"):
            print("! %s is not a download URL: pass --system-url / --vendor-url for the phone" % u)
    sx = fetch(a.system, os.path.join(CACHE, a.id + "-system.img.xz"))
    vz = fetch(a.vendor, os.path.join(CACHE, a.id + "-vendor.zip"))
    with zipfile.ZipFile(vz) as z:
        if "vendor.img" not in z.namelist():
            sys.exit("the vendor zip has no vendor.img")
    sys_sha, ven_sha = sha256(sx), sha256(vz)
    sys_len, ven_len = os.path.getsize(sx), os.path.getsize(vz)
    print("unpacking the system image…")
    raw = unpack_system(sx, os.path.join(CACHE, a.id + "-system.img"))
    rep = inspect(raw)

    print("\n== system image")
    for k in ("display", "release", "sdk", "abilist", "zygote"):
        print("  %-8s %s" % (k, rep.get(k)))
    if rep.get("sdk") and int(rep["sdk"]) != a.sdk:
        print("  ! --sdk %d does not match the image (%s)" % (a.sdk, rep["sdk"]))
    for n in rep["notes"]:
        print("  - " + n)
    if a.sdk < 33:
        print("  - Android below 13 has no ANGLE: NestBox renders it in software")
    print("\n== suggested removals (%d)" % len(rep["remove"]))
    for r in rep["remove"]:
        print("  " + r)

    gb = (sys_len + ven_len) / 1e9
    remove = ",\n".join('                "%s"' % r for r in rep["remove"])
    print("\n== Catalog.kt entry (app/src/main/java/app/nestbox/vm/Catalog.kt, list `roms`)\n")
    print('''        Rom(
            "{id}", MODERN, "{name}", "PORT", "{short}",
            "Android {rel} · {gmsText} · {gb:.1f} GB download", "{gb:.1f} GB",
            Source.Port(
                "{surl}", {slen}, "{ssha}",
                "{vurl}", {vlen}, "{vsha}",
            ),
            gms = {gms}, sdk = {sdk},
            props = {props},
            remove = listOf(
{remove}
            ),
            afterBoot = PORT_AFTER_BOOT,
        ),'''.format(id=a.id, name=a.name, short=(rep.get("release") or "?") + " " + "".join(c for c in a.id if c.isalpha()).upper(),
                    rel=rep.get("release") or "?", gmsText="Play Store" if a.gms else "no Google apps", gb=gb,
                    surl=surl, slen="{:_}".format(sys_len), ssha=sys_sha,
                    vurl=vurl, vlen="{:_}".format(ven_len), vsha=ven_sha,
                    gms=str(a.gms).lower(), sdk=a.sdk, remove=remove,
                    props="GSI_64BIT + SOFTWARE_GL + SPEED_PROPS" if a.sdk < 33 else "GSI_64BIT + SPEED_PROPS"))

    path = os.path.join(REPO, "roms", "catalog.json")
    data = json.load(open(path, encoding="utf-8")) if os.path.isfile(path) else {"roms": []}
    data["roms"] = [r for r in data["roms"] if r.get("id") != a.id]
    data["roms"].append({
        "id": a.id, "engine": "modern", "name": a.name, "sdk": a.sdk, "kind": "port",
        "system": {"url": surl, "bytes": sys_len, "sha256": sys_sha},
        "vendor": {"url": vurl, "bytes": ven_len, "sha256": ven_sha, "entry": "vendor.img"},
        "remove": rep["remove"],
    })
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(data, open(path, "w", encoding="utf-8"), indent=2)
    print("\nrecorded in roms/catalog.json")


if __name__ == "__main__":
    main()
