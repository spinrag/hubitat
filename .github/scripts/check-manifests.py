#!/usr/bin/env python3
"""Checks HPM manifests. Run from the repository root.

- every packageManifest.json and repository.json is valid JSON
- every app/driver location in a manifest points at a file in this repo
- each app's version in its source matches its packageManifest version
  (drivers are checked too when their source declares a version)
- beta channel: a betaLocation is a file in this repo whose version is the
  manifest's betaVersion; with no beta out it must be identical to the stable
  file; a betaVersion needs betaReleaseNotes and must be one HPM offers over version
- repository.json entries point at existing manifests, and every manifest is listed
- HPM schema: manifest and app/driver fields follow HPM's sampleManifest.json;
  repository.json category and tags come from hubitat-packagerepositories settings.json
"""
import datetime
import filecmp
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

# https://github.com/HubitatCommunity/hubitatpackagemanager/blob/main/sampleManifest.json
MANIFEST_KEYS = {
    "packageName", "minimumHEVersion", "author", "version", "betaVersion", "dateReleased",
    "documentationLink", "communityLink", "licenseFile", "releaseNotes", "betaReleaseNotes",
    "apps", "drivers", "bundles", "files",
}
REQUIRED_MANIFEST_KEYS = ("packageName", "author", "version", "minimumHEVersion", "dateReleased", "releaseNotes")
# "version" per app/driver is allowed (HPM README: version the package or each item)
ITEM_KEYS = {"id", "name", "namespace", "alternateNames", "location", "betaLocation", "required", "oauth", "primary", "version"}
REQUIRED_ITEM_KEYS = ("id", "name", "namespace", "location", "required")
GUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
# Ids that predate GUIDs; HPM tracks installs by id, so these must never change.
LEGACY_IDS = {"cloudflareDnsUpdater", "dmsMonitor", "dnsMadeEasyUpdater"}
# https://raw.githubusercontent.com/HubitatCommunity/hubitat-packagerepositories/master/settings.json
CATEGORIES = {"Control", "Convenience", "Integrations", "Notifications", "Security", "Utility"}
TAGS = {
    "Alarm Systems", "Appliances", "Automations & Groups", "Bathroom", "Buttons", "Cameras",
    "Cleaning Devices", "Climate Control", "Cloud", "Dashboards", "Doors & Windows",
    "Energy Monitoring", "Garage Doors", "HDMI", "Health & Fitness", "IR & RF", "Irrigation", "KVM",
    "LAN", "Lights & Switches", "Locks", "Matter", "Misc. Devices", "Monitoring", "Motion Control",
    "MQTT", "Multimedia", "Multi Sensors", "Notifications", "Pets & Animals", "Pools & Spas",
    "Presence & Location", "Relays", "Repeaters & Extenders", "Safety & Security", "Scales", "Shower",
    "Sleep", "Speakers", "Tasmota", "Temperature & Humidity", "Timers", "Tools & Utilities", "Valves",
    "Vehicles & Transportation", "Voice Assistants", "Water Heater", "Water", "Weather",
    "Window Coverings", "Zigbee", "ZWave",
}

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


def hpm_compare(old, new):
    """Hubitat Package Manager's compareVersions (apps/Package_Manager.groovy), as is:
    1 when any part of new is higher than old's, 0 when equal, else -1."""
    new = re.sub(r"[^\d.]", "", new)
    old = re.sub(r"[^\d.]", "", old)
    if new == old:
        return 0
    old_parts, new_parts = old.split("."), new.split(".")
    for i, part in enumerate(new_parts):
        if i >= len(old_parts) or int(old_parts[i]) < int(part):
            return 1
    return -1


