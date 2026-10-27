// Groovy scripts do not have constants, so we use functions instead
// Functions that start with get can be referenced as variables,
// i.e. "getFunctionName" can be referenced as "functionName"
String getSbBaseSecureUrl() { 'https://app.signalbricks.com' } // TODO: update to SignalBricks backend URL
String getSbBaseFastUrl() { 'https://api.signalbricks.com' } // TODO: update to SignalBricks backend URL
String getSbAppVersion() { '1.3.5' } // major.minor.patch[-prerelease]
String getSbAppName() { 'SignalBricks Integration' }
// Shown on the app page and in logs, e.g. "SignalBricks Integration 1.3.4" (#20).
// API responses and headers keep the bare version.
String getSbAppNameAndVersion() { sbAppName + ' ' + sbAppVersion }

import groovy.json.JsonOutput

// Define the app
definition(
    name: 'SignalBricks Integration',
    namespace: 'com.signalbricks.hubitat',
    author: 'SignalBricks',
    description: 'Syncs OwnerRez booking door codes from the SignalBricks dispatcher to your locks. Runs alongside the OwnerRez app; give each app a different code name prefix.',
    category: 'Convenience', // Unused
    oauth: true, // Unused
    iconUrl: '', // Unused
    iconX2Url: '', // Unused
    singleInstance: true,
    singleThreaded: true,
)

// Define the App's preferences (i.e. the settings UI)
preferences {
    page(name: 'main', install: true, uninstall: true) {
        section() {
            paragraph '<b>' + sbAppNameAndVersion + '</b>'
            paragraph 'Syncs OwnerRez booking door codes from the SignalBricks dispatcher to the locks selected below. ' +
                'Each code is named <code>&lt;prefix&gt;ORB&lt;booking id&gt;-&lt;guest&gt;</code> and is on the lock from before check-in to after checkout. ' +
                'It manages codes named with its prefix + <code>ORB&lt;booking id&gt;</code>, and any slot it assigned earlier ' +
                '(it can treat such a slot as its own by position even if the slot was reassigned outside the app). ' +
                'It writes new codes only into empty slots from the minimum code position up. ' +
                'Do not run Hubitat Lock Code Manager on the same locks.'
            paragraph 'Runs alongside the OwnerRez app. Give the two apps <b>different, non-empty</b> code name prefixes, neither the start of the other ' +
                '(e.g. OwnerRez <code>G:</code>, SignalBricks Integration <code>GT:</code>) and minimum code positions above the Telemetry slots ' +
                "(e.g. OwnerRez 30, this app 50; a minimum is a lower bound, not a separate range). <a href='https://github.com/spinrag/hubitat/blob/main/signalbricksIntegration/README.md' target='_blank'>Documentation</a>"
        }

        // We can only access the locks the user has selected
        section(title: '<h2>Configuration</h2>') {
            input(name: 'locks', type: 'capability.lockCodes', title: 'Locks', submitOnChange: true, multiple: true)
            // enhancements by CTO
            input(name: 'minCodePosition', type: 'number', title: 'Minimum Code Position', submitOnChange: true, defaultValue: 0)
            input(name: 'idAtEnd', type: 'bool', title: 'Booking ID at End?', submitOnChange: true)
            input(name: 'codeNamePrefix', type: 'string', title: 'Prefix for code name', submitOnChange: true, defaultValue: '')
            if (!settings?.codeNamePrefix) {
                paragraph '<b>Set a code name prefix</b> (e.g. <code>GT:</code>) that differs from the OwnerRez app\'s prefix. Without one this app treats every <code>ORB</code> booking code on the selected locks as its own.'
            }
        }

        // #24: re-reading every slot over Z-Wave is slow, so it is one lock at a time and Off by default
        section(title: 'Door code refresh') {
            paragraph 'Re-reads all door codes from the locks, one lock at a time, so the hub has the current codes. ' +
                'The app writes and reconciles its own codes, so a routine full read is not needed. ' +
                'A large lock can take several minutes; a refresh of many locks can take hours.'
            input(name: 'codeRefreshCadence', type: 'enum', title: 'Scheduled refresh (applies when you click Done)', submitOnChange: true, defaultValue: 'off',
                options: [off: 'Off', weekly: 'Weekly (Tuesday, 01:00-03:59 hub time)', daily: 'Daily (01:00-03:59 hub time)'])
            input(name: 'codeRefreshLock', type: 'enum', title: 'Refresh now', submitOnChange: true, defaultValue: 'all', options: codeRefreshLockOptions())
            input(name: 'btnRefreshCodes', type: 'button', title: 'Refresh door codes now')
            codeRefreshStatus().each { paragraph it }
        }

        section(title: 'Connection') {
            if (settings) { // Prevents this code from executing during app install, as it will error
                if (!settings.locks || settings.locks.length == 0) {
                    paragraph 'Please configure the locks before connecting to SignalBricks.'
                }
                else if (!state.accessToken) {
                    paragraph 'Please click "Done" to complete setup before connecting to SignalBricks.'
                }
                else {
                    // Always show endpoint and access token for CLI configuration
                    paragraph '<b>Cloud URL:</b> ' + fullApiServerUrl
                    try {
                        paragraph '<b>Local URL:</b> ' + fullLocalApiServerUrl
                    } catch (e) {
                        paragraph '<b>Local URL:</b> (not available)'
                    }
                    paragraph '<b>Access Token:</b> ' + state.accessToken
                    paragraph '<b>App Version:</b> ' + sbAppNameAndVersion

                    if (state.sbId) {
                        paragraph '<b>Status:</b> Connected (ID: ' + state.sbId + ')'
                        input(name: 'btnDisconnect', type: 'button', title: 'Disconnect from SignalBricks')
                    } else {
                        paragraph '<b>Status:</b> Not registered (use CLI add-hub to configure)'
                        paragraph '<small>Expected and harmless: the SignalBricks dispatcher drives this app through its endpoints (<code>/sync</code> etc.) with the access token above. ' +
                            'Registration only enables an outbound webhook to a SignalBricks backend that was never wired up; lock events reach SignalBricks through the SignalBricks Telemetry app.</small>'
                    }
                }
            } else {
                paragraph 'Please configure the locks before connecting to SignalBricks.'
            }
        }

        section(title: 'Diagnostics') {
            href(name: 'hrefDebug', title: 'Debug', description: 'API links, manual reconcile, reset state', page: 'debug')
        }
    }

    page(name: 'debug', title: 'Debug')
}

