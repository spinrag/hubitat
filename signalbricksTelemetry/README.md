# SignalBricks Telemetry

A Hubitat Elevation app that sends read-only device telemetry to [SignalBricks](https://app.signalbricks.co): lock state, contact sensors, temperature, humidity, and battery levels.

It runs alongside the **SignalBricks Integration** (lock-code) app. The two apps are independent. Telemetry never controls devices.

Version 1.1.0 adds optional **lock code management** (off by default): SignalBricks can list code slots and manage admin and managed codes on locks you select for it. See [Lock code management](#lock-code-management-optional).

## Requirements

- Hubitat Elevation platform **2.5.0** or later
- A SignalBricks account with the **admin** role in the organization that owns the property

## Install

### Hubitat Package Manager (recommended)

1. Open **Hubitat Package Manager** on your hub.
2. Choose **Install** → **Search by Keywords** and search for `SignalBricks`.
3. Select **SignalBricks Telemetry** and follow the prompts. HPM enables OAuth for the app code automatically.

### Manual

1. In **Apps Code**, click **New App**, paste [`app.groovy`](app.groovy), and click **Save**.
2. Click **OAuth** → **Enable OAuth in App** → **Update**.
3. In **Apps** → **Add User App**, choose **SignalBricks Telemetry**.

## Connect

1. Open **SignalBricks Telemetry**. It appears under **Integrations**.
2. Select the locks and sensors you want to report, then click **Done** and reopen the app.
3. Click **Connect to SignalBricks**. Sign in, then choose the **organization**, **property**, and **hub**. If this hub was connected before, it is preselected. You can also create a new hub entry. If the organization has no properties yet, you can create one on the same page.
   - If you work for a management company, choose the **property owner's organization**. Hubs belong to the owner, not the management company.
4. After you approve, Hubitat sends you back to the app. A confirmation page appears, and the app shows **Status: Connected**.

**Reconnect** issues a new token and revokes the previous one. **Disconnect** stops sending right away. To revoke the token on the SignalBricks side as well, remove it under **Tokens** in the portal.

## Events sent

| Devices | Attributes |
|---|---|
| Locks | `lock`, `lastCodeName`, `battery` |
| Contact sensors | `contact`, `battery` |
| Temperature sensors | `temperature`, `battery` |
| Humidity sensors | `humidity`, `battery` |

Each event is sent with `asynchttpPost` to the SignalBricks ingestion API (`https://ingest.signalbricks.co/v1/webhooks/hubitat`) using `Authorization: Bearer <token>`. Each event includes the device id and name, the attribute name and value, the unit, the description text, the event time, and the app version.

## Lock code management (optional)

Off by default. When enabled, SignalBricks can list each selected lock's code slots (slot number, name, set or empty, never the code), set and delete **admin** (`SBA: …`) and **managed** (`SBM: …`) codes, and reveal one code at a time for an authorized user. Booking codes set by the SignalBricks Integration or OwnerRez apps are never touched.

### Enable it

Already-connected hubs need only an app update:

1. Update **SignalBricks Telemetry** to 1.1.0 or later (HPM **Update**, or paste the new `app.groovy` in **Apps Code** and **Save**).
2. Open the app. Under **Lock code management (optional)**, turn on **Enable lock code management**.
3. Select the locks SignalBricks may manage codes on. This list is separate from the telemetry locks.
4. Check **Highest slot SignalBricks may manage** (default 25). Keep it below the booking apps' **Minimum code position**.
5. Click **Done**. The app registers with SignalBricks (see below). Reopen the app and check **Registration: registered …**.
6. Nothing can be written yet: SignalBricks first sends the slot rules (admin range, managed range, adopted slots) during lock onboarding in the portal. If it asks to manage existing codes above the ceiling, they are listed in the app under **Approve these slots**. Approve only codes you recognise as admin or managed codes.

New hubs: install, **Connect to SignalBricks** first, then follow steps 2 to 5.

To turn it off, switch off **Enable lock code management**. This takes effect immediately, even before you click **Done**. The app rotates its access token (so the token SignalBricks held stops working), clears the slot rules and approvals, and tells SignalBricks lock management is off. **Disconnect** does the same.

While lock code management is on, **Reconnect to SignalBricks** is hidden, because the connect link would carry the access token SignalBricks uses for lock control. To reconnect, turn lock code management off, reconnect, then turn it back on.

### What the hub enforces

The hub decides which slots may be touched. Anyone holding the app's access token (SignalBricks, or someone who stole it) can only reach what the operator allowed in the app UI:

- **Ceiling (local only):** *Highest slot SignalBricks may manage*, default 25, is set only in the app UI. `POST /lockConfig` is refused (`400`) if the admin or managed range goes above it. Lowering the ceiling later immediately refuses slots above it. Raising it automatically allows any adopted slots that fall under the new ceiling, without a separate approval.
- **Allowed slots** = admin range ∪ managed range (both ≤ ceiling) ∪ the lock's adopted slots. Adopted slots at or below the ceiling are allowed. Adopted slots **above** it stay refused until the operator approves them in the app UI. The API can never approve them. Each `POST /lockConfig` drops approvals for slots the new config no longer lists as adopted. Before a config exists, every write and reveal is refused. **A lock that isn't listed in the config's `locks` allows nothing.** The global `admin`/`managed` ranges are only defaults for listed locks, so locks that haven't been onboarded can't be touched even with the app token. Any other slot gets `403`.
- **Booking codes:** any slot whose current name has an upper-case `ORB` at the start or right after a space or hyphen (case-sensitive `(^|[\s-])ORB`) is always refused for write, delete and reveal, even if adopted and approved. Refused examples:
  - `ORB123-Guest` (signalbricksIntegration's default naming with an empty prefix)
  - `GT: ORB123-Guest`
  - the idAtEnd forms `Guest-ORB12345` (signalbricksIntegration) and `Guest -ORB12345` (OwnerRez fork)

  `Forbes`, `Orbit` and `orb123` are not booking codes. The SignalBricks platform applies the same rule.
- **Names:** a code set in the admin range must be named `SBA: …`, in the managed range `SBM: …`. Adopted slots (existing codes recorded in place at onboarding) keep their current names. Every name must be 1–64 printable ASCII characters. It must not have an upper-case `ORB` at the start or after a space or hyphen, and must not contain an upper-case `ORB` followed by a digit anywhere (`ORB\d`, case-sensitive). signalbricksIntegration claims any name containing `ORB` plus digits, so such a name could be taken over or deleted by the booking app. `SBA: Forbes`, `SBA: Orbit`, `SBA: FORBES` and `SBA: orb123` are fine. `SBA: ORB1`, `SBA:ORB12`, `SBA: Guest-ORB12` and `SBM:x-ORB5` are refused.
- **Existing codes:** in the admin and managed ranges, a slot holding a code without that range's `SBA:`/`SBM:` marker can't be overwritten, deleted or revealed until it is adopted. Empty slots may be written. `/slots` still reports `allowedAs` (admin/managed) for such slots, based on the range alone, even though writes refuse them (`403 slot_not_managed`) until they are adopted.
- Codes must be 4 to 10 digits. Slots above the lock's `maxCodes` are refused.
- **Reveal** returns one slot's code, only for `SBA:`/`SBM:` codes in their ranges or allowed adopted slots, at most 10 times a minute across the app (`429` beyond that).
- Codes are never logged, never stored by the app and never sent as telemetry. Logs record the lock and slot only.

Recommended layout (signalbricks/platform#472): admin slots 1–10, managed 11–25, and the booking apps' **Minimum code position** set to 26 so booking codes fill upward from there.

### Endpoints

All are served from the hub's cloud endpoint (`https://cloud.hubitat.com/api/<hubUID>/apps/<appId>`) with `?access_token=<app access token>`, and return `404` unless lock code management is enabled and the hub is connected.

| Method | Path | Body / result |
|---|---|---|
| `GET` | `/locks` | `{locks: [{id, name, label, maxCodes}]}` |
| `GET` | `/locks/:id/slots` | `{lockId, maxCodes, slots: [{slot, name, set, allowedAs}]}`. `allowedAs` is `admin`, `managed`, `adopted` or `null`. Never code values. |
| `POST` | `/locks/:id/codes` | `{slot, code, name}` → `202 {lockId, slot, status: "pending"}`. The lock confirms asynchronously: re-read `/slots`. |
| `DELETE` | `/locks/:id/codes/:slot` | → `202 {lockId, slot, status: "pending"}` |
| `GET` | `/locks/:id/codes/:slot/reveal` | `{lockId, slot, name, code}`; `429` when rate-limited |
| `GET` | `/lockConfig` | `{config, ceiling, pendingApproval}`. `config` is `null` until set. `pendingApproval` lists `"<lockId>:<slot>"` adopted slots above the ceiling that the operator hasn't approved. |
| `POST` | `/lockConfig` | `{admin: {from, to}, managed: {from, to}, locks: {"<lockId>": {admin?, managed?, adopted?: [slot]}}}` replaces the rules and returns the same shape as `GET`. Only locks listed in `locks` (use `{}` to take the default ranges) can be written or revealed. Ranges must not overlap and must stay within 1..ceiling. Lock ids must be selected for lock code management. Otherwise `400` with a message. |

Errors: `400` invalid input, `403` (`slot_not_allowed`, `name_not_allowed`, `booking_code`, `slot_not_managed`), `404` not enabled / unknown lock / empty slot, `409` lock codes unreadable (see below), `429` reveal rate limit.

### Registration

After every Connect (once the access token has been rotated), on every **Done**, on **Rotate access token and re-register**, and on **Disconnect**, the app POSTs to `<ingest URL>/register` (`https://ingest.signalbricks.co/v1/webhooks/hubitat/register`) with its ingestion token:

```json
{"cloudEndpointBase": "https://cloud.hubitat.com/api/<hubUID>/apps/<appId>", "appAccessToken": "<only while enabled, else null>", "hubUid": "<hubUID>", "appId": "<appId>", "version": "1.1.0", "lockManagement": true}
```

SignalBricks accepts it only with the ingestion token the Connect flow issued for this hub and app. It stores the endpoint and token encrypted against that hub, and never logs or returns them. The app sends tokens only to an `https://` ingest URL.

### Encrypted lock codes

Some newer lock drivers can store `lockCodes` encrypted (a driver preference such as *Encrypt lock codes*). The app decrypts them with the hub's `decrypt()` when the platform provides it to apps. If it can't, `/slots` and `/reveal` answer `409 lock_codes_unreadable`: turn off code encryption in that lock's driver preferences, or leave that lock out of lock code management.

## Development

`tests/LockManagementHarness.groovy` exercises the lock-management handlers against a stubbed Hubitat sandbox, with fake locks and codes. It is not loaded by Hubitat or HPM. Run it from the repository root with Docker:

```bash
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
  groovy /w/tests/LockManagementHarness.groovy /w/app.groovy
```

It prints one `PASS`/`FAIL` line per case and exits non-zero on any failure. It checks behaviour in the stub only, not on a real hub.

## Privacy

- **Door codes (PINs) are never sent; only the code slot/name used.** The app never subscribes to `lockCodes`. Lock drivers include the code used in an unlock event's `data`. Before sending any event, the app removes every code/PIN field from `data` (keeping slot numbers and code names) and drops `data` entirely if it can't be parsed. `lastCodeName` is the *name* of the code that was used (for example, a guest or booking label), not the code.
- **Telemetry is read-only.** With lock code management off (the default), the app sends no device commands and its only working endpoint is the one-time `/oauthCallback` used during Connect. With it on, the lock endpoints above work only for the separately selected locks and the slots the hub allows.
- **No shared secret.** Connect uses OAuth with PKCE (S256), so the app doesn't need to store a client secret. The hub's own app access token in the OAuth `state` is used only by Hubitat cloud to route the callback, and is never logged.
- The hub access token used during Connect is rotated immediately after the callback; the new token is then registered with SignalBricks (sent only while lock code management is on).
- Events go only to the ingestion URL that SignalBricks returned during Connect. Nothing is sent until the hub is connected.

## Troubleshooting

- **"OAuth is not enabled for this app"**: enable OAuth for the app code (see Manual install, step 2).
- **"SignalBricks rejected the token"**: the token was revoked in the portal. Click **Reconnect to SignalBricks** (turn lock code management off first if it's on).
- **Registration error** under Lock code management: check the hub is connected, then click **Rotate access token and re-register**.
- **Slots answer `409 lock_codes_unreadable`**: see [Encrypted lock codes](#encrypted-lock-codes).
- Turn on **debug logging** to see each event as it is sent. Debug logging turns itself off after 30 minutes.

## License

MIT © Spinrag, LLC
