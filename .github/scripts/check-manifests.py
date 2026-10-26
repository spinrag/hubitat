#!/usr/bin/env python3
"""Checks HPM manifests. Run from the repository root.

- every packageManifest.json and repository.json is valid JSON
- every app/driver location in a manifest points at a file in this repo
- each app's version in its source matches its packageManifest version
  (drivers are checked too when their source declares a version)
- repository.json entries point at existing manifests
"""
import json
import re
import subprocess
import sys

RAW_PREFIX = "https://raw.githubusercontent.com/spinrag/hubitat/main/"
# APP_VERSION = '1.3.0' | getSbAppVersion() { '1.3.3' } | definition(..., version: "1.0.0")
VERSION_PATTERNS = [
    r"APP_VERSION\s*=\s*['\"]([^'\"]+)['\"]",
    r"get\w*Version\(\)\s*\{\s*(?:return\s+)?['\"]([^'\"]+)['\"]",
    r"\bversion\s*:\s*['\"]([^'\"]+)['\"]",
]

errors = []
warnings = []


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        errors.append(f"{path}: invalid JSON ({e})")
        return None


def local_path(url):
    return url[len(RAW_PREFIX):] if url and url.startswith(RAW_PREFIX) else None


def source_version(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for pattern in VERSION_PATTERNS:
        m = re.search(pattern, text)
        if m:
            return m.group(1)
    return None


files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.split()
tracked = set(files)
manifests = sorted(f for f in files if f.endswith("packageManifest.json"))

for path in manifests:
    m = load(path)
    if m is None:
        continue
    version = m.get("version")
    if not version:
        errors.append(f"{path}: no version")
    # The top-level location is optional in HPM; when present it must be this file.
    if "location" in m and local_path(m["location"]) != path:
        errors.append(f"{path}: location {m.get('location')!r} does not point at itself")
    for kind in ("apps", "drivers"):
        for entry in m.get(kind, []):
            src = local_path(entry.get("location"))
            if src not in tracked:
                errors.append(f"{path}: {kind} location {entry.get('location')!r} is not a file in this repo")
                continue
            found = source_version(src)
            if found is None:
                if kind == "apps":
                    errors.append(f"{src}: no version found in source (manifest {path} says {version})")
                continue
            if found != version:
                errors.append(f"{src}: version {found} does not match {path} version {version}")
            else:
                print(f"OK   {src} version {found} matches {path}")

repo = load("repository.json")
listed = set()
if repo is not None:
    for pkg in repo.get("packages", []):
        target = local_path(pkg.get("location"))
        if target not in tracked or not target.endswith("packageManifest.json"):
            errors.append(f"repository.json: {pkg.get('name')!r} points at {pkg.get('location')!r}, not a manifest in this repo")
        else:
            listed.add(target)
            print(f"OK   repository.json -> {target}")

for path in manifests:
    if path not in listed:
        warnings.append(f"{path} is not listed in repository.json")

for w in warnings:
    print(f"WARN {w}")
for e in errors:
    print(f"FAIL {e}")
print(f"{len(manifests)} manifests, {len(errors)} errors, {len(warnings)} warnings")
sys.exit(1 if errors else 0)
