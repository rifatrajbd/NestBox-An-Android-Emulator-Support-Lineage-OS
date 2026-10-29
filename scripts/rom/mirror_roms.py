"""Copy every download in roms/catalog.json to the GitHub release "roms" of this repository.

NestBox falls back to these copies when the original host (SourceForge, upstream GitHub) fails;
the app checks the same SHA-256 either way. Files already uploaded are skipped.

  GITHUB_TOKEN=... python -I scripts/rom/mirror_roms.py            # all files
  GITHUB_TOKEN=... python -I scripts/rom/mirror_roms.py pe10       # one ROM

Without GITHUB_TOKEN the token comes from `git credential fill` for github.com.
Each release file may be up to 2 GiB.

Licensing: some images contain Google apps (Play Store, Play services), which Google does not
license for redistribution. Mirroring those publicly is your decision; GitHub may remove them.
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_DIR = os.path.dirname(os.path.dirname(HERE))
CACHE = os.path.join(REPO_DIR, "build", "rom-cache")
REPO = "rifatrajbd/NestBox-An-Android-Emulator-Support-Lineage-OS"
TAG = "roms"
API = "https://api.github.com/repos/" + REPO


def token():
    t = os.environ.get("GITHUB_TOKEN")
    if t:
        return t
    out = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True, env=dict(os.environ, GIT_TERMINAL_PROMPT="0")).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[9:]
    sys.exit("no GitHub token: set GITHUB_TOKEN")


def call(method, url, tok, body=None, headers=None):
    h = {"Authorization": "Bearer " + tok, "Accept": "application/vnd.github+json"}
    h.update(headers or {})
    data = json.dumps(body).encode() if isinstance(body, dict) else body
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"null")


def release(tok):
    try:
        return call("GET", API + "/releases/tags/" + TAG, tok)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    return call("POST", API + "/releases", tok, {
        "tag_name": TAG, "target_commitish": "main", "name": "ROM mirror", "prerelease": True,
        "body": "Copies of the system images NestBox downloads, used when the original host is down. "
                "See roms/catalog.json for sources and SHA-256.",
    })


def download(url, dest):
    if os.path.isfile(dest):
        return dest
    os.makedirs(CACHE, exist_ok=True)
    print("downloading", url)
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8.5.0"})
    with urllib.request.urlopen(req) as r, open(dest + ".part", "wb") as f:
        for b in iter(lambda: r.read(1 << 20), b""):
            f.write(b)
    os.replace(dest + ".part", dest)
    return dest


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    only = set(sys.argv[1:])
    cat = json.load(open(os.path.join(REPO_DIR, "roms", "catalog.json"), encoding="utf-8"))
    tok = token()
    rel = release(tok)
    have = {a["name"] for a in rel.get("assets", [])}
    upload = rel["upload_url"].split("{")[0]
    for rom in cat["roms"]:
        if only and rom["id"] not in only:
            continue
        for part in ("system", "vendor"):
            f = rom.get(part)
            if not f or not f.get("mirror"):
                continue
            name = f["mirror"]
            if name in have:
                print("already mirrored:", name)
                continue
            path = download(f["url"], os.path.join(CACHE, "mirror-" + name))
            if f.get("bytes") and os.path.getsize(path) != f["bytes"]:
                sys.exit("%s: size %d, expected %d" % (name, os.path.getsize(path), f["bytes"]))
            if f.get("sha256") and sha256(path) != f["sha256"]:
                sys.exit("%s: checksum mismatch" % name)
            print("uploading", name)
            with open(path, "rb") as fh:
                call("POST", upload + "?name=" + name, tok, fh.read() if os.path.getsize(path) < (1 << 30) else fh,
                     {"Content-Type": "application/octet-stream", "Content-Length": str(os.path.getsize(path))})
            print("  https://github.com/%s/releases/download/%s/%s" % (REPO, TAG, name))


if __name__ == "__main__":
    main()
