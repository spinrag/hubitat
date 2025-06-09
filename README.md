# Spinrag Hubitat Drivers

This repository contains custom Hubitat Elevation drivers developed and maintained by [Chris Ogden](https://github.com/ChrisOgden) / [spinrag](https://github.com/spinrag). These drivers are designed to enable integrations and capabilities not natively available in Hubitat — with a focus on local execution, stability, and simplicity.

---

## 📦 Included Drivers

### 🔹 [DNS Made Easy Updater](dnsMadeEasyUpdater/README.md)
Updates a DNS Made Easy A record with your current public IP address.

Useful when your router (e.g., UniFi or CGNAT setups) cannot update DDNS properly because it reports a non-public WAN IP.

- Uses [ipify.org](https://api.ipify.org) to determine the true external IP
- Supports scheduled updates, manual refresh, and change notifications

📂 [`dnsMadeEasyUpdater`](dnsMadeEasyUpdater/)

---

### 🔹 [DMS Monitor (Dead Man's Snitch)](dmsMonitor/README.md)
Sends scheduled heartbeat pings to [Dead Man's Snitch](https://deadmanssnitch.com) to verify that your Hubitat hub and internet connection are alive.

- Detects total failure of hub or network
- Works well with UPS setups or reboot automations
- Uses `asynchttpGet()` for non-blocking performance

📂 [`dmsMonitor`](dmsMonitor/)

---

## 🛠 Installation via Hubitat Package Manager (HPM)

These drivers are fully compatible with [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/):

1. Open HPM on your hub
2. Choose **Install** > **Search by Keywords**
3. Search for:
   - `"DNS Made Easy Updater"`
   - `"DMS Monitor"`
4. Follow prompts to install and configure

Alternatively, use the raw URLs in the `packageManifest.json` files if installing manually.

---

## 📜 License

- All drivers are licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0)
- See individual driver files for details

---

## 🤝 Contributions & Issues

Contributions and feedback are welcome!

- 🐛 Open an issue for bug reports or feature requests
- 📬 Submit pull requests for enhancements or compatibility extensions

---

© 2025 Spinrag, LLC • Developed by [Chris Ogden](https://github.com/ChrisOgden) / [spinrag](https://github.com/spinrag)
