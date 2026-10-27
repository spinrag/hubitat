# SignalBricks Integration

A Hubitat Elevation app that syncs **OwnerRez booking door codes** to your locks. The SignalBricks dispatcher pushes each booking (guest, code, check-in and checkout, and which locks) to the app, and the app adds the code to those locks before check-in and removes it after checkout.

It runs **alongside the OwnerRez app** on the same hub and locks, and alongside [SignalBricks Telemetry](../signalbricksTelemetry/README.md). The apps are independent and versioned separately; the app page shows the name with the version, e.g. *SignalBricks Integration 1.3.7*.

Originally based on the [HubitatCommunity OwnerRez](https://github.com/HubitatCommunity/OwnerRez) app, rebranded and substantially reworked.

## What it does

- **Code names.** Each booking code is named `<prefix>ORB<booking id>-<guest>`, e.g. `GT:ORB123456-Ann` (or `<prefix><guest>-ORB<booking id>` with **Booking ID at End?** on).
- **Timing.** A code is on the lock from before check-in to after checkout: the dispatcher sends the booking window and the app reconciles the lock codes at its start and end.
- **Which codes it manages.** The app adds, changes and removes codes whose name starts with its code name prefix and contains `ORB` followed by the booking id. It also treats any slot it assigned earlier as its own, by position, even if that slot was later reassigned outside the app (it tracks its assignments because some lock drivers don't keep code names). It writes new codes only into empty slots from its **Minimum Code Position** up. SignalBricks Telemetry never names its admin and managed codes (`SBA:` / `SBM:`) with `ORB` plus a digit, so the app doesn't claim them by name; keep Telemetry's slots below the app's minimum.
- **Retries.** Each code set is verified and re-sent until the lock confirms it; a reconcile loop catches missed confirmations.
- **Door code refresh.** Off by default. Reads the codes back from the locks one lock at a time, on a schedule you choose or from the app page (see [Door code refresh](#door-code-refresh)).
- **Bookings page.** Shows the bookings the app knows against its codes on the locks, with codes masked (see [Bookings page](#bookings-page)).
- **API.** A small access-token gated REST API (`/sync`, `/devices`, `/info`, …) used by the dispatcher. The dispatcher is not part of this repository.

## Running alongside the OwnerRez app

Both apps write `ORB<booking id>` codes. Each one recognises its own codes by its **code name prefix**, so:

- **Both prefixes must be set (non-empty) and different, and neither may be the start of the other.** With an empty prefix the app treats every `ORB` code on its locks as its own and can change or delete the other app's codes; the app page warns when the prefix is empty. `G:` and `GT:` are fine (`GT:` does not start with `G:`); `G` and `GT` are not.
- **Set each app's Minimum Code Position** above the slots SignalBricks Telemetry manages. A minimum is only a lower bound, not a separate range: each app uses the first empty slot from its minimum up to the lock's `maxCodes`, so once OwnerRez's slots 30–49 are full it also uses slots from 50 up, alongside this app. Where possible, size it so OwnerRez stays below this app's minimum (enough slots between the two minimums for OwnerRez's overlapping bookings).

Known-good setup:

| App | Code name prefix | Minimum Code Position |
| --- | --- | --- |
| OwnerRez (fork 1.1.4-cto-sb2) | `G:` | 30 |
| SignalBricks Integration | `GT:` | 50 |
| SignalBricks Telemetry | `SBA:` / `SBM:` | admin and managed slots 1–25 |

The app only uses slots from its **Minimum Code Position** up to the lock's reported `maxCodes`. A lock with fewer slots than the minimum, or a lock that doesn't report `maxCodes`, gets **no codes**, and the log shows `no free code position (>= <minimum>) on lock <id>`. Check the lock's `maxCodes` on its device page (run **Get Codes** on the lock if it is empty) and choose the minimum accordingly.

## Door code refresh

A door code refresh asks each lock for every code it holds (the lock's **Get Codes**), so the hub's copy of the codes is current. Over Z-Wave or Zigbee that walks every slot: a 250-slot lock takes many minutes. The app writes and reconciles its own codes, so it doesn't need a routine full read, and the refresh is **Off** by default.

The **Door code refresh** section of the app page has:

- **Scheduled refresh**: **Off** (default), **Weekly** (Tuesday) or **Daily**, at a time between 01:00 and 03:59 hub time picked at random once per hub. It applies when you click **Done**. The OwnerRez app's weekly refresh runs on Wednesday, so the two don't read the locks on the same night unless one of them is set to Daily.
- **Refresh now** and **Refresh door codes now**: start a refresh of all locks, or of one chosen lock, right away. The first lock starts about a minute later.
- Progress: which lock is being read (e.g. *running, lock 2 of 13 (Front Door)*), the schedule, and when the last refresh finished. Reload the page to update it. The Debug page shows the same.

Locks are always read one at a time. The next lock starts when the current one has reported its codes (counted only after 2.6 seconds per slot, at least 60 seconds, because large locks report codes while still reading; then 30 seconds to settle), or when its time budget runs out (3 seconds per slot, at least 2 and at most 20 minutes), whichever comes first. A refresh of 13 large locks can take a few hours. Starting a refresh while one is running does nothing. Booking syncs and reconciles carry on during a refresh.

Clicking **Done** never starts a refresh. Up to 1.3.4 the app refreshed every lock at midnight each night, starting a new lock every 90 seconds; on hubs with large locks the reads overlapped and Z-Wave timed out for hours. After updating to 1.3.5 that midnight schedule is removed when you open the app and click **Done**, or else the next time it fires (it then reads no lock).

## Bookings page

Added in 1.3.7. Open the app and click **Bookings** under **Diagnostics**. The page shows what the app knows and what is on the locks, from the hub's own state and each lock's last reported codes (it sends nothing and reads nothing from the locks; reload the page to update it, or run a [door code refresh](#door-code-refresh) first if a lock's codes may be out of date):

- **Summary:** `N bookings known · M codes on locks · K not on lock · J orphans`, and when the last booking sync arrived and whether it was a full sync (PUT) or a partial one (PATCH). The sync time is recorded from 1.3.7 on.
- **Known bookings:** one row per booking: booking id, guest, check-in and checkout in hub time, and whether it is current, upcoming or past. For each lock assigned to the booking, the slot that holds its code, matched the same way reconcile matches (its prefix + `ORB<booking id>`, or a slot the app assigned earlier), or **not on lock**. An upcoming booking is normally not on the lock yet; its code is added at check-in. A current booking that is not on a lock is the case to look at (try **Debug** → **Reconcile Door Codes**).
- **Orphans:** codes on the selected locks that carry the app's prefix and an `ORB` booking id (or sit in a slot it assigned) but match no known booking for that lock. The next reconcile removes them. Many orphans and few known bookings usually means the dispatcher's last full sync left bookings out.

Codes are shown masked, with only their last 2 digits (e.g. `••••12`), and are never logged. A lock whose codes the hub can't read (e.g. encrypted lock codes) is noted and not counted.

## Conflicts

- **Don't run Hubitat's Lock Code Manager on the same locks.** It manages codes as its own users and can rename or delete codes this app and the OwnerRez app wrote (observed on a production hub).

## Registration status and the outbound webhook

The app page shows **Status: Not registered**, and with debug or trace logging the log shows `webhook: No SignalBricks ID — skipping outbound POST`. Both are expected and harmless. The app is driven inbound: the SignalBricks dispatcher calls its endpoints (`/sync`, `/devices`, `/info`, …) with the app's access token. Registration would only enable an outbound webhook of lock events to a SignalBricks backend, which was never wired up. Lock events reach SignalBricks through the [SignalBricks Telemetry](../signalbricksTelemetry/README.md) app instead. Since 1.3.4 that log line is at trace level.

## Requirements

- Hubitat Elevation platform **2.5.0** or later
- The SignalBricks dispatcher configured with this hub's cloud URL and access token

## Install

### Hubitat Package Manager (new hubs)

1. Open **Hubitat Package Manager** on your hub.
2. Choose **Install** → **Search by Keywords** and search for `SignalBricks`.
3. Select **SignalBricks Integration** and follow the prompts. HPM enables OAuth for the app code.
4. In **Apps** → **Add User App**, choose **SignalBricks Integration**, select the locks, set the code name prefix and minimum code position (see [above](#running-alongside-the-ownerrez-app)), and click **Done**.

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

You don't need to open the app and click **Done** after an update; the existing schedules and subscriptions keep running with the new code. (After updating to 1.3.5, clicking **Done** removes the old midnight door code refresh right away; see [Door code refresh](#door-code-refresh).)

## Troubleshooting

- **No codes are generated for a booking.** Check the log for `no free code position`: the lock has no empty slot between **Minimum Code Position** and its `maxCodes`, or doesn't report `maxCodes`. Check `maxCodes` on the lock's device page and lower the minimum or free slots. Also check that the lock is selected in the app.
- **Codes vanished.** Don't use **Reset State** on the Debug page: it clears the app's bookings and code assignments, so the next reconcile removes this app's codes until the dispatcher syncs the bookings again.
- **A current (in-house) booking's code is missing.** Open **Debug** → **Reconcile Door Codes**. It re-adds codes for every booking the app holds that is active now, about a minute apart per code.
- **The OwnerRez app's codes are changed or removed.** Check that both apps have a non-empty code name prefix, that the prefixes differ, and that neither is the start of the other, and that Lock Code Manager isn't running on the lock (see [Conflicts](#conflicts)).
- **Z-Wave is slow or locks time out after midnight** (hubs with many large locks, 1.3.4 or earlier). Update to 1.3.6 or later and click **Done** in the app; check that **Scheduled refresh** is **Off** or Weekly (see [Door code refresh](#door-code-refresh)).
- **Status: Not registered** / `skipping outbound POST` in the log: expected, see [Registration status](#registration-status-and-the-outbound-webhook).

## Development

`tests/IntegrationHarness.groovy` loads the app in a stubbed Hubitat sandbox with fake locks and codes, and checks the name and version display, code naming, which codes the app claims, and which slots a reconcile sets and deletes. `tests/RefreshHarness.groovy` checks the door code refresh: Off by default and after an upgrade, the old midnight job removed, Done not refreshing, the Weekly/Daily times, the one-lock-at-a-time queue, the manual button and progress, re-arming after a booking sync, and that no code is logged. `tests/BookingsHarness.groovy` checks the Bookings page: it renders with no state (as when the hub saves the code), the on lock, not on lock and orphan statuses, the summary and last sync, and that codes are masked and never rendered in full or logged. None is loaded by Hubitat or HPM. Run them from the repository root with Docker:

```bash
for h in IntegrationHarness RefreshHarness BookingsHarness; do
  docker run --rm --network none -v "$PWD/signalbricksIntegration:/w:ro" groovy:4.0-jdk17 \
    groovy /w/tests/$h.groovy /w/app.groovy
done
```

Each prints one `PASS`/`FAIL` line per case and exits non-zero on any failure. CI runs it on every pull request.

## License

MIT © 2025–2026 Spinrag, LLC
