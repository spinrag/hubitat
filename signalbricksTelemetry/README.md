# SignalBricks Telemetry

A Hubitat Elevation app that sends read-only device telemetry to [SignalBricks](https://app.signalbricks.co): lock state, contact sensors, temperature, humidity, and battery levels.

It runs alongside the [**SignalBricks Integration**](../signalbricksIntegration/README.md) app (booking door codes). The two apps are independent and versioned separately; each shows its own name next to its version. Telemetry never controls devices.

Version 1.1.0 adds optional **lock code management** (off by default): SignalBricks can list code slots and manage admin and managed codes on locks you select for it. See [Lock code management](#lock-code-management-optional). Version 1.2.0 verifies every code set and delete on the hub, retries it if the lock drops it, and reports the outcome to SignalBricks. See [Verification and results](#verification-and-results). Version 1.3.0 adds code [fingerprints](#fingerprints) so SignalBricks can see which slots share a code across locks (for onboarding) without reading the codes. Version 1.3.1 [re-registers](#registration) by itself after an update, so SignalBricks sees the new version without opening the app. Version 1.3.2 adds a [daily check-in](#updating) so quiet hubs report a new version within a day. Version 1.3.3 adds a [batch fingerprints](#batch-fingerprints) endpoint that reads every lock in one call. Version 1.3.4 shows the app name with the version (*SignalBricks Telemetry 1.3.4*) on the app page and in log lines, so it can't be mistaken for the separately versioned SignalBricks Integration app, and recognises booking codes by `ORB` followed by a digit anywhere in the name, so prefixed names such as `GT:ORB123-x` are [always refused](#what-the-hub-enforces). Version 1.3.5 adds the optional, off-by-default [Include door codes (hashed by SignalBricks)](#door-codes-in-telemetry-optional) setting. Version 1.4.0 lets SignalBricks [rename or move a code](#relocate) on the hub without ever having the code. Version 1.5.0 adds [registered slots and full lock control](#registered-slots-and-full-lock-control): custom code names without `SBA:`/`SBM:`, and per-lock control of every non-booking code, both off until SignalBricks and you turn them on.

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
4. After you approve, Hubitat shows a confirmation page from the hub. Since 1.5.1 it is a card with the outcome: *Connected*, *Not connected*, *Connect link expired* or *Connection failed*, with what to do next. Its **Back to the app** link opens the app by the hub's local IP address, so it works on the hub's network; elsewhere, go back to the hub window you connected from. The app then shows **Status: Connected**. If the connect failed, the app's **Last error** line shows the reason.

**Reconnect** issues a new ingestion token and revokes the previous one. **Disconnect** stops sending right away. To revoke the token on the SignalBricks side as well, remove it under **Tokens** in the portal.

### Reconnect

Since 1.5.2 **Reconnect to SignalBricks** is a button, and it is shown whether or not lock code management is on (#46):

1. Click **Reconnect to SignalBricks**. The page then shows **Continue to SignalBricks**, with the time the link stops working: 10 minutes after the click.
2. Click **Continue to SignalBricks** and sign in as for Connect.

Hubitat cloud only routes the sign-in result back to the app when the link carries the app's current access token, and while lock code management is on that is the token SignalBricks uses for lock control. So the link exists only for those 10 minutes, and the token in it is always replaced:

- When you finish, or cancel, the sign-in, the app replaces its access token and registers the new one with SignalBricks.
- If no sign-in result arrives within 10 minutes, the app does the same with the ingestion token it already has. The link stops working; click **Reconnect** again for a new one.

Only the token changes. Lock code management stays on, and the slot rules, fingerprint key, approvals, full lock control and code changes in progress are kept; SignalBricks can't reach the hub only for the moment between the swap and the registration. If that registration fails for a reason that may pass (a timeout, rate limiting, SignalBricks unavailable), the app retries after about 30 seconds, 2 minutes and 5 minutes, and the **Connection** section shows *Registration retries: retry N of 3 at …*. It never goes back to the old token.

## Updating

Use Hubitat Package Manager (HPM) to keep the app up to date. Set this up once on each hub:

1. Open **Hubitat Package Manager** → **Package Manager Settings**.
2. Set **Install updates automatically when** to **Only those I list**, then add **SignalBricks Telemetry** under **Which packages should be automatically updated?** Other packages on the hub keep updating the way you choose.
3. Turn on **Notify me if automatic updates are unsuccessful**, pick a notification device, and turn on **Include hub name in notifications**, so you can tell which hub needs attention when you run several.
4. If **Hub Login Security** is on (Settings → Hub Login Security), enter the hub's username and password under **Hub Security** in HPM settings. HPM needs them to install the new code; without them updates fail.
   If the hub has HTTPS turned on, also turn on **SSL (HTTPS) Enabled** in HPM settings.
5. Set the update check time away from 3am: the hub runs its nightly maintenance and backups then. Any time outside 2–4am works.

**From 1.3.1 on, you don't need to open the app and click Done after an update.** HPM replaces the code without running Done; the app notices the new version and [re-registers](#registration) with SignalBricks by itself. Open the app and click **Done** only when a release adds a new setting or permission you want to use (the release notes say so).

**Daily check-in (1.3.2).** Once a day the app checks whether it was updated and, if so, re-registers, so a hub that sends few events still reports the new version within a day. Each hub picks its own random time once, never between 2 and 4am (hub time), so hubs don't all call SignalBricks at once; the app page shows it under **Status** as *Daily update check-in at HH:MM*. The check sends nothing when the version hasn't changed, and follows the same rules as re-registering after an event. It is set up on **Done** or **Connect** (or, for a hub updated from 1.3.1, with its next event) and removed on **Disconnect**.

### Beta channel

New releases can go to one or two canary hubs before every other hub. The package has an HPM beta channel: hubs that opt in install [`beta/app.groovy`](beta/app.groovy) instead of [`app.groovy`](app.groovy) while a beta is out, and go back to the stable code when it is promoted. Other hubs never see the beta.

**Opt a hub in** (on the canary hub only):

1. Open **Hubitat Package Manager** → **Package Manager Settings**.
2. Under **General**, turn on **When updating, install pre-release versions**.
3. In **Choose which app/packages to enable Beta/Early Access code**, select **SignalBricks Telemetry** only. The beta setting is per package, so other packages on the hub stay on their stable releases.
4. Click **Done**. Keep auto-update for SignalBricks Telemetry on (see [Updating](#updating)) so the hub takes each beta as soon as HPM's daily check sees it, or run **Update** in HPM to take it now.

While a beta is out, HPM's **Install** page also shows **Install beta package** for a new install; turning it on does steps 2 and 3 for you. To leave the beta channel, remove **SignalBricks Telemetry** from that package list. Turning **install pre-release versions** off alone is not enough: HPM keeps the list and still installs the beta files for packages on it. The hub moves back to stable with the next stable release.

Opting in while no beta is out changes nothing: the beta copy is then identical to the stable release.

**Caveat (HPM behaviour).** If HPM updates SignalBricks Telemetry while a beta is out, it records the package as "beta" on any hub that has **install pre-release versions** on for any package, even when SignalBricks Telemetry is not on that hub's list. Such a hub can show the beta's version as installed while it runs the stable code. It still takes the next stable release whose version is at least the beta's. Hubs with pre-releases off are not affected. The app page (*SignalBricks Telemetry …*) and SignalBricks always show the code that actually runs.

**How it works.** The manifest's app entry has a `betaLocation` pointing at `beta/app.groovy` on `main`. A beta is "out" when the manifest has a `betaVersion`: opted-in hubs are offered that version, with `betaReleaseNotes`, and install the beta file. Without a `betaVersion`, every hub gets `app.groovy`.

**Cut a beta** (one PR):

1. Make the change in `beta/app.groovy` and set its `APP_VERSION` to the beta version.
2. In `packageManifest.json` set `betaVersion` to that version and `betaReleaseNotes` to its notes. Leave `version`, `releaseNotes` and `app.groovy` alone.
3. Pick a `betaVersion` HPM will offer: HPM compares versions part by part and only offers a beta when **every** part is at least the same part of `version`. After 1.3.4, `1.3.5` and `1.4.4` work; `1.4.0` does not (HPM would quietly install the stable code instead). CI rejects a `betaVersion` HPM would skip.
4. To ship a fix to a beta that is already out, change `beta/app.groovy` again and raise `betaVersion`, so opted-in hubs see an update.

**Promote a beta** (one PR):

1. Copy `beta/app.groovy` over `app.groovy`. The two files are now identical.
2. Set `version` to the beta's version, set `dateReleased`, and add its notes at the top of `releaseNotes`.
3. **Remove `betaVersion` and `betaReleaseNotes`.** Keep `betaLocation`. Opted-in hubs then get the stable code of the same version and leave the beta. Don't set `betaVersion` equal to `version`: HPM then keeps offering hubs with pre-releases turned on the same update every day. CI rejects it.

**Drop a beta** without releasing it: copy `app.groovy` over `beta/app.groovy` and remove `betaVersion` and `betaReleaseNotes`. Hubs that took the beta go back to stable with the next stable release whose version is at least the dropped beta's.

**Stable-only release while no beta is out:** change `app.groovy` and bump `version` as usual, and copy the result to `beta/app.groovy` in the same PR. CI checks that `beta/app.groovy` has `betaVersion` while a beta is out and is identical to `app.groovy` otherwise, and runs the harness against both files. A stable fix while a beta is out changes only `app.groovy` (and the beta, if it needs the fix too); CI says if `betaVersion` must then be raised.

## Events sent

| Devices | Attributes |
|---|---|
| Locks | `lock`, `lastCodeName`, `battery` |
| Contact sensors | `contact`, `battery` |
| Temperature sensors | `temperature`, `battery` |
| Humidity sensors | `humidity`, `battery` |

Each event is sent with `asynchttpPost` to the SignalBricks ingestion API (`https://ingest.signalbricks.co/v1/webhooks/hubitat`) using `Authorization: Bearer <token>`. Each event includes the device id and name, the attribute name and value, the unit, the description text, the event time, the app version, and (since 1.3.1) the hub firmware version.

### Door codes in telemetry (optional)

Added in 1.3.5 (signalbricks/platform#388). **Include door codes (hashed by SignalBricks)** is off by default. It is one of two keys; both must be on before SignalBricks keeps anything about a code:

1. **Hub:** this setting, in the app's *Door codes in telemetry (optional)* section.
2. **SignalBricks:** an organization admin turns on *Hash door codes from Hubitat telemetry* (Security → Door codes → Settings). Each change there is recorded with who made it and when.

| This setting | SignalBricks policy | What SignalBricks keeps |
|---|---|---|
| off (default) | either | no code: the app removes it before sending (as in every earlier version) |
| on | off | no code: SignalBricks removes it on arrival |
| on | on | a keyed hash of the code, never the code |

With the setting on, the app keeps only the `code` field (a string or number) in the `data` of `lock` events (lock/unlock). Everything else is unchanged: other secret fields (`pin`, `passcode`, `usedCode`, `lockCodes`, ...) are still removed from every event; `codeChanged`, `lastCodeName` and all other events never carry a code; the app still never subscribes to `lockCodes`; codes are never logged (debug logging shows the event name and value only, and a failed send logs only the error class). The code travels only to the ingestion URL SignalBricks returned during Connect, over HTTPS with the hub's ingestion token, the same channel as every other event.

SignalBricks replaces the code with an HMAC-SHA256 hash under a secret key of your organization as soon as the event arrives, before it is stored, streamed or logged. The same code gives the same hash within your organization. Door codes are short, so the hash is only as safe as that key: SignalBricks keeps it on its server and never sends it to the hub.

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

You don't need to turn lock code management off to reconnect: see [Reconnect](#reconnect). (Before 1.5.2, Reconnect was hidden while lock code management was on.)

### What the hub enforces

The hub decides which slots may be touched. Anyone holding the app's access token (SignalBricks, or someone who stole it) can only reach what the operator allowed in the app UI:

- **Ceiling (local only):** *Highest slot SignalBricks may manage*, default 25, is set only in the app UI. `POST /lockConfig` is refused (`400`) if the admin or managed range goes above it. Lowering the ceiling later immediately refuses slots above it. Raising it automatically allows any adopted slots that fall under the new ceiling, without a separate approval.
- **Allowed slots** = admin range ∪ managed range (both ≤ ceiling) ∪ the lock's adopted slots. Adopted slots at or below the ceiling are allowed. Adopted slots **above** it stay refused until the operator approves them in the app UI. The API can never approve them. Each `POST /lockConfig` drops approvals for slots the new config no longer lists as adopted. Before a config exists, every write and reveal is refused. **A lock that isn't listed in the config's `locks` allows nothing.** The global `admin`/`managed` ranges are only defaults for listed locks, so locks that haven't been onboarded can't be touched even with the app token. Any other slot gets `403`.
- **Booking codes:** any slot whose current name contains an upper-case `ORB` followed by a digit anywhere (case-sensitive `ORB\d`, since 1.3.4, signalbricks/platform#581) is always refused for write, delete and reveal, even if adopted and approved, and is left out of fingerprints and survey results. This is the same rule signalbricksIntegration uses to claim its codes. Refused examples:
  - `ORB123-Guest` (signalbricksIntegration's naming with an empty prefix)
  - `GT:ORB123-x` and `G:ORB1-x` (prefixes without a space, as signalbricksIntegration and the OwnerRez app write them), `GT: ORB123-Guest`
  - the idAtEnd forms `Guest-ORB12345` (signalbricksIntegration) and `Name -ORB123` (OwnerRez fork)

  `Forbes`, `Orbit`, `ORBIT`, `Corbin`, `ORB-Guest` and `orb123` are not booking codes. Before 1.3.4 the guard only matched `ORB` at the start of a name or after a space or hyphen, so prefixed names such as `GT:ORB123-x` were not recognised. The SignalBricks platform applies the same rule.
- **Names:** a code set in the admin range must be named `SBA: …`, in the managed range `SBM: …`. Adopted slots (existing codes recorded in place at onboarding) keep their current names. Every name must be 1–64 printable ASCII characters. It must not contain an upper-case `ORB` followed by a digit anywhere (`ORB\d`, case-sensitive): that is the booking-code rule above, and signalbricksIntegration claims any such name, so it could be taken over or deleted by the booking app. `SBA: Forbes`, `SBA: Orbit`, `SBA: ORBIT`, `SBA: Corbin`, `SBA: FORBES` and `SBA: orb123` are fine. `SBA: ORB1`, `SBA:ORB12`, `SBA: Guest-ORB12`, `SBA: GT:ORB123-x` and `SBM:x-ORB5` are refused.
- **Existing codes:** in the admin and managed ranges, a slot holding a code without that range's `SBA:`/`SBM:` marker can't be overwritten, deleted or revealed until it is adopted. Empty slots may be written. `/slots` still reports `allowedAs` (admin/managed) for such slots, based on the range alone, even though writes refuse them (`403 slot_not_managed`) until they are adopted.
- Codes must be 4 to 10 digits. Slots above the lock's `maxCodes` are refused.
- **Registered slots and full lock control** (1.5.0): see [Registered slots and full lock control](#registered-slots-and-full-lock-control). Without them in the config, nothing above changes.
- **Reveal** returns one slot's code, only for `SBA:`/`SBM:` codes in their ranges, allowed adopted slots or allowed registered slots, at most 10 times a minute across the app (`429` beyond that).
- Codes are never logged and never sent as telemetry or in result events. Logs record the lock and slot only. While a set is being verified (and for up to 24 hours if it fails), the app keeps that one code in its own state so it can re-send it. A relocate keeps the code it copies there too, only until it is settled. See [Verification and results](#verification-and-results).

Recommended layout (signalbricks/platform#472): admin slots 1–10, managed 11–25, and the booking apps' **Minimum code position** set to 26 so booking codes fill upward from there.

### Endpoints

All are served from the hub's cloud endpoint (`https://cloud.hubitat.com/api/<hubUID>/apps/<appId>`) with `?access_token=<app access token>`, and return `404` unless lock code management is enabled and the hub is connected.

| Method | Path | Body / result |
|---|---|---|
| `GET` | `/locks` | `{locks: [{id, name, label, maxCodes}]}` |
| `GET` | `/locks/:id/slots` | `{lockId, maxCodes, surveyOnly, slots: [{slot, name, set, allowedAs, registered?}]}`. `allowedAs` is `admin`, `managed`, `adopted` or `null` (always `null` on a survey-only lock). A registered slot (1.5.0) has `registered: true` and `allowedAs: "adopted"` while the hub would let SignalBricks touch it, else `null`. Never code values. |
| `POST` | `/locks/:id/codes` | `{slot, code, name, requestId?}` → `202 {lockId, slot, status: "pending", requestId}`. The hub verifies the change and sends a `lock_code_result` event. |
| `DELETE` | `/locks/:id/codes/:slot` | Optional `?requestId=<uuid>` (or `{requestId}` JSON body) → `202 {lockId, slot, status: "pending", requestId}` |
| `POST` | `/locks/:id/codes/:slot/retry` | No body needed. Re-sends the hub's own pending or failed change for that slot → `202 {lockId, slot, action, status: "pending", requestId}`. `404 no_pending_change` if nothing is pending (confirmed, superseded, expired or no longer allowed). `409 slot_changed` if the slot now holds someone else's code. |
| `POST` | `/locks/:id/codes/:slot/relocate` | `{name, toSlot?, requestId?}` → `202 {lockId, fromSlot, toSlot, status: "pending", requestId}`. Renames the code in `:slot` (no `toSlot`, or `toSlot` equal to `:slot`) or moves it to the empty `toSlot`, on the hub. The code is never in the request or the response. The hub sends a `relocate_result` event. See [Relocate](#relocate). |
| `GET` | `/locks/:id/codes/:slot/reveal` | `{lockId, slot, name, code}`; `429` when rate-limited |
| `GET` | `/locks/:id/fingerprints` | `{lockId, slots: [{slot, fp}]}` for each occupied, allowed slot. Never codes. `409 fingerprint_key_missing` until SignalBricks sends a key; `429` when rate-limited. See [Fingerprints](#fingerprints). |
| `GET` | `/fingerprints` | Optional `?ids=<id>,<id>`. `{locks: [{id, slots: [{slot, fp}]} \| {id, error, message?}]}` for every lock selected for code management, or the listed ids. Same rules as `/locks/:id/fingerprints`. See [Batch fingerprints](#batch-fingerprints). |
| `GET` | `/lockConfig` | `{config, ceiling, pendingApproval, fingerprintKeySet, features, fullControlAllowed}`. `fingerprintKeySet` says whether a fingerprint key is held; the key itself is never returned. `config` is `null` until set. `pendingApproval` lists `"<lockId>:<slot>"` adopted (and registered) slots above the ceiling that the operator hasn't approved. `features` (1.5.0) lists the config fields this version reads (`registered_slots`, `full_control`); `fullControlAllowed` the lock ids the operator chose under **Locks SignalBricks may fully control**. |
| `POST` | `/lockConfig` | `{admin: {from, to}, managed: {from, to}, locks: {"<lockId>": {admin?, managed?, adopted?: [slot], registered?: [{slot, name, pendingName?}], fullControl?} | {surveyOnly: true}}}` replaces the rules and returns the same shape as `GET`. Only locks listed in `locks` (use `{}` to take the default ranges) can be written or revealed. `{surveyOnly: true}` lists a lock for reading only (see [Survey-only locks](#survey-only-locks)); it can't be combined with `admin`, `managed`, `adopted`, `registered` or `fullControl`. For `registered` and `fullControl` see [Registered slots and full lock control](#registered-slots-and-full-lock-control). Ranges must not overlap and must stay within 1..ceiling. Lock ids must be selected for lock code management. Optional `fingerprintKey` (see [Fingerprints](#fingerprints)); a POST without it (or with `null`) keeps the held key. Otherwise `400` with a message, and nothing is changed. |

Errors: `400` invalid input (including `invalid_request_id` and `invalid_ids`), `403` (`slot_not_allowed`, `name_not_allowed`, `booking_code`, `slot_not_managed`, `name_mismatch`), `404` not enabled / unknown lock / empty slot / `no_pending_change`, `409` lock codes unreadable (see below), `slot_changed` (retry), `fingerprint_key_missing`, `booking_region_overlap`, or for relocate `slot_occupied`, `code_not_copyable`, `change_pending` and `move_not_supported`, `502 send_error` (the lock driver threw on the first command of a set, delete, retry or relocate), `429` reveal, retry, fingerprint or write rate limit, or `too_many_pending`.

### Registered slots and full lock control

Added in 1.5.0 (signalbricks/platform#684). Both are optional fields of a lock's entry in `POST /lockConfig`. Without them the hub behaves exactly as 1.4.1, and older app versions ignore them (so a slot SignalBricks registered is simply refused there).

**Registered slots** let SignalBricks keep codes with custom names, without `SBA:`/`SBM:`. Each entry is `{slot, name, pendingName?}`:

- An occupied registered slot can be set, deleted, renamed, revealed and fingerprinted only while the lock shows `name` (or `pendingName`). A code that someone replaced on the lock under any other name, or with no name, is refused (`403 name_mismatch`) and never taken over. (Adopted slots are slot numbers only and don't have this check.)
- A set on a registered slot must use `name` or `pendingName` (`403 name_not_allowed` otherwise).
- An empty registered slot can be written only at or under the ceiling, and outside the admin and managed ranges only when the lock has `fullControl`. An empty slot above the ceiling is never written (that's where the booking apps pick free slots).
- **Booking region check (fail closed):** if the lock holds any booking-named code (the rule below) at or under the ceiling, the booking apps' *Minimum code position* reaches into SignalBricks' slots. Writes into empty registered slots outside the admin and managed ranges are then refused with `409 booking_region_overlap` until it is fixed: raise the booking apps' minimum code position above the ceiling (or lower the ceiling), and move the booking code out at its next booking.
- A new config that no longer accepts the name of a set or rename in flight on a registered slot (for example a rename SignalBricks cancelled) drops that change at once and reports it `failed` (`name_not_allowed`), so it is never re-sent. A command the lock already received can still land; SignalBricks waits for the final result before treating a rename as cancelled.
- A registered slot above the ceiling is reachable only on a lock with `fullControl` that the operator also chose under **Locks SignalBricks may fully control**, or once the operator approves it under **Approve these slots** like an adopted slot.
- Registered slots are renamed in place only (`relocate` without `toSlot`); a move gets `409 move_not_supported`.
- `name`: 1–64 printable ASCII characters, no leading or trailing spaces, and not a booking name (below). `pendingName` follows the same rules and also must not start with `SBA:`/`SBM:` (any case), and must differ from `name`. A slot can't be both `adopted` and `registered`, and the two lists together hold at most 250 slots per lock.

**Booking rule for these paths** (stricter than the general one): a name is a booking name if it contains `orb` followed by a digit in any case, an upper-case `ORB` at the start of a word, or starts with `G:` or `GT:` (any case, spaces allowed before the colon). Examples refused: `GT:ORB1-x`, `G: Guest -ORB4`, `orb4`, `Orb5 x`, `G: Gardener`, `gt : x`, `ORBIT`. Such names can't be registered, and a registered slot whose lock name turns into one is refused (`403 booking_code`) on every path. The SignalBricks platform applies the same rule.

**Rename handshake.** To rename a registered slot, SignalBricks first sends `{slot, name: <old>, pendingName: <new>}`. The hub then accepts either name in that slot, so whichever the lock holds after a crash or a dropped command, the slot is still SignalBricks'. It then renames it with `relocate`. Once a re-read shows the slot holding the code under the new name, the hub makes `pendingName` the registered name and stops accepting the old one. SignalBricks then sends `{slot, name: <new>}` itself. A config it sends in between that still carries both names is harmless.

**Full lock control** (`fullControl: true`) never makes an unregistered slot reachable. It only lets new codes go into empty registered slots outside the admin and managed ranges (at or under the ceiling), and, on locks the operator also chose in the app, reaches registered codes above the ceiling without approving each slot. To allow it on a lock: open the app, choose the lock under **Locks SignalBricks may fully control**, click **Done**. Turning lock code management off or **Disconnect** clears that choice.

### Verification and results

Added in 1.2.0 (signalbricks/platform#520). Z-Wave locks sometimes drop code changes silently, so the hub checks each one itself.

- **Request id.** `POST /codes` and `DELETE /codes/:slot` accept an optional `requestId` (a UUID, any case; stored lower-case). Without one, the hub generates a UUID. Either way it comes back in the `202` and in every result event for that change. Send a new id for each request.
- **One pending change per lock and slot.** A new set or delete for the same slot replaces the old one, and the old one is reported `superseded` (if it was still being verified). A request that is refused (`400`/`403`/`429`) leaves the existing change alone.
- **Driver error.** Since 1.4.1, if the lock driver throws on the first command of a set or delete, the request gets `502 send_error`, nothing is held (a change already held for the slot is left as it was and is not reported `superseded`), the write is given back to the limit, and the log records only the exception's class name, never its message, which could include the code. The same on the retry endpoint, except that the retried change is dropped with its code (reported `failed`, `send_error`, if it was still in progress) and the call is given back to the retry limit. If the command is sent but reporting the replaced change as `superseded` fails, the request still succeeds and the new change is held; the log records the class name only.
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

### Relocate

Added in 1.4.0 (#31). SignalBricks sometimes needs to change a code's name (for example `SBM: Chris` to `SBA: Chris` when its class changes) or move it into its class's range. Many codes were adopted, so SignalBricks has no copy of them, and `POST /codes` needs the code. `POST /locks/:id/codes/:slot/relocate` does it on the hub instead: the hub reads the code from the lock's own `lockCodes` and writes it back. The code is never in the request, the response, a log line or an event.

- **Request.** `{"name": "SBA: Chris", "toSlot": 3, "requestId": "<uuid>"}`. `name` is required. Without `toSlot` (or with `toSlot` equal to `:slot`) it is a **rename**: `setCode(slot, sameCode, name)`. Otherwise it is a **move** to `toSlot`. `requestId` works as for set and delete.
- **Source (`:slot`).** Must hold a code the hub would let SignalBricks delete: an `SBA:`/`SBM:` code in its range, or an allowed adopted slot (above the ceiling only once approved). Never a booking code (`403 booking_code`), never an unmarked code in the admin or managed range (`403 slot_not_managed`), never an empty slot (`404 slot_empty`). A driver that reports the code as a JSON number has lost any leading zeros, so such a code is refused (`409 code_not_copyable`) rather than written back wrong. So is a code that isn't 4 to 10 digits (what a set accepts).
- **Target and name.** The same rules as a set on the target slot (`resolveSet`): 1-64 printable ASCII characters, no `ORB` followed by a digit, `SBA:` in the admin range and `SBM:` in the managed range. In addition the new name must always start with `SBA:` or `SBM:`, adopted slots included (`403 name_not_allowed`). A move's `toSlot` must be at or under the ceiling (`403 slot_not_allowed`), at or under the lock's `maxCodes` (`400 invalid_slot`), allowed on this lock, and empty (`409 slot_occupied`).
- **Busy slots.** If a change is still being verified on either slot, the relocate gets `409 change_pending`. While a relocate is in progress, a set, delete or retry on either of its slots also gets `409 change_pending`. A failed change held for retry on either slot is replaced by the relocate.
- **Limits.** A relocate counts as one write against the 30-a-minute write limit shared with set and delete, and as one held change against the 100 cap (`429 rate_limited`, `429 too_many_pending`).
- **Driver error.** If the lock driver throws on the first command, the request gets `502 send_error`, nothing is held, the write is given back to the limit, and the log records only the exception's class name.
- **Move order.** The hub writes the target first. It deletes the source only after a re-read of `lockCodes` shows the target holding the new name **and** the same code (compared as strings, as for a set), and only while that same read shows the source still holding the code and name it had at request time. If the target doesn't confirm after 3 attempts, the source is left as it was and the move is reported `failed` (`not_applied`). So the code is never left in neither slot by the hub. The source delete is then verified the same way (up to 3 attempts), and every re-sent delete needs a fresh read showing the target still holding the code and the source still holding its request-time name and code (or already empty). Each phase settles within about 7.5 minutes.
- **Assumption.** Confirmation trusts `lockCodes`, which Hubitat lock drivers update from the lock's own reports (not when a command is merely sent). A driver that writes `lockCodes` optimistically on `setCode` could make a move delete the source before the lock really holds the target; check your drivers before relying on moves.
- **Approvals.** A rename keeps an approved adopted slot approved (approval is per slot). After a confirmed move out of an approved adopted slot above the ceiling, that slot's approval is withdrawn, so the now-empty slot can't be written without a new approval. Moves always land at or under the ceiling, where no approval is needed.
- **Duplicate codes.** Many residential locks refuse a code that is already in another slot. On those locks every move ends `failed` (`not_applied`) with the source untouched, while renames work. Try a move on each lock model in use before relying on it.

The outcome is sent like `lock_code_result`, with `name: "relocate_result"`, so SignalBricks receives event type `hubitat.relocate_result`:

```json
{"id": "relocate_result:<requestId>:<seq>:<result>", "deviceId": "<lockId>", "name": "relocate_result", "displayName": "<lock name>", "value": "confirmed", "data": "{\"lockId\":\"7\",\"slot\":3,\"fromSlot\":12,\"toSlot\":3,\"action\":\"relocate\",\"result\":\"confirmed\",\"attempts\":1,\"requestId\":\"<uuid>\",\"name\":\"SBA: Chris\",\"targetConfirmed\":true,\"fingerprint\":\"3f9a0c1d2e4b5a67\"}", "date": "<UTC ISO time>", "version": "1.4.0"}
```

`data` holds `{lockId, slot (= toSlot), fromSlot, toSlot, action: "relocate", result: "confirmed"|"failed", attempts, requestId, name, targetConfirmed, reason?, fingerprint?}`:

- `targetConfirmed` is `true` once the hub has seen the target hold the code under the new name. `confirmed` means the rename is done, or the move is done and the source is empty.
- `fingerprint` is the target code's [fingerprint](#fingerprints), included when `targetConfirmed` is `true` and the hub holds a fingerprint key.
- `reason` on `failed`: `not_applied` (the target never confirmed; source untouched), `target_name_mismatch` (the lock stored the code in the target under a different name; the source is kept, not re-sent), `slot_occupied` (the target took another code before the set confirmed), `source_not_deleted` (the target holds the code, but the source still does after 3 deletes: the code is in both slots), `source_changed` (the source holds a different code or name now, so it was not deleted), `target_changed` (the target lost the code before the source was deleted; the source delete is not re-sent), `slot_changed` (the source changed before the target confirmed), `lock_codes_unreadable`, `send_error`, a rule error such as `slot_not_allowed`, `lock_not_found`, `lock_management_disabled`, `disconnected` or `expired`.
- `attempts` counts the sends of the phase the move was in (the source deletes, once the target confirmed).
- **After any `failed` result, re-read `/slots` (or `/fingerprints`)**: depending on the reason and on what the lock did late, the code may be in the source, the target, or both. A failed relocate is never held for the retry endpoint: send a new relocate (it needs no code).
- After a move that ends `failed` once its source delete was sent, an approved adopted source found empty loses its approval, as after a confirmed move.

### Fingerprints

Added in 1.3.0 (signalbricks/platform#532), for the SignalBricks onboarding wizard: it groups slots holding the same code across locks without reading any code.

- **Key.** SignalBricks generates one 32-byte fingerprint key per organization and sends it in `POST /lockConfig` as `fingerprintKey`: standard base64 with padding (exactly 44 characters, e.g. Node's `buf.toString('base64')`). No surrounding whitespace is accepted. Anything else is refused with `400 invalid_config`, and neither the key nor the rules change. A later POST without the field keeps the held key; one with a new key replaces it. The key is write-only: `GET /lockConfig` and every other response only say `fingerprintKeySet: true|false`, and it is never logged. It is dropped when lock code management is turned off and on **Disconnect**; SignalBricks sends it again with the next `POST /lockConfig`. Like the rules, it sits in the app's state (visible to hub admins on App Status).
- **Fingerprint.** `fp` = HMAC-SHA256(key, code) as lower-case hex, first 16 characters (64 bits). The code is taken exactly as the lock driver reports it (a driver that reports a JSON number has already lost leading zeros). The same code gives the same `fp` on every lock and every hub with the same key, whatever its slot or name; different keys give unrelated values.
- **Which slots.** `GET /locks/:id/fingerprints` lists occupied slots that the hub would allow SignalBricks to manage (`allowedAs` non-null in `/slots`): on a lock listed in the config, within the admin or managed range under the ceiling, or an adopted slot (above the ceiling only once approved). Unmarked codes in those ranges are included, so they can be grouped before adoption. Booking-code names (the `ORB` guard), empty slots, slots above the ceiling that aren't approved adopted slots, and every slot of an unlisted lock are left out. Sorted by slot.
- **Survey-only locks** (below) return every occupied slot at or under the ceiling, except booking-code names.
- **Errors and limits.** `404` when lock code management is off or the lock isn't selected, `409 fingerprint_key_missing` before a key is set, `409 lock_codes_unreadable` as for `/slots`. Limited to 10 calls a minute across the app (`429 rate_limited`), separate from reveal, retry and write limits.
- **Batch.** `GET /fingerprints` returns the same for several locks in one call; see [Batch fingerprints](#batch-fingerprints).
- **Threat model.** Codes are 4 to 10 digits, so anyone holding the key can recover a code from its fingerprint by trying every candidate. SignalBricks holds the key (stored encrypted), and since signalbricks/platform#521 it is already trusted to store managed codes, so this is accepted. Without the key, a fingerprint reveals nothing beyond which slots share a code. Booking codes are never fingerprinted. On a survey-only lock, fingerprints cover every non-booking occupied slot up to the ceiling, so SignalBricks (holding the key) could recover any such code on a lock that hasn't been onboarded yet. SignalBricks uses a separate key per organization, so fingerprints from different organizations can't be compared. `javax.crypto.Mac` is on Hubitat's list of allowed imports.

### Batch fingerprints

Added in 1.3.3 (#17). `GET /locks/:id/fingerprints` is limited to 10 calls a minute, so reading a hub with more than 10 locks one lock at a time hits the limit. `GET /fingerprints` reads them all in one call:

- **Which locks.** Without `ids`, every lock selected for code management, in the order of that selection. With `?ids=7,12,30` (comma-separated lock ids, spaces around ids ignored, duplicates dropped, at most 100), only those, in the order given. An id that isn't selected for code management gets `{id, error: "lock_not_found"}` in the list instead of failing the call.
- **Which slots.** Each lock's `slots` are exactly what `/locks/:id/fingerprints` would return for it at that moment (same ceiling, ranges, adopted and approved slots, survey-only rule and booking-code guard; a selected lock not listed in the config returns `[]`). Never codes.
- **Per-lock errors.** A lock whose `lockCodes` can't be read or decrypted gets `{id, error: "lock_codes_unreadable", message}` (no `slots`); the other locks are still returned and the call is `200`.
- **Response.**

  ```json
  {"locks": [
    {"id": "7", "slots": [{"slot": 1, "fp": "3f9a0c1d2e4b5a67"}, {"slot": 12, "fp": "8c41e2a09b7d6f15"}]},
    {"id": "8", "error": "lock_codes_unreadable", "message": "lockCodes could not be read or decrypted"},
    {"id": "9", "slots": []}
  ]}
  ```
- **Errors for the whole call.** `404 not_found` when lock code management is off or the hub isn't connected, `400 invalid_ids` (an empty, non-numeric or longer-than-12-digit id, or more than 100 ids), `409 fingerprint_key_missing` before SignalBricks sends a key, `429 rate_limited`.
- **Limit.** 4 calls a minute across the app, however many locks each reads. This bucket is separate: batch calls don't use up the per-lock endpoint's 10 a minute, and the per-lock limit doesn't block batch calls.

### Survey-only locks

Added in 1.3.0 (signalbricks/platform#532). The onboarding wizard needs `/slots` and `/fingerprints` for locks that haven't been onboarded yet, but listing a lock normally lets SignalBricks write in its ranges. A `locks` entry of exactly `{"surveyOnly": true}` lists the lock for reading only:

- `GET /locks/:id/slots` works and reports `surveyOnly: true`, with `allowedAs: null` on every slot.
- `GET /locks/:id/fingerprints` (and its entry in `GET /fingerprints`) returns every occupied slot at or under the ceiling, whatever its name or marker, except booking-code names. Slots above the ceiling are left out (a survey-only lock has no adopted slots).
- Set, delete, relocate, reveal and retry are refused with `403 slot_not_allowed`, and the hub never sends a command to it: switching a lock to survey-only drops any change held for it (reporting `failed`, `slot_not_allowed`, if it was in progress), and verification never re-sends to it.
- `surveyOnly` must be a boolean. `true` can't be combined with `admin`, `managed` or `adopted` (`400`). `false` is the same as leaving it out.
- Onboarding replaces the entry with a normal one (`{}`, ranges and/or `adopted`), which allows writes as usual.

### Registration

After every Connect or Reconnect (once the access token has been rotated), when a Reconnect link expires unused, on every **Done**, on **Rotate access token and re-register**, and on **Disconnect**, the app POSTs to `<ingest URL>/register` (`https://ingest.signalbricks.co/v1/webhooks/hubitat/register`) with its ingestion token:

```json
{"cloudEndpointBase": "https://cloud.hubitat.com/api/<hubUID>/apps/<appId>", "appAccessToken": "<only while enabled, else null>", "hubUid": "<hubUID>", "appId": "<appId>", "version": "1.5.2", "hubFirmwareVersion": "<Hubitat platform version>", "lockManagement": true, "capabilities": ["relocate", "registered_slots", "full_control"]}
```

Since 1.4.0 `capabilities` lists the optional lock management features this version supports (`relocate`; since 1.5.0 also `registered_slots` and `full_control`), so SignalBricks only offers them on hubs that have them. Older versions don't send it.

Since 1.2.1 it does this only while lock code management is on, or to send a disable (`lockManagement: false`) when an earlier enable may still be stored. A hub that has never used lock code management doesn't register at all.

Since 1.3.1 the app remembers the version it last registered. Updating through Hubitat Package Manager replaces the code without running **Done**, so when the version differs the app re-registers on its own on the next event it sends (or the next code-change check), under the same conditions, at most every 30 minutes until SignalBricks accepts it. Registration and every event carry the app `version` and the hub's firmware version (`hubFirmwareVersion`, null when Hubitat doesn't report it), so SignalBricks shows both on its hub page for every connected hub, including hubs that don't use lock code management, and picks up a firmware update with the next event.

Since 1.3.2 the same check also runs once a day (see [Daily check-in](#updating)), so the version reaches SignalBricks within a day even on a hub that sends few events.

SignalBricks accepts it only with the ingestion token the Connect flow issued for this hub and app. It stores the endpoint and token encrypted against that hub, and never logs or returns them. The app sends tokens only to an `https://` ingest URL.

Hubs connected before lock code management existed (signalbricks/platform#505) hold a token without that binding. SignalBricks answers their enable with `403 TOKEN_NOT_BOUND_TO_APP`, and their disable with `200` and `reconnect_required: true`, storing nothing and leaving any existing registration as it is (signalbricks/platform#522). In either case the app shows **Reconnect to SignalBricks to enable lock management** and doesn't retry. Click **Reconnect to SignalBricks** and finish the sign-in; the app then registers lock code management with the new token.

Since 1.5.2 a registration that fails for a reason that may pass (no answer, `408` timeout, `429`, or `5xx`) is retried automatically after about 30 seconds, 2 minutes and 5 minutes, and the **Connection** section shows the retry. Other answers (`400`, `401`, `404`, reconnect required) aren't retried. **Done** starts a fresh registration.

### Encrypted lock codes

Some newer lock drivers can store `lockCodes` encrypted (a driver preference such as *Encrypt lock codes*). The app decrypts them with the hub's `decrypt()` when the platform provides it to apps. If it can't, `/slots`, `/reveal` and `/locks/:id/fingerprints` answer `409 lock_codes_unreadable` (`/fingerprints` reports it on that lock's entry): turn off code encryption in that lock's driver preferences, or leave that lock out of lock code management.

## Development

`tests/LockManagementHarness.groovy` exercises the lock-management handlers against a stubbed Hubitat sandbox, with fake locks and codes, `tests/RelocateHarness.groovy` the [relocate](#relocate) endpoint, `tests/FullControlHarness.groovy` [registered slots and full lock control](#registered-slots-and-full-lock-control), including booking codes on every new path, `tests/ConnectHarness.groovy` the Connect confirmation page for each outcome (headline and marker, inline CSS only, no-referrer, the back link, escaped text, no token, code or request parameter), and `tests/ReconnectHarness.groovy` the [Reconnect](#reconnect) button and its 10-minute link with lock code management on (click then callback, click then expiry, expiry racing the callback, the expiry surviving **Done**, registration retries, the slot rules, fingerprint key, approvals and pending code changes kept, and no token logged or shown outside the open link). They are not loaded by Hubitat or HPM. Run each from the repository root with Docker:

```bash
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
  groovy /w/tests/LockManagementHarness.groovy /w/app.groovy
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
  groovy /w/tests/RelocateHarness.groovy /w/app.groovy
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
  groovy /w/tests/FullControlHarness.groovy /w/app.groovy
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
  groovy /w/tests/ConnectHarness.groovy /w/app.groovy
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
  groovy /w/tests/ReconnectHarness.groovy /w/app.groovy
```

Each prints one `PASS`/`FAIL` line per case and exits non-zero on any failure. It checks behaviour in the stub only, not on a real hub. Pass `/w/beta/app.groovy` instead to test the [beta copy](#beta-channel); CI runs both.

To look at the Connect confirmation pages without connecting a hub, give `ConnectHarness` a writable directory as a second argument. It writes each outcome page there as `connected.html`, `failed.html`, `denied.html`, `missing.html` and `expired.html` (with fake values), to open in a browser on a phone-sized window and a desktop, in light and dark mode:

```bash
mkdir -p /tmp/sb-pages
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" -v /tmp/sb-pages:/out groovy:4.0-jdk17 \
  groovy /w/tests/ConnectHarness.groovy /w/app.groovy /out
```

## Privacy

- **By default, door codes (PINs) are never sent; only the code slot/name used.** The app never subscribes to `lockCodes`. Lock drivers include the code used in an unlock event's `data`. Before sending any event, the app removes every code/PIN field from `data` (keeping slot numbers and code names) and drops `data` entirely if it can't be parsed. `lastCodeName` is the *name* of the code that was used (for example, a guest or booking label), not the code.
- **Include door codes (optional, off by default, 1.3.5+).** When you turn it on, the `code` of lock events is sent to SignalBricks, which keeps only a keyed hash, and only if your organization's admin has also turned on hashing; otherwise SignalBricks removes it. Nothing else changes, and codes are never logged. See [Door codes in telemetry](#door-codes-in-telemetry-optional).
- **Telemetry is read-only.** With lock code management off (the default), the app sends no device commands and its only working endpoint is the one-time `/oauthCallback` used during Connect. With it on, the lock endpoints above work only for the separately selected locks and the slots the hub allows.
- **No shared secret.** Connect uses OAuth with PKCE (S256), so the app doesn't need to store a client secret. The hub's own app access token in the OAuth `state` is used only by Hubitat cloud to route the callback, and is never logged.
- The hub access token used during Connect is rotated immediately after the callback; the new token is then registered with SignalBricks (sent only while lock code management is on). A Reconnect link works for 10 minutes; if it is not used, the token in it is rotated then (1.5.2+).
- Events go only to the ingestion URL that SignalBricks returned during Connect. Nothing is sent until the hub is connected.

## Troubleshooting

- **"OAuth is not enabled for this app"**: enable OAuth for the app code (see Manual install, step 2).
- **"SignalBricks rejected the token"**: the token was revoked in the portal. Click **Reconnect to SignalBricks**, then **Continue to SignalBricks**, and sign in. Lock code management can stay on.
- **"Reconnect to SignalBricks to enable lock management"**: this hub was connected before lock code management existed, so its token can't enable it. Click **Reconnect to SignalBricks**, then **Continue to SignalBricks**, and sign in; lock code management can stay on. Telemetry keeps working meanwhile.
- **Registration error** under Lock code management: check the hub is connected, then click **Rotate access token and re-register**.
- **A code change failed** (`lock_code_result` with `result: "failed"`): check the lock is awake and in range of the hub, then retry from SignalBricks. Changes that failed with `not_applied` or `lock_codes_unreadable` can be retried without re-entering the code for 24 hours; other failures need a new request. `Code changes awaiting the lock` in the app shows how many are in progress or failed.
- **Slots answer `409 lock_codes_unreadable`**: see [Encrypted lock codes](#encrypted-lock-codes).
- Turn on **debug logging** to see each event as it is sent. Debug logging turns itself off after 30 minutes.

## License

MIT © 2025–2026 Spinrag, LLC
