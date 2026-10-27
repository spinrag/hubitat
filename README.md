# Spinrag Hubitat Drivers & Apps

This repository contains custom Hubitat Elevation drivers and apps developed and maintained by [Chris Ogden](https://github.com/ChrisOgden) / [spinrag](https://github.com/spinrag). These integrations enable capabilities not natively available in Hubitat — with a focus on local execution, stability, and simplicity.

---

## 📦 Included

### 🔹 [DNS Made Easy Updater](dnsMadeEasyUpdater/README.md) — *driver*
Updates a DNS Made Easy A record with your current public IP address.

Useful when your router (e.g., UniFi or CGNAT setups) cannot update DDNS properly because it reports a non-public WAN IP.

- Uses [ipify.org](https://api.ipify.org) to determine the true external IP
- Supports scheduled updates, manual refresh, and change notifications

📂 [`dnsMadeEasyUpdater`](dnsMadeEasyUpdater/)

---

### 🔹 [DMS Monitor (Dead Man's Snitch)](dmsMonitor/README.md) — *driver*
Sends scheduled heartbeat pings to [Dead Man's Snitch](https://deadmanssnitch.com) to verify that your Hubitat hub and internet connection are alive.

- Detects total failure of hub or network
- Works well with UPS setups or reboot automations
- Uses `asynchttpGet()` for non-blocking performance

📂 [`dmsMonitor`](dmsMonitor/)

---

### 🔹 [Cloudflare DNS Updater](cloudflareDnsUpdater/README.md) — *driver*
Updates an **existing** Cloudflare A or AAAA record with your current public IP. Same use case as the DNS Made Easy Updater, but for Cloudflare zones.

- Uses a **scoped** Cloudflare API token (Zone:DNS:Edit on a single zone)
- Update-only — refuses to create new records, warns explicitly when no matching record exists
- Caches record ID after first lookup; skips API calls when IP is unchanged
- Documented security model — see the README for what is and isn't protected

📂 [`cloudflareDnsUpdater`](cloudflareDnsUpdater/)

---

### 🔹 [SignalBricks Integration](signalbricksIntegration/README.md) — *app*
Syncs OwnerRez booking door codes from the SignalBricks dispatcher to Z-Wave / Zigbee locks. Runs alongside the OwnerRez app. Originally based on the [HubitatCommunity OwnerRez](https://github.com/HubitatCommunity/OwnerRez) app, rebranded and substantially reworked.

- Codes are named `<prefix>ORB<booking id>-<guest>` and are on the lock from before check-in to after checkout
- Both apps need different, non-empty code name prefixes (e.g. OwnerRez `G:`, SignalBricks Integration `GT:`)
- Manages codes named with its prefix + `ORB<booking id>`, plus slots it assigned earlier; writes new codes only into empty slots from its minimum code position up
- Don't run Hubitat's Lock Code Manager on the same locks
- Per-code retry-until-confirmed, with a reconcile loop for missed confirmations
- Small access-token gated REST API (`/sync`, `/devices`, `/info`, …) used by the dispatcher (not included in this repo)

📂 [`signalbricksIntegration`](signalbricksIntegration/)

> **Hand-installed hubs:** use HPM **Match Up** to adopt the existing app code instead of installing it again. See the [README](signalbricksIntegration/README.md#hubs-that-installed-it-by-hand-hpm-match-up).

---

### 🔹 [SignalBricks Telemetry](signalbricksTelemetry/README.md) — *app*
Sends read-only lock, contact, temperature, humidity and battery events to SignalBricks. Runs alongside the SignalBricks Integration app; the two are versioned separately.

- One-click **Connect to SignalBricks** (OAuth via Hubitat cloud). No tokens to paste.
- Telemetry never subscribes to `lockCodes`, never controls devices, and doesn't send PINs unless you turn on *Include door codes* (1.3.5+, off by default; SignalBricks then keeps only a keyed hash, and only if your organization's admin also allows it)
- Non-blocking `asynchttpPost` delivery
- Optional, off-by-default **lock code management** for admin (`SBA:`) and managed (`SBM:`) codes, with hub-side slot enforcement

📂 [`signalbricksTelemetry`](signalbricksTelemetry/)

---

## 🛠 Installation via Hubitat Package Manager (HPM)

The drivers and apps above are compatible with [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/):

1. Open HPM on your hub
2. Choose **Install** > **Search by Keywords**
3. Search for:
   - `"DNS Made Easy Updater"`
   - `"DMS Monitor"`
   - `"Cloudflare DNS Updater"`
   - `"SignalBricks"` (SignalBricks Integration, SignalBricks Telemetry)
4. Follow prompts to install and configure

Alternatively, use the raw URLs in the `packageManifest.json` files if installing manually.

---

## ✅ CI

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs on every pull request and on pushes to `main`:

- **Harness tests** — every `*/tests/*Harness.groovy` runs against its app (and its `beta/` copy, if any) in the `groovy:4.0-jdk17` container with networking disabled; any `FAIL` line or non-zero exit fails the build.
- **Groovy compile check** — every `app.groovy` / `driver.groovy` is compiled as a plain Groovy script ([`groovy-parse-check.groovy`](.github/scripts/groovy-parse-check.groovy)). Hubitat's DSL is just method calls, so no stubs are needed; it catches syntax errors and unresolvable imports, not runtime behaviour.
- **Manifest checks** — [`check-manifests.py`](.github/scripts/check-manifests.py): manifests and `repository.json` are valid JSON, manifest locations point at files in this repo, each app's version in source matches its `packageManifest.json`, beta channel fields are consistent (a `betaLocation` file carries `betaVersion`, or is identical to the stable file when no beta is out; `betaVersion` has `betaReleaseNotes` and is one HPM will offer), `repository.json` entries point at existing manifests, and every manifest is listed in `repository.json`.
- **Secret scan** — [gitleaks](https://github.com/gitleaks/gitleaks) over the full git history. Reviewed false positives go in [`.gitleaksignore`](.gitleaksignore).

Run the same checks locally from the repository root:

```bash
python3 .github/scripts/check-manifests.py
docker run --rm --network none -v "$PWD:/w:ro" -w /w groovy:4.0-jdk17 \
  groovy .github/scripts/groovy-parse-check.groovy $(git ls-files '*.groovy' ':!:*/tests/*' ':!:.github/*')
gitleaks git --redact .
```

When you bump an app's version, update its `packageManifest.json` in the same change or CI fails.

---

## 📜 License

- All drivers are licensed under the [MIT License](https://opensource.org/license/mit)
- See individual driver files for details

---

## 🤝 Contributions & Issues

Contributions and feedback are welcome!

- 🐛 Open an issue for bug reports or feature requests
- 📬 Submit pull requests for enhancements or compatibility extensions

---

© 2025–2026 Spinrag, LLC • Developed by [Chris Ogden](https://github.com/ChrisOgden) / [spinrag](https://github.com/spinrag)