// Debug page for testing
def debug() {
    dynamicPage(name: 'debug', title: 'Debug') {
        section {
            input(name: 'btbAccessToken', type: 'button', title: 'Refresh Access Token')
            input(name: 'btnTest', type: 'button', title: 'Test Webhook')
            input(name: 'btnReconcile', type: 'button', title: 'Reconcile Door Codes')
            input(name: 'btnRefreshCodes', type: 'button', title: 'Refresh Door Codes (one lock at a time)')
            input(name: 'btnReset', type: 'button', title: 'Reset State')
        }
        // Direct links to API endpoints
        section(title: 'Door code refresh') {
            codeRefreshStatus().each { paragraph it }
        }
        section(title: 'Links') {
            String url

            // Hub info
            url = fullApiServerUrl + "/info?access_token=${state.accessToken}"
            href(title: url, style: 'external', url: url)

            // Device List
            url = fullApiServerUrl + "/devices?access_token=${state.accessToken}"
            href(title: url, style: 'external', url: url)

            // Device detail links
            locks.each { lock ->
                url = fullApiServerUrl + "/devices/${lock.id}?access_token=${state.accessToken}"
                href(title: url, style: 'external', url: url)
            }
        }
    }
}

// We only get one button handler function, so we have to switch on the button name
void appButtonHandler(String btnName) {
    log.debug "appButtonHandler: $btnName"

    switch (btnName) {
        // Disconnect from SignalBricks
        case 'btnDisconnect':
            state.accessToken = null
            state.sbId = null
            unschedule()
            unsubscribe()
            atomicState.codeRefresh = null
            break
        // Create/refresh access token (this will break the connection until the user reconnects)
        case 'btbAccessToken':
            state.accessToken = createAccessToken()
            break
        // Manually run the reconcileDoorCodes function
        case 'btnReconcile':
            reconcileDoorCodes()
            break
        // Read door codes from the chosen lock, or all locks one at a time
        case 'btnRefreshCodes':
            String target = settings?.codeRefreshLock
            startCodeRefresh(target && target != 'all' ? target : null)
            break
        case 'btnReset':
            unsubscribe()
            unschedule()
            state.lastVersion = sbAppVersion
            atomicState.bookings = [:]
            atomicState.codeAssignments = [:]
            atomicState.reconcileRetries = 0
            atomicState.codeRefresh = null
            scheduleCodeRefresh()
            // Re-establish subscriptions so codeChangedHandler still fires
            // for any locks the user touches manually after the reset.
            subscribeToEvents()
            break
        // Send a test webhook
        case 'btnTest':
            Map testEvent = [
                name: 'test',
                value: 'test',
                displayName: 'Test Webhook',
                deviceId: null,
                descriptionText: null,
                unit: null,
                type: null,
                data: null
            ]

            sbHttpPostJson('/hubitat', testEvent, { r ->
                log.debug "Test Webhook: ${r.data}"
            })
            break
    }
}

// Mappings define the API endpoints
mappings {
    // Set (or unset) the SignalBricks Id (ORLACT) state variable
    path('/register') {
        action: [
            POST: 'apiRegister',
            DELETE: 'apiUnregister',
        ]
    }

    // Hub and App info, including bookings
    path('/info') {
        action: [
            GET: 'apiGetInfo',
        ]
    }

    // List of devices
    path('/devices') {
        action: [
            GET: 'apiGetDevices',
        ]
    }

    // Detailed device info
    path('/devices/:deviceId') {
        action: [
            GET: 'apiGetDevice',
        ]
    }

    // Execute a command on a device
    path('/devices/:deviceId/:command') {
        action: [
            POST: 'apiExecuteCommand',
        ]
    }

    // Sync all bookings (replaces state.bookings)
    path('/sync') {
        action: [
            PUT: 'apiSync',
            PATCH: 'apiSyncPatch',
        ]
    }

    // Sync a single booking (Legacy)
    path('/sync/:bookingId') {
        action: [
            POST: 'apiSyncBooking',
            PUT: 'apiSyncBooking',
            DELETE: 'apiDeleteBooking',
        ]
    }

    // Sync a single booking by lock
    path('/sync/:bookingId/:lockId') {
        action: [
            POST: 'apiSyncBookingByLock',
            PUT: 'apiSyncBookingByLock',
            DELETE: 'apiDeleteBookingByLock',
        ]
    }
}

// Only gets called when the user clicks "Done" on the main page
void installed() {
    log.info "${sbAppNameAndVersion} installed"

    // Initialize default state

    // Future use for version checking / update process
    state.lastVersion = sbAppVersion

    // Stores current and future bookings
    state.bookings = [:]

    // The API Key
    state.accessToken = createAccessToken()

    // The SignalBricks Id (ORLACT)
    state.sbId = null

    // Unlikely any reason to call, but just in case, clear out any old hooks
    unsubscribe()
    unschedule()

    // Door code refresh is Off unless a cadence was chosen (#24)
    scheduleCodeRefresh()
}

// Called when the settings page is updated (and use clicks done)
void updated() {
    log.info "${sbAppNameAndVersion} updated"

    // Make sure these defautls are set

    if (!state.bookings) {
        state.bookings = [:]
    }

    if (!state.accessToken) {
        state.accessToken = createAccessToken()
    }

    if (state.lastVersion != sbAppVersion) {
        state.lastVersion = sbAppVersion
    }

    SyncState()
}

// Setup event subscriptions, and any scheduled tasks
void SyncState()
{
    Map bookings = helperGetBookings(atomicState.bookings)
    atomicState.bookings = bookings
    SyncState(bookings)
}

void SyncState(Map bookings)
{
    // Subscriptions and schedules drive local door-code reconciliation and
    // must run regardless of SignalBricks registration. Outbound posting is
    // gated separately inside webhook().
    // No door code refresh here: clicking Done or /register must not start a full
    // read of every lock (#24). It runs on its cadence or from the app page.
    subscribeToEvents()
    scheduleEvents(bookings)
}

// Subscribe to events for all locks
void subscribeToEvents() {
    log.debug 'subscribeToEvents'

    // Remove old subscriptions from potentially removed locks
    unsubscribe()

    // Separate handler for each event for future flexibility
    subscribe(locks, 'lock', lockHandler)
    subscribe(locks, 'unlock', lockHandler)
    subscribe(locks, 'lastCodeName', lastCodeNameHandler)
    subscribe(locks, 'lockCodes', lockCodesHandler)
    subscribe(locks, 'codeChanged', codeChangedHandler)
    subscribe(locks, 'maxCodes', maxCodesHandler)
    subscribe(locks, 'codeLength', codeLengthHandler)
}

// Setup scheduled tasks for all bookings
void scheduleEvents(Map bookings) {
    log.debug 'scheduleEvents'

    // Remove old scheduled tasks
    unschedule()

    // unschedule() also removed the refresh cadence and any running refresh's
    // timer: put back only what the cadence and the running refresh need (#24)
    codeRefreshRearm()
    scheduleCodeRefresh()

    // Keep track of scheduled times as we add them to avoid simultaneous executions of reconcileDoorCodes
    List schedules = []

    // Schedule tasks
    if (bookings) {
        // Iterate through all bookings for each lock
        // There's an assumption there can only be one active booking per lock
        Map nextBooking = helperFindNextBooking(bookings)

        if (nextBooking) {
            Date now = new Date()

            // Schedule reconcileDoorCodes for next booking
            // This will add new codes and remove old codes
            nextBooking.each { lockId, booking ->

                // Ensure dates are Date objects (atomicState serialization loses types)
                Date checkIn = helperToDate(booking.checkIn)
                Date checkOut = helperToDate(booking.checkOut)

                if (!checkIn || !checkOut) return

                // Only schedule check-in if its in the future
                if (checkIn.after(now)) {
                    if (!schedules.contains(checkIn)) {
                        log.debug "scheduleEvents: schedule reconcileDoorCodes for ${booking}"
                        runOnce(checkIn, 'reconcileDoorCodes', [overwrite: false])
                        schedules.add(checkIn)
                    }
                } else if (!schedules.contains(checkOut)) {
                    log.debug "scheduleEvents: schedule reconcileDoorCodes for ${booking}"
                    runOnce(checkOut, 'reconcileDoorCodes', [overwrite: false])
                    schedules.add(checkOut)
                }
            }
        }
    }
}

// --- Door code refresh (#24) ---
// getCodes() walks every slot of a lock over Z-Wave/Zigbee; on a 250-slot lock
// that takes many minutes. Up to 1.3.4 the app started a lock every 90 s at
// midnight, so reads overlapped and Z-Wave timed out on every lock for hours.
// Now locks are read one at a time (the same approach as our OwnerRez
// coexistence work): the next lock starts once the current one has reported
// lockCodes (counted only after 2.6 s per slot, at least 60 s, because large
// locks send lockCodes mid-read; then 30 s with no further lockCodes to settle) or once its time budget (3 s per slot, 120 s to
// 20 min) runs out. The run lives in atomicState.codeRefresh; every timer
// carries the run id and lock index, so a timer from an earlier step or run
// does nothing. Off by default: it runs only on the chosen cadence or when
// started from the app page.
int getCodeRefreshStartDelaySec() { 60 }
int getCodeRefreshSettleSec() { 30 }
int getCodeRefreshGapSec() { 5 }
// A run whose step is this long past its budget lost its timers (e.g. a reboot)
int getCodeRefreshStaleSec() { 300 }

int codeRefreshFloorSec(int slots) { Math.max((int) (slots * 2.6), 60) }
int codeRefreshBudgetSec(int slots) { Math.min(Math.max(slots * 3, 120), 1200) }

// Start reading door codes from all selected locks (or only lockId), one at a time
void startCodeRefresh(String lockId = null) {
    Map r = atomicState.codeRefresh
    if (r && !codeRefreshStalled(r)) {
        log.info "Door code refresh already running (${codeRefreshProgress(r)}), not restarting"
        return
    }

    List lockIds = (locks ?: []).collect { lock -> lock.id.toString() }
    if (lockId) lockIds = lockIds.findAll { id -> id == lockId }
    if (!lockIds) {
        atomicState.codeRefresh = null
        log.info 'Door code refresh: no locks to read'
        return
    }

    long runId = now()
    // index -1: waiting to start the first lock
    atomicState.codeRefresh = [runId: runId, lockIds: lockIds, index: -1, stepAt: runId, budgetSec: codeRefreshStartDelaySec, floorSec: 0]
    log.info "Door code refresh started: ${lockIds.size()} lock(s), one at a time"
    runIn(codeRefreshStartDelaySec, 'codeRefreshNext', [overwrite: true, data: [runId: runId, index: 0]])
}

// Cadence (cron) handler
void codeRefreshScheduled() {
    startCodeRefresh()
}

// True when data is a timer of the running refresh's step at index
boolean codeRefreshIsCurrent(Map r, Map data, int index) {
    return r && data && r.runId == data.runId && (r.index as int) == index
}

boolean codeRefreshStalled(Map r) {
    return now() - (r.stepAt as long) > ((r.budgetSec as long) + codeRefreshStaleSec) * 1000L
}

// Start getCodes() on the lock at data.index, or finish the run
void codeRefreshNext(Map data) {
    Map r = atomicState.codeRefresh
    int index = data?.index as int
    if (!codeRefreshIsCurrent(r, data, index - 1)) return // stale timer

    int total = r.lockIds.size()
    if (index >= total) {
        atomicState.codeRefresh = null
        state.codeRefreshLast = [finishedAt: now(), locks: total]
        log.info "Door code refresh finished: ${total} lock(s)"
        return
    }

    def lock = locks?.find { lock -> lock.id.toString() == r.lockIds[index] }
    int slots = lock ? ((lock.currentValue('maxCodes') ?: 30) as int) : 0
    r.index = index
    r.stepAt = now()
    r.budgetSec = codeRefreshBudgetSec(slots)
    r.floorSec = codeRefreshFloorSec(slots)
    atomicState.codeRefresh = r

    if (!lock) {
        log.warn "Door code refresh: lock ${index + 1} of ${total} (id ${r.lockIds[index]}) is no longer selected, skipping"
        codeRefreshAdvance([runId: r.runId, index: index])
        return
    }

    log.info "Door code refresh: reading lock ${index + 1} of ${total} (${lock.label ?: lock.name}), up to ${r.budgetSec} s"
    runIn(r.budgetSec, 'codeRefreshTimeout', [overwrite: true, data: [runId: r.runId, index: index]])
    // Varies by lock driver, but this can iterate through all code positions over z-wave/zigbee and can take a while
    lock.getCodes()
}

// lockCodes from the lock being read: done once past its floor, after a settle period
void codeRefreshOnLockCodes(e) {
    Map r = atomicState.codeRefresh
    if (!r || (r.index as int) < 0) return
    int index = r.index as int
    if (index >= r.lockIds.size() || r.lockIds[index] != e.deviceId?.toString()) return
    // Some drivers send lockCodes while still walking the slots; ignore early ones
    if (now() - (r.stepAt as long) < (r.floorSec as long) * 1000L) return

    runIn(codeRefreshSettleSec, 'codeRefreshSettled', [overwrite: true, data: [runId: r.runId, index: index]])
}

void codeRefreshSettled(Map data) {
    if (!codeRefreshIsCurrent(atomicState.codeRefresh, data, data?.index as int)) return
    log.info "Door code refresh: lock ${(data.index as int) + 1} read"
    codeRefreshAdvance(data)
}

void codeRefreshTimeout(Map data) {
    if (!codeRefreshIsCurrent(atomicState.codeRefresh, data, data?.index as int)) return
    log.warn "Door code refresh: lock ${(data.index as int) + 1} did not finish within its time budget, moving on"
    codeRefreshAdvance(data)
}

