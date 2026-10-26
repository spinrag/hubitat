# SignalBricks Telemetry

A Hubitat Elevation app that sends read-only device telemetry to [SignalBricks](https://app.signalbricks.co): lock state, contact sensors, temperature, humidity, and battery levels.

It runs alongside the **SignalBricks Integration** (lock-code) app. The two apps are independent. Telemetry never controls devices.

Version 1.1.0 adds optional **lock code management** (off by default): SignalBricks can list code slots and manage admin and managed codes on locks you select for it. See [Lock code management](#lock-code-management-optional). Version 1.2.0 verifies every code set and delete on the hub, retries it if the lock drops it, and reports the outcome to SignalBricks. See [Verification and results](#verification-and-results).

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
- Codes are never logged and never sent as telemetry or in result events. Logs record the lock and slot only. While a set is being verified (and for up to 24 hours if it fails), the app keeps that one code in its own state so it can re-send it. See [Verification and results](#verification-and-results).

Recommended layout (signalbricks/platform#472): admin slots 1–10, managed 11–25, and the booking apps' **Minimum code position** set to 26 so booking codes fill upward from there.

### Endpoints

All are served from the hub's cloud endpoint (`https://cloud.hubitat.com/api/<hubUID>/apps/<appId>`) with `?access_token=<app access token>`, and return `404` unless lock code management is enabled and the hub is connected.

| Method | Path | Body / result |
|---|---|---|
| `GET` | `/locks` | `{locks: [{id, name, label, maxCodes}]}` |
| `GET` | `/locks/:id/slots` | `{lockId, maxCodes, slots: [{slot, name, set, allowedAs}]}`. `allowedAs` is `admin`, `managed`, `adopted` or `null`. Never code values. |
| `POST` | `/locks/:id/codes` | `{slot, code, name, requestId?}` → `202 {lockId, slot, status: "pending", requestId}`. The hub verifies the change and sends a `lock_code_result` event. |
| `DELETE` | `/locks/:id/codes/:slot` | Optional `?requestId=<uuid>` (or `{requestId}` JSON body) → `202 {lockId, slot, status: "pending", requestId}` |
| `POST` | `/locks/:id/codes/:slot/retry` | No body needed. Re-sends the hub's own pending or failed change for that slot → `202 {lockId, slot, action, status: "pending", requestId}`. `404 no_pending_change` if nothing is pending (confirmed, superseded, expired or no longer allowed). `409 slot_changed` if the slot now holds someone else's code. |
| `GET` | `/locks/:id/codes/:slot/reveal` | `{lockId, slot, name, code}`; `429` when rate-limited |
| `GET` | `/lockConfig` | `{config, ceiling, pendingApproval}`. `config` is `null` until set. `pendingApproval` lists `"<lockId>:<slot>"` adopted slots above the ceiling that the operator hasn't approved. |
| `POST` | `/lockConfig` | `{admin: {from, to}, managed: {from, to}, locks: {"<lockId>": {admin?, managed?, adopted?: [slot]}}}` replaces the rules and returns the same shape as `GET`. Only locks listed in `locks` (use `{}` to take the default ranges) can be written or revealed. Ranges must not overlap and must stay within 1..ceiling. Lock ids must be selected for lock code management. Otherwise `400` with a message. |

Errors: `400` invalid input (including `invalid_request_id`), `403` (`slot_not_allowed`, `name_not_allowed`, `booking_code`, `slot_not_managed`), `404` not enabled / unknown lock / empty slot / `no_pending_change`, `409` lock codes unreadable (see below) or `slot_changed` (retry), `429` reveal, retry or write rate limit, or `too_many_pending`.

### Verification and results

Added in 1.2.0 (signalbricks/platform#520). Z-Wave locks sometimes drop code changes silently, so the hub checks each one itself.

- **Request id.** `POST /codes` and `DELETE /codes/:slot` accept an optional `requestId` (a UUID, any case; stored lower-case). Without one, the hub generates a UUID. Either way it comes back in the `202` and in every result event for that change. Send a new id for each request.
- **One pending change per lock and slot.** A new set or delete for the same slot replaces the old one, and the old one is reported `superseded` (if it was still being verified). A request that is refused (`400`/`403`/`429`) leaves the existing change alone.
- **Limits.** Set and delete together are limited to 30 requests a minute across the app (`429 rate_limited`; reveal and retry have their own limits). At most 100 changes can be held at once; past that a new set or delete for another slot gets `429 too_many_pending` (one replacing a held change for the same slot is still allowed).
- **Verify.** About 30 seconds after the command, the hub re-reads that slot from the lock's `lockCodes` (decrypted as for `/slots`). A set is **confirmed** when the slot holds the expected name **and** the code that was sent. A delete is confirmed when the slot is empty. Names and codes are compared as strings, so `0123` never matches `123`. The one exception: if a driver reports the code as a JSON number (it has then already lost any leading zeros), that number is compared with the sent code without its leading zeros. If it is not confirmed yet, the hub checks again at the attempt's final check: 120 s after attempt 1, 150 s after attempt 2 and 180 s after attempt 3.
- **Retry.** If the final check fails, the hub re-sends the command, up to **3 attempts in total**. Before each re-send it re-applies the same slot rules as a new request (ceiling, ranges, `SBA:`/`SBM:` names, booking guard). It also checks the slot hasn't been taken over: for a delete, the slot must still hold the name it had at request time; for a set, it must be empty or hold either the requested name or the name it had before the request. A code without a name never counts as the change's own: if the slot had no name at request time and holds an unnamed code now, the reason is `slot_unnamed` (the hub can't tell whose code it is), otherwise `slot_changed`. Neither is re-sent. Otherwise it stops and reports `failed` with `slot_changed` or that rule's error as the reason. A change is settled within about 7.5 minutes of the request.
- **Retry endpoint.** `POST /locks/:id/codes/:slot/retry` re-sends the hub's stored change. It needs no code, applies the same rules (including the `slot_changed` check), resets the attempt count to 1 and keeps the same `requestId`. If the change can no longer be sent, the endpoint returns the error and drops it (reporting `failed` only if it was still in progress). It is limited to 10 calls a minute across the app (`429`).
- **Code retention.** A pending set keeps its code in the app's state only, never in logs, events or responses. It is dropped when the change is confirmed, superseded (including by a delete), when lock code management is turned off, on **Disconnect**, or 24 hours after the original request. Changes that failed because the lock didn't apply them (`not_applied`) or its codes couldn't be read (`lock_codes_unreadable`) are kept until then so the retry endpoint can re-send them. Changes that failed for any other reason (the slot is no longer allowed or was taken over, the lock was removed, management turned off, Disconnect) are dropped at once, code included, because a retry could never succeed. A `POST /lockConfig` that stops allowing a slot drops its change too, reporting `failed` (`slot_not_allowed`) if it was in progress. Retries don't extend the 24 hours.
- **Who can see a pending code.** Hub admins can see the app's state, including a pending set's code, under the app's **App Status** page (the gear icon). That is the same exposure as the lock's own `lockCodes` on its device page, which already shows every code.
- **Done / app updates** reschedule the checks for changes still in progress. They also drop every held change (failed ones too) for a lock that is no longer selected for lock code management, reporting `failed` (`lock_not_found`) for one still in progress. One scheduled job covers all changes (it runs at the earliest due check), so re-sends and superseded changes don't pile up jobs. If a check throws (for example the driver's `setCode` on a re-send), only that change fails, with reason `send_error`, and is dropped; the log records only the exception's class name, never its message, which could include the code.

Each outcome is sent like a telemetry event: the same ingest URL and ingestion token, `name: "lock_code_result"`, so SignalBricks receives event type `hubitat.lock_code_result`:

```json
{"id": "lock_code_result:<requestId>:<seq>:<result>", "deviceId": "<lockId>", "name": "lock_code_result", "displayName": "<lock name>", "value": "confirmed", "data": "{\"lockId\":\"7\",\"slot\":3,\"action\":\"set\",\"result\":\"confirmed\",\"attempts\":1,\"requestId\":\"<uuid>\",\"name\":\"SBA: Cleaner\"}", "date": "<UTC ISO time>", "version": "1.2.0"}
```

`data` (a JSON string, as for telemetry) holds `{lockId, slot, action: "set"|"delete", result: "confirmed"|"failed"|"superseded", attempts, requestId, reason?, name?}`:

- `reason` is set on `failed`: `not_applied` (3 attempts, never confirmed), `lock_codes_unreadable`, `slot_changed` (someone else's code is in the slot now), `slot_unnamed` (an unnamed code is in the slot, as it was at request time), `send_error` (the lock driver threw while re-sending; if even the cleanup fails, the change is dropped without an event), a rule error such as `slot_not_allowed`, `booking_code`, `slot_not_managed`, `name_not_allowed` or `invalid_slot`, `lock_not_found` (lock no longer selected), `lock_management_disabled`, `disconnected` or `expired`. Only `not_applied` and `lock_codes_unreadable` can be retried with the retry endpoint.
- `name` is included only when it is an `SBA:` or `SBM:` name, never an adopted or booking name.
- `attempts` is the number of sends so far, counted from the last retry-endpoint call.

Neither the event nor the `202` ever contains the code.

### Registration

After every Connect (once the access token has been rotated), on every **Done**, on **Rotate access token and re-register**, and on **Disconnect**, the app POSTs to `<ingest URL>/register` (`https://ingest.signalbricks.co/v1/webhooks/hubitat/register`) with its ingestion token:

```json
{"cloudEndpointBase": "https://cloud.hubitat.com/api/<hubUID>/apps/<appId>", "appAccessToken": "<only while enabled, else null>", "hubUid": "<hubUID>", "appId": "<appId>", "version": "1.2.1", "lockManagement": true}
```

Since 1.2.1 it does this only while lock code management is on, or to send a disable (`lockManagement: false`) when an earlier enable may still be stored. A hub that has never used lock code management doesn't register at all.

SignalBricks accepts it only with the ingestion token the Connect flow issued for this hub and app. It stores the endpoint and token encrypted against that hub, and never logs or returns them. The app sends tokens only to an `https://` ingest URL.

Hubs connected before lock code management existed (signalbricks/platform#505) hold a token without that binding. SignalBricks answers their enable with `403 TOKEN_NOT_BOUND_TO_APP`, and their disable with `200` and `reconnect_required: true`, storing nothing and leaving any existing registration as it is (signalbricks/platform#522). In either case the app shows **Reconnect to SignalBricks to enable lock management** and doesn't retry. Reconnect, then turn lock code management on.

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
- **"Reconnect to SignalBricks to enable lock management"**: this hub was connected before lock code management existed, so its token can't enable it. Turn lock code management off, click **Reconnect to SignalBricks**, then turn it back on and click **Done**. Telemetry keeps working meanwhile.
- **Registration error** under Lock code management: check the hub is connected, then click **Rotate access token and re-register**.
- **A code change failed** (`lock_code_result` with `result: "failed"`): check the lock is awake and in range of the hub, then retry from SignalBricks. Changes that failed with `not_applied` or `lock_codes_unreadable` can be retried without re-entering the code for 24 hours; other failures need a new request. `Code changes awaiting the lock` in the app shows how many are in progress or failed.
- **Slots answer `409 lock_codes_unreadable`**: see [Encrypted lock codes](#encrypted-lock-codes).
- Turn on **debug logging** to see each event as it is sent. Debug logging turns itself off after 30 minutes.

## License

MIT © Spinrag, LLC
