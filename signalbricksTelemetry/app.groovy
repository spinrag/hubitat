/**
 * SignalBricks Telemetry
 *
 * Read-only telemetry sender for SignalBricks. Forwards lock, contact,
 * temperature, humidity and battery events to the SignalBricks ingestion API.
 * Telemetry never sends device commands and never reads or sends lock codes
 * (PINs): the `lockCodes` attribute is deliberately not subscribed to.
 *
 * "Include door codes (hashed by SignalBricks)" (off by default,
 * signalbricks/platform#388): the hub half of a two-key opt-in. When on, the
 * code used for a `lock` event (the `code` in its data) is sent over the same
 * authenticated ingestion channel; every other secret is still stripped.
 * SignalBricks keeps it only if the organization's admin-only "Hash door
 * codes from Hubitat telemetry" policy is also on, and then only as a keyed
 * hash; otherwise it redacts it. Codes are still never logged.
 *
 * Optional "Lock code management" (off by default, signalbricks/platform#472):
 * when enabled, separately selected locks get access-token gated endpoints
 * that let SignalBricks list slots (never codes), set and delete codes and
 * reveal one code at a time. The hub enforces which slots may be touched:
 * admin/managed ranges and adopted slots come from SignalBricks (/lockConfig),
 * but only up to a ceiling set in this app's UI; adopted slots above it need
 * the operator's approval in the UI. Slots whose name contains an upper-case
 * "ORB" followed by a digit anywhere (booking codes) are always refused, and
 * created names must carry SBA:/SBM: and never such an "ORB".
 * So neither the platform nor anyone holding its token can reach booking
 * codes. Codes are never logged or put in events.
 *
 * Fingerprints (signalbricks/platform#532): SignalBricks may push a per-org
 * fingerprint key in /lockConfig; /locks/:id/fingerprints then returns a
 * truncated HMAC-SHA256 of each allowed slot's code (never the code), so
 * SignalBricks can see which slots share a code across locks and hubs. The key
 * is write-only: never returned, never logged, cleared on disable/disconnect.
 * /fingerprints (#17) returns the same for several locks in one call.
 *
 * Each set/delete is verified on the hub (signalbricks/platform#520): the app
 * keeps one pending change per lock and slot, re-reads the slot from
 * lockCodes, re-sends the command up to 3 times and reports the outcome as a
 * `lock_code_result` event (never the code). A pending set keeps its code in
 * app state only, until it is confirmed, superseded, cleared or 24h old.
 *
 * Relocate (#31): /locks/:id/codes/:slot/relocate renames a code or moves it
 * to another slot entirely on the hub. The code is read from the lock's own
 * lockCodes and never appears in the request, response, logs or events. A move
 * writes the new slot first and deletes the old one only once a re-read shows
 * the new slot holding the same code, so the code is never in neither slot.
 *
 * Registered slots and full lock control (signalbricks/platform#684): a lock
 * in /lockConfig may list `registered` slots, each a slot plus the exact name
 * SignalBricks expects there (and, during a rename, a `pendingName`). Unlike
 * adopted slots, the hub only lets SignalBricks touch a registered slot while
 * the lock shows one of those names, so custom names without SBA:/SBM: stay
 * safe. `fullControl` on a lock lets new codes go into empty registered slots
 * outside the admin/managed ranges (never above the ceiling), and lets
 * SignalBricks reach registered codes above the ceiling only on locks the hub
 * operator also allowed here. A stricter booking rule guards these paths:
 * names with "orb" + digit in any case, an upper-case "ORB" starting a word,
 * or a G:/GT: prefix, and unnamed slots, are never registered or touched.
 * Without these fields everything works exactly as before.
 *
 * Updates (#13): a daily check-in at a random per-hub time re-registers when
 * the version changed, since an HPM update doesn't run updated().
 *
 * Setup uses Hubitat's documented OAuth "stateredirect" pattern with
 * SignalBricks as the OAuth provider: "Connect to SignalBricks" opens the
 * SignalBricks portal, the user picks the organization/property/hub, and
 * Hubitat cloud forwards the authorization code to this app's /oauthCallback
 * mapping, which exchanges it for an ingestion token. The app is a public
 * OAuth client and uses PKCE (S256); there is no client secret.
 *
 * Reconnect (#46) is a button that opens the connect link for 10 minutes, so
 * it is available while lock code management is on: the callback, or the
 * link's expiry, replaces the access token the link carried and registers the
 * new one. Only the token changes.
 *
 * Runs alongside the SignalBricks Integration (lock-code) app; the two are
 * independent.
 *
 * Copyright (c) 2026 Spinrag, LLC. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Field static final String APP_NAME = 'SignalBricks Telemetry'
@Field static final String APP_VERSION = '1.5.2'
@Field static final String SB_PORTAL_URL = 'https://app.signalbricks.co'
// Public OAuth client (this source is public): no client secret. Each connect
// is protected by PKCE (S256) plus a signed-in SignalBricks admin's consent.
@Field static final String OAUTH_CLIENT_ID = 'signalbricks-hubitat-telemetry'
@Field static final String HUBITAT_STATE_REDIRECT = 'https://cloud.hubitat.com/oauth/stateredirect'
@Field static final String DOCS_URL = 'https://github.com/spinrag/hubitat/blob/main/signalbricksTelemetry/README.md'
// Door-code (PIN) keys, matched exactly on the whole key, case-insensitively
// (never substrings: errorCode, statusCode, codeNumber, codeName pass through).
// Same rules as the platform's redactSecrets (signalbricks/platform#387).
@Field static final Set<String> ALWAYS_SECRET_KEYS = ['pin', 'passcode', 'lockcodes', 'usedpin', 'door_codes', 'doorcodes'] as Set
// `code` is ambiguous (PIN vs an error code), so it is only removed in lock
// context: lock events, or a per-slot lock-code map {"3":{"name":"Guest","code":"1234"}}.
@Field static final Set<String> LOCK_CONTEXT_SECRET_KEYS = ['code', 'usedcode'] as Set
@Field static final Set<String> LOCK_EVENT_NAMES = ['lock', 'unlock', 'codechanged', 'lastcodename', 'lockcodes'] as Set
// "Include door codes" (#388): the only event whose `code` may be sent, and only that key.
@Field static final String CODE_PASSTHROUGH_EVENT = 'lock'
@Field static final String CODE_PASSTHROUGH_KEY = 'code'

// Lock code management (#472)
@Field static final int MAX_SLOT = 1000
@Field static final int MAX_ADOPTED_PER_LOCK = 250
@Field static final int MAX_NAME_LENGTH = 64
// Default hub-local ceiling for admin + managed ranges (layout: admin 1-10,
// managed 11-25, bookings from 26). Only the app UI can change it.
@Field static final int DEFAULT_MAX_MANAGED_SLOT = 25
@Field static final int REVEAL_LIMIT_PER_MIN = 10
// Fingerprints (#532): HMAC-SHA256(fingerprintKey, code), first 16 hex chars.
@Field static final int FINGERPRINT_LIMIT_PER_MIN = 10
// Batch fingerprints (#17): one call reads many locks, so its own small limit.
@Field static final int FINGERPRINT_BATCH_LIMIT_PER_MIN = 4
@Field static final int FINGERPRINT_BATCH_MAX_IDS = 100
@Field static final int FINGERPRINT_HEX_LENGTH = 16
@Field static final int FINGERPRINT_KEY_BYTES = 32
@Field static final String ADMIN_NAME_PREFIX = 'SBA:'
@Field static final String MANAGED_NAME_PREFIX = 'SBM:'
// Hubs connected before signalbricks/platform#505 hold an ingestion token that
// can't register lock management; only a reconnect issues one that can (#522).
@Field static final String RECONNECT_MESSAGE = 'Reconnect to SignalBricks to enable lock management'
// An HPM update replaces the code without calling updated(), so the app
// re-registers itself once the version changes, at most this often (#539).
@Field static final long REREGISTER_INTERVAL_MS = 30L * 60 * 1000
// Reconnect link (#46): a click on Reconnect opens a sign-in link for this long.
@Field static final int CONNECT_LINK_SECONDS = 600
// Automatic retries of a registration that failed for a reason that may pass
// (timeout, rate limit, SignalBricks unavailable): after about 30 s, 2 min, 5 min.
@Field static final List<Integer> REGISTER_RETRY_SECONDS = [30, 120, 300]
// Daily check-in (#13): runs that check once a day, so a quiet hub reports a
// new version without waiting for an event. Each hub picks its own random
// time, outside 02:00-03:59 (hub maintenance and backups).
@Field static final int CHECK_IN_WINDOW_START_SEC = 4 * 3600
@Field static final int CHECK_IN_WINDOW_SEC = 22 * 3600

// Set/delete verification (#520). Each attempt is checked 30s after it is
// sent and again at its final check; the final check backs off per attempt
// (120s, 150s, 180s), so a change is settled within 7.5 minutes.
@Field static final int CODE_MAX_ATTEMPTS = 3
@Field static final int CODE_FIRST_CHECK_SECONDS = 30
@Field static final List<Integer> CODE_FINAL_CHECK_SECONDS = [120, 150, 180]
@Field static final long PENDING_CODE_TTL_MS = 24L * 60 * 60 * 1000
@Field static final int RETRY_LIMIT_PER_MIN = 10
// Set + delete requests across the app, and the most changes held at once.
@Field static final int WRITE_LIMIT_PER_MIN = 30
@Field static final int MAX_PENDING_CODE_CHANGES = 100
// Sent at registration so SignalBricks only offers what this version supports.
@Field static final List<String> CAPABILITIES = ['relocate', 'registered_slots', 'full_control']
// The /lockConfig fields this version reads (signalbricks/platform#684), returned
// with every config reply so SignalBricks can tell it isn't talking to an older app.
@Field static final List<String> CONFIG_FEATURES = ['registered_slots', 'full_control']
@Field static final java.util.regex.Pattern REQUEST_ID = ~/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/

definition(
    name: 'SignalBricks Telemetry',
    namespace: 'spinrag',
    author: 'Chris Ogden',
    description: 'Sends read-only lock, contact, temperature, humidity and battery events to SignalBricks.',
    category: 'Integrations', // Unused by the platform; `menu` places the app
    menu: 'Integrations',
    singleThreaded: true,
    installOnOpen: true,
    oauth: true,
    iconUrl: '',
    iconX2Url: '',
    documentationLink: 'https://github.com/spinrag/hubitat/blob/main/signalbricksTelemetry/README.md',
)

preferences {
    page(name: 'mainPage')
}

mappings {
    // Hubitat cloud forwards the OAuth `code` here (via the stateredirect `state`).
    path('/oauthCallback') {
        action: [GET: 'oauthCallback']
    }

    // Lock code management (#472). Every handler returns 404 unless the
    // section is enabled and the hub is connected.
    path('/lockConfig') {
        action: [GET: 'apiGetLockConfig', POST: 'apiSetLockConfig']
    }
    path('/locks') {
        action: [GET: 'apiListLocks']
    }
    path('/locks/:lockId/slots') {
        action: [GET: 'apiListSlots']
    }
    path('/locks/:lockId/fingerprints') {
        action: [GET: 'apiListFingerprints']
    }
    path('/fingerprints') {
        action: [GET: 'apiListFingerprintsBatch']
    }
    path('/locks/:lockId/codes') {
        action: [POST: 'apiSetCode']
    }
    path('/locks/:lockId/codes/:slot') {
        action: [DELETE: 'apiDeleteCode']
    }
    path('/locks/:lockId/codes/:slot/reveal') {
        action: [GET: 'apiRevealCode']
    }
    path('/locks/:lockId/codes/:slot/retry') {
        action: [POST: 'apiRetryCode']
    }
    path('/locks/:lockId/codes/:slot/relocate') {
        action: [POST: 'apiRelocateCode']
    }
}

Map mainPage() {
    // Turning lock management off takes effect at once (submitOnChange), not
    // only on Done.
    syncLockManagementEnabled()
    dynamicPage(name: 'mainPage', title: 'SignalBricks Telemetry', install: true, uninstall: true) {
        section() {
            paragraph 'Sends device events to SignalBricks. Telemetry is read-only: it never controls devices and never sends lock codes (PINs).'
        }

        section('<b>Devices</b>') {
            input(name: 'locks', type: 'capability.lock', title: 'Locks (lock state, battery, last code name)', multiple: true, required: false, submitOnChange: true)
            input(name: 'contactSensors', type: 'capability.contactSensor', title: 'Contact sensors', multiple: true, required: false, submitOnChange: true)
            input(name: 'temperatureSensors', type: 'capability.temperatureMeasurement', title: 'Temperature sensors', multiple: true, required: false, submitOnChange: true)
            input(name: 'humiditySensors', type: 'capability.relativeHumidityMeasurement', title: 'Humidity sensors', multiple: true, required: false, submitOnChange: true)
        }

        section('<b>Door codes in telemetry (optional)</b>') {
            paragraph 'Off by default: the code used to unlock a lock is removed before anything is sent; only its slot and name are. When on, the code of lock events is sent to SignalBricks, which keeps only a keyed hash of it, and only if an admin of your SignalBricks organization has also turned on <i>Hash door codes from Hubitat telemetry</i>. Otherwise SignalBricks removes it. Codes are never logged here, and lock code lists (<code>lockCodes</code>) are never read for telemetry.'
            input(name: 'includeDoorCodes', type: 'bool', title: 'Include door codes (hashed by SignalBricks)', defaultValue: false, submitOnChange: true)
        }

        section('<b>Connection</b>') {
            if (!ensureAccessToken()) {
                paragraph '<b>OAuth is not enabled for this app.</b> In <i>Apps Code</i>, open SignalBricks Telemetry, click <i>OAuth</i> → <i>Enable OAuth in App</i> → <i>Update</i>, then reopen this app.'
            } else if (state.sbToken) {
                paragraph "<b>Status:</b> Connected${state.connectedAt ? " since ${state.connectedAt}" : ''}"
                paragraph "<b>Sending to:</b> ${state.ingestUrl}"
                if (checkInTime()) {
                    paragraph "<small>Daily update check-in at ${checkInTime()} (hub time).</small>"
                }
                if (state.lastError) {
                    paragraph "<b>Last error:</b> ${escapeHtml(state.lastError)}"
                }
                String retry = registerRetryStatus()
                if (retry) paragraph retry
                if (state.lockMgmtReconnectRequired) {
                    paragraph "<b>Lock code management:</b> ${RECONNECT_MESSAGE}."
                }
                // The connect URL carries this app's access token in the OAuth
                // state, and while lock management is on that token is the one
                // SignalBricks holds for lock control: so the link exists only
                // for a few minutes after a click (#46).
                String until = connectLinkUntil()
                if (until) {
                    href(name: 'hrefReconnect', title: 'Continue to SignalBricks', description: "Sign in again. This link works until ${until} (hub time); after that the app replaces its access token and registers the new one", style: 'external', url: connectUrl())
                } else {
                    input(name: 'btnReconnect', type: 'button', title: 'Reconnect to SignalBricks')
                    paragraph '<small>Reconnect makes a sign-in link that works for 10 minutes and issues a new ingestion token. Finishing it, or letting it expire, replaces this app\'s access token and registers the new one. Lock code management stays on, with its slot rules, approvals and pending code changes.</small>'
                }
                input(name: 'btnDisconnect', type: 'button', title: 'Disconnect')
                paragraph '<small>Disconnect stops sending immediately. To also revoke the token, remove it under Tokens in the SignalBricks portal.</small>'
            } else {
                paragraph '<b>Status:</b> Not connected'
                if (state.lastError) {
                    paragraph "<b>Last error:</b> ${escapeHtml(state.lastError)}"
                }
                // Not connected: SignalBricks holds no valid token for this app
                // (Disconnect rotated it), so the token in this URL controls nothing.
                href(name: 'hrefConnect', title: 'Connect to SignalBricks', description: 'Sign in and choose the property this hub belongs to', style: 'external', url: connectUrl())
            }
        }

        section('<b>Lock code management (optional)</b>') {
            paragraph 'Lets SignalBricks list code slots and manage admin (<code>SBA:</code>) and managed (<code>SBM:</code>) codes on the locks selected here. Off by default. SignalBricks can only touch slots up to the ceiling you set here, plus existing codes you approve below; booking codes (names with <code>ORB</code> followed by a digit) are always refused. Codes are never logged or sent as telemetry.'
            input(name: 'lockManagementEnabled', type: 'bool', title: 'Enable lock code management', defaultValue: false, submitOnChange: true)
            if (lockManagementEnabled) {
                input(name: 'codeLocks', type: 'capability.lockCodes', title: 'Locks SignalBricks may manage codes on', multiple: true, required: false, submitOnChange: true)
                input(name: 'maxManagedSlot', type: 'number', title: "Highest slot SignalBricks may manage (admin + managed codes; keep it below the booking apps' minimum code position)", range: "1..${MAX_SLOT}", defaultValue: DEFAULT_MAX_MANAGED_SLOT, required: true, submitOnChange: true)
                if (codeLocks) {
                    paragraph '<b>Full lock control (optional).</b> On the locks chosen here, and only once SignalBricks also turns full control on for them, SignalBricks may reach codes above the ceiling that it registered by slot and name (codes an admin adopted in SignalBricks). It never writes into empty slots above the ceiling, and never touches booking codes (names with <code>ORB</code> followed by a digit, or starting with <code>G:</code> or <code>GT:</code>) or codes without a name. Leave empty to keep everything above the ceiling behind the approvals below.'
                    input(name: 'fullControlLocks', type: 'enum', title: 'Locks SignalBricks may fully control', options: codeLocks.collectEntries { [(it.id.toString()): escapeHtml(it.displayName?.toString())] }, multiple: true, required: false, submitOnChange: true)
                }
                Map pending = pendingAdoptedOptions()
                if (pending) {
                    paragraph '<b>Existing codes above the ceiling that SignalBricks asks to manage.</b> Approve only codes you recognise as admin or managed codes; never booking codes.'
                    input(name: 'approvedAdoptedSlots', type: 'enum', title: 'Approve these slots', options: pending, multiple: true, required: false, submitOnChange: true)
                }
                if (!state.sbToken) {
                    paragraph '<b>Connect to SignalBricks first.</b> Lock code management registers with SignalBricks after Connect.'
                } else {
                    if (state.lockMgmtReconnectRequired) {
                        paragraph "<b>Registration:</b> ${RECONNECT_MESSAGE} (click Reconnect to SignalBricks above)."
                    } else {
                        paragraph "<b>Registration:</b> ${state.lockMgmtRegisteredAt ? "registered ${state.lockMgmtRegisteredAt}" : 'not registered yet (click Done)'}"
                    }
                    if (state.lockMgmtRegisterError) {
                        paragraph "<b>Last registration error:</b> ${state.lockMgmtRegisterError}"
                    }
                    paragraph "<b>Slot rules:</b> ${state.lockConfig ? 'set by SignalBricks' : 'not set yet (all code writes are refused until SignalBricks sends them)'}"
                    if (state.lockConfig) paragraph "<b>Fingerprint key:</b> ${state.fingerprintKey ? 'set by SignalBricks' : 'not set (code fingerprints for onboarding are unavailable)'}"
                    Map changes = pendingCodeChanges()
                    if (changes) {
                        int failed = changes.values().count { it.status == 'failed' }
                        paragraph "<b>Code changes awaiting the lock:</b> ${changes.size() - failed} in progress, ${failed} failed (SignalBricks can retry failed changes for 24 hours)"
                    }
                    input(name: 'btnRotateLockToken', type: 'button', title: 'Rotate access token and re-register')
                }
            }
        }

        section('<b>Logging</b>') {
            input(name: 'debugLogging', type: 'bool', title: 'Enable debug logging (turns off after 30 minutes)', defaultValue: false, submitOnChange: true)
            paragraph "<small>${appNameAndVersion()} · <a href='${DOCS_URL}' target='_blank'>Documentation</a></small>"
        }
    }
}

// Shown on the app page and in log lines that carry the version, e.g.
// "SignalBricks Telemetry 1.3.4" (#20). Registration and events keep sending
// the bare `version`.
String appNameAndVersion() {
    return "${APP_NAME} ${APP_VERSION}"
}

void installed() {
    log.info "${appNameAndVersion()} installed"
    ensureAccessToken()
    initialize()
}

void updated() {
    logDebug 'updated'
    initialize()
    if (!syncLockManagementEnabled()) {
        // Re-register on every save, so enabling and app upgrades reach the
        // platform. Cheap: one request, only when connected and lock
        // management is on or was registered.
        registerWithPlatform()
    }
}

// On an off -> on flip: rotate the access token before it is registered, so
// an older token (e.g. from an abandoned Reconnect link) never becomes the
// live lock token. On an on -> off flip: rotate it (the one SignalBricks holds
// stops working), drop the slot rules and tell the platform. Returns true when
// it just handled a disable (and so already registered).
Boolean syncLockManagementEnabled() {
    Boolean enabled = lockManagementEnabled == true
    Boolean wasEnabled = state.lockMgmtLastEnabled == true
    state.lockMgmtLastEnabled = enabled
    if (!wasEnabled && enabled) {
        log.info 'SignalBricks Telemetry: lock code management turned on; access token rotated'
        createAccessToken()
        return false
    }
    if (!wasEnabled || enabled) return false
    log.info 'SignalBricks Telemetry: lock code management turned off; access token rotated'
    createAccessToken()
    abandonPendingCodeChanges('lock_management_disabled')
    state.remove('lockConfig')
    state.remove('fingerprintKey')
    state.remove('revealTimes')
    state.remove('retryTimes')
    state.remove('writeTimes')
    state.remove('fingerprintTimes')
    state.remove('fingerprintBatchTimes')
    app.removeSetting('approvedAdoptedSlots')
    app.removeSetting('fullControlLocks')
    registerWithPlatform(false)
    return true
}

void uninstalled() {
    unsubscribe()
    unschedule()
}

void initialize() {
    unsubscribe()
    unschedule()
    subscribeToEvents()
    scheduleDailyCheckIn()
    // unschedule() also removed these timers; put them back for the time left
    connectLinkRearm()
    registerRetryRearm()
    if (debugLogging) {
        runIn(1800, 'logsOff')
    }
    dropCodeChangesForMissingLocks()
    resumePendingCodeChanges()
}

void logsOff() {
    log.info 'Debug logging disabled'
    app.updateSetting('debugLogging', [value: 'false', type: 'bool'])
}

// Subscribe ONLY to read-only telemetry attributes. Never `lockCodes` (contains PINs).
void subscribeToEvents() {
    if (locks) {
        subscribe(locks, 'lock', 'eventHandler')
        subscribe(locks, 'lastCodeName', 'eventHandler')
    }
    if (contactSensors) subscribe(contactSensors, 'contact', 'eventHandler')
    if (temperatureSensors) subscribe(temperatureSensors, 'temperature', 'eventHandler')
    if (humiditySensors) subscribe(humiditySensors, 'humidity', 'eventHandler')

    // Battery once per device, even if it is selected in several lists.
    List batteryDevices = ((locks ?: []) + (contactSensors ?: []) + (temperatureSensors ?: []) + (humiditySensors ?: []))
        .unique { it.id }
    batteryDevices.each { subscribe(it, 'battery', 'eventHandler') }
}

void appButtonHandler(String btn) {
    if (btn == 'btnDisconnect') {
        log.info 'Disconnected from SignalBricks'
        // Tell the platform lock management is off (best effort), then
        // invalidate the access token it may hold.
        registerWithPlatform(false)
        createAccessToken()
        clearConnectLink()
        clearRegisterRetry()
        abandonPendingCodeChanges('disconnected')
        state.remove('lockConfig')
        state.remove('fingerprintKey')
        app.removeSetting('approvedAdoptedSlots')
        app.removeSetting('fullControlLocks')
        state.remove('sbToken')
        state.remove('ingestUrl')
        state.remove('connectedAt')
        state.remove('lastError')
        state.remove('lockMgmtRegisteredAt')
        state.remove('lockMgmtRegisterError')
        state.remove('lockMgmtRegistered')
        state.remove('lockMgmtReconnectRequired')
        state.remove('registeredVersion')
        state.remove('reregisterAttemptAt')
        unschedule('dailyCheckIn')
        atomicState.remove('checkInSecond')
    } else if (btn == 'btnReconnect') {
        issueConnectLink()
    } else if (btn == 'btnRotateLockToken') {
        log.info 'SignalBricks Telemetry: access token rotated'
        // Any open Reconnect link carried the old token, so it is dead now
        clearConnectLink()
        createAccessToken()
        registerWithPlatform()
    }
}

void eventHandler(evt) {
    if (!state.sbToken || !isHttps(state.ingestUrl)) return
    // Hubs updated by HPM from before 1.3.2 never ran initialize(); schedule once.
    if (atomicState.checkInSecond == null) scheduleDailyCheckIn()

    Map payload = [
        id: evt.id,
        deviceId: evt.deviceId,
        name: evt.name,
        displayName: evt.displayName,
        source: evt.source,
        value: evt.value,
        data: sanitizeData(evt.data, evt.name),
        descriptionText: evt.descriptionText,
        isStateChange: evt.isStateChange,
        type: evt.type,
        date: evt.date ? evt.date.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC')) : null,
        unit: evt.unit,
        version: APP_VERSION,
        hubFirmwareVersion: hubFirmwareVersion(),
    ]
    // Never the data: it may carry a door code when "Include door codes" is on.
    logDebug "sending ${evt.displayName} ${evt.name}=${evt.value}"

    Map params = [
        uri: state.ingestUrl,
        requestContentType: 'application/json',
        contentType: 'application/json',
        headers: [Authorization: "Bearer ${state.sbToken}"],
        body: JsonOutput.toJson(payload),
        timeout: 15,
    ]
    try {
        asynchttpPost('postCallback', params, [name: evt.name, deviceId: evt.deviceId])
    } catch (Exception e) {
        // Only the class name: the request body may carry a door code (#388).
        log.warn "SignalBricks Telemetry: failed to send ${evt.name} for device ${evt.deviceId} (${e.class.simpleName})"
    }
    reregisterIfVersionChanged()
}

// Door codes (PINs) must never leave the hub, unless "Include door codes" is
// on (#388). `evt.data` is a JSON string; parse it and drop secret keys at any
// depth (slot numbers, code names, error and status fields stay). Anything
// that isn't parseable JSON is dropped entirely rather than sent raw. Applied
// to every event, not just locks. With "Include door codes" on, only the
// `code` (a string or number) of `lock` events is kept; every other secret key
// is still dropped, on every event.
String sanitizeData(String raw, String eventName) {
    if (!raw) return null
    try {
        Boolean lockContext = LOCK_EVENT_NAMES.contains(eventName?.toLowerCase())
        Boolean keepCode = includeDoorCodes == true && eventName == CODE_PASSTHROUGH_EVENT
        return JsonOutput.toJson(stripSecrets(parseJson(raw), lockContext, keepCode))
    } catch (Exception e) {
        return null
    }
}

def stripSecrets(obj, Boolean lockContext, Boolean keepCode = false) {
    if (obj instanceof Map) {
        Boolean lockHere = lockContext || isLockCodeMap(obj)
        return obj.findAll { k, v -> !isSecretKey(k, lockHere) || (keepCode && isPassthroughCode(k, v)) }
            .collectEntries { k, v -> [(k): stripSecrets(v, lockHere, keepCode)] }
    }
    if (obj instanceof List) return obj.collect { stripSecrets(it, lockContext, keepCode) }
    return obj
}

Boolean isPassthroughCode(key, value) {
    return key.toString().toLowerCase() == CODE_PASSTHROUGH_KEY && (value instanceof String || value instanceof Number)
}

Boolean isSecretKey(key, Boolean lockContext) {
    String k = key.toString().toLowerCase()
    return ALWAYS_SECRET_KEYS.contains(k) || (lockContext && LOCK_CONTEXT_SECRET_KEYS.contains(k))
}

// {"3":{"name":"Guest","code":"1234"}}: Hubitat's per-slot lock-code map.
Boolean isLockCodeMap(Map obj) {
    return !obj.isEmpty() && obj.every { k, v ->
        k.toString() ==~ /\d+/ && v instanceof Map && v.keySet().any { it.toString().toLowerCase() == 'code' }
    }
}

void postCallback(resp, Map data) {
    if (resp.hasError()) {
        int status = resp.getStatus()
        String msg = status == 401 ?
            'SignalBricks rejected the token (revoked?). Reconnect to SignalBricks.' :
            "HTTP ${status}: ${resp.getErrorMessage()}"
        log.warn "SignalBricks Telemetry: failed to send ${data?.name} for device ${data?.deviceId}: ${msg}"
        state.lastError = "${new Date().format('yyyy-MM-dd HH:mm')} ${msg}"
        return
    }
    if (state.lastError) state.remove('lastError')
    logDebug "sent ${data?.name} for device ${data?.deviceId} (HTTP ${resp.getStatus()})"
}

// --- OAuth connect flow ---

Boolean ensureAccessToken() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return true
    } catch (Exception e) {
        logDebug "createAccessToken failed: ${e.message}"
        return false
    }
}

// PKCE (RFC 7636): code_verifier = 96 hex chars from three random UUIDs
// (java.util.UUID.randomUUID() is backed by SecureRandom; ~366 bits). Hex is
// within the unreserved charset and 96 is within the 43-128 length limit.
String newCodeVerifier() {
    return (1..3).collect { UUID.randomUUID().toString().replace('-', '') }.join('')
}

// code_challenge = BASE64URL(SHA-256(verifier)) without padding (S256 only; never `plain`).
String codeChallengeFor(String verifier) {
    byte[] digest = java.security.MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('US-ASCII'))
    return digest.encodeBase64().toString().replace('+', '-').replace('/', '_').replace('=', '')
}

String connectUrl() {
    // One verifier per connect attempt; kept until the callback uses it, so
    // page re-renders don't invalidate a link the user already opened.
    if (!state.pkceVerifier) state.pkceVerifier = newCodeVerifier()

    // Same stateredirect pattern as other published Hubitat OAuth apps.
    // SECURITY: stateRedirect contains this app's access token; never log it
    // (or this URL).
    String stateRedirect = URLEncoder.encode("${getHubUID()}/apps/${app.id}/oauthCallback?access_token=${state.accessToken}", 'UTF-8')
    Map query = [
        client_id: OAUTH_CLIENT_ID,
        redirect_uri: HUBITAT_STATE_REDIRECT,
        response_type: 'code',
        code_challenge: codeChallengeFor(state.pkceVerifier),
        code_challenge_method: 'S256',
        hub_uid: getHubUID(),
        app_id: app.id.toString(),
        version: APP_VERSION,
        location: location?.name ?: '',
    ]
    String qs = query.collect { k, v -> "${k}=${URLEncoder.encode(v.toString(), 'UTF-8')}" }.join('&')
    return "${SB_PORTAL_URL}/oauth/hubitat/authorize?${qs}&state=${stateRedirect}"
}

// Never log `params` here: it carries the authorization code and this app's access_token.
def oauthCallback() {
    // The link was used: its expiry has nothing left to do (#46)
    clearConnectLink()
    Map page = handleOauthCallback()
    // The access token travelled in the authorize URL's `state`, so copies can
    // sit in proxy/CDN access logs. Replace it once the callback has run
    // (success or failure): createAccessToken() invalidates the previous token,
    // the same behaviour signalbricksIntegration's "Refresh Access Token"
    // button relies on. The only other inbound calls are the lock-management
    // endpoints, which use the new token: it is registered with SignalBricks
    // below and never appears in a URL. Only the token changes: the slot
    // rules, fingerprint key, approvals and pending code changes stay.
    createAccessToken()
    registerWithPlatform()
    return page
}

// --- Reconnect link (#46) ---
// Reconnect is a button: a click opens the sign-in link for CONNECT_LINK_SECONDS.
// The callback (success or failure) replaces the access token and registers
// the new one; if no callback arrives in time, expireConnectLink does the same
// with the ingestion token the app already holds. Either way the token in the
// link stops working and lock code management carries on with the new one.
// Kept in atomicState with its expiry time, so initialize()'s unschedule() can
// put the timer back.

void issueConnectLink() {
    if (!state.sbToken || !ensureAccessToken()) return
    long at = now()
    atomicState.connectLink = [issuedAt: at, expiresAt: at + CONNECT_LINK_SECONDS * 1000L]
    runIn(CONNECT_LINK_SECONDS, 'expireConnectLink', [overwrite: true, data: [issuedAt: at]])
    log.info "SignalBricks Telemetry: Reconnect link opened for ${CONNECT_LINK_SECONDS.intdiv(60)} minutes"
}

// The open link's expiry as hub time (HH:mm:ss), or null when no link is open.
// A link past its expiry whose timer was lost is expired here.
String connectLinkUntil() {
    Map l = atomicState.connectLink
    if (!l) return null
    if (now() >= (l.expiresAt as long)) {
        expireConnectLink([issuedAt: l.issuedAt])
        return null
    }
    return new Date(l.expiresAt as long).format('HH:mm:ss', location?.timeZone ?: TimeZone.getDefault())
}

void connectLinkRearm() {
    Map l = atomicState.connectLink
    if (!l) return
    long left = (long) (((l.expiresAt as long) - now()) / 1000)
    runIn(Math.max(left, 1L), 'expireConnectLink', [overwrite: true, data: [issuedAt: l.issuedAt]])
}

void clearConnectLink() {
    atomicState.remove('connectLink')
    unschedule('expireConnectLink')
}

// No callback in time: replace the token in the link and register the new one,
// as the callback would. A link already used, or replaced by a newer one, is left.
void expireConnectLink(Map data) {
    Map l = atomicState.connectLink
    if (!l || data?.issuedAt == null || (data.issuedAt as long) != (l.issuedAt as long)) return
    atomicState.remove('connectLink')
    state.remove('pkceVerifier')
    createAccessToken()
    log.info 'SignalBricks Telemetry: Reconnect link expired unused; access token replaced'
    registerWithPlatform()
}

Map handleOauthCallback() {
    String code = params.code
    String verifier = state.pkceVerifier
    // Single use: the next connect attempt gets a fresh verifier.
    state.remove('pkceVerifier')
    if (params.error || !code) {
        String reason = params.error ?: 'no authorization code returned'
        // Fixed text only: the error param comes from the request.
        log.warn "SignalBricks connect cancelled: ${params.error == 'access_denied' ? 'access denied' : (params.error ? 'other error' : 'no authorization code')}"
        // The raw error goes to the app page only; the callback page never shows a request param.
        state.lastError = "SignalBricks did not authorize this hub (${reason})"
        return renderPage(false, 'Not connected',
            params.error == 'access_denied' ? 'Sign-in was cancelled, or SignalBricks denied access.' : 'SignalBricks did not authorize this hub.')
    }
    if (!verifier) {
        state.lastError = 'Connect session expired; start again from the app'
        return renderPage(false, 'Connect link expired', 'This connect link is no longer valid.')
    }

    Map tokenParams = [
        uri: "${SB_PORTAL_URL}/oauth/hubitat/token",
        requestContentType: 'application/json',
        contentType: 'application/json',
        body: JsonOutput.toJson([
            grant_type: 'authorization_code',
            code: code,
            redirect_uri: HUBITAT_STATE_REDIRECT,
            client_id: OAUTH_CLIENT_ID,
            code_verifier: verifier,
        ]),
        timeout: 20,
    ]

    try {
        // One-off exchange; synchronous so the result can be shown to the user.
        httpPost(tokenParams) { resp ->
            Map body = resp.data instanceof Map ? resp.data : [:]
            if (resp.status == 200 && body.access_token && isHttps(body.ingest_url)) {
                state.sbToken = body.access_token
                state.ingestUrl = body.ingest_url
                state.connectedAt = new Date().format('yyyy-MM-dd HH:mm')
                state.remove('lastError')
                // A fresh Connect issues a token bound to this hub and app.
                state.remove('lockMgmtReconnectRequired')
            } else {
                state.lastError = "Token exchange returned HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        // Do not log the exception body; it may echo request data.
        state.lastError = "Token exchange failed: ${e.class.simpleName}"
        log.warn "SignalBricks token exchange failed: ${e.class.simpleName}"
    }

    if (state.sbToken && !state.lastError) {
        log.info 'Connected to SignalBricks'
        subscribeToEvents()
        scheduleDailyCheckIn()
        return renderPage(true, 'Connected', 'This hub is now connected to SignalBricks and sends its telemetry there.',
            'Reopen the app: it shows <b>Status: Connected</b>. You can close this window.')
    }
    return renderPage(false, 'Connection failed', "Could not complete the connection: ${escapeHtml(state.lastError ?: 'unknown error')}.")
}

// The page oauthCallback returns through Hubitat cloud (#44), styled like SignalBricks
// Integration's (#40): one self-contained response, inline CSS only. detailHtml and
// nextHtml must be escaped by the caller.
Map renderPage(boolean ok, String headline, String detailHtml,
        String nextHtml = 'Close this window, open the app and connect again from its <b>Connection</b> section.') {
    String title = escapeHtml("${APP_NAME}: ${headline}")
    String back = appPageUrl()
    String backHtml = back
        ? """<a class="btn" href="${escapeHtml(back)}">Back to the app</a>
<p class="hint">The link works on the hub's local network. Elsewhere, go back to the hub window you connected from, or open <b>Apps</b> &rarr; <b>${escapeHtml(APP_NAME)}</b> on the hub.</p>"""
        : """<p class="hint">To get back to the app, go back to the hub window you connected from, or open <b>Apps</b> &rarr; <b>${escapeHtml(APP_NAME)}</b> on the hub.</p>"""
    String html = """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark"><meta name="referrer" content="no-referrer"><title>${title}</title>
<style>
:root{--bg:#f3f4f6;--card:#fff;--text:#111827;--muted:#4b5563;--border:#e5e7eb;--ok:#15803d;--err:#b91c1c;--on-mark:#fff;--link:#1d4ed8}
@media (prefers-color-scheme:dark){:root{--bg:#0b0f14;--card:#161b22;--text:#e6edf3;--muted:#9da7b3;--border:#30363d;--ok:#3fb950;--err:#f85149;--on-mark:#0b0f14;--link:#58a6ff}}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{width:100%;max-width:28rem;background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;overflow-wrap:anywhere}
.app{margin:0 0 16px;color:var(--muted);font-size:14px}
h1{display:flex;align-items:center;gap:12px;margin:0 0 12px;font-size:22px;line-height:1.3}
.mark{flex:none;display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;border-radius:50%;color:var(--on-mark);font-size:18px;font-weight:700}
.ok .mark{background:var(--ok)}.err .mark{background:var(--err)}
p{margin:0 0 12px}
.btn{display:inline-block;margin-top:8px;padding:10px 16px;border:1px solid var(--border);border-radius:8px;color:var(--link);font-weight:600;text-decoration:none}
.btn:hover,.btn:focus{text-decoration:underline}
.hint{margin:16px 0 0;color:var(--muted);font-size:14px}
</style></head>
<body><main class="${ok ? 'ok' : 'err'}">
<p class="app">${escapeHtml(appNameAndVersion())}</p>
<h1><span class="mark" aria-hidden="true">${ok ? '&#10003;' : '!'}</span>${escapeHtml(headline)}</h1>
<p>${detailHtml}</p>
<p>${nextHtml}</p>
${backHtml}
</main></body></html>"""
    return render(contentType: 'text/html', data: html, status: 200)
}

// The app's page on the hub, by local IP; null when it can't be built
String appPageUrl() {
    try {
        String ip = location?.hub?.localIP?.toString()
        String id = app?.id?.toString()
        if (ip ==~ /\d{1,3}(\.\d{1,3}){3}/ && id ==~ /\d+/) return "http://${ip}/installedapp/configure/${id}"
    } catch (Exception ignored) { }
    return null
}

// --- Lock code management (#472) ---

// Tokens (ingestion and app access token) only ever go to an https URL.
Boolean isHttps(def url) {
    return url?.toString()?.startsWith('https://')
}

Boolean lockManagementActive() {
    return lockManagementEnabled == true && state.sbToken
}

// True while SignalBricks may hold this app's endpoint: an enable was
// registered and no disable since. Hubs upgraded from 1.2.0 only have
// lockMgmtRegisteredAt.
Boolean lockMgmtPreviouslyRegistered() {
    if (state.lockMgmtRegistered != null) return state.lockMgmtRegistered == true
    return state.lockMgmtRegisteredAt != null
}

// Registers this app's cloud endpoint and access token with SignalBricks,
// authenticated with the ingestion token. The access token is sent only while
// lock management is on; with it off, the platform drops what it stored. A
// disable is sent only if an earlier registration may still be stored (#522).
// SECURITY: the body carries the app access token; never log it.
// Returns whether a registration was sent. A retry (#46) keeps its attempt count;
// any other registration starts a fresh retry sequence.
Boolean registerWithPlatform(Boolean enabledOverride = null, Boolean retry = false) {
    if (!retry) clearRegisterRetry()
    if (!state.sbToken || !isHttps(state.ingestUrl)) return false
    Boolean enabled = enabledOverride != null ? enabledOverride : lockManagementEnabled == true
    if (!enabled && !lockMgmtPreviouslyRegistered()) return false
    if (enabled && !state.accessToken) ensureAccessToken()
    Map body = [
        cloudEndpointBase: getFullApiServerUrl(),
        appAccessToken: enabled ? state.accessToken : null,
        hubUid: getHubUID(),
        appId: app.id.toString(),
        version: APP_VERSION,
        hubFirmwareVersion: hubFirmwareVersion(),
        lockManagement: enabled,
        capabilities: CAPABILITIES,
    ]
    Map params = [
        uri: "${state.ingestUrl}/register",
        requestContentType: 'application/json',
        contentType: 'application/json',
        headers: [Authorization: "Bearer ${state.sbToken}"],
        body: JsonOutput.toJson(body),
        timeout: 15,
    ]
    asynchttpPost('registerCallback', params, [enabled: enabled])
    return true
}

// --- Registration retries (#46) ---
// A failed registration may leave SignalBricks with a token the app no longer
// accepts (after a Reconnect or a rotation), so failures that may pass are
// retried: timeout (408, how Hubitat reports an async timeout), rate limited
// (429), SignalBricks unavailable (5xx) or no answer. A retry re-sends the
// current settings. Never falls back to the old token: it is gone.
Boolean registerRetryable(Integer status) {
    return !status || status in [408, 429] || status >= 500
}

void scheduleRegisterRetry() {
    int attempt = ((atomicState.registerRetry?.attempt ?: 0) as int) + 1
    if (attempt > REGISTER_RETRY_SECONDS.size()) {
        atomicState.registerRetry = [attempt: attempt - 1, exhausted: true]
        unschedule('registerRetryRun')
        return
    }
    int delay = REGISTER_RETRY_SECONDS[attempt - 1]
    atomicState.registerRetry = [attempt: attempt, at: now() + delay * 1000L]
    runIn(delay, 'registerRetryRun', [overwrite: true])
}

// unschedule() removed a pending retry's timer; put it back for the time left
void registerRetryRearm() {
    Map r = atomicState.registerRetry
    // An in-flight retry is answered by registerCallback, which schedules the next one
    if (!r || r.exhausted || r.inFlight) return
    long left = (long) (((r.at as long) - now()) / 1000)
    runIn(Math.max(left, 5L), 'registerRetryRun', [overwrite: true])
}

void registerRetryRun() {
    Map r = atomicState.registerRetry
    if (!r || r.exhausted || r.inFlight) return
    atomicState.registerRetry = [attempt: r.attempt, inFlight: true]
    // Nothing to send any more (disconnected, or lock management off and never registered)
    if (!registerWithPlatform(null, true)) clearRegisterRetry()
}

void clearRegisterRetry() {
    atomicState.remove('registerRetry')
    unschedule('registerRetryRun')
}

// The retry line for the app page, or null
String registerRetryStatus() {
    Map r = atomicState.registerRetry
    if (!r) return null
    int total = REGISTER_RETRY_SECONDS.size()
    if (r.exhausted) return "<b>Registration retries:</b> all ${total} automatic retries failed; click Done to try again"
    if (r.inFlight) return "<b>Registration retries:</b> retry ${r.attempt} of ${total} in progress"
    String when = new Date(r.at as long).format('HH:mm:ss', location?.timeZone ?: TimeZone.getDefault())
    return "<b>Registration retries:</b> retry ${r.attempt} of ${total} at ${when} (hub time)"
}

// Hubitat platform version, sent with events and registration for the
// SignalBricks hub page. Null if unavailable.
String hubFirmwareVersion() {
    try {
        return location?.hub?.firmwareVersionString?.toString()
    } catch (Exception e) {
        return null
    }
}

// After an update that didn't run updated() (HPM), re-register so SignalBricks
// sees the new version: on the next event or scheduled check, only when
// registering is otherwise allowed, and at most every REREGISTER_INTERVAL_MS.
void reregisterIfVersionChanged() {
    if (state.registeredVersion == APP_VERSION) return
    if (!state.sbToken || !isHttps(state.ingestUrl)) return
    if (lockManagementEnabled != true && !lockMgmtPreviouslyRegistered()) return
    if (state.reregisterAttemptAt && now() - (state.reregisterAttemptAt as long) < REREGISTER_INTERVAL_MS) return
    state.reregisterAttemptAt = now()
    log.info "SignalBricks Telemetry: re-registering after update to ${appNameAndVersion()}"
    try {
        registerWithPlatform()
    } catch (Exception e) {
        // Only the class name: the request body carries the access token.
        log.warn "SignalBricks Telemetry: re-registration failed (${e.class.simpleName})"
    }
}

// Schedules dailyCheckIn at this hub's time of day, picked at random once and
// kept, so hubs don't all call SignalBricks at the same moment. Only while
// connected. Schedules survive later code updates.
void scheduleDailyCheckIn() {
    if (!state.sbToken || !isHttps(state.ingestUrl)) {
        unschedule('dailyCheckIn')
        return
    }
    // atomicState: two first events running at once must not pick two times.
    if (atomicState.checkInSecond == null) {
        atomicState.checkInSecond = (CHECK_IN_WINDOW_START_SEC + new Random().nextInt(CHECK_IN_WINDOW_SEC)) % 86400
    }
    int t = atomicState.checkInSecond as int
    schedule("${t % 60} ${(t.intdiv(60)) % 60} ${t.intdiv(3600)} * * ?".toString(), 'dailyCheckIn')
}

String checkInTime() {
    if (atomicState.checkInSecond == null) return null
    int t = atomicState.checkInSecond as int
    return String.format('%02d:%02d', t.intdiv(3600), (t.intdiv(60)) % 60)
}

// Scheduled daily. Sends nothing unless the version changed since the last
// registration (same guards and backoff as after an event).
void dailyCheckIn() {
    logDebug 'daily check-in'
    reregisterIfVersionChanged()
}

void registerCallback(resp, Map data) {
    Boolean failed = resp.hasError()
    int status = resp.getStatus()
    Map reply = [:]
    try {
        def parsed = parseJson((failed ? resp.getErrorData() : resp.getData()) ?: '{}')
        if (parsed instanceof Map) reply = parsed
    } catch (Exception e) {
        // Unparseable body: handled by status alone.
    }
    // The token can't register lock management (connected before
    // signalbricks/platform#505). Only a reconnect fixes that, so say so and
    // don't retry; a disable answered this way changed nothing on the platform.
    if ((failed && status == 403 && reply.code == 'TOKEN_NOT_BOUND_TO_APP') || (!failed && reply.reconnect_required == true)) {
        clearRegisterRetry()
        if (!state.lockMgmtReconnectRequired) log.warn "SignalBricks Telemetry: ${RECONNECT_MESSAGE}"
        state.lockMgmtReconnectRequired = true
        state.remove('lockMgmtRegisterError')
        state.registeredVersion = APP_VERSION
        return
    }
    if (failed) {
        String msg = status == 401 ? 'SignalBricks rejected the token. Reconnect to SignalBricks.' : "HTTP ${status}"
        log.warn "SignalBricks Telemetry: lock management registration failed: ${msg}"
        state.lockMgmtRegisterError = "${new Date().format('yyyy-MM-dd HH:mm')} ${msg}"
        if (state.sbToken && registerRetryable(status)) scheduleRegisterRetry()
        else clearRegisterRetry()
        return
    }
    clearRegisterRetry()
    state.remove('lockMgmtRegisterError')
    state.remove('lockMgmtReconnectRequired')
    state.registeredVersion = APP_VERSION
    state.lockMgmtRegistered = data?.enabled == true
    state.lockMgmtRegisteredAt = new Date().format('yyyy-MM-dd HH:mm')
    log.info "SignalBricks Telemetry: registered ${appNameAndVersion()} with SignalBricks (lock management ${data?.enabled ? 'on' : 'off'})"
}

Map jsonResponse(def data, int status = 200) {
    return render(contentType: 'application/json', data: JsonOutput.toJson(data), status: status)
}

Map notEnabled() {
    return jsonResponse([error: 'not_found'], 404)
}

def findCodeLock(lockId) {
    return (codeLocks ?: []).find { it.id.toString() == lockId?.toString() }
}

Integer parseSlot(def raw) {
    String s = raw?.toString()
    if (!(s ==~ /\d{1,4}/)) return null
    Integer n = s.toInteger()
    return (n >= 1 && n <= MAX_SLOT) ? n : null
}

// 250, "250" or "250.0" -> 250; anything else (fractions, text) -> null.
Integer parseIntLoose(def v) {
    if (v == null) return null
    try {
        BigDecimal d = new BigDecimal(v.toString().trim())
        return d.stripTrailingZeros().scale() <= 0 ? d.intValueExact() : null
    } catch (Exception e) {
        return null
    }
}

// Hub-local ceiling for admin + managed slots. Set only in the app UI, never
// via the API, so a token holder can't widen the ranges into booking slots.
Integer managedCeiling() {
    Integer n = parseIntLoose(maxManagedSlot)
    return (n != null && n >= 1 && n <= MAX_SLOT) ? n : DEFAULT_MAX_MANAGED_SLOT
}

// Range {from, to} with 1 <= from <= to <= MAX_SLOT, or null if invalid.
Map parseRange(def raw) {
    if (!(raw instanceof Map)) return null
    Integer from = parseSlot(raw.from)
    Integer to = parseSlot(raw.to)
    if (from == null || to == null || from > to) return null
    return [from: from, to: to]
}

Boolean rangesOverlap(Map a, Map b) {
    return a.from <= b.to && b.from <= a.to
}

// Booking codes from the SignalBricks Integration / OwnerRez apps: an
// upper-case "ORB" followed by a digit anywhere in the name, case-sensitive
// (signalbricks/platform#581). Covers every prefix with or without a space,
// e.g. "ORB123-Guest", "GT:ORB123-Guest", "G:ORB1-Guest", "GT: ORB123-Guest",
// and the idAtEnd forms "Guest-ORB123" and "Guest -ORB123". It is also the
// rule signalbricksIntegration uses to claim codes (helperOnlySbCodes), so a
// name SignalBricks writes must never match it either. Ordinary words such as
// "Forbes", "Orbit" or "Corbin" don't match. The platform mirrors this rule.
@Field static final java.util.regex.Pattern BOOKING_NAME = ~/ORB\d/

Boolean isBookingName(String name) {
    return name != null && BOOKING_NAME.matcher(name).find()
}

// The stricter booking rule for registered slots and full lock control
// (signalbricks/platform#684), mirrored by the platform's isBookingSlotName:
// "orb" followed by a digit in any case, an upper-case "ORB" at the start of a
// word (the pre-1.3.4 guard), or a name starting with the booking apps' G: or
// GT: prefix (any case, spaces allowed before the colon). An unnamed slot
// isn't a booking name, but it can never be registered (it could be a booking
// code on a driver that drops names), so it is never reached either. The
// older paths (SBA:/SBM: ranges, adopted slots) keep isBookingName.
@Field static final java.util.regex.Pattern STRICT_BOOKING_NAME = ~/(?i:orb\d)|(^|[\s-])ORB|^\s*(?i:gt?)\s*:/
@Field static final java.util.regex.Pattern CLASS_PREFIX_NAME = ~/^\s*(?i:sb[am])\s*:/

Boolean isStrictBookingName(String name) {
    return name != null && STRICT_BOOKING_NAME.matcher(name).find()
}

// A name the hub may hold as a registered slot's `name` (what the lock shows):
// 1-64 printable ASCII without surrounding spaces, never a booking name.
// Null when acceptable.
String registeredNameError(def raw) {
    if (!(raw instanceof String)) return 'must be a string'
    String name = raw
    if (!name || name.length() > MAX_NAME_LENGTH || !(name ==~ /[\x20-\x7E]+/) || name != name.trim()) {
        return "must be 1-${MAX_NAME_LENGTH} printable ASCII characters without leading or trailing spaces"
    }
    if (isBookingName(name) || isStrictBookingName(name)) return 'looks like a booking code name'
    return null
}

// A custom name SignalBricks writes (a `pendingName`): a registered name that
// also doesn't start with SBA:/SBM: (those keep the class-range rules).
String customNameError(def raw) {
    String err = registeredNameError(raw)
    if (err) return err
    if (CLASS_PREFIX_NAME.matcher(raw).find()) return 'must not start with SBA: or SBM:'
    return null
}

// Validates a /lockConfig body. Returns [config: Map] or [error: String].
//   { admin: {from, to}, managed: {from, to},
//     locks: { "<lockId>": { admin?: {from, to}, managed?: {from, to}, adopted?: [slot, ...],
//                            registered?: [{slot, name, pendingName?}], fullControl?: bool }
//              | { surveyOnly: true } } }
// `registered` and `fullControl` (#684) are kept only when given, so a config
// without them is stored exactly as before.
// A surveyOnly lock (#532) is listed for reading only (/slots, /fingerprints):
// every write, delete, reveal and retry on it is refused.
// Ranges must stay within the hub-local ceiling. Adopted slots above it are
// stored but stay refused until the operator approves them in the app UI.
Map validateLockConfig(def body) {
    if (!(body instanceof Map)) return [error: 'body must be a JSON object']
    Integer ceiling = managedCeiling()
    Map admin = parseRange(body.admin)
    Map managed = parseRange(body.managed)
    if (!admin || !managed) return [error: 'admin and managed must be {from, to} slot ranges']
    if (rangesOverlap(admin, managed)) return [error: 'admin and managed ranges overlap']
    if (admin.to > ceiling || managed.to > ceiling) return [error: "admin and managed ranges must stay within slots 1-${ceiling} (the hub's ceiling, set in the app)"]
    Map locksOut = [:]
    def locksIn = body.locks ?: [:]
    if (!(locksIn instanceof Map)) return [error: 'locks must be an object keyed by lock id']
    for (entry in locksIn) {
        String lockId = entry.key.toString()
        if (!findCodeLock(lockId)) return [error: "lock ${lockId} is not selected for lock code management"]
        def v = entry.value
        if (!(v instanceof Map)) return [error: "lock ${lockId}: must be an object"]
        if (v.containsKey('surveyOnly')) {
            if (!(v.surveyOnly instanceof Boolean)) return [error: "lock ${lockId}: surveyOnly must be true or false"]
            if (v.surveyOnly) {
                if (v.keySet().any { it != 'surveyOnly' }) return [error: "lock ${lockId}: surveyOnly can't be combined with admin, managed or adopted"]
                locksOut[lockId] = [surveyOnly: true]
                continue
            }
        }
        Map lockAdmin = v.admin != null ? parseRange(v.admin) : admin
        Map lockManaged = v.managed != null ? parseRange(v.managed) : managed
        if (!lockAdmin || !lockManaged) return [error: "lock ${lockId}: invalid admin or managed range"]
        if (rangesOverlap(lockAdmin, lockManaged)) return [error: "lock ${lockId}: admin and managed ranges overlap"]
        if (lockAdmin.to > ceiling || lockManaged.to > ceiling) return [error: "lock ${lockId}: ranges must stay within slots 1-${ceiling}"]
        def adoptedIn = v.adopted ?: []
        if (!(adoptedIn instanceof List) || adoptedIn.size() > MAX_ADOPTED_PER_LOCK) {
            return [error: "lock ${lockId}: adopted must be a list of at most ${MAX_ADOPTED_PER_LOCK} slots"]
        }
        List adopted = adoptedIn.collect { parseSlot(it) }
        if (adopted.any { it == null }) return [error: "lock ${lockId}: adopted slots must be integers 1-${MAX_SLOT}"]
        Map out = [adopted: adopted.unique().sort()]
        if (v.admin != null) out.admin = lockAdmin
        if (v.managed != null) out.managed = lockManaged
        Map reg = parseRegistered(lockId, v.registered, out.adopted)
        if (reg.error) return [error: reg.error]
        if (reg.registered) out.registered = reg.registered
        if (v.containsKey('fullControl')) {
            if (!(v.fullControl instanceof Boolean)) return [error: "lock ${lockId}: fullControl must be true or false"]
            if (v.fullControl) out.fullControl = true
        }
        locksOut[lockId] = out
    }
    return [config: [admin: admin, managed: managed, locks: locksOut]]
}

// `registered` (#684): [{slot, name, pendingName?}]. Returns [registered: List]
// (null when absent or empty) or [error: String]. A slot is either adopted or
// registered, never both; together at most MAX_ADOPTED_PER_LOCK.
Map parseRegistered(String lockId, def raw, List adopted) {
    if (raw == null) return [registered: null]
    if (!(raw instanceof List) || raw.size() + adopted.size() > MAX_ADOPTED_PER_LOCK) {
        return [error: "lock ${lockId}: registered must be a list, with adopted at most ${MAX_ADOPTED_PER_LOCK} slots"]
    }
    List out = []
    for (r in raw) {
        if (!(r instanceof Map) || r.keySet().any { !(it in ['slot', 'name', 'pendingName']) }) {
            return [error: "lock ${lockId}: registered entries must be {slot, name, pendingName?}"]
        }
        Integer slot = r.slot instanceof Number ? parseSlot(parseIntLoose(r.slot)) : null
        if (slot == null) return [error: "lock ${lockId}: registered slots must be integers 1-${MAX_SLOT}"]
        if (adopted.contains(slot) || out.any { it.slot == slot }) return [error: "lock ${lockId}: slot ${slot} is listed twice (registered and adopted, or registered twice)"]
        String nameErr = registeredNameError(r.name)
        if (nameErr) return [error: "lock ${lockId}: registered slot ${slot} name ${nameErr}"]
        Map e = [slot: slot, name: r.name]
        if (r.pendingName != null) {
            String pendingErr = customNameError(r.pendingName)
            if (pendingErr) return [error: "lock ${lockId}: registered slot ${slot} pendingName ${pendingErr}"]
            if (r.pendingName == r.name) return [error: "lock ${lockId}: registered slot ${slot} pendingName must differ from name"]
            e.pendingName = r.pendingName
        }
        out << e
    }
    return [registered: out ? out.sort { it.slot } : null]
}

// The registration of a slot on a lock, or null.
Map registeredEntry(String lockId, Integer slot) {
    def l = state.lockConfig?.locks?.get(lockId)
    if (!(l instanceof Map) || slot == null) return null
    return (l.registered ?: []).find { (it.slot as Integer) == slot } as Map
}

// Whether `name` is one the registration accepts: its name, or the new name
// of a rename in progress (both are accepted until the hub confirms it).
Boolean registeredNameMatches(Map entry, String name) {
    return entry != null && name != null && (name == entry.name || (entry.pendingName != null && name == entry.pendingName))
}

Boolean fullControlOn(String lockId) {
    def l = state.lockConfig?.locks?.get(lockId)
    return l instanceof Map && l.fullControl == true
}

// Lock ids the hub operator allowed full control on, in this app's UI only
// (never via the API): registered codes above the ceiling are reachable there
// when SignalBricks also turned full control on for the lock.
List fullControlAllowedIds() {
    def v = fullControlLocks
    if (!v) return []
    List ids = (v instanceof Collection ? v : [v]).collect { it.toString() }
    return ids.findAll { findCodeLock(it) }
}

Boolean fullControlAboveCeiling(String lockId) {
    return fullControlOn(lockId) && fullControlAllowedIds().contains(lockId)
}

List approvedAdoptedKeys() {
    def v = approvedAdoptedSlots
    if (!v) return []
    return (v instanceof Collection ? v : [v]).collect { it.toString() }
}

// Adopted slots above the ceiling, as "<lockId>:<slot>" keys, plus registered
// slots above it on locks without full control allowed here (#684): those
// need the operator's approval slot by slot, as adopted slots do.
List adoptedAboveCeiling() {
    Map cfg = state.lockConfig
    if (!cfg) return []
    Integer ceiling = managedCeiling()
    List out = []
    (cfg.locks ?: [:]).each { lockId, l ->
        (l.adopted ?: []).collect { it as Integer }.findAll { it > ceiling }.each { out << "${lockId}:${it}".toString() }
        if (!fullControlAboveCeiling(lockId.toString())) {
            (l.registered ?: []).collect { it.slot as Integer }.findAll { it > ceiling }.each { out << "${lockId}:${it}".toString() }
        }
    }
    return out
}

List pendingAdoptedKeys() {
    List approved = approvedAdoptedKeys()
    return adoptedAboveCeiling().findAll { !approved.contains(it) }
}

// Options for the approval input: slot number and current name only.
Map pendingAdoptedOptions() {
    Map options = [:]
    adoptedAboveCeiling().each { String key ->
        List parts = key.split(':')
        def lock = findCodeLock(parts[0])
        if (!lock) return
        Map codes = readLockCodes(lock)
        def entry = codes?.get(parts[1])
        String name = entry instanceof Map ? entry.name?.toString() : null
        String label = "${lock.displayName}: slot ${parts[1]}${name ? " (${escapeHtml(name)})" : ' (empty)'}"
        if (isBookingName(name) || isStrictBookingName(name)) label += ' - booking code, always refused'
        options[key] = label
    }
    return options
}

// Which class a slot belongs to on this lock: 'registered' (#684), 'adopted',
// 'admin', 'managed', or null when the hub must refuse it. A registered slot
// has further rules (registeredSlotRefusal). No config yet = nothing allowed. A
// lock not listed in config.locks (not onboarded) allows nothing: the global
// ranges are only defaults for listed locks. Nothing above the ceiling is
// allowed unless it is an adopted slot the operator approved in the app UI.
// A surveyOnly lock (listed for reading only) allows nothing.
String slotClass(String lockId, Integer slot) {
    Map cfg = state.lockConfig
    if (!cfg || slot == null) return null
    Map lockCfg = cfg.locks?.get(lockId)
    if (!(lockCfg instanceof Map) || lockCfg.surveyOnly == true) return null
    Integer ceiling = managedCeiling()
    // Registered (#684): within the ceiling, or above it on a lock with full
    // control allowed here, or with the operator's approval for the slot.
    if ((lockCfg.registered ?: []).any { (it.slot as Integer) == slot }) {
        if (slot <= ceiling || fullControlAboveCeiling(lockId) || approvedAdoptedKeys().contains("${lockId}:${slot}".toString())) return 'registered'
        return null
    }
    if ((lockCfg.adopted ?: []).collect { it as Integer }.contains(slot)) {
        if (slot <= ceiling || approvedAdoptedKeys().contains("${lockId}:${slot}".toString())) return 'adopted'
    }
    if (slot > ceiling) return null
    Map admin = lockCfg.admin ?: cfg.admin
    Map managed = lockCfg.managed ?: cfg.managed
    if (slot >= (admin.from as Integer) && slot <= (admin.to as Integer)) return 'admin'
    if (slot >= (managed.from as Integer) && slot <= (managed.to as Integer)) return 'managed'
    return null
}

String classPrefix(String cls) {
    return cls == 'admin' ? ADMIN_NAME_PREFIX : (cls == 'managed' ? MANAGED_NAME_PREFIX : null)
}

// The lock's lockCodes as a Map keyed by slot string, or null if it can't be
// read. Newer drivers can store lockCodes encrypted (driver preference
// "Encrypt lock codes"); those values are decrypted with the hub's decrypt()
// when the platform provides it to apps. The result holds codes: never log it.
Map readLockCodes(lock) {
    def raw = lock.currentValue('lockCodes', true)
    if (!raw) return [:]
    String s = raw.toString().trim()
    if (!s.startsWith('{')) {
        try {
            s = decrypt(s)?.toString()?.trim()
        } catch (Exception e) {
            return null
        }
        if (!s?.startsWith('{')) return null
    }
    try {
        def parsed = parseJson(s)
        return parsed instanceof Map ? parsed : null
    } catch (Exception e) {
        return null
    }
}

Boolean isSurveyOnly(String lockId) {
    def l = state.lockConfig?.locks?.get(lockId)
    return l instanceof Map && l.surveyOnly == true
}

Integer maxCodesOf(lock) {
    return parseIntLoose(lock.currentValue('maxCodes'))
}

// GET and POST /lockConfig reply. Says whether a fingerprint key is held,
// never the key itself.
Map lockConfigReply() {
    return [config: state.lockConfig ?: null, ceiling: managedCeiling(), pendingApproval: pendingAdoptedKeys(), fingerprintKeySet: state.fingerprintKey != null,
            features: CONFIG_FEATURES, fullControlAllowed: fullControlAllowedIds()]
}

Map apiGetLockConfig() {
    if (!lockManagementActive()) return notEnabled()
    return jsonResponse(lockConfigReply())
}

// Optional fingerprintKey: standard base64 (with padding) of exactly 32 bytes.
// Returns [key: String] (null when absent, so the stored key is kept) or
// [error: String]. The key is a secret: never echo it in errors or logs.
Map parseFingerprintKey(def body) {
    def raw = body instanceof Map ? body.fingerprintKey : null
    if (raw == null) return [key: null]
    String s = raw instanceof String ? raw : null
    if (!(s ==~ /[A-Za-z0-9+\/]{43}=/)) return [error: "fingerprintKey must be base64 of exactly ${FINGERPRINT_KEY_BYTES} bytes"]
    byte[] bytes = s.decodeBase64()
    if (bytes.length != FINGERPRINT_KEY_BYTES) return [error: "fingerprintKey must be base64 of exactly ${FINGERPRINT_KEY_BYTES} bytes"]
    return [key: s]
}

Map apiSetLockConfig() {
    if (!lockManagementActive()) return notEnabled()
    Map fpKey = parseFingerprintKey(request.JSON)
    if (fpKey.error) return jsonResponse([error: 'invalid_config', message: fpKey.error], 400)
    Map result = validateLockConfig(request.JSON)
    if (result.error) return jsonResponse([error: 'invalid_config', message: result.error], 400)
    state.lockConfig = result.config
    if (fpKey.key) state.fingerprintKey = fpKey.key
    // Approvals only cover adopted slots the current config still lists.
    List stillAdopted = adoptedAboveCeiling()
    List kept = approvedAdoptedKeys().findAll { stillAdopted.contains(it) }
    if (kept.size() != approvedAdoptedKeys().size()) {
        if (kept) app.updateSetting('approvedAdoptedSlots', [type: 'enum', value: kept])
        else app.removeSetting('approvedAdoptedSlots')
    }
    dropDisallowedCodeChanges()
    log.info "SignalBricks Telemetry: lock slot rules updated${fpKey.key ? ' (fingerprint key updated)' : ''}"
    return jsonResponse(lockConfigReply())
}

Map apiListLocks() {
    if (!lockManagementActive()) return notEnabled()
    List out = (codeLocks ?: []).collect { lock ->
        [id: lock.id.toString(), name: lock.name, label: lock.label ?: lock.displayName, maxCodes: maxCodesOf(lock)]
    }
    return jsonResponse([locks: out])
}

// Slot metadata only: slot number, name, set. Never code values.
Map apiListSlots() {
    if (!lockManagementActive()) return notEnabled()
    def lock = findCodeLock(params.lockId)
    if (!lock) return jsonResponse([error: 'lock_not_found'], 404)
    Map codes = readLockCodes(lock)
    if (codes == null) return jsonResponse([error: 'lock_codes_unreadable', message: 'lockCodes could not be read or decrypted'], 409)
    List slots = codes.findAll { k, v -> k.toString() ==~ /\d+/ }.collect { k, v ->
        Integer slot = k.toString().toInteger()
        String name = v instanceof Map ? v.name?.toString() : null
        Boolean set = v instanceof Map ? (v.code != null && v.code.toString() != '') : false
        String cls = isBookingName(name) ? null : slotClass(lock.id.toString(), slot)
        // Registered slots (#684) are reported as adopted (older SignalBricks
        // versions know no other value) plus registered: true, and only while
        // the hub would let SignalBricks touch them.
        if (cls == 'registered') {
            Boolean ok = registeredSlotRefusal(lock.id.toString(), slot, name, set, true, codes) == null
            return [slot: slot, name: name, set: set, allowedAs: ok ? 'adopted' : null, registered: true]
        }
        [slot: slot, name: name, set: set, allowedAs: cls]
    }.sort { it.slot }
    return jsonResponse([lockId: lock.id.toString(), maxCodes: maxCodesOf(lock), surveyOnly: isSurveyOnly(lock.id.toString()), slots: slots])
}

// HMAC-SHA256(fingerprintKey, code) as lower-case hex, first 16 characters.
// The same code gives the same fingerprint on every lock and every hub holding
// the same key. Takes and returns no code: never log its input.
String codeFingerprint(byte[] key, String code) {
    Mac mac = Mac.getInstance('HmacSHA256')
    mac.init(new SecretKeySpec(key, 'HmacSHA256'))
    return mac.doFinal(code.getBytes('UTF-8')).encodeHex().toString().substring(0, FINGERPRINT_HEX_LENGTH)
}

// Fingerprints of the occupied slots SignalBricks may manage (slotClass
// non-null: a listed lock's ranges within the ceiling, or allowed adopted
// slots), or on a surveyOnly lock every occupied slot within the ceiling;
// never booking-code names. Unmarked codes are included, so onboarding can
// group them before adopting. Shared by /locks/:id/fingerprints and
// /fingerprints. Returns [slots: [{slot, fp}]] sorted by slot, or
// [error: 'lock_codes_unreadable']. Never returns or logs codes.
Map lockFingerprints(lock, byte[] key) {
    Map codes = readLockCodes(lock)
    if (codes == null) return [error: 'lock_codes_unreadable']
    String lockId = lock.id.toString()
    Boolean survey = isSurveyOnly(lockId)
    Integer ceiling = managedCeiling()
    List slots = []
    codes.each { k, v ->
        if (!(k.toString() ==~ /\d{1,4}/) || !(v instanceof Map)) return
        String code = v.code?.toString()
        if (!code) return
        Integer slot = k.toString().toInteger()
        if (isBookingName(v.name?.toString())) return
        if (slot < 1 || (survey ? slot > ceiling : !slotClass(lockId, slot))) return
        // A registered slot (#684) only while it holds its registered name.
        if (!survey && slotClass(lockId, slot) == 'registered' &&
            (isStrictBookingName(v.name?.toString()) || !registeredNameMatches(registeredEntry(lockId, slot), v.name?.toString()))) return
        slots << [slot: slot, fp: codeFingerprint(key, code)]
    }
    return [slots: slots.sort { it.slot }]
}

Map apiListFingerprints() {
    if (!lockManagementActive()) return notEnabled()
    if (rateLimited('fingerprintTimes', FINGERPRINT_LIMIT_PER_MIN)) return jsonResponse([error: 'rate_limited'], 429)
    def lock = findCodeLock(params.lockId)
    if (!lock) return jsonResponse([error: 'lock_not_found'], 404)
    if (!state.fingerprintKey) return jsonResponse([error: 'fingerprint_key_missing', message: 'SignalBricks has not sent a fingerprint key'], 409)
    Map result = lockFingerprints(lock, state.fingerprintKey.toString().decodeBase64())
    if (result.error) return jsonResponse([error: result.error, message: 'lockCodes could not be read or decrypted'], 409)
    log.info "SignalBricks Telemetry: fingerprints listed for ${lock.displayName} (${result.slots.size()} slots)"
    return jsonResponse([lockId: lock.id.toString(), slots: result.slots])
}

// Optional ?ids=7,8 (comma-separated lock ids, at most FINGERPRINT_BATCH_MAX_IDS).
// Returns [ids: List] (null when absent: every selected lock) or [error: String].
Map parseBatchIds(def raw) {
    if (raw == null) return [ids: null]
    List parts = (raw instanceof Collection ? raw.join(',') : raw.toString()).split(',', -1).collect { it.trim() }
    if (parts.any { !(it ==~ /\d{1,12}/) }) return [error: 'ids must be a comma-separated list of lock ids']
    List ids = parts.unique()
    if (ids.size() > FINGERPRINT_BATCH_MAX_IDS) return [error: "at most ${FINGERPRINT_BATCH_MAX_IDS} ids"]
    return [ids: ids]
}

// Fingerprints for every lock selected for code management, or for ?ids=,
// with the same rules as /locks/:id/fingerprints. A lock that can't be read
// (or isn't selected) gets an error entry; the call itself still succeeds.
// One call counts once against its own limit, not against the per-lock one.
Map apiListFingerprintsBatch() {
    if (!lockManagementActive()) return notEnabled()
    if (rateLimited('fingerprintBatchTimes', FINGERPRINT_BATCH_LIMIT_PER_MIN)) return jsonResponse([error: 'rate_limited'], 429)
    Map parsed = parseBatchIds(params.ids)
    if (parsed.error) return jsonResponse([error: 'invalid_ids', message: parsed.error], 400)
    if (!state.fingerprintKey) return jsonResponse([error: 'fingerprint_key_missing', message: 'SignalBricks has not sent a fingerprint key'], 409)
    byte[] key = state.fingerprintKey.toString().decodeBase64()
    List ids = parsed.ids != null ? parsed.ids : (codeLocks ?: []).collect { it.id.toString() }
    int slotCount = 0
    List out = ids.collect { String id ->
        def lock = findCodeLock(id)
        if (!lock) return [id: id, error: 'lock_not_found']
        Map result = lockFingerprints(lock, key)
        if (result.error) return [id: id, error: result.error, message: 'lockCodes could not be read or decrypted']
        slotCount += result.slots.size()
        return [id: id, slots: result.slots]
    }
    log.info "SignalBricks Telemetry: fingerprints listed for ${out.size()} locks (${slotCount} slots)"
    return jsonResponse([locks: out])
}

// A refusal: [response, error]. `error` doubles as the result event's reason.
Map refuse(String error, int status, String message = null) {
    Map body = [error: error]
    if (message) body.message = message
    return [response: jsonResponse(body, status), error: error]
}

// Common checks for write/delete/reveal. Returns [lock, slot, slotClass,
// codes, currentName] or [response, error]. Refuses:
//   - slots outside admin/managed/approved-adopted (and above the ceiling)
//   - any slot whose current name is a booking code (isBookingName)
//   - in the admin/managed ranges, a slot holding a code without that
//     range's SBA:/SBM: marker (existing codes must be adopted first);
//     an empty slot may be written (`allowEmpty`), not deleted or revealed.
Map resolveAllowedSlot(def lockIdRaw, def slotRaw, Boolean allowEmpty) {
    def lock = findCodeLock(lockIdRaw)
    if (!lock) return refuse('lock_not_found', 404)
    Integer slot = parseSlot(slotRaw)
    if (slot == null) return refuse('invalid_slot', 400)
    Integer maxCodes = maxCodesOf(lock)
    if (maxCodes != null && slot > maxCodes) return refuse('invalid_slot', 400, 'slot exceeds maxCodes')
    String cls = slotClass(lock.id.toString(), slot)
    if (!cls) return refuse('slot_not_allowed', 403)
    Map codes = readLockCodes(lock)
    if (codes == null) return refuse('lock_codes_unreadable', 409)
    def entry = codes[slot.toString()]
    String currentName = entry instanceof Map ? entry.name?.toString() : null
    Boolean occupied = entry instanceof Map && entry.code != null && entry.code.toString() != ''
    if (isBookingName(currentName)) return refuse('booking_code', 403, 'slot holds a booking code')
    if (cls == 'registered') {
        Map refusal = registeredSlotRefusal(lock.id.toString(), slot, currentName, occupied, allowEmpty, codes)
        if (refusal) return refusal
    }
    String prefix = classPrefix(cls)
    if (prefix && occupied && !(currentName?.startsWith(prefix))) {
        return refuse('slot_not_managed', 403, "slot holds a code without the ${prefix} marker; adopt it first")
    }
    if (!occupied && !allowEmpty) return refuse('slot_empty', 404)
    return [lock: lock, slot: slot, slotClass: cls, codes: codes, currentName: currentName, occupied: occupied]
}

// Registered slots (#684), on top of slotClass: the stricter booking rule on
// the lock's current name; an occupied slot only while it shows the registered
// name (or the rename's pendingName), so a code replaced outside SignalBricks
// is never taken over; an empty slot is written only within the ceiling (never
// where the booking apps pick free slots), and outside the admin/managed
// ranges only on a full-control lock. Null when allowed.
// A booking-named code (strict rule) at or under the ceiling means the booking
// apps also write there (their minimum code position is at or below the
// ceiling), so new codes outside the class ranges could race them: refused
// (fail closed) until the overlap is fixed. `codes` is the lock's lockCodes.
Map registeredSlotRefusal(String lockId, Integer slot, String currentName, Boolean occupied, Boolean allowEmpty, Map codes) {
    if (isStrictBookingName(currentName)) return refuse('booking_code', 403, 'slot holds a booking code')
    Map entry = registeredEntry(lockId, slot)
    if (occupied) {
        if (!registeredNameMatches(entry, currentName)) return refuse('name_mismatch', 403, 'slot holds a code under a name other than the registered one')
        return null
    }
    if (!allowEmpty) return null
    if (slot > managedCeiling()) return refuse('slot_not_allowed', 403, 'empty slots above the ceiling are never written')
    if (!fullControlOn(lockId) && !inClassRange(lockId, slot)) {
        return refuse('slot_not_allowed', 403, 'empty registered slots outside the admin and managed ranges need full control on the lock')
    }
    if (!inClassRange(lockId, slot) && bookingCodeAtOrUnderCeiling(codes)) {
        return refuse('booking_region_overlap', 409, 'a booking code sits at or under the ceiling; keep the booking apps\' minimum code position above the ceiling')
    }
    return null
}

Boolean bookingCodeAtOrUnderCeiling(Map codes) {
    Integer ceiling = managedCeiling()
    return (codes ?: [:]).any { k, v ->
        k.toString() ==~ /\d{1,4}/ && k.toString().toInteger() <= ceiling && v instanceof Map &&
            v.code != null && v.code.toString() != '' && (isBookingName(v.name?.toString()) || isStrictBookingName(v.name?.toString()))
    }
}

Boolean inClassRange(String lockId, Integer slot) {
    Map cfg = state.lockConfig
    Map lockCfg = cfg?.locks?.get(lockId) ?: [:]
    Map admin = lockCfg.admin ?: cfg.admin
    Map managed = lockCfg.managed ?: cfg.managed
    return (slot >= (admin.from as Integer) && slot <= (admin.to as Integer)) || (slot >= (managed.from as Integer) && slot <= (managed.to as Integer))
}

// Name and slot rules for a set. Checked on the request and again before
// every re-send, so a change stops once its slot is no longer allowed.
Map resolveSet(def lockIdRaw, def slotRaw, String name) {
    if (!name || name.length() > MAX_NAME_LENGTH || !(name ==~ /[\x20-\x7E]+/)) {
        return refuse('invalid_name', 400, "name must be 1-${MAX_NAME_LENGTH} printable ASCII characters")
    }
    // Never create a name the booking guard would read as a booking code
    // (which signalbricksIntegration would also claim).
    if (isBookingName(name)) {
        return refuse('name_not_allowed', 403, 'names must not contain ORB followed by a digit')
    }
    Map r = resolveAllowedSlot(lockIdRaw, slotRaw, true)
    if (r.response) return r
    // Registered slots take only their registered name or the rename's new name.
    if (r.slotClass == 'registered') {
        if (isStrictBookingName(name) || !registeredNameMatches(registeredEntry(r.lock.id.toString(), r.slot), name)) {
            return refuse('name_not_allowed', 403, 'registered slots take only their registered name')
        }
        return r
    }
    // Created codes carry their class marker; adopted slots keep their name.
    String prefix = classPrefix(r.slotClass)
    if (prefix && !name.startsWith(prefix)) return refuse('name_not_allowed', 403, "${r.slotClass} slots need a ${prefix} name")
    return r
}

// Re-send checks (automatic re-sends and the retry endpoint): the same rules
// as a new request, and the slot must not have been taken over since. A
// delete may find the slot already empty (then the check confirms it).
Map resolvePendingChange(Map p) {
    if (p.action == 'relocate') return resolvePendingRelocate(p)
    Map r = p.action == 'set' ? resolveSet(p.lockId, p.slot, p.name) : resolveAllowedSlot(p.lockId, p.slot, true)
    if (r.response || !r.occupied) return r
    // Delete: the slot must still hold the name it had at request time.
    // Set: it may hold our name or the name it had before the request. An
    // unnamed code never counts as ours (it could be anyone's).
    String current = r.currentName
    // Unnamed at request time and now: the hub can't tell whose code it is.
    String requestName = p.action == 'delete' ? p.name : p.prevName
    if (current == null && requestName == null) return refuse('slot_unnamed', 409, 'the slot holds an unnamed code; send a new request')
    Boolean ours = current != null && (current == p.name || (p.action == 'set' && current == p.prevName))
    if (!ours) return refuse('slot_changed', 409, 'the slot now holds a different code; send a new request')
    return r
}

// Optional request id from the platform (a UUID), echoed in the response and
// the result events; the hub makes one when it is absent.
Map requestIdFrom(def raw) {
    if (raw == null || raw.toString() == '') return [id: UUID.randomUUID().toString()]
    String s = raw.toString()
    if (!(s ==~ REQUEST_ID)) return refuse('invalid_request_id', 400, 'requestId must be a UUID')
    return [id: s.toLowerCase()]
}

// At most `limit` calls a minute across the app, counted in state[key].
Boolean rateLimited(String key, int limit) {
    long nowMs = now()
    List recent = (state[key] ?: []).findAll { (nowMs - (it as long)) < 60000L }
    Boolean limited = recent.size() >= limit
    if (!limited) recent << nowMs
    state[key] = recent
    return limited
}

// Checks shared by set and delete once the request is valid: the pending cap
// (a change replacing one for the same slot doesn't count) and the write rate
// limit. Returns a response, or null to go ahead.
Map writeRefusal(lock, Integer slot) {
    Map all = pendingCodeChanges()
    if (!all.containsKey(pendingKey(lock.id.toString(), slot)) && all.size() >= MAX_PENDING_CODE_CHANGES) {
        return jsonResponse([error: 'too_many_pending', message: "at most ${MAX_PENDING_CODE_CHANGES} code changes can be pending; retry later"], 429)
    }
    if (rateLimited('writeTimes', WRITE_LIMIT_PER_MIN)) return jsonResponse([error: 'rate_limited'], 429)
    return null
}

// The driver threw on the first send of a change (#33). Only the class name:
// the driver's message can carry the command's arguments, i.e. the code. The
// caller holds nothing for it; the call goes back to its rate limit (limitKey).
Map sendErrorResponse(String action, lock, String slots, Exception e, String limitKey) {
    log.warn "SignalBricks Telemetry: ${action} on ${lock.displayName} slot ${slots} could not be sent (${e.class.simpleName})"
    List times = (state[limitKey] ?: []) as List
    if (times) state[limitKey] = times.take(times.size() - 1)
    return jsonResponse([error: 'send_error', message: 'the lock driver refused the command'], 502)
}

// Body {slot, code, name, requestId?}. The hub verifies the change and
// reports a lock_code_result event; /slots shows the lock's current state.
Map apiSetCode() {
    if (!lockManagementActive()) return notEnabled()
    def body = request.JSON
    if (!(body instanceof Map)) return jsonResponse([error: 'invalid_body'], 400)
    String code = body.code?.toString()
    if (!(code ==~ /\d{4,10}/)) return jsonResponse([error: 'invalid_code', message: 'code must be 4-10 digits'], 400)
    Map rid = requestIdFrom(body.requestId)
    if (rid.response) return rid.response
    String name = body.name?.toString()?.trim()
    Map r = resolveSet(params.lockId, body.slot, name)
    if (r.response) return r.response
    if (changePendingOn(r.lock.id.toString(), r.slot, true)) return relocatePending()
    Map refusal = writeRefusal(r.lock, r.slot)
    if (refusal) return refusal
    try {
        startCodeChange(r, 'set', name, code, rid.id)
    } catch (Exception e) {
        return sendErrorResponse('setCode', r.lock, "${r.slot}", e, 'writeTimes')
    }
    log.info "SignalBricks Telemetry: setCode requested on ${r.lock.displayName} slot ${r.slot}"
    return jsonResponse([lockId: r.lock.id.toString(), slot: r.slot, status: 'pending', requestId: rid.id], 202)
}

// requestId may come as a query parameter (or in a JSON body, if any).
Map apiDeleteCode() {
    if (!lockManagementActive()) return notEnabled()
    def raw = params.requestId
    if (raw == null) {
        try {
            def b = request?.JSON
            if (b instanceof Map) raw = b.requestId
        } catch (Exception e) {
            raw = null
        }
    }
    Map rid = requestIdFrom(raw)
    if (rid.response) return rid.response
    Map r = resolveAllowedSlot(params.lockId, params.slot, false)
    if (r.response) return r.response
    if (changePendingOn(r.lock.id.toString(), r.slot, true)) return relocatePending()
    Map refusal = writeRefusal(r.lock, r.slot)
    if (refusal) return refusal
    try {
        startCodeChange(r, 'delete', r.currentName, null, rid.id)
    } catch (Exception e) {
        return sendErrorResponse('deleteCode', r.lock, "${r.slot}", e, 'writeTimes')
    }
    log.info "SignalBricks Telemetry: deleteCode requested on ${r.lock.displayName} slot ${r.slot}"
    return jsonResponse([lockId: r.lock.id.toString(), slot: r.slot, status: 'pending', requestId: rid.id], 202)
}

// Re-sends the hub's own pending (or failed) change for this slot, with the
// same rules as a new request. No code in the request. Resets the attempts.
Map apiRetryCode() {
    if (!lockManagementActive()) return notEnabled()
    if (rateLimited('retryTimes', RETRY_LIMIT_PER_MIN)) return jsonResponse([error: 'rate_limited'], 429)
    expirePendingCodeChanges()
    def lock = findCodeLock(params.lockId)
    if (!lock) return jsonResponse([error: 'lock_not_found'], 404)
    Integer slot = parseSlot(params.slot)
    if (slot == null) return jsonResponse([error: 'invalid_slot'], 400)
    // A relocate (either of its slots) retries on its own.
    if (changePendingOn(lock.id.toString(), slot, true)) return relocatePending()
    Map p = pendingCodeChanges()[pendingKey(lock.id.toString(), slot)]
    if (!p) return jsonResponse([error: 'no_pending_change', message: 'nothing pending for this slot (confirmed, superseded, expired or no longer allowed)'], 404)
    Map r = resolvePendingChange(p)
    if (r.response) {
        // It can never be re-sent: report it once (if still in flight) and drop it.
        if (r.error != 'lock_codes_unreadable') finishCodeChange(p, 'failed', r.error, p.status == 'pending')
        return r.response
    }
    Boolean wasPending = p.status == 'pending'
    p.attempts = 0
    p.status = 'pending'
    p.remove('reason')
    try {
        sendCodeChange(p, lock)
    } catch (Exception e) {
        // Dropped (with its code), as when a scheduled re-send throws.
        finishCodeChange(p, 'failed', 'send_error', wasPending)
        return sendErrorResponse("${p.action} retry", lock, "${slot}", e, 'retryTimes')
    }
    log.info "SignalBricks Telemetry: ${p.action} retry requested on ${lock.displayName} slot ${slot}"
    return jsonResponse([lockId: p.lockId, slot: slot, action: p.action, status: 'pending', requestId: p.requestId], 202)
}

// One slot, allowed slots only (SBA:/SBM: names in their ranges, or approved
// adopted slots), at most REVEAL_LIMIT_PER_MIN per minute across the app.
// The code is returned to the caller and never logged.
Map apiRevealCode() {
    if (!lockManagementActive()) return notEnabled()
    if (rateLimited('revealTimes', REVEAL_LIMIT_PER_MIN)) return jsonResponse([error: 'rate_limited'], 429)
    Map r = resolveAllowedSlot(params.lockId, params.slot, false)
    if (r.response) return r.response
    String code = r.codes[r.slot.toString()].code.toString()
    log.info "SignalBricks Telemetry: code revealed for ${r.lock.displayName} slot ${r.slot}"
    return jsonResponse([lockId: r.lock.id.toString(), slot: r.slot, name: r.currentName, code: code])
}

// --- Relocate (#31) ---

// True when a change still being verified uses this slot. With relocatesOnly,
// only relocates count (a set or delete on the slot of another set or delete
// supersedes it, as before).
Boolean changePendingOn(String lockId, Integer slot, Boolean relocatesOnly) {
    return pendingCodeChanges().values().any { p ->
        p.status == 'pending' && p.lockId == lockId && (!relocatesOnly || p.action == 'relocate') &&
            ((p.slot as Integer) == slot || (p.fromSlot != null && (p.fromSlot as Integer) == slot))
    }
}

Map relocatePending() {
    return jsonResponse([error: 'change_pending', message: 'a code change is in progress on this slot; wait for its result'], 409)
}

// Source and target checks for a relocate. Source: an occupied slot the hub
// would let SignalBricks delete (resolveAllowedSlot). Target: the source itself
// (rename) or an empty slot within the ceiling, with the same rules as a set
// (resolveSet: name, booking guard, class marker, maxCodes). The new name must
// carry SBA: or SBM:, adopted slots included. Returns [lock, slot (target),
// fromSlot, fromName, prevName, code] or [response, error]. Holds the code:
// never log the result.
Map resolveRelocate(def lockIdRaw, def slotRaw, def toSlotRaw, String name) {
    Map src = resolveAllowedSlot(lockIdRaw, slotRaw, false)
    if (src.response) return src
    Integer toSlot = src.slot
    if (toSlotRaw != null && toSlotRaw.toString() != '') {
        Integer n = parseIntLoose(toSlotRaw)
        toSlot = n == null ? null : parseSlot(n)
        if (toSlot == null) return refuse('invalid_slot', 400, "toSlot must be a slot number 1-${MAX_SLOT}")
    }
    Boolean move = toSlot != src.slot
    // A registered slot (#684) is renamed in place only, to its registered
    // name or the rename's pendingName (checked by resolveSet).
    if (move && src.slotClass == 'registered') return refuse('move_not_supported', 409, 'registered slots are renamed in place only')
    if (move && toSlot > managedCeiling()) return refuse('slot_not_allowed', 403, "toSlot must be within slots 1-${managedCeiling()}")
    Map dst = resolveSet(lockIdRaw, toSlot, name)
    if (dst.response) return dst
    if (dst.slotClass != 'registered' && !name.startsWith(ADMIN_NAME_PREFIX) && !name.startsWith(MANAGED_NAME_PREFIX)) {
        return refuse('name_not_allowed', 403, "the new name must start with ${ADMIN_NAME_PREFIX} or ${MANAGED_NAME_PREFIX}")
    }
    if (move && dst.occupied) return refuse('slot_occupied', 409, 'toSlot already holds a code')
    // A driver reporting the code as a number has lost any leading zeros, so
    // writing it back could change the code. Other codes must be what a set
    // accepts.
    def code = src.codes[src.slot.toString()].code
    if (!(code instanceof String) || !(code ==~ /\d{4,10}/)) {
        return refuse('code_not_copyable', 409, 'the lock reports this code as a number or not as 4-10 digits; it cannot be copied safely')
    }
    return [lock: src.lock, slot: toSlot, fromSlot: src.slot, fromName: src.currentName, prevName: dst.currentName, code: code]
}

// Re-send checks for a relocate, from a fresh read of lockCodes. Set phase:
// the same rules as the request, and the source must still hold the code and
// name it had then. Delete phase: the source must still be allowed, the target
// must hold the code under the new name, and the source must be empty or still
// hold its request-time name and code. A source delete is only ever sent
// after these pass on the same read.
Map resolvePendingRelocate(Map p) {
    if (p.phase == 'delete') {
        Map d = resolveAllowedSlot(p.lockId, p.fromSlot, true)
        if (d.response) return d
        if (!codeChangeApplied(p, d.codes)) return refuse('target_changed', 409, 'the target slot no longer holds the code')
        def src = d.codes[p.fromSlot.toString()]
        if (d.occupied && (d.currentName != p.fromName || !sameCode(src.code, p.code?.toString()))) {
            return refuse('source_changed', 409, 'the source slot now holds a different code')
        }
        return d
    }
    Map r = resolveRelocate(p.lockId, p.fromSlot, p.slot, p.name)
    if (r.response) return r
    if (r.code != p.code || r.fromName != p.fromName) return refuse('slot_changed', 409, 'the source slot now holds a different code; send a new request')
    return r
}

// Body {name, toSlot?, requestId?}. No toSlot (or toSlot equal to :slot)
// renames the code in place; otherwise moves it to toSlot. The code comes
// from the lock's own lockCodes and is never returned, logged or sent. The
// hub verifies the change and reports a relocate_result event.
Map apiRelocateCode() {
    if (!lockManagementActive()) return notEnabled()
    def body = request.JSON
    if (!(body instanceof Map)) return jsonResponse([error: 'invalid_body'], 400)
    Map rid = requestIdFrom(body.requestId)
    if (rid.response) return rid.response
    String name = body.name?.toString()?.trim()
    Map r = resolveRelocate(params.lockId, params.slot, body.toSlot, name)
    if (r.response) return r.response
    expirePendingCodeChanges()
    String lockId = r.lock.id.toString()
    if (changePendingOn(lockId, r.fromSlot, false) || changePendingOn(lockId, r.slot, false)) return relocatePending()
    Map refusal = writeRefusal(r.lock, r.slot)
    if (refusal) return refusal
    // Failed changes held for retry on either slot are replaced by this one.
    Map all = pendingCodeChanges()
    all.remove(pendingKey(lockId, r.fromSlot))
    all.remove(pendingKey(lockId, r.slot))
    savePendingCodeChanges(all)
    Map p = [lockId: lockId, slot: r.slot, fromSlot: r.fromSlot, action: 'relocate', phase: 'set', name: name, fromName: r.fromName,
             prevName: r.prevName, code: r.code, requestId: rid.id, attempts: 0, createdAt: now(), status: 'pending']
    try {
        sendCodeChange(p, r.lock)
    } catch (Exception e) {
        // The change was never stored.
        return sendErrorResponse('relocate', r.lock, "${r.fromSlot} -> ${r.slot}", e, 'writeTimes')
    }
    log.info "SignalBricks Telemetry: relocate requested on ${r.lock.displayName} slot ${r.fromSlot} -> ${r.slot}"
    return jsonResponse([lockId: lockId, fromSlot: r.fromSlot, toSlot: r.slot, status: 'pending', requestId: rid.id], 202)
}

// Moves a relocate on from one read of lockCodes. Returns true when it settled
// the change or started its delete phase. The source is only ever deleted
// right after this same read showed the target holding the code under the new
// name, so a move never leaves the code in neither slot.
Boolean relocateProgress(Map p, lock, Map codes) {
    Boolean targetHolds = codeChangeApplied(p, codes)
    Boolean startDelete = p.phase != 'delete'
    if (startDelete) {
        if (!targetHolds) {
            if (!targetNameMismatch(p, codes)) return false
            // Re-sending can't fix it (and the target now fails the set rules).
            finishCodeChange(p, 'failed', 'target_name_mismatch', true)
            return true
        }
        if ((p.fromSlot as Integer) == (p.slot as Integer)) {
            finishCodeChange(p, 'confirmed', null, true)
            return true
        }
        p.phase = 'delete'
    } else if (!targetHolds) {
        finishCodeChange(p, 'failed', 'target_changed', true)
        return true
    }
    def src = codes[p.fromSlot.toString()]
    Boolean occupied = src instanceof Map && src.code != null && src.code.toString() != ''
    if (!occupied) {
        withdrawApproval(p.lockId, p.fromSlot as Integer)
        finishCodeChange(p, 'confirmed', null, true)
        return true
    }
    if (src.name?.toString() != p.fromName || !sameCode(src.code, p.code?.toString())) {
        finishCodeChange(p, 'failed', 'source_changed', true)
        return true
    }
    if (!startDelete) return false
    Map r = resolvePendingRelocate(p)
    if (r.response) {
        finishCodeChange(p, 'failed', r.error, true)
        return true
    }
    p.attempts = 0
    sendCodeChange(p, lock)
    return true
}

// The lock stored the code in the target under a name other than the new one
// (for a rename, other than the old one too). The source is kept.
Boolean targetNameMismatch(Map p, Map codes) {
    def t = codes[p.slot.toString()]
    return t instanceof Map && sameCode(t.code, p.code?.toString()) && t.name?.toString() != p.name &&
        ((p.fromSlot as Integer) != (p.slot as Integer) || t.name?.toString() != p.fromName)
}

// A move that ends failed after its source delete was sent: if the source is
// empty now, its approval goes, as after a confirmed move.
void withdrawApprovalIfSourceEmpty(Map p) {
    if (p.action != 'relocate' || p.phase != 'delete') return
    def lock = findCodeLock(p.lockId)
    Map codes = lock ? readLockCodes(lock) : null
    if (codes == null) return
    def src = codes[p.fromSlot.toString()]
    if (!(src instanceof Map) || src.code == null || src.code.toString() == '') withdrawApproval(p.lockId, p.fromSlot as Integer)
}

// After a move: the source's operator approval (adopted slots above the
// ceiling) goes with the code, so the emptied slot can't be written again
// without a new approval. Targets are within the ceiling and need none.
void withdrawApproval(String lockId, Integer slot) {
    String key = "${lockId}:${slot}".toString()
    List approved = approvedAdoptedKeys()
    if (!approved.contains(key)) return
    List kept = approved.findAll { it != key }
    if (kept) app.updateSetting('approvedAdoptedSlots', [type: 'enum', value: kept])
    else app.removeSetting('approvedAdoptedSlots')
}

// --- Set/delete verification (#520) ---
//
// state.pendingCodeChanges: "<lockId>:<slot>" -> {lockId, slot, action, name,
// prevName (the slot's name at request time), code (set and relocate only),
// requestId, attempts, createdAt, status, seq, checkAt, check}. One per lock
// and slot, at most MAX_PENDING_CODE_CHANGES. A relocate (#31) is keyed by its
// target slot and also has fromSlot, fromName and phase (set, then delete for
// a move). `status` is pending (being verified) or
// failed (kept only while the retry endpoint could still succeed). Entries go
// on confirm, supersede, disable/disconnect, when the slot stops being
// allowed, or 24h after createdAt. The code lives only here: never log it,
// return it or report it. (Hub admins can see app state on App Status, as
// they can see lockCodes on the device page.)
//
// One verifyCodeChanges job is scheduled for the earliest `checkAt`, so
// re-sends and superseded changes never stack scheduled jobs.

String pendingKey(String lockId, Integer slot) {
    return "${lockId}:${slot}".toString()
}

Map pendingCodeChanges() {
    return (state.pendingCodeChanges ?: [:]) as Map
}

// Reassigned on every change so Hubitat persists the nested map. Also
// reschedules the expiry and verification jobs.
void savePendingCodeChanges(Map all) {
    if (all) {
        state.pendingCodeChanges = all
        long next = all.values().collect { it.createdAt as long }.min() + PENDING_CODE_TTL_MS
        runIn(secondsUntil(next), 'expirePendingCodeChanges')
    } else {
        state.remove('pendingCodeChanges')
        unschedule('expirePendingCodeChanges')
    }
    List due = all.values().findAll { it.status == 'pending' }.collect { it.checkAt as long }
    if (due) runIn(secondsUntil(due.min()), 'verifyCodeChanges')
    else unschedule('verifyCodeChanges')
}

long secondsUntil(long atMs) {
    return Math.max(1L, (long) Math.ceil((atMs - now()) / 1000.0d))
}

// Throws when the driver does; then nothing is stored and the change it would
// replace is left as it was (so it is reported superseded only after the send).
void startCodeChange(Map r, String action, String name, String code, String requestId) {
    expirePendingCodeChanges()
    String lockId = r.lock.id.toString()
    Map old = pendingCodeChanges()[pendingKey(lockId, r.slot)]
    Map p = [lockId: lockId, slot: r.slot, action: action, name: name, prevName: r.currentName, requestId: requestId, attempts: 0, createdAt: now(), status: 'pending']
    if (code) p.code = code
    sendCodeChange(p, r.lock)
    if (old?.status == 'pending' && old.requestId != requestId) {
        // The new change is stored: a failed report must not fail the request.
        try {
            reportCodeResult(old, 'superseded', null)
        } catch (Exception e) {
            log.warn "SignalBricks Telemetry: superseded report failed for lock ${lockId} slot ${r.slot} (${e.class.simpleName})"
        }
    }
}

// Sends the command, stores the change and schedules its first check.
void sendCodeChange(Map p, lock) {
    p.attempts = (p.attempts as Integer) + 1
    p.seq = ((state.codeChangeSeq ?: 0) as long) + 1
    state.codeChangeSeq = p.seq
    if (p.action == 'set') lock.setCode(p.slot as Integer, p.code, p.name)
    else if (p.action == 'relocate' && p.phase == 'delete') lock.deleteCode(p.fromSlot as Integer)
    else if (p.action == 'relocate') lock.setCode(p.slot as Integer, p.code, p.name)
    else lock.deleteCode(p.slot as Integer)
    p.check = 1
    p.checkAt = now() + CODE_FIRST_CHECK_SECONDS * 1000L
    Map all = pendingCodeChanges()
    all[pendingKey(p.lockId, p.slot as Integer)] = p
    savePendingCodeChanges(all)
}

// Codes are compared as strings, never as numbers ("0123" != "123"). A driver
// that reports the code as a JSON number has already lost leading zeros, so
// then the number is compared with the sent code minus its leading zeros.
Boolean sameCode(def actual, String expected) {
    if (actual == null || expected == null) return false
    if (actual instanceof Number) {
        return new BigDecimal(actual.toString()).toPlainString() == expected.replaceFirst(/^0+(?=\d)/, '')
    }
    return actual.toString() == expected
}

// Set: the slot holds the expected name and code. Delete: the slot is empty.
// Relocate: the target slot holds the new name and the code (either phase).
Boolean codeChangeApplied(Map p, Map codes) {
    def entry = codes[p.slot.toString()]
    Boolean occupied = entry instanceof Map && entry.code != null && entry.code.toString() != ''
    if (p.action == 'delete') return !occupied
    return occupied && entry.name?.toString() == p.name?.toString() && sameCode(entry.code, p.code?.toString())
}

// Scheduled: runs every check that is due, then schedules the next one. A
// check that throws (e.g. the driver's setCode) fails only its own change.
void verifyCodeChanges() {
    reregisterIfVersionChanged()
    try {
        expirePendingCodeChanges()
        long due = now() + 1000L
        List keys = pendingCodeChanges().findAll { k, p -> p.status == 'pending' && (p.checkAt as long) <= due }.keySet() as List
        keys.each { String key ->
            try {
                verifyCodeChange(key)
            } catch (Exception e) {
                // Only the class name: exception messages can carry the
                // command's arguments, i.e. the code.
                log.warn "SignalBricks Telemetry: code change check failed for ${key} (${e.class.simpleName})"
                try {
                    Map p = pendingCodeChanges()[key]
                    if (p) finishCodeChange(p, 'failed', 'send_error', p.status == 'pending')
                } catch (Exception e2) {
                    // Cleanup failed too: drop the change (and its code) so the
                    // job doesn't re-run it every second until it expires.
                    log.warn "SignalBricks Telemetry: dropped code change ${key} after a second error (${e2.class.simpleName})"
                    Map all = pendingCodeChanges()
                    all.remove(key)
                    if (all) state.pendingCodeChanges = all
                    else state.remove('pendingCodeChanges')
                }
            }
        }
    } finally {
        savePendingCodeChanges(pendingCodeChanges())
    }
}

// Check 1 (30s after a send) confirms early; check 2 (the attempt's final
// check) re-sends, or fails after CODE_MAX_ATTEMPTS.
void verifyCodeChange(String key) {
    Map p = pendingCodeChanges()[key]
    if (!p || p.status != 'pending') return
    def lock = findCodeLock(p.lockId)
    if (!lockManagementActive() || !lock) {
        finishCodeChange(p, 'failed', lock ? 'lock_management_disabled' : 'lock_not_found', true)
        return
    }
    Map codes = readLockCodes(lock)
    if (codes != null && p.action == 'relocate') {
        if (relocateProgress(p, lock, codes)) return
    } else if (codes != null && codeChangeApplied(p, codes)) {
        finishCodeChange(p, 'confirmed', null, true)
        return
    }
    int attempts = p.attempts as Integer
    if ((p.check as Integer) == 1) {
        int finalAt = CODE_FINAL_CHECK_SECONDS[Math.min(attempts, CODE_FINAL_CHECK_SECONDS.size()) - 1]
        p.check = 2
        p.checkAt = (p.checkAt as long) + (finalAt - CODE_FIRST_CHECK_SECONDS) * 1000L
        Map all = pendingCodeChanges()
        all[key] = p
        savePendingCodeChanges(all)
        return
    }
    if (attempts >= CODE_MAX_ATTEMPTS) {
        String reason = codes == null ? 'lock_codes_unreadable' : (p.phase == 'delete' ? 'source_not_deleted' : 'not_applied')
        finishCodeChange(p, 'failed', reason, true)
        return
    }
    // Re-send only while the slot is still allowed and still ours.
    Map r = resolvePendingChange(p)
    if (r.response) {
        finishCodeChange(p, 'failed', r.error, true)
        return
    }
    // The fresh read in resolvePendingRelocate found the source already empty.
    if (p.action == 'relocate' && p.phase == 'delete' && !r.occupied) {
        withdrawApproval(p.lockId, p.fromSlot as Integer)
        finishCodeChange(p, 'confirmed', null, true)
        return
    }
    log.info "SignalBricks Telemetry: ${p.action} not applied yet on ${lock.displayName} slot ${p.action == 'relocate' ? "${p.fromSlot} -> ${p.slot}" : p.slot}; re-sending (attempt ${attempts + 1})"
    sendCodeChange(p, lock)
}

// Failed reasons the retry endpoint can still fix; any other failure drops
// the change (and its code). A failed relocate is always dropped: a new
// relocate request needs no code, so there is nothing to keep it for.
@Field static final Set<String> RETRYABLE_FAILURES = ['not_applied', 'lock_codes_unreadable'] as Set

// Reports the outcome (when `report`) and stores the change's final state:
// confirmed or permanently failed changes are dropped, retryable failures are
// kept for the retry endpoint until they expire.
void finishCodeChange(Map p, String result, String reason, Boolean report) {
    if (result == 'confirmed' && p.action != 'delete') promoteRegisteredName(p.lockId, p.slot as Integer, p.name?.toString())
    if (report) reportCodeResult(p, result, reason)
    if (result == 'failed') withdrawApprovalIfSourceEmpty(p)
    Map all = pendingCodeChanges()
    String key = pendingKey(p.lockId, p.slot as Integer)
    if (result == 'failed' && p.action != 'relocate' && RETRYABLE_FAILURES.contains(reason)) {
        p.status = 'failed'
        p.reason = reason
        all[key] = p
    } else {
        all.remove(key)
    }
    savePendingCodeChanges(all)
}

// Rename handshake (#684): once the hub has seen the slot hold the code under
// a registration's pendingName, that name becomes the registered one and the
// old name is no longer accepted. Until then both are. SignalBricks then sends
// {name: <new>} itself; a config it sends meanwhile with both names is fine.
void promoteRegisteredName(String lockId, Integer slot, String name) {
    Map entry = registeredEntry(lockId, slot)
    if (entry?.pendingName == null || entry.pendingName != name) return
    Map cfg = state.lockConfig
    List regs = cfg.locks[lockId].registered.collect { Map e ->
        (e.slot as Integer) == slot ? [slot: slot, name: name] : e
    }
    cfg.locks[lockId].registered = regs
    // Reassigned so Hubitat persists the nested change.
    state.lockConfig = cfg
    log.info "SignalBricks Telemetry: registered name updated on lock ${lockId} slot ${slot}"
}

// After POST /lockConfig: drop changes whose slot is no longer allowed (an
// in-flight one is reported failed), since they could never be re-sent.
// Also drops a set or relocate on a registered slot whose name the new config
// no longer accepts (#684: e.g. a rename cancelled back to the old name), so a
// late re-send can't leave a name on the lock that no registration accepts.
void dropDisallowedCodeChanges() {
    Map all = pendingCodeChanges()
    Map reasons = [:]
    all.each { k, p ->
        if (slotClass(p.lockId, p.slot as Integer) == null || (p.fromSlot != null && slotClass(p.lockId, p.fromSlot as Integer) == null)) {
            reasons[k] = 'slot_not_allowed'
        } else if (p.action != 'delete' && slotClass(p.lockId, p.slot as Integer) == 'registered' &&
                   !registeredNameMatches(registeredEntry(p.lockId, p.slot as Integer), p.name?.toString())) {
            reasons[k] = 'name_not_allowed'
        }
    }
    Map gone = all.findAll { k, p -> reasons.containsKey(k) }
    if (!gone) return
    gone.each { k, p ->
        if (p.status == 'pending') reportCodeResult(p, 'failed', reasons[k])
        withdrawApprovalIfSourceEmpty(p)
        all.remove(k)
    }
    savePendingCodeChanges(all)
}

// Drops changes older than 24h; one still being verified is reported failed.
void expirePendingCodeChanges() {
    Map all = pendingCodeChanges()
    if (!all) return
    long cutoff = now() - PENDING_CODE_TTL_MS
    Map expired = all.findAll { k, p -> (p.createdAt as long) <= cutoff }
    if (!expired) return
    expired.each { k, p ->
        if (p.status == 'pending') reportCodeResult(p, 'failed', 'expired')
        all.remove(k)
    }
    savePendingCodeChanges(all)
}

// On Done/update: drop changes (failed ones too) for locks no longer selected
// for lock code management; an in-flight one is reported failed.
void dropCodeChangesForMissingLocks() {
    Map all = pendingCodeChanges()
    Map gone = all.findAll { k, p -> !findCodeLock(p.lockId) }
    if (!gone) return
    gone.each { k, p ->
        if (p.status == 'pending') reportCodeResult(p, 'failed', 'lock_not_found')
        all.remove(k)
    }
    savePendingCodeChanges(all)
}

// After initialize() (which unschedules everything): check in-flight changes again.
void resumePendingCodeChanges() {
    expirePendingCodeChanges()
    Map all = pendingCodeChanges()
    all.values().findAll { it.status == 'pending' }.each {
        it.check = 1
        it.checkAt = now() + CODE_FIRST_CHECK_SECONDS * 1000L
    }
    savePendingCodeChanges(all)
}

// Lock management turned off or the hub disconnected: drop every change (and
// its code); in-flight ones are reported failed.
void abandonPendingCodeChanges(String reason) {
    pendingCodeChanges().values().findAll { it.status == 'pending' }.each { reportCodeResult(it, 'failed', reason) }
    savePendingCodeChanges([:])
}

// Sent like a telemetry event (same ingest URL and token), so it arrives as
// event type hubitat.lock_code_result (hubitat.relocate_result for a
// relocate). Never carries the code; the name only when it is an SBA:/SBM:
// name. A relocate adds fromSlot, toSlot, targetConfirmed (the hub saw the
// target hold the code under the new name) and then the target's fingerprint
// when a fingerprint key is held.
void reportCodeResult(Map p, String result, String reason) {
    Boolean relocate = p.action == 'relocate'
    String slots = relocate ? "${p.fromSlot} -> ${p.slot}" : "${p.slot}"
    log.info "SignalBricks Telemetry: code ${p.action} ${result} on lock ${p.lockId} slot ${slots} after ${p.attempts} attempt(s)${reason ? " (${reason})" : ''}"
    if (!state.sbToken || !isHttps(state.ingestUrl)) return
    String eventName = relocate ? 'relocate_result' : 'lock_code_result'
    Map data = [lockId: p.lockId, slot: p.slot as Integer, action: p.action, result: result, attempts: p.attempts as Integer, requestId: p.requestId]
    if (reason) data.reason = reason
    if (relocate) {
        Boolean targetConfirmed = result == 'confirmed' || p.phase == 'delete'
        data.fromSlot = p.fromSlot as Integer
        data.toSlot = p.slot as Integer
        data.targetConfirmed = targetConfirmed
        if (targetConfirmed && state.fingerprintKey && p.code) {
            data.fingerprint = codeFingerprint(state.fingerprintKey.toString().decodeBase64(), p.code.toString())
        }
    }
    String name = p.name?.toString()
    if (name && (name.startsWith(ADMIN_NAME_PREFIX) || name.startsWith(MANAGED_NAME_PREFIX)) && !isBookingName(name)) {
        data.name = name
    }
    Map payload = [
        id: "${eventName}:${p.requestId}:${p.seq}:${result}".toString(),
        deviceId: p.lockId,
        name: eventName,
        displayName: findCodeLock(p.lockId)?.displayName,
        value: result,
        data: JsonOutput.toJson(data),
        date: new Date(now()).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone('UTC')),
        version: APP_VERSION,
    ]
    Map params = [
        uri: state.ingestUrl,
        requestContentType: 'application/json',
        contentType: 'application/json',
        headers: [Authorization: "Bearer ${state.sbToken}"],
        body: JsonOutput.toJson(payload),
        timeout: 15,
    ]
    asynchttpPost('postCallback', params, [name: eventName, deviceId: p.lockId])
}

String escapeHtml(String s) {
    return s?.replace('&', '&amp;')?.replace('<', '&lt;')?.replace('>', '&gt;')?.replace('"', '&quot;')
}

void logDebug(String msg) {
    if (debugLogging) log.debug "SignalBricks Telemetry: ${msg}"
}