void codeRefreshAdvance(Map data) {
    Map r = atomicState.codeRefresh
    if (!codeRefreshIsCurrent(r, data, data?.index as int)) return
    runIn(codeRefreshGapSec, 'codeRefreshNext', [overwrite: true, data: [runId: r.runId, index: (r.index as int) + 1]])
}

// unschedule() removed the running refresh's timer; put it back
void codeRefreshRearm() {
    Map r = atomicState.codeRefresh
    if (!r) return

    long left = (r.budgetSec as long) - (long) ((now() - (r.stepAt as long)) / 1000)
    int index = r.index as int
    if (index < 0) {
        runIn(Math.max(left, 5L), 'codeRefreshNext', [overwrite: true, data: [runId: r.runId, index: 0]])
    } else {
        runIn(Math.max(left, 5L), 'codeRefreshTimeout', [overwrite: true, data: [runId: r.runId, index: index]])
    }
}

// Cron for the cadence, or null when Off (also when never set, e.g. after an
// upgrade). The time is a random 01:00-03:59 hub time, picked once per install.
String codeRefreshCron() {
    String cadence = settings?.codeRefreshCadence
    if (cadence != 'weekly' && cadence != 'daily') return null

    if (state.codeRefreshHour == null || state.codeRefreshMinute == null) {
        state.codeRefreshHour = 1 + (int) (Math.random() * 3)
        state.codeRefreshMinute = (int) (Math.random() * 60)
    }

    // Weekly on Tuesday: the OwnerRez app's weekly refresh runs on Wednesday
    return "0 ${state.codeRefreshMinute} ${state.codeRefreshHour} ? * ${cadence == 'daily' ? '*' : 'TUE'}"
}

// Schedule the cadence, and remove the midnight refresh of 1.3.4 and earlier
void scheduleCodeRefresh() {
    unschedule('refreshDoorCodes')

    String cron = codeRefreshCron()
    state.codeRefreshCron = cron
    if (cron) {
        schedule(cron, 'codeRefreshScheduled')
        log.info "Door code refresh scheduled (${settings?.codeRefreshCadence}, ${codeRefreshTime()} hub time)"
    } else {
        unschedule('codeRefreshScheduled')
    }
}

String codeRefreshTime() {
    return String.format('%02d:%02d', state.codeRefreshHour as int, state.codeRefreshMinute as int)
}

String codeRefreshProgress(Map r) {
    int index = r.index as int
    int total = r.lockIds.size()
    if (index < 0) return "starting, ${total} lock(s)"
    def lock = locks?.find { lock -> lock.id.toString() == r.lockIds[index] }
    return "lock ${index + 1} of ${total}" + (lock ? " (${lock.label ?: lock.name})" : '')
}

// Lines for the app page
List codeRefreshStatus() {
    List lines = []
    Map r = atomicState.codeRefresh
    if (!r) {
        lines << '<b>Refresh:</b> not running'
    } else if (codeRefreshStalled(r)) {
        lines << "<b>Refresh:</b> stopped at ${codeRefreshProgress(r)} (hub restarted?). Click Refresh door codes now to start again."
    } else {
        lines << "<b>Refresh:</b> running, ${codeRefreshProgress(r)}, one lock at a time"
    }

    String cron = state.codeRefreshCron
    if (cron) {
        lines << "<b>Scheduled:</b> ${cron.endsWith('*') ? 'daily' : 'weekly on Tuesday'} at ${codeRefreshTime()} hub time"
    } else {
        lines << '<b>Scheduled:</b> Off'
    }

    Map last = state.codeRefreshLast
    if (last) {
        lines << "<b>Last refresh finished:</b> ${new Date(last.finishedAt as long)} (${last.locks} lock(s))"
    }
    return lines
}

Map codeRefreshLockOptions() {
    Map options = [all: 'All locks, one at a time']
    (locks ?: []).each { lock -> options[lock.id.toString()] = "Only ${lock.label ?: lock.name}" }
    return options
}

// The midnight cron of 1.3.4 and earlier may fire once on a hub that updated
// without clicking Done: remove it and don't read any lock
void refreshDoorCodes() {
    log.info 'Removed the old midnight door code refresh; set the refresh on the app page'
    scheduleCodeRefresh()
}

// Per-lock jobs queued by 1.3.4 and earlier may fire once after an update; ignore them
void tryRefreshDoorCodes(Map data) {
    log.debug 'tryRefreshDoorCodes: old job ignored (locks are now read one at a time)'
}

// Reconcile door codes for all locks
void reconcileDoorCodes() {
    log.debug 'reconcileDoorCodes (no args)'

    Map bookings = helperGetBookings(atomicState.bookings)
    reconcileDoorCodes(bookings)
}

