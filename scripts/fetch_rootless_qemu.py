#!/usr/bin/env python3
"""Fetch the pinned Android/arm64 QEMU runtime used by the rootless backend.

The runtime binaries are intentionally NOT committed to VineOS.  This script copies
one known-good, pinned bundle into app/src/main/jniLibs/arm64-v8a at build time and
writes a SHA-256 manifest so the resulting APK is reproducible/auditable.

Source bundle: AI2TH/Pockr at an immutable commit.  Pockr's application code is MIT;
the binary bundle contains QEMU and third-party libraries with their own licenses.
See docs/ROOTLESS_RUNTIME_NOTICES.md before redistributing a build.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.error
import urllib.request

REPO = "AI2TH/Pockr"
COMMIT = "f6d43483cc519987b4048962ea96303d99f3030d"
REMOTE_DIR = "android/app/src/main/jniLibs/arm64-v8a"
API_URL = f"https://api.github.com/repos/{REPO}/contents/{REMOTE_DIR}?ref={COMMIT}"
LICENSE_URL = f"https://raw.githubusercontent.com/{REPO}/{COMMIT}/LICENSE"

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
THIRD_PARTY = ROOT / "third_party" / "rootless-qemu"
MANIFEST = THIRD_PARTY / "SHA256SUMS"
POCKR_LICENSE = THIRD_PARTY / "POCKR_LICENSE.txt"
PROVENANCE = THIRD_PARTY / "PROVENANCE.txt"

REQUIRED = {
    "libqemu.so",
    "libqemu_img.so",
    "libslirp.so",
    "libpixman-1.so",
    "libstubs.so",
}


def request(url: str) -> bytes:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "VineOS-rootless-build/1",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} fetching {url}: {body[:500]}") from exc


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    print(f"Fetching rootless QEMU runtime from {REPO}@{COMMIT}")
    listing = json.loads(request(API_URL).decode("utf-8"))
    if not isinstance(listing, list):
        raise RuntimeError("GitHub contents response was not a directory listing")

    candidates = [
        item for item in listing
        if item.get("type") == "file"
        and str(item.get("name", "")).endswith(".so")
        and item.get("download_url")
    ]
    names = {str(item["name"]) for item in candidates}
    missing = sorted(REQUIRED - names)
    if missing:
        raise RuntimeError(f"Pinned QEMU bundle is missing required files: {missing}")

    # Only this generated directory is cleaned.  CMake output lives under build/
    # and is not affected.
    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True, exist_ok=True)
    THIRD_PARTY.mkdir(parents=True, exist_ok=True)

    sums: list[tuple[str, str]] = []
    for index, item in enumerate(sorted(candidates, key=lambda x: x["name"]), start=1):
        name = str(item["name"])
        data = request(str(item["download_url"]))
        dest = DEST / name
        dest.write_bytes(data)
        digest = sha256(dest)
        sums.append((digest, name))
        print(f"[{index:02d}/{len(candidates):02d}] {name:28s} {len(data):>10,d} B  {digest[:12]}…")

    MANIFEST.write_text(
        "".join(f"{digest}  {name}\n" for digest, name in sums),
        encoding="utf-8",
    )
    POCKR_LICENSE.write_bytes(request(LICENSE_URL))
    PROVENANCE.write_text(
        "Rootless QEMU runtime provenance\n"
        "================================\n\n"
        f"Repository: https://github.com/{REPO}\n"
        f"Commit: {COMMIT}\n"
        f"Directory: {REMOTE_DIR}\n\n"
        "The files under app/src/main/jniLibs/arm64-v8a are generated at build time\n"
        "by scripts/fetch_rootless_qemu.py and are not source-controlled binaries.\n"
        "See docs/ROOTLESS_RUNTIME_NOTICES.md for redistribution/license notes.\n",
        encoding="utf-8",
    )

    print(f"Downloaded {len(candidates)} ELF/shared-object files to {DEST}")
    print(f"Wrote {MANIFEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise
