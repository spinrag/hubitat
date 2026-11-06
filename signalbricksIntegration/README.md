# SignalBricks Integration

A Hubitat Elevation app that syncs **OwnerRez booking door codes** to your locks. The SignalBricks dispatcher sends each booking (guest, code, check-in and checkout, and which locks) to the app, and the app adds the code to those locks before check-in and removes it after checkout.

It can share the hub and locks with other lock-code apps that use their own code name prefix, and runs alongside [SignalBricks Telemetry](../signalbricksTelemetry/README.md). The apps are independent and versioned separately; the app page shows the name with the version, e.g. *SignalBricks Integration 1.4.14*.

Originally based on the [HubitatCommunity OwnerRez](https://github.com/HubitatCommunity/OwnerRez) app, rebranded and substantially reworked.

## What it does

- **Code names.** Each booking code is named with the app's code name prefix, the booking id and the guest name (or with the booking id at the end, with **Booking ID at End?** on).
- **Timing.** A code is on the lock from before check-in to after checkout.
- **Which codes it manages.** Only codes named with its own prefix (or its optional previous prefix, see [Changing the code name prefix](#changing-the-code-name-prefix)), and slots it assigned itself. It writes new codes only into empty slots from its **Minimum Code Position** up, and leaves every other code on the lock alone.
- **Retries.** Each code write is re-sent until the lock confirms it, and a regular reconcile catches anything missed.
- **Door code refresh.** Off by default. Reads the codes back from the locks one lock at a time, on a schedule you choose or from the app page (see [Door code refresh](#door-code-refresh)).
- **Bookings page.** Shows the bookings the app knows against its codes on the locks, with codes masked (see [Bookings page](#bookings-page)).
- **Connect to SignalBricks.** Since 1.4.0 the app connects itself to SignalBricks with OAuth, so you don't copy URLs or tokens (see [Connect to SignalBricks](#connect-to-signalbricks)).
- **App info for SignalBricks.** Since 1.4.6 the app tells SignalBricks Telemetry on the same hub its version and slot settings, for the SignalBricks hub page (see [App info for SignalBricks](#app-info-for-signalbricks)).
- **API.** A small access-token gated REST API used by the dispatcher. The dispatcher is not part of this repository.

## Running alongside other lock-code apps

Other apps on the hub may also put codes on the same locks. To keep them apart:

- **Give each app its own code name prefix.** Prefixes must be set (non-empty), different, and neither may be the start of the other. With an empty prefix this app can change or delete another app's codes; the app page warns when the prefix is empty.
- **Give each app its own range of slots.** Set **Minimum Code Position** above the slots SignalBricks Telemetry manages, and leave room below it for any other booking app. A minimum is a lower bound: the app uses the first empty slot from there up to the lock's `maxCodes`.

A lock with fewer slots than the minimum, or one that doesn't report `maxCodes`, gets **no codes**, and the log says there is no free code position. Check the lock's `maxCodes` on its device page (run **Get Codes** on the lock if it is empty) and choose the minimum accordingly.

## Changing the code name prefix

Codes already on the locks keep their name until their booking ends; only codes written later use the new prefix.

1. Make sure no other app on the hub uses the new prefix. Never give this app a prefix, or a previous prefix, that another app still uses.
2. Set **Prefix for code name** to the new prefix and **Previous prefix for code name** to the old one, and click **Done**. The app page says if it can't use the previous prefix.
3. Sync the bookings again from SignalBricks.
4. Check the [Bookings page](#bookings-page): no current booking should be *not on lock*.
5. Once the bookings that were on the locks at the change have checked out, run **Refresh door codes now** and check that the Bookings page shows no code with the old prefix and no orphans. Only then clear **Previous prefix for code name**.

## Connect to SignalBricks

Added in 1.4.0.

1. Open the app. Under **Connection**, click **Connect to SignalBricks**.
2. Sign in to SignalBricks with an account that has the **admin** role in the organization that owns the property.
3. Choose the organization, the property and the hub, confirm it is the same physical hub, and click **Connect**.
4. A confirmation page from the hub shows the outcome and what to do next. Close that window (browsers often block its **Close this window** button) and reopen the app, which is still open in your other tab. After a few seconds its **Status** line shows **Connected to** *organization → hub*. If registration fails for a reason that may pass, the app retries automatically and the page shows the retries.

The hub needs Hubitat cloud access while it connects.

- **Connect, Cancel and Reconnect replace the app's access token.** If a dispatcher still reads this hub's token from a config file, it needs the new token from the **Connection** section. Connect only once the dispatcher reads hub tokens from SignalBricks, or be ready to update the file.
- **Reconnect to SignalBricks** runs the same steps again. Its sign-in link works for 10 minutes; whether it is used or not, the app then replaces its access token and registers the new one. Bookings and code assignments are kept.
- **Don't share the app page's links**, or screenshots or saved copies of the page, with anyone you wouldn't give the hub's admin login to.
- **Disconnect from SignalBricks** tells SignalBricks to forget the token and replaces it on the hub. If SignalBricks can't be reached, the app still disconnects itself and says so: then also click **Disconnect** on the hub's SignalBricks hub page.
- **Debug → Refresh Access Token** replaces the token and registers the new one with SignalBricks.
- A hub can be connected in only one SignalBricks organization at a time. To move it, disconnect it in the first organization.

## Door code refresh

A door code refresh asks each lock for every code it holds (the lock's **Get Codes**). Over Z-Wave or Zigbee that is slow on large locks, so the refresh is **Off** by default.

The **Door code refresh** section of the app page has:

- **Scheduled refresh**: **Off** (default), **Weekly** (Tuesday) or **Daily**, at a random time between 01:00 and 03:59 hub time. It applies when you click **Done**.
- **Refresh now** and **Refresh door codes now**: start a refresh of all locks, or of one chosen lock, right away.
- Progress: which lock is being read, the schedule, and when the last refresh finished. Reload the page to update it.

Locks are always read one at a time; a refresh of many large locks can take a few hours. Booking syncs and reconciles carry on during a refresh.

Since 1.4.10 the app waits until it has read a lock's codes before adding new codes to a lock that reports none (for example after a driver change or a re-pair). It reads such a lock itself; while it waits, the **Door code refresh** section shows *Waiting* for that lock. See [Troubleshooting](#troubleshooting) if it keeps waiting.

Since 1.4.11 the app also reads locks whose driver encrypts its code list. A lock whose codes can't be read at all is shown as *Waiting* with the reason, and the app neither adds nor removes codes on it until it can be read (see [Troubleshooting](#troubleshooting)).

## App info for SignalBricks

Since 1.4.6 the app announces its version and slot settings (minimum code position and prefixes) on the hub, so SignalBricks can show which version runs and warn when its slots overlap the ones SignalBricks Telemetry manages. The announcement stays on the hub: only [SignalBricks Telemetry](../signalbricksTelemetry/README.md#daily-hub-report) 1.6.0 or later picks it up for its daily report. It carries settings only: no codes, code names, tokens, booking ids or guest names. It is sent on install, when you click **Done**, and once a day.

## Bookings page

Added in 1.3.7. Open the app and click **Bookings** under **Diagnostics**. It shows what the app knows and what is on the locks, from the hub's own state (reload the page to update it, or run a [door code refresh](#door-code-refresh) first if a lock's codes may be out of date):

- **Summary:** the number of bookings known, codes on locks, current bookings not on a lock, and orphans, and when the last booking sync arrived.
- **Known bookings:** each booking with its guest, dates and masked code, and for each lock the slot that holds its code or *not on lock*. A current booking that is not on a lock is shown in red: try **Debug** → **Reconcile Door Codes**.
- **Notes about other copies:** codes that look like a copy of a booking's code but that the app doesn't manage, and slots whose name *names two bookings*. The app leaves these alone: delete them by hand on the lock's device page if they aren't needed.
- **Orphans:** codes with the app's prefix that match no known booking for that lock. The next reconcile removes them.

Codes are shown masked, with only their last 2 digits, and no log line carries a door code or the access token.

## Conflicts

- **Don't run Hubitat's Lock Code Manager on the same locks.** It can rename or delete codes this app and other apps wrote.

## The old registration and the outbound webhook

With trace logging the log may show `webhook: No SignalBricks ID — skipping outbound POST`. That is expected and harmless: it belongs to an old outbound registration that was never used. Lock events reach SignalBricks through the [SignalBricks Telemetry](../signalbricksTelemetry/README.md) app instead.

## Requirements

- Hubitat Elevation platform **2.5.0** or later
- A SignalBricks account with the **admin** role to [connect the app](#connect-to-signalbricks)
- OAuth enabled for the app code (HPM does this; for a manual install see below)

## Install

### Hubitat Package Manager (new hubs)

1. Open **Hubitat Package Manager** on your hub.
2. Choose **Install** → **Search by Keywords** and search for `SignalBricks`.
3. Select **SignalBricks Integration** and follow the prompts. HPM enables OAuth for the app code.
4. In **Apps** → **Add User App**, choose **SignalBricks Integration**, select the locks, set the code name prefix and minimum code position (see [above](#running-alongside-other-lock-code-apps)), and click **Done**.
5. Open the app again and click **Connect to SignalBricks** (see [Connect to SignalBricks](#connect-to-signalbricks)).

### Hubs that installed it by hand: HPM Match Up

If the app code was pasted into **Apps Code** by hand, **don't Install it again through HPM** (that would add a second copy of the code). Let HPM adopt the existing code instead:

1. Open **Hubitat Package Manager** → **Match Up**.
2. Turn **Fast Match** off (the local match reads this repository directly) and click **Next**.
3. Select **SignalBricks Integration** in the list of matches. HPM matches it by app name and namespace (`SignalBricks Integration`, `com.signalbricks.hubitat`), so don't rename the app code.
4. Leave **Assume that packages are up-to-date?** off, so the next HPM update installs the current release, and click **Next** / **Done**.
5. Run **Update** in HPM (or wait for the automatic update, below). The app page then shows *SignalBricks Integration 1.3.4* or later.

Match Up and updates replace only the code: the app's settings, bookings, scheduled reconciles and code assignments stay as they are.

### Manual

1. In **Apps Code**, click **New App**, paste [`app.groovy`](app.groovy), and click **Save**.
2. Click **OAuth** → **Enable OAuth in App** → **Update**.
3. In **Apps** → **Add User App**, choose **SignalBricks Integration** and configure it as above.

## Updating

Use Hubitat Package Manager (HPM). Set this up once on each hub:

1. Open **Hubitat Package Manager** → **Package Manager Settings**.
2. Set **Install updates automatically when** to **Only those I list**, then add **SignalBricks Integration** under **Which packages should be automatically updated?** (and **SignalBricks Telemetry**, if the hub runs it).
3. Turn on **Notify me if automatic updates are unsuccessful**, pick a notification device, and turn on **Include hub name in notifications**.
4. If **Hub Login Security** is on, enter the hub's username and password under **Hub Security** in HPM settings. If the hub has HTTPS turned on, also turn on **SSL (HTTPS) Enabled**.
5. Set the update check time outside 2–4am, when the hub runs nightly maintenance and backups.

You don't need to open the app and click **Done** after an update; the existing schedules keep running with the new code.

**After updating to 1.4.13 or 1.4.14:** open the **Bookings** page and, on the lock's device page, delete by hand any codes it lists as copies of a booking's code and any slot it says *names two bookings*.

**After updating to 1.4.15:** when SignalBricks no longer accepts the hub's connection, the app replaces its access token and shows **Connect to SignalBricks** again.

## Troubleshooting

- **No codes are generated for a booking.** Check the log for `no free code position`: the lock has no empty slot between **Minimum Code Position** and its `maxCodes`. Lower the minimum or free slots, and check that the lock is selected in the app.
- **Codes vanished.** Don't use **Reset State** on the Debug page: it clears the app's bookings and code assignments, so the app's codes are removed until the dispatcher syncs the bookings again.
- **A current booking's code is missing.** Check the app page for *Waiting*. Otherwise open **Debug** → **Reconcile Door Codes**, which also rewrites codes on locks that hide them.
- **A lock stays on *Waiting*.** The lock didn't answer its code reads (offline, out of range, low battery) or its driver doesn't report codes. Check the lock is online and that **Get Codes** on its device page fills in **lockCodes**. The app tries again on its own every few hours.
- **A lock shows *its lockCodes can't be read*.** The app leaves that lock alone until it can read it, so it neither adds nor removes codes there: **remove a past guest's code by hand** on the lock's device page if needed. Run **Get Codes**, and if **lockCodes** still isn't readable, check the lock's driver.
- **Another app's codes are changed or removed.** Check that each app has its own non-empty code name prefix and that neither is the start of the other, and that Lock Code Manager isn't running on the lock (see [Conflicts](#conflicts)).
- **Z-Wave is slow or locks time out after midnight** (1.3.4 or earlier). Update and click **Done** in the app; check that **Scheduled refresh** is **Off** or Weekly.
- **Connect fails with "connected to SignalBricks in another organization".** An admin in that organization must disconnect the hub (hub page → **Disconnect**) first.
- **Last connect error: SignalBricks could not verify this hub.** Check the hub's cloud connection and click **Done** to try again, or **Reconnect to SignalBricks**.
- **Last connect error: Registration failed.** Click **Done** to retry, or **Reconnect to SignalBricks**.
- **"SignalBricks no longer accepts this hub's connection".** Click **Connect to SignalBricks** in the app to connect the hub again.

## Development

The harnesses in `tests/` load the app in a stubbed Hubitat sandbox with fake locks, codes and tokens, and check its behaviour; none is loaded by Hubitat or HPM. Run them from the repository root with Docker:

```bash
for h in IntegrationHarness RefreshHarness BookingsHarness ConnectHarness ReconnectHarness LogsHarness AppInfoHarness; do
  docker run --rm --network none -v "$PWD/signalbricksIntegration:/w:ro" groovy:4.0-jdk17 \
    groovy /w/tests/$h.groovy /w/app.groovy
done
```

Each prints one `PASS`/`FAIL` line per case and exits non-zero on any failure. CI runs them on every pull request. See [CONTRIBUTING.md](../CONTRIBUTING.md) before opening an issue or pull request.

## License

MIT © 2025–2026 Spinrag, LLC