def source_definition(path):
    """name and namespace from the definition(...) call in an app or driver."""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    start = re.search(r"\bdefinition\s*\(", text)
    if not start:
        return None
    head = text[start.end():start.end() + 1000]
    fields = {k: v for k, _, v in re.findall(r"\b(name|namespace)\s*:\s*(['\"])(.*?)\2", head)[::-1]}
    return fields.get("name"), fields.get("namespace")


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
    beta_version = m.get("betaVersion")
    if beta_version and version:
        # HPM installs the stable code instead when compareVersions(betaVersion, version) != -1.
        if beta_version == version:
            errors.append(f"{path}: betaVersion equals version {version}; remove betaVersion and betaReleaseNotes when promoting a beta")
        elif not all(re.fullmatch(r"\d+(\.\d+)*", re.sub(r"[^\d.]", "", v)) for v in (beta_version, version)):
            errors.append(f"{path}: malformed version or betaVersion ({version!r}, {beta_version!r}); use dotted numbers like 1.3.3")
        elif hpm_compare(beta_version, version) != -1:
            errors.append(f"{path}: HPM would not offer betaVersion {beta_version} over version {version}; every part of betaVersion must be at least the same part of version")
        if not m.get("betaReleaseNotes"):
            errors.append(f"{path}: betaVersion {beta_version} has no betaReleaseNotes")
    elif m.get("betaReleaseNotes"):
        errors.append(f"{path}: betaReleaseNotes without betaVersion")
    if beta_version and not any(e.get("betaLocation") for k in ("apps", "drivers") for e in m.get(k, [])):
        errors.append(f"{path}: betaVersion {beta_version} but no app or driver has a betaLocation")
    for key in sorted(set(m) - MANIFEST_KEYS):
        errors.append(f"{path}: {key!r} is not an HPM manifest field (category and tags belong in repository.json)")
    for key in REQUIRED_MANIFEST_KEYS:
        if not m.get(key):
            errors.append(f"{path}: missing {key}")
    if m.get("minimumHEVersion") and not re.fullmatch(r"\d+(\.\d+)*", m["minimumHEVersion"]):
        errors.append(f"{path}: malformed minimumHEVersion {m['minimumHEVersion']!r}")
    if m.get("dateReleased"):
        try:
            datetime.date.fromisoformat(m["dateReleased"])
        except (TypeError, ValueError):
            errors.append(f"{path}: dateReleased {m['dateReleased']!r} is not YYYY-MM-DD")
    if not m.get("apps") and not m.get("drivers"):
        errors.append(f"{path}: no apps or drivers")
    ids = set()
    for kind in ("apps", "drivers"):
        for entry in m.get(kind, []):
            for key in sorted(set(entry) - ITEM_KEYS):
                errors.append(f"{path}: {kind} {entry.get('name')!r}: {key!r} is not an HPM {kind} field")
            for key in REQUIRED_ITEM_KEYS:
                if key not in entry:
                    errors.append(f"{path}: {kind} {entry.get('name')!r}: missing {key}")
            for key in ("required", "oauth", "primary"):
                if key in entry and not isinstance(entry[key], bool):
                    errors.append(f"{path}: {kind} {entry.get('name')!r}: {key} must be true or false")
            item_id = entry.get("id")
            if item_id in ids:
                errors.append(f"{path}: duplicate id {item_id!r}")
            ids.add(item_id)
            if item_id and item_id not in LEGACY_IDS and not GUID.fullmatch(item_id):
                errors.append(f"{path}: {kind} {entry.get('name')!r}: id {item_id!r} is not a lowercase GUID")
            src = local_path(entry.get("location"))
            if src not in tracked:
                errors.append(f"{path}: {kind} location {entry.get('location')!r} is not a file in this repo")
                continue
            # HPM's Match Up finds code already on a hub by name and namespace.
            for s in (src, local_path(entry.get("betaLocation"))):
                if s in tracked and source_definition(s) != (entry.get("name"), entry.get("namespace")):
                    errors.append(f"{s}: definition name/namespace {source_definition(s)} does not match {path} ({entry.get('name')!r}, {entry.get('namespace')!r})")
            found = source_version(src)
            if found is None:
                if kind == "apps":
                    errors.append(f"{src}: no version found in source (manifest {path} says {version})")
                continue
            if found != version:
                errors.append(f"{src}: version {found} does not match {path} version {version}")
            else:
                print(f"OK   {src} version {found} matches {path}")
            if "betaLocation" not in entry:
                continue
            beta_src = local_path(entry["betaLocation"])
            if beta_src not in tracked:
                errors.append(f"{path}: {kind} betaLocation {entry['betaLocation']!r} is not a file in this repo")
                continue
            if not beta_version:
                # Between betas HPM still installs the beta copy on hubs opted in to betas.
                if not filecmp.cmp(src, beta_src, shallow=False):
                    errors.append(f"{beta_src}: beta copy differs from stable while no beta is out; cp {src} {beta_src}")
                else:
                    print(f"OK   {beta_src} is identical to {src} (no beta out)")
                continue
            found = source_version(beta_src)
            if found != beta_version:
                errors.append(f"{beta_src}: version {found} does not match {path} betaVersion {beta_version}")
            else:
                print(f"OK   {beta_src} version {found} matches {path} betaVersion")

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
        for key in ("name", "description"):
            if not pkg.get(key):
                errors.append(f"repository.json: {pkg.get('name')!r} has no {key}")
        if pkg.get("category") not in CATEGORIES:
            errors.append(f"repository.json: {pkg.get('name')!r} category {pkg.get('category')!r} is not one of {sorted(CATEGORIES)}")
        if not isinstance(pkg.get("tags"), list) or not pkg["tags"]:
            errors.append(f"repository.json: {pkg.get('name')!r} has no tags")
        else:
            for tag in pkg["tags"]:
                if tag not in TAGS:
                    errors.append(f"repository.json: {pkg.get('name')!r} tag {tag!r} is not an HPM tag")

for path in manifests:
    if path not in listed:
        errors.append(f"{path} is not listed in repository.json")

for w in warnings:
    print(f"WARN {w}")
for e in errors:
    print(f"FAIL {e}")
print(f"{len(manifests)} manifests, {len(errors)} errors, {len(warnings)} warnings")
sys.exit(1 if errors else 0)
