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

### 🔹 SignalBricks Integration — *app*
Manages short-term-rental door codes on Z-Wave / Zigbee locks, driven by booking data pushed in from an external dispatcher (OwnerRez or compatible). Originally based on the [HubitatCommunity OwnerRez](https://github.com/HubitatCommunity/OwnerRez) app, rebranded and substantially reworked.

- Sets, updates, and removes lock codes per booking lifecycle
- Per-code retry-until-confirmed loop with bounded backoff (sets that silently drop on flaky Z-Wave eventually take)
- Tracks code assignments in `atomicState` so reconcile can recover from missed `codeChanged` events
- Exposes a small REST API (`/sync`, `/devices`, `/info`, …) used by the upstream dispatcher; access-token gated
- Configurable code-name prefix, minimum code position, and booking-ID placement

📂 [`signalbricksIntegration`](signalbricksIntegration/)

> **Note:** Not currently distributed via HPM. Install by pasting `app.groovy` into Hubitat's Apps Code editor. Requires an external dispatcher (not included in this repo) to push booking data.

---

## 🛠 Installation via Hubitat Package Manager (HPM)

The two drivers above are compatible with [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/):

1. Open HPM on your hub
2. Choose **Install** > **Search by Keywords**
3. Search for:
   - `"DNS Made Easy Updater"`
   - `"DMS Monitor"`
4. Follow prompts to install and configure

Alternatively, use the raw URLs in the `packageManifest.json` files if installing manually. The SignalBricks app must be installed manually (see above).

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

© 2025 Spinrag, LLC • Developed by [Chris Ogden](https://github.com/ChrisOgden) / [spinrag](https://github.com/spinrag)
