# Cloudflare DNS Updater (Hubitat Driver)

Updates an **existing** Cloudflare A or AAAA DNS record with the hub's current public IP. Useful when your router cannot reliably update DDNS (CGNAT, double NAT, PPPoE interfaces that report the wrong IP, etc.).

This driver is **update-only**. It will never create a new DNS record — if no matching record exists in Cloudflare, it logs a warning and stops. You must create the record in the Cloudflare dashboard first.

---

## ❓ Why Use This?

Many routers — including UniFi Dream Machine, UDM Pro, and other firewall appliances — report the wrong WAN IP to DDNS providers when the WAN interface sees a private/CGNAT IP rather than a true public IP. This driver:

- Queries your **real public IP** from ipify (IPv4 from `api.ipify.org` for **A** records, IPv6 from `api6.ipify.org` for **AAAA** records)
- PUTs it directly to the Cloudflare API to update the existing record
- Caches the record ID after first lookup to keep the API surface small
- Skips API calls entirely when the IP hasn't changed

> **IPv6 / AAAA caveat:** `api6.ipify.org` only returns a v6 address if the hub itself has IPv6 transit (i.e. the hub can reach the open IPv6 internet). Many home setups are IPv4-only behind NAT — in that case AAAA updates will fail with status `no ipv6 transit`. If you need to update an AAAA record from a hub without IPv6, you'll need a different IP-discovery source than this driver provides.

---

## 🔐 Security Model & Limitations

**Read this before installing.** Hubitat does not have a real secrets vault — settings are stored on the hub's local DB at rest. Anyone with admin access to the hub can read them.

What this driver does to limit exposure:

1. **Scoped API token, not the Global API Key.** You create a Cloudflare API token with permission `Zone:DNS:Edit` on **only the specific zone** you're updating. If the token leaks, the blast radius is one zone's DNS — nothing else in your Cloudflare account.
2. **`password` input type.** The token is masked in the Hubitat UI and never logged by this driver.
3. **Update-only.** Even with the token, this driver only knows how to PUT to a single cached record ID. It cannot create, delete, or list records elsewhere.

What this driver does **not** do:

- It does **not** encrypt the token at rest. Hubitat sandboxes don't expose a meaningful key-management primitive — any "encryption" we could add would just be obfuscation since the decryption key would have to live on the same hub. We considered this and chose to be honest about it rather than add security theater.

**Practical implication**: treat your Hubitat hub like any other device that holds API tokens. Don't expose its admin UI to the internet, keep firmware up to date, and rotate the Cloudflare token if you suspect compromise.

---

## 🔧 Setup

### 1. Create a scoped Cloudflare API token

1. Log in to the [Cloudflare dashboard](https://dash.cloudflare.com).
2. Click your profile (top right) → **My Profile** → **API Tokens** → **Create Token**.
3. Use the **Edit zone DNS** template (or **Custom token** with `Zone → DNS → Edit`).
4. Under **Zone Resources**, select **Include → Specific zone → \<your zone\>**. Do **not** leave it at "All zones".
5. (Optional but recommended) Under **Client IP Address Filtering**, restrict to your hub's public IP — but note this is brittle if your IP changes (which is the whole reason you're running this driver). Leave it open if needed.
6. (Optional) Set a **TTL** on the token so it expires automatically.
7. Click **Continue to summary** → **Create Token** → copy the token (shown only once).

### 2. Find your Zone ID

1. Cloudflare dashboard → click the zone (domain) you want to update.
2. The **Overview** page shows **Zone ID** in the right-hand sidebar under "API". Copy it.

### 3. Make sure the DNS record exists

This driver does **not** create records. In the Cloudflare dashboard → **DNS → Records**, confirm there's an existing **A** record (or **AAAA** for IPv6) with the FQDN you intend to update (e.g. `home.example.com`). If it doesn't exist, create it now with any placeholder IP — the driver will overwrite the value on the next update.

### 4. Install the driver in Hubitat

1. **Drivers Code** → **New Driver** → paste `driver.groovy` → **Save**.
2. **Devices** → **Add Virtual Device**.
3. Name it (e.g. "Cloudflare DDNS – home"), Type = **Cloudflare DNS Updater**, namespace `spinrag` → **Save Device**.
4. Configure preferences:
   - **Cloudflare API Token**: the scoped token from step 1
   - **Zone ID**: from step 2
   - **Record Name (FQDN)**: full hostname, e.g. `home.example.com`
   - **Record Type**: `A` (or `AAAA` for IPv6)
   - **Cloudflare Proxy (orange cloud)**: match the existing record's setting
   - **TTL**: `1` for Auto (recommended), or seconds
   - **Update Frequency**: minutes between checks (default 15)
5. Click **Save Preferences**. The driver will look up and cache the record ID immediately; check the device's **status** attribute.

---

## 🧭 Status Attribute

The `status` attribute on the device reflects what's going on. Watch this in the device page or a dashboard tile:

| Status | Meaning |
|---|---|
| `ready (record cached)` | Record found, ready to update on next IP-change check. |
| `up-to-date` | Last check found the IP unchanged; skipped the API call. |
| `ok` | Most recent update succeeded. |
| `not configured` | Token / zone / record name not set. |
| `record not found — create it in Cloudflare first` | Lookup ran but no record matches the configured name+type in this zone. The log will have a louder warning. |
| `lookup failed (HTTP NNN)` | Cloudflare rejected the lookup — usually a token problem. |
| `update failed (HTTP NNN)` | Cloudflare rejected the update — token, zone, or record-id stale. |
| `ip lookup failed (HTTP NNN)` | ipify didn't respond — transient connectivity issue. |
| `no ipv6 transit` | Configured for AAAA but the hub has no IPv6 connectivity (ipify returned non-IPv6). |
| `ip family mismatch` | Configured for A but ipify returned an IPv6-looking address — shouldn't normally happen. |
| `lookup error: ...` / `update error: ...` | Cloudflare returned a JSON error; message is included. |

---

## 🛑 "No record found" — what to do

If you see `lookupRecord: NO RECORD FOUND for '...'` in the logs, **the driver intentionally stops**. Options:

1. **Most common cause**: the record doesn't exist yet. Create the A/AAAA record in the Cloudflare dashboard with any placeholder IP (e.g. `1.1.1.1`), then on the device page click **Lookup Record**. The driver will cache the ID and start updating.
2. **Typo in record name**: the name must be the **fully-qualified** hostname (`home.example.com`, not `home`). Fix the preference and click **Lookup Record**.
3. **Wrong record type**: if your record is `AAAA` but the preference is `A` (or vice versa), the lookup won't match.
4. **Wrong zone ID**: confirm the zone ID matches the zone that owns this hostname.

This driver will **not** create the missing record for you — that's intentional, to make sure you've thought about whether the record should exist.

---

## 🌐 Updating Multiple Records

Create a separate virtual device per record, each with its own preferences. The device's `state.recordId` is per-instance, so no two devices interfere with each other.

---

## 🔔 Notifications

Implements Hubitat's `Notification` capability. When the public IP changes, the driver fires a `deviceNotification()` event. Wire it up in **Rule Machine** or the **Notification app** as you'd expect.

---

## 🧪 Manual Commands

- **Refresh** / **Update DNS** — runs an update immediately
- **Lookup Record** — re-fetches the record ID from Cloudflare; useful if you've just created the record in the dashboard
- **Clear Record Cache** — drops the cached ID; next update will re-lookup

---

## 📜 License

MIT License

---

## 🤝 Contributions

Forks and pull requests welcome. If you add IPv6 (AAAA) testing notes, multi-record support, or hardening of any kind, please contribute.
