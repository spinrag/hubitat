# SignalBricks Telemetry

A Hubitat Elevation app that sends read-only device telemetry to [SignalBricks](https://app.signalbricks.co): lock state, contact sensors, temperature, humidity, and battery levels.

It runs alongside the [**SignalBricks Integration**](../signalbricksIntegration/README.md) app (booking door codes). The two apps are independent and versioned separately; each shows its own name next to its version. Telemetry never controls devices unless you turn on the optional [lock code management](#lock-code-management-optional).

Highlights by version:

- **1.1.0** optional lock code management (off by default) for admin and managed codes on locks you select.
- **1.2.0** the hub verifies each code change, retries it if the lock drops it, and reports the outcome to SignalBricks.
- **1.3.0** code fingerprints, so SignalBricks can group slots that hold the same code across locks without reading the codes.
- **1.3.1 / 1.3.2** re-registers by itself after an update, and checks in daily, so SignalBricks shows the new version within a day.
- **1.3.5** optional, off-by-default [Include door codes (hashed by SignalBricks)](#door-codes-in-telemetry-optional).
- **1.4.0** SignalBricks can rename or move a code on the hub without ever having the code.
- **1.5.0** custom code names and full lock control, per lock and opt-in on the hub.
- **1.6.0** a [daily hub report](#daily-hub-report): app versions and slot settings, hub firmware and uptime, and lock health, counts only.

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
3. Click **Connect to SignalBricks**. Sign in, then choose the **organization**, **property**, and **hub**. If this hub was connected before, it is preselected. You can also create a new hub entry, and a property if the organization has none yet.
   - If you work for a management company, choose the **property owner's organization**. Hubs belong to the owner, not the management company.
4. After you approve, a confirmation page from the hub shows the outcome and what to do next. Close that window (browsers often block its **Close this window** button) and return to the app, which is still open in your other tab. It shows **Status: Connected**, or the reason under **Last error**.

**Disconnect** stops sending right away. To revoke the token on the SignalBricks side as well, remove it under **Tokens** in the portal.

### Reconnect

**Reconnect to SignalBricks** works whether or not lock code management is on. Click it and sign in, as for Connect. The sign-in link works for 10 minutes; whether it is used or not, the app then replaces its access token and registers the new one. Lock code management stays on and its settings are kept.

To move the hub to another property or organization while lock code management is on, turn **Enable lock code management** off first, then Reconnect. SignalBricks tells you when this is needed.

**Don't share the app page's links**, or screenshots or saved copies of the page, with anyone you wouldn't give the hub's admin login to.

## Updating

Use Hubitat Package Manager (HPM) to keep the app up to date. Set this up once on each hub:

1. Open **Hubitat Package Manager** → **Package Manager Settings**.
2. Set **Install updates automatically when** to **Only those I list**, then add **SignalBricks Telemetry** under **Which packages should be automatically updated?** Other packages on the hub keep updating the way you choose.
3. Turn on **Notify me if automatic updates are unsuccessful**, pick a notification device, and turn on **Include hub name in notifications**, so you can tell which hub needs attention when you run several.
4. If **Hub Login Security** is on (Settings → Hub Login Security), enter the hub's username and password under **Hub Security** in HPM settings. HPM needs them to install the new code; without them updates fail.
   If the hub has HTTPS turned on, also turn on **SSL (HTTPS) Enabled** in HPM settings.
5. Set the update check time away from 3am: the hub runs its nightly maintenance and backups then. Any time outside 2–4am works.

**You don't need to open the app and click Done after an update.** The app notices the new version and re-registers with SignalBricks by itself. Open the app and click **Done** only when a release adds a new setting or permission you want to use (the release notes say so).

**Daily check-in.** Once a day, at a random time per hub outside 2–4am, the app re-registers if it was updated and sends the [daily hub report](#daily-hub-report). The app page shows the time under **Status**.

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

Each event goes over HTTPS to the SignalBricks ingestion API with the hub's ingestion token, and includes the device id and name, the attribute and value, the unit, the description text, the event time, the app version and the hub firmware version.

### Door codes in telemetry (optional)

Added in 1.3.5. **Include door codes (hashed by SignalBricks)** is off by default. Both of these must be on before SignalBricks keeps anything about a code:

1. **Hub:** this setting, in the app's *Door codes in telemetry (optional)* section.
2. **SignalBricks:** an organization admin turns on *Hash door codes from Hubitat telemetry* in the portal's door code settings.

| This setting | SignalBricks policy | What SignalBricks keeps |
|---|---|---|
| off (default) | either | no code: the app removes it before sending |
| on | off | no code: SignalBricks removes it on arrival |
| on | on | a keyed hash of the code, never the code |

With the setting on, only the code used in lock and unlock events is sent; every other event still carries no code, and codes are never logged.

## Daily hub report

Since 1.6.0 the app sends SignalBricks a short report about the hub once a day, when you click **Done**, after **Connect** or **Reconnect**, and shortly after a booking app on the hub announces a new version or settings. The hub's page in SignalBricks then shows:

- the versions and slot settings of SignalBricks Telemetry and of booking apps on the hub that announce themselves;
- hub firmware, uptime and time zone;
- for each selected lock: battery, lock state, jammed, capacity, driver and the **number** of occupied slots.

It never contains codes, code names or guest names. SignalBricks raises alerts from it, for example when a booking app's slots overlap the ones SignalBricks manages, a lock is jammed or low on battery, or the hub stops reporting. A report that fails is retried a few times, and the **Connection** section shows the error.

## Lock code management (optional)

Off by default. When enabled, SignalBricks can list each selected lock's code slots (slot number, name, set or empty, never the code), set and delete **admin** (`SBA: …`) and **managed** (`SBM: …`) codes, and reveal one code at a time for an authorized user. Booking codes are never touched.

### Enable it

Already-connected hubs need only an app update:

1. Update **SignalBricks Telemetry** to the latest version (HPM **Update**).
2. Open the app. Under **Lock code management (optional)**, turn on **Enable lock code management**.
3. Select the locks SignalBricks may manage codes on. This list is separate from the telemetry locks.
4. Check **Highest slot SignalBricks may manage** (default 25). Keep it below every booking app's **Minimum code position**.
5. Click **Done**. Reopen the app and check **Registration: registered …**.
6. Nothing can be written yet: SignalBricks first sends the slot rules during lock onboarding in the portal. If it asks to manage existing codes above the ceiling, they are listed in the app under **Approve these slots**. Approve only codes you recognise as admin or managed codes.
7. If SignalBricks asks to replace its fingerprint key (rare), the app shows **Approve the new fingerprint key** and **Reject the new fingerprint key**. Approve only if your SignalBricks admin expects it; otherwise reject it and click **Rotate access token and re-register**.

New hubs: install, **Connect to SignalBricks** first, then follow steps 2 to 5.

To turn it off, switch off **Enable lock code management**. This takes effect immediately, even before you click **Done**, and even when SignalBricks can't be reached: the app replaces its access token and clears its lock code management settings. **Disconnect** does the same.

### What the hub enforces

The hub decides which slots may be touched, using the settings you choose in the app:

- SignalBricks can only reach the locks you selected for lock code management, and on each of them only the slots its onboarding set up, at or below **Highest slot SignalBricks may manage**.
- Existing codes above that ceiling stay out of reach until you approve them under **Approve these slots**.
- A new fingerprint key from SignalBricks is used only once you approve it in the app (1.6.3).
- **Locks SignalBricks may fully control** is a separate, per-lock opt-in in the app.
- Booking codes are always refused.
- Codes are never logged, and never sent as telemetry or in results.

### Locks with encrypted codes

Some lock drivers can store their code list encrypted. The app decrypts it when the hub allows. If it can't, lock code management reports that lock's codes as unreadable: turn off code encryption in that lock's driver preferences, or leave that lock out of lock code management.

## Development

The harnesses in `tests/` exercise the app against a stubbed Hubitat sandbox with fake locks, codes and tokens; they are not loaded by Hubitat or HPM. Run each from the repository root with Docker:

```bash
for h in LockManagementHarness RelocateHarness FullControlHarness ConnectHarness ReconnectHarness HubReportHarness; do
  docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" groovy:4.0-jdk17 \
    groovy /w/tests/$h.groovy /w/app.groovy
done
```

Each prints one `PASS`/`FAIL` line per case and exits non-zero on any failure. It checks behaviour in the stub only, not on a real hub. Pass `/w/beta/app.groovy` instead to test the [beta copy](#beta-channel); CI runs both.

To look at the Connect confirmation pages without connecting a hub, give `ConnectHarness` a writable directory as a second argument. It writes each outcome page there (with fake values), to open in a browser on a phone-sized window and a desktop, in light and dark mode:

```bash
mkdir -p /tmp/sb-pages
docker run --rm --network none -v "$PWD/signalbricksTelemetry:/w:ro" -v /tmp/sb-pages:/out groovy:4.0-jdk17 \
  groovy /w/tests/ConnectHarness.groovy /w/app.groovy /out
```

See [CONTRIBUTING.md](../CONTRIBUTING.md) before opening an issue or pull request.

## Privacy

- **By default, door codes (PINs) are never sent**, only the name of the code used. The app removes every code field from events before sending them, and the [daily hub report](#daily-hub-report) only counts occupied slots.
- **Include door codes (optional, off by default).** When you and your organization's admin both turn it on, SignalBricks keeps only a keyed hash of the code used. See [Door codes in telemetry](#door-codes-in-telemetry-optional).
- **Telemetry is read-only** unless lock code management is on, and then only for the locks and slots you allow.
- **No shared secret.** Connect uses OAuth with PKCE, so the app stores no client secret. The hub's access token is replaced after every Connect and Reconnect.
- Events go only to the SignalBricks ingestion address returned during Connect. Nothing is sent until the hub is connected.

## Troubleshooting

- **"OAuth is not enabled for this app"**: enable OAuth for the app code (see Manual install, step 2).
- **"SignalBricks rejected the token"**: the token was revoked in the portal. Click **Reconnect to SignalBricks** and sign in. Lock code management can stay on.
- **"Reconnect to SignalBricks to enable lock management"**: click **Reconnect to SignalBricks** and sign in; lock code management can stay on. Telemetry keeps working meanwhile.
- **Registration error** under Lock code management: check the hub is connected, then click **Rotate access token and re-register**.
- **A code change failed**: check the lock is awake and in range of the hub, then retry from SignalBricks. `Code changes awaiting the lock` in the app shows how many are in progress or failed.
- **A lock's codes are unreadable**: see [Locks with encrypted codes](#locks-with-encrypted-codes).
- Turn on **debug logging** to see each event as it is sent. Debug logging turns itself off after 30 minutes.

## License

MIT © 2025–2026 Spinrag, LLC