void reconcileDoorCodes(Map bookings) {
    log.debug 'reconcileDoorCodes'

    // We only care about bookings that should be active right now
    Map currentBookings = helperFindCurrentBookings(bookings)

    int i = 0

    // Iterate through all locks
    locks.each { lock ->
        log.trace "reconcileDoorCodes: lock ${lock.id} ${lock.label}"

        // Get current bookings for the current lock
        // Normalize both sides to strings for comparison since types may vary
        // (CLI sends strings, atomicState may deserialize as integers)
        def lockIdMatch = lock.id
        Map currentLockBookings = currentBookings
            .findAll { key, booking ->
                booking.lockId.any { it == lockIdMatch || "${it}" == "${lockIdMatch}" }
            }
            .collectEntries { key, booking -> [(booking.id): booking] }

        log.trace "reconcileDoorCodes: current lock bookings ${currentLockBookings}"

        // Get the lock's current codes, as we don't remove non-SignalBricks codes
        def lockCodesRaw = lock.currentValue('lockCodes', true)

        if (!lockCodesRaw) {
            log.debug "reconcileDoorCodes: No lock codes"
            return
        }

        Map lockCodes = parseJson(lockCodesRaw)
        Map sbCodes = helperOnlySbCodes(lockCodes, lock.id)
        log.trace "reconcileDoorCodes: lock codes ${lockCodes}"
        log.trace "reconcileDoorCodes: sb codes ${sbCodes}"

        // Remove all codes that are not in the current bookings
        sbCodes.each { bookingId, lockCode ->
            if (!currentLockBookings[bookingId]) {
                int codePosition

                // deleteCode has to be called with an integer
                if (lockCode.key instanceof String) {
                    codePosition = lockCode.key.toInteger()
                } else {
                    codePosition = lockCode.key
                }

                log.trace "reconcileDoorCodes: tryDeleteCode ${lock.id} ${codePosition}"

                // Remove the code
                runIn(++i * 60, 'tryDeleteCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition ]])
            }
        }

        // Are there any active bookings
        if (currentLockBookings) {
            // Get all available code positions as each one has a static index
            List availableCodePositions = helperFindCodePositions(lock, lockCodes)
            int index = 0

            currentLockBookings.each { bookingId, booking ->
                String codeName = helperGetCodeName(booking)

                // Is the booking missing from the list of codes
                if (!sbCodes[bookingId]) {
                    // Get the next available code position. If the lock has no free
                    // slot >= minCodePosition, availableCodePositions is exhausted and
                    // returns null -> casting to int throws (GroovyCastException) and
                    // aborts the whole reconcile. Guard it: warn and skip this booking.
                    def nextPosition = availableCodePositions[index]
                    if (nextPosition == null) {
                        log.warn "reconcileDoorCodes: no free code position (>= ${minCodePosition}) on lock ${lock.id} for ${codeName} - skipping"
                        return // continue to next booking
                    }
                    index++
                    int codePosition = nextPosition

                    log.debug "reconcileDoorCodes: trySetCode ${booking}"

                    // Create the code
                    runIn(++i * 60, 'trySetCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition, code: booking.code, codeName: codeName ]])
                }
                // Is the booking's code different from the current code 
                else if (sbCodes[bookingId].code != booking.code) {
                    int codePosition

                    // setCode has to be called with an integer
                    if (sbCodes[bookingId].key instanceof String) {
                        codePosition = sbCodes[bookingId].key.toInteger()
                    } else {
                        codePosition = sbCodes[bookingId].key
                    }

                    // Re-set the code
                    log.debug "reconcileDoorCodes: re-running trySetCode ${codePosition} ${booking}"

                    runIn(++i * 60, 'trySetCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition, code: booking.code, codeName: codeName ]])
                }
            }
        }
    }

    if (i == 0) {
        log.debug 'reconcileDoorCodes: No codes to reconcile'
        atomicState.reconcileRetries = 0
        scheduleEvents(bookings)
    } else {
        int retries = (atomicState.reconcileRetries ?: 0) + 1
        // Per-code retry now lives inside trySetCode/verifySetCode (capped at
        // sbMaxSetCodeAttempts), so this outer loop only needs to be a safety
        // net for missed codeChangedHandler events. Raised from 4 to 20.
        int maxRetries = 20
        if (retries > maxRetries) {
            log.warn "reconcileDoorCodes: max retries (${maxRetries}) reached, stopping retry loop"
            atomicState.reconcileRetries = 0
            scheduleEvents(bookings)
        } else {
            log.debug "reconcileDoorCodes: ${i} codes to reconcile (retry ${retries}/${maxRetries})"
            atomicState.reconcileRetries = retries
            runIn(++i * 180, 'reconcileDoorCodes', [ overwrite: true ])
        }
    }
}

// Maximum delay between trySetCode retries (seconds). Backoff grows linearly
// with attempt # but is capped here so a stubborn lock keeps retrying at a
// reasonable cadence rather than hours apart. There is NO cap on attempt
// count — match upstream OwnerRez behavior of retrying until the lock
// confirms (or the assignment is cleared by reconcile/btnReset/delete).
int getSbMaxRetryDelaySec() { 600 }

void trySetCode(Map data) {
    log.debug "trySetCode (${data})"

    def lock = locks.find { lock -> lock.id == data.lockId }

    if (!lock) {
        log.debug "trySetCode: Lock not found"
        return
    }

    int attempt = (data.attempt ?: 0) + 1

    lock.setCode(data.codePosition, data.code, data.codeName)

    // Track the assignment in state (lock drivers may not preserve code names)
    Map assignments = atomicState.codeAssignments ?: [:]
    String lockKey = "${data.lockId}"
    if (!assignments[lockKey]) assignments[lockKey] = [:]
    assignments[lockKey]["${data.codePosition}"] = [
        bookingId: data.codeName?.find(/ORB\d+/) ?: 'unknown',
        code: data.code,
        codeName: data.codeName,
        attempt: attempt,
        confirmed: false,
    ]
    atomicState.codeAssignments = assignments
    log.debug "trySetCode: tracked assignment lock ${data.lockId} pos ${data.codePosition} (attempt ${attempt})"

    // Schedule verification. If codeChangedHandler doesn't flip confirmed=true
    // by the time this fires, verifySetCode re-issues setCode. Loop terminates
    // only when the lock confirms or the assignment is cleared.
    Map retryData = [
        lockId: data.lockId,
        codePosition: data.codePosition,
        code: data.code,
        codeName: data.codeName,
        attempt: attempt,
    ]
    int delay = Math.min(60 + (attempt * 30), sbMaxRetryDelaySec)
    runIn(delay, 'verifySetCode', [ overwrite: false, data: retryData ])
}

// Verify that a previously-issued setCode actually took. If codeChangedHandler
// already flipped confirmed=true, no-op. Otherwise, re-issue trySetCode (which
// increments the attempt counter and reschedules another verify).
void verifySetCode(Map data) {
    log.debug "verifySetCode (${data})"

    Map assignments = atomicState.codeAssignments ?: [:]
    String lockKey = "${data.lockId}"
    Map lockAssignments = assignments[lockKey] ?: [:]
    Map a = lockAssignments["${data.codePosition}"]

    // Assignment cleared (deleted, reset, or replaced) — stop retrying
    if (!a) {
        log.debug "verifySetCode: assignment cleared, stopping retry"
        return
    }

    if (a.confirmed) {
        log.debug "verifySetCode: already confirmed for lock ${data.lockId} pos ${data.codePosition}"
        return
    }

    log.warn "verifySetCode: not confirmed for lock ${data.lockId} pos ${data.codePosition} after attempt ${a.attempt} — retrying"
    trySetCode(data)
}

void tryDeleteCode(Map data) {
    log.debug "tryDeleteCode (${data})"

    def lock = locks.find { lock -> lock.id == data.lockId }

    if (!lock) {
        log.debug "tryDeleteCode: Lock not found"
        return
    }

    lock.deleteCode(data.codePosition)

    // Remove from tracked assignments
    Map assignments = atomicState.codeAssignments ?: [:]
    String lockKey = "${data.lockId}"
    if (assignments[lockKey]) {
        assignments[lockKey].remove("${data.codePosition}")
        atomicState.codeAssignments = assignments
        log.debug "tryDeleteCode: removed assignment lock ${data.lockId} pos ${data.codePosition}"
    }
}

// Send outbound webhook to the SignalBricks platform.
// When the hub isn't registered with SignalBricks (no sbId — current default
// in the dispatcher-only deployment), we silently skip posting. We must NOT
// unsubscribe()/unschedule() here: those operate app-wide and would also tear
// down lock event subscriptions (codeChangedHandler — which confirms/retries
// failed setCode calls) and any pending trySetCode/tryDeleteCode runIn jobs.
// That bug caused atomicState.codeAssignments to drift optimistic while the
// underlying locks never received the codes.
void webhook(e) {
    log.debug "webhook: ${e.name}"

    if (!state.sbId) {
        log.trace "webhook: No SignalBricks ID — skipping outbound POST (subscriptions/schedules preserved)"
        return
    }

    // Normalize the event payload
    Map payload = [
        id: e.id,
        deviceId: e.deviceId,
        name: e.name,
        displayName: e.displayName,
        source: e.source,
        value: e.value,
        data: e.jsonData,
        descriptionText: e.descriptionText,
        isStateChange: e.isStateChange,
        type: e.type,
        date: e.date,
        unit: e.unit,
    ]

    sbHttpPostJson('/hubitat', payload, { r ->
        log.debug "Webhook: ${r.data}"
    })
}

void lockHandler(e) {
    webhook(e)
}

void lastCodeNameHandler(e) {
    webhook(e)
}

void lockCodesHandler(e) {
    codeRefreshOnLockCodes(e)
    webhook(e)
}

void codeChangedHandler(e) {
    // Verify our tracked assignments against what the lock confirmed
    try {
        Map data = e.data ? parseJson(e.data) : null
        if (data?.codeNumber) {
            String lockId = "${e.device.id}"
            String pos = "${data.codeNumber}"
            Map assignments = atomicState.codeAssignments ?: [:]
            Map lockAssignments = assignments[lockId] ?: [:]
            Map assignment = lockAssignments[pos]

            if (assignment) {
                if (e.value == 'added' || e.value == 'changed') {
                    log.info "codeChangedHandler: confirmed code at pos ${pos} on lock ${lockId} for ${assignment.bookingId}"
                    assignment.confirmed = true
                    lockAssignments[pos] = assignment
                    assignments[lockId] = lockAssignments
                    atomicState.codeAssignments = assignments
                } else if (e.value == 'failed') {
                    int attempt = (assignment.attempt ?: 0)
                    log.warn "codeChangedHandler: FAILED at pos ${pos} on lock ${lockId} for ${assignment.bookingId} (attempt ${attempt}) — retrying"
                    // Trigger an immediate retry rather than waiting for verifySetCode
                    Map retryData = [
                        lockId: lockId,
                        codePosition: data.codeNumber instanceof Number ? data.codeNumber : pos.toInteger(),
                        code: assignment.code,
                        codeName: assignment.codeName,
                        attempt: attempt,
                    ]
                    runIn(30, 'trySetCode', [ overwrite: false, data: retryData ])
                } else if (e.value == 'deleted') {
                    log.info "codeChangedHandler: confirmed delete at pos ${pos} on lock ${lockId}"
                }
            }
        }
    } catch (Exception ex) {
        log.debug "codeChangedHandler: ${ex.message}"
    }

    webhook(e)
}

void maxCodesHandler(e) {
    webhook(e)
}

void codeLengthHandler(e) {
    webhook(e)
}

// Get the Connect to SignalBricks URL
// This endpoint will create/update the LinkedAccount for this hub,
// And will reach back out to the hub's /register endpoint to set the SignalBricks Id (ORLACT) state variable
String getSbConnectUrl() {
    String connectUrl = sbBaseSecureUrl + '/settings/locks/HubitatConnect'

    if (state.accessToken) {
        connectUrl += '?'
        connectUrl += '&hubId=' + URLEncoder.encode(hubUID)
        connectUrl += '&appId=' + URLEncoder.encode(app.id.toString())
        connectUrl += '&accessToken=' + URLEncoder.encode(state.accessToken)
        connectUrl += '&version=' + URLEncoder.encode(sbAppVersion)
        connectUrl += '&hub=' + URLEncoder.encode(location.hub.name)
    }

    return connectUrl
}

// Send API request to SignalBricks FastApi application
void sbHttpPostJson(String uri, Map body, Closure closure) {
    log.debug "sbHttpPostJson: $uri, $body"
    log.debug "sbBaseFastUrl: $sbBaseFastUrl"

    Map params = [
        uri: sbBaseFastUrl, // Base URL
        path: uri, // Uri appended to base URL
        contentType: 'application/json',
        body: body,
        headers: [
            // Headers used for matching to the correct LinkedAccount,
            // And for authentication
            'X-Hubitat-SB-Id': state.sbId,
            'X-Hubitat-Hub-Id': hubUID,
            'X-Hubitat-App-Id': app.id,
            'X-Hubitat-Access-Token': state.accessToken,
            'X-Hubitat-SB-Version': sbAppVersion,
        ]
    ]

    // Do the request. The closure will be called when the request completes
    // Hubitat appears to do this synchronously, so we don't have to worry about concurrency
    httpPostJson(params, closure)
}

// Create a response for the API request
// Done to include custom headers/response codes
Map sbHttpResponseJson(def data, int status = 200) {
    return [
        renderMethod: true,
        status: status,
        contentType: 'application/json',
        headers: [
            'X-Hubitat-SB-Version': sbAppVersion,
        ],
        data: data ? JsonOutput.toJson(data) : null,
    ]
}

// Register the SignalBricks account with this hub
// SignalBricks needs to already have the hub's Id and Access Token
// So just accept whatever is passed in at face value
Map apiRegister() {
    log.debug "apiRegister: ${request.JSON}"

    try {
        atomicState.sbId = request.JSON.sbId

        // Now that we have the SignalBricks Id, we can setup the webhook subscriptions
        // And schedule the reconcileDoorCodes tasks
        Map bookings = helperGetBookings(atomicState.bookings)
        SyncState(bookings)

        return apiGetInfo()
    } catch (Exception ex) {
        log.error "apiRegister: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Disassociate the SignalBricks account from this hub
Map apiUnregister() {
    log.debug 'apiUnregister'

    try {
        atomicState.sbId = null

        // This will break the connection until the user reconnects
        atomicState.accessToken = null

        // Unsubscribe from events and unschedule tasks
        unschedule()
        unsubscribe()
        atomicState.codeRefresh = null

        return sbHttpResponseJson(null)
    } catch (Exception ex) {
        log.error "apiUnregister: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

 // Get basic hub info, including bookings
Map apiGetInfo() {
    log.debug 'apiGetInfo'

    try {
        return sbHttpResponseJson([
            sbId: atomicState.sbId,
            hubId: hubUID,
            appId: app.id,
            endpoint: fullApiServerUrl,
            version: sbAppVersion,
            location: location.name,
            name: location.hub.name,
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(null),
        ])
    } catch (Exception ex) {
        log.error "apiGetInfo: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Get simplified list of devices
Map apiGetDevices() {
    log.debug 'apiGetDevices'

    try {
        List resp = []

        locks.each { lock ->
            resp << [id: lock.id, name: lock.name, type: lock.typeName, label: lock.label ?: lock.displayName]
        }

        return sbHttpResponseJson(resp)
    } catch (Exception ex) {
        log.error "apiGetDevices: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Get detailed per-device info
Map apiGetDevice() {
    log.debug "apiGetDevice: $params"

    try {
    def deviceId = params.deviceId
    def lock = locks.find { lock -> lock.id == deviceId }

    if (!lock) {
        return sbHttpResponseJson([ error: 'Device not found' ], 404)
    }

    Map resp = [
        id: lock.id,
        name: lock.name,
        type: lock.typeName,
        label: lock.label,
        displayName: lock.displayName,
        // Simplify the attibutes as the data structure is too complex/verbose
        attributes: lock.supportedAttributes.collect { attr ->
        [
            name: attr.name,
            dataType: attr.dataType,
            values: attr.values,
            currentValue: lock.currentValue(attr.name),
        ]}.collect { attr ->
            switch (attr.dataType) {
                case 'JSON_OBJECT':
                    if (attr.currentValue) {
                        attr.currentValue = parseJson(attr.currentValue)
                        if (attr.name == 'lockCodes' && minCodePosition > 0) {
                            log.debug('Redact Lock Codes')
                            attr.currentValue.each { key, code ->
                                if (key.toInteger() < minCodePosition) {
                                    code.code = '<redacted>'
                                }
                                log.debug("Code: ${key} ${code}")
                            }
                        }
                    } else {
                        attr.currentValue = null
                    }
                    break
            }

            return attr
        },
        // Simplify the commands as the data structure is too complex/verbose
        commands: lock.supportedCommands.collect { cmd -> [
            name: cmd.name,
            arguments: cmd.arguments,
            parameters: cmd.parameters.collect { param -> [
                name: param.name,
                type: param.type,
                description: param.description,
            ]},
        ]},
        // Just need the capabilities names
        capabilities: lock.capabilities.collect { cap -> cap.name },
    ]

    return sbHttpResponseJson(resp)
    } catch (Exception ex) {
        log.error "apiGetDevice: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Execute a command on a device
// Only allows a subset of commands
def apiExecuteCommand() {
    log.debug "apiExecuteCommand: $params ${request.JSON}"

    try {
        def deviceId = params.deviceId
        def lock = locks.find { lock -> lock.id == deviceId }

        if (!lock) {
            return sbHttpResponseJson([ error: 'Device not found' ], 404)
        }

        def command = params.command
        def cmd = lock.supportedCommands.find { cmd -> cmd.name == command }

        if (!cmd) {
            return sbHttpResponseJson([ error: 'Command not found' ], 404)
        }

        // Even if just an empty object, gotta pass in something
        if (request.JSON == null) {
            return sbHttpResponseJson([ error: 'No JSON body' ], 400)
        }

        switch (command) {
            case 'lock':
            case 'unlock':
            case 'refresh':
            case 'configure':
                return lock."$command"()
            case 'deleteCode':
                return lock.deleteCode(request.JSON.codePosition)
            case 'setCode':
                return lock.setCode(request.JSON.codePosition, request.JSON.pinCode, request.JSON.name)
            case 'setCodeLength':
                return lock.setCodeLength(request.JSON.pinCodeLength)
            default:
                return sbHttpResponseJson([ error: 'Command not supported' ], 400)
        }
    } catch (Exception ex) {
        log.error "apiExecuteCommand: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Sync all bookings (replaces state.bookings), schedule tasks, and reconcile door codes
Map apiSync() {
    try {
        log.debug "apiSync ${request.JSON}"

        atomicState.bookings = helperGetBookings(request.JSON)
        atomicState.reconcileRetries = 0
        scheduleEvents(atomicState.bookings)

        runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
        ])
    } catch (Exception ex) {
        log.error "apiSync: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
            error: ex.message
        ], 500)
    }
}

Map apiSyncPatch() {
    try {
        log.debug "apiSyncPatch ${request.JSON}"

        def combined = atomicState.bookings + request.JSON

        atomicState.bookings = helperGetBookings(combined)
        atomicState.reconcileRetries = 0
        scheduleEvents(atomicState.bookings)

        runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
        ])
    } catch (Exception ex) {
        log.error "apiSyncPatch: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
            error: ex.message
        ], 500)
    }
}

// Sync a single booking, schedule tasks, and reconcile door codes
Map apiSyncBooking() {
    log.debug "apiSyncBooking ${params.bookingId}"

    try {
        params.lockId = request.JSON.lockId

        return apiSyncBookingByLock()
    } catch (Exception ex) {
        log.error "apiSyncBooking: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Sync a single booking, by lockID, schedule tasks, and reconcile door codes
Map apiSyncBookingByLock() {
    log.debug "apiSyncBookingByLock ${params.bookingId} ${params.lockId}"

    try {
        Map bookings = atomicState.bookings
        String key = params.bookingId
        Map existing = bookings[key]
        Map booking = request.JSON

        // If the booking is already in the state, merge the lockId
        if (existing) {
            booking = existing + booking
            if (existing.lockId instanceof List) {
                booking.lockId = existing.lockId
            } else {
                booking.lockId = [existing.lockId]
            }
            booking.lockId << params.lockId
        } else {
            booking.lockId = [params.lockId]
        }

        booking.lockId = booking.lockId.unique()
        bookings[key] = booking
        bookings = helperGetBookings(bookings)
        scheduleEvents(bookings)

        atomicState.bookings = bookings

        runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

        // If you try to save a booking that's already passed, it will be removed by helperGetBookings
        if (atomicState.bookings[key] == null) {
            booking.code = null
            return sbHttpResponseJson(booking, 202)
        }

        if (helperHasDuplicateCode(params.lockId, atomicState.bookings[key], atomicState.bookings)) {
            return sbHttpResponseJson([ error: "Duplicate code: '${booking.code}'."], 409)
        }

        return sbHttpResponseJson(atomicState.bookings[key])
    } catch (Exception ex) {
        log.error "apiSyncBookingByLock: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Delete a single booking, schedule tasks, and reconcile door codes
Map apiDeleteBooking() {
    log.debug "apiDeleteBooking ${params.bookingId}"

    try {
        params.lockId = request.JSON.lockId

        return apiDeleteBookingByLock()
    } catch (Exception ex) {
        log.error "apiDeleteBooking: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Delete a single booking, schedule tasks, and reconcile door codes
Map apiDeleteBookingByLock() {
    log.debug "apiDeleteBookingByLock ${params.bookingId} ${params.lockId}"

    try {
        String key = params.bookingId

        Map bookings = atomicState.bookings.collectEntries { k, booking ->
            if (k == key) {
                booking.lockId = booking.lockId - params.lockId
            }
            return [ k, booking ]
        }
        .findAll { k, booking ->
            return booking.lockId.size() > 0
        }

        atomicState.bookings = helperGetBookings(bookings)
        scheduleEvents(bookings)

        runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

        return sbHttpResponseJson(null)
    } catch (Exception ex) {
        log.error "apiDeleteBookingByLock: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Help functions
// Convert various date representations to Date objects
// Handles: Date, Number (epoch millis), String (via toDateTime)
Date helperToDate(def value) {
    if (value == null) return null
    if (value instanceof Date) return value
    if (value instanceof Number) return new Date(value)
    if (value instanceof String) {
        try {
            return toDateTime(value)
        } catch (e) {
            log.error "helperToDate: could not parse '${value}': ${e.message}"
            return null
        }
    }
    return null
}

String helperGetCodeName(Map booking) {
    String prefix = codeNamePrefix ?: ''
    String orbId = 'ORB' + booking.id
    String guest = booking.guest ?: ''

    if (!guest) return prefix + orbId

    if (idAtEnd) {
        return prefix + guest + '-' + orbId
    }
    return prefix + orbId + '-' + guest
}

// Format booking (ensure keyed by id, dates are objects), and only return future bookings
Map helperGetBookings(Map bookings) {
    log.debug 'helperGetBookings'

    Date now = new Date()

    return bookings.collect { key, booking ->
        log.trace "helperGetBookings state.bookings.collect ${key} ${booking}"
        booking.checkIn = helperToDate(booking.checkIn)
        booking.checkOut = helperToDate(booking.checkOut)
        return booking
    }
    .findAll { booking ->
        return booking.checkIn >= now || booking.checkOut >= now
    }
    .groupBy { booking ->
        log.trace "helperGetBookings state.bookings.groupBy ${booking}"
        return booking.id
    }
    .collectEntries { k, group ->
        log.trace "helperGetBookings state.bookings.collectEntries ${k} ${group}"
        return [ k, group.inject(group[0] + [lockId: []]) { acc, v -> acc.lockId += v.lockId; acc } ]
    }
}

// Check for duplicate code
boolean helperHasDuplicateCode(String lockId, Map booking, Map bookings = null) {
    log.debug "helperHasDuplicateCode ${lockId} ${booking.id} ${booking.code}"

    def lock = locks.find { lock -> lock.id == lockId }

    if (!lock) {
        return false
    }

    String lockCodesJson = lock.currentValue('lockCodes', true);

    if (!lockCodesJson) {
        return false
    }

    Map lockCodes = parseJson(lockCodesJson)

    String codeName = helperGetCodeName(booking)

    return lockCodes.any { k, existing ->
        if (existing.name == codeName || existing.code != booking.code) {
            return false
        }

        if (existing.name.contains('ORB')) {
            // Extract the booking id with a regex (hyphen-safe) instead of splitting
            // on '-', which threw when the guest name contained a hyphen (the id
            // landed past parts[1], so substring(indexOf('ORB')) == substring(-1)).
            // SignalBricks keys bookings by BARE digits, so capture group 1.
            def m = (existing.name =~ /ORB(\d+)/)
            if (m.find()) {
                String bookingId = m.group(1)
                if (bookings && bookings.containsKey(bookingId)) {
                    // Only warn if the bookings overlap check-in to check-out
                    Map otherBooking = bookings[bookingId]
                    log.debug "helperHasDuplicateCode: checking active booking ${otherBooking}"
                    // Other booking already active - only need check-out after check-in
                    return otherBooking.checkOut > booking.checkIn
                }
            }
        }

        return true
    }
}

// For each lock, find the next/current booking
Map helperFindNextBooking(Map bookings) {
    log.debug 'helperFindNextBooking'

    if (!bookings) {
        bookings = helperGetBookings(state.bookings)
    }

    // Aggregate next bookings by lockId
    return bookings.inject([:]) { byLock, key, booking ->
        booking.lockId.each { lockId ->
            if (byLock[lockId] == null) {
                byLock[lockId] = booking
            } else if (booking.checkIn < byLock[lockId].checkIn) {
                byLock[lockId] = booking
            }
        }

        return byLock
    }
}

// Find all bookings that are currently active
Map helperFindCurrentBookings(Map bookings) {
    log.debug 'helperFindCurrentBooking'

    Date now = new Date()

    return bookings.findAll { id, booking ->
        if (booking.checkIn <= now && booking.checkOut >= now) {
            log.trace "helperFindCurrentBooking ${booking}"
            return true
        }

        return false
    }
}

// Get only the SignalBricks codes based on code name beginning with the booking Id
Map helperOnlySbCodes(Map codes, def lockId = null) {
    log.debug 'helperOnlySbCodes'

    String prefix = codeNamePrefix ?: ''
    Map result = [:]

    // First: check lock's reported code names (works when lock driver preserves names)
    codes.each { key, code ->
        if (code.name?.contains('ORB')) {
            if (prefix && !code.name.startsWith(prefix)) return
            def matcher = (code.name =~ /ORB(\d+)/)
            if (matcher.find()) {
                code.key = key
                String bId = matcher.group(1) // Just the digits, no ORB prefix
                code.bookingId = bId
                result[bId] = code
                log.debug("helperOnlySbCodes: found by name: ${bId} at pos ${key}")
            }
        }
    }

    // Second: check tracked assignments (for lock drivers that don't preserve names)
    if (lockId) {
        Map assignments = atomicState.codeAssignments ?: [:]
        String lockKey = "${lockId}"
        Map lockAssignments = assignments[lockKey] ?: [:]

        lockAssignments.each { pos, assignment ->
            // Extract numeric ID from tracked bookingId (may be "ORB12345" or "12345")
            String bId = assignment.bookingId
            if (!bId) return
            def bMatcher = (bId =~ /(\d+)/)
            if (bMatcher.find()) bId = bMatcher.group(1)
            if (result[bId]) return // Already found by name

            // Verify there is a code at this position on the lock
            // Don't compare code values — some drivers redact codes as ********
            if (codes[pos] || codes["${pos}"]) {
                def lockCode = codes[pos] ?: codes["${pos}"]
                lockCode.key = pos
                lockCode.bookingId = bId
                lockCode.code = assignment.code // Use our tracked code, not the lock's (may be redacted)
                result[bId] = lockCode
                log.debug("helperOnlySbCodes: found by tracking: ${bId} at pos ${pos}")
            }
        }
    }

    return result
}

// Find all unused code positions
List helperFindCodePositions(def lock, Map codes) {
    log.debug 'helperFindCodePositions'

    List positions = []

    for (int i = minCodePosition; i <= lock.currentValue('maxCodes'); i++) {
        // The map key is a string, so we have to check for both just to be safe
        if (!codes[i] && !codes["${i}"]) {
            positions << i
        }
    }

    log.trace "helperFindCodePositions ${positions}"

    return positions
}

// EOF
