#!/usr/bin/env python3
"""Fetch, verify and compile the latest YARA Forge Full package with YARA 4.5.0."""
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

if len(sys.argv) != 2:
    raise SystemExit("usage: validate-yara-forge-package.py /path/to/yarac")
yarac = pathlib.Path(sys.argv[1]).resolve()
if not yarac.is_file():
    raise SystemExit(f"yarac not found: {yarac}")

headers = {"User-Agent": "IKAR-YARA-Forge-CI", "Accept": "application/vnd.github+json"}
# Use the workflow's short-lived, read-only token to avoid the very low
# unauthenticated GitHub API rate limit. Never print or persist this token.
github_token = os.environ.get("GITHUB_TOKEN", "").strip()
if github_token:
    headers["Authorization"] = f"Bearer {github_token}"
request = urllib.request.Request(
    "https://api.github.com/repos/YARAHQ/yara-forge/releases/latest", headers=headers
)
with urllib.request.urlopen(request, timeout=30) as response:
    release = json.load(response)

tag = release.get("tag_name", "")
if not tag.isdigit() or len(tag) != 8:
    raise SystemExit(f"Invalid YARA Forge release tag: {tag!r}")
asset = next(
    (item for item in release.get("assets", [])
     if item.get("name") == "yara-forge-rules-full.zip"),
    None,
)
if not asset:
    raise SystemExit("YARA Forge Full ZIP asset is missing")
expected_digest = (asset.get("digest") or "").removeprefix("sha256:").lower()
if len(expected_digest) != 64:
    raise SystemExit("YARA Forge asset has no SHA-256 digest")
if asset.get("size", 0) <= 0 or asset["size"] > 10 * 1024 * 1024:
    raise SystemExit("YARA Forge archive size is outside the allowed limit")

download = urllib.request.Request(asset["browser_download_url"], headers=headers)
with urllib.request.urlopen(download, timeout=60) as response:
    archive = response.read(10 * 1024 * 1024 + 1)
if len(archive) != asset["size"] or len(archive) > 10 * 1024 * 1024:
    raise SystemExit("YARA Forge archive size mismatch")
actual_digest = hashlib.sha256(archive).hexdigest()
if actual_digest != expected_digest:
    raise SystemExit("YARA Forge archive SHA-256 mismatch")

with tempfile.TemporaryDirectory(prefix="ikar-yara-forge-") as temporary:
    root = pathlib.Path(temporary).resolve()
    rules_root = root / "rules"
    rules_root.mkdir()
    rule_files = []
    expanded = 0
    with zipfile.ZipFile(__import__("io").BytesIO(archive)) as package:
        entries = package.infolist()
        if len(entries) > 15000:
            raise SystemExit("YARA Forge archive contains too many entries")
        for entry in entries:
            name = entry.filename.replace("\\", "/")
            path = pathlib.PurePosixPath(name)
            if path.is_absolute() or any(part in ("", "..") for part in path.parts):
                raise SystemExit(f"Unsafe path in YARA Forge archive: {name!r}")
            if entry.is_dir():
                continue
            suffix = path.suffix.lower()
            is_license_file = path.name.upper() in {"LICENSE", "COPYING", "NOTICE", "AUTHORS"}
            if suffix not in {
                ".yar", ".yara", ".inc", ".txt", ".md", ".json",
                ".yml", ".yaml", ".license", ".licence", ".toml"
            } and not is_license_file:
                continue
            if entry.file_size > 32 * 1024 * 1024:
                raise SystemExit(f"YARA Forge entry exceeds 32 MiB: {name}")
            expanded += entry.file_size
            if expanded > 128 * 1024 * 1024:
                raise SystemExit("YARA Forge expanded archive exceeds 128 MiB")
            target = rules_root.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(package.read(entry))
            if suffix in (".yar", ".yara"):
                rule_files.append(target)

    if not rule_files:
        raise SystemExit("YARA Forge Full package contains no .yar/.yara rules")
    compiled = root / "yara-forge-full.yarc"
    command = [str(yarac)] + [str(path) for path in sorted(rule_files)] + [str(compiled)]
    subprocess.run(command, check=True)
    if not compiled.is_file() or compiled.stat().st_size == 0:
        raise SystemExit("YARA compiler produced no compiled rules")
    print(
        f"YARA Forge {tag}: {len(rule_files)} rule files compiled successfully "
        f"with YARA 4.5.0; archive SHA-256 {actual_digest}"
    )
