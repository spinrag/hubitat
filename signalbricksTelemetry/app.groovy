/**
 * SignalBricks Telemetry
 *
 * Read-only telemetry sender for SignalBricks. Forwards lock, contact,
 * temperature, humidity and battery events to the SignalBricks ingestion API.
 * Telemetry never sends device commands and never reads or sends lock codes
 * (PINs): the `lockCodes` attribute is deliberately not subscribed to.
 *
 * Optional "Lock code management" (off by default, signalbricks/platform#472):
 * when enabled, separately selected locks get access-token gated endpoints
 * that let SignalBricks list slots (never codes), set and delete codes and
 * reveal one code at a time. The hub enforces which slots may be touched:
 * admin/managed ranges and adopted slots come from SignalBricks (/lockConfig),
 * but only up to a ceiling set in this app's UI; adopted slots above it need
 * the operator's approval in the UI. Slots whose name has an upper-case "ORB"
 * at the start or after a space (booking codes) are always refused, and
 * created names must carry SBA:/SBM: and never such an "ORB".
 * So neither the platform nor anyone holding its token can reach booking
 * codes. Codes are never logged or put in events.
 *
 * Setup uses Hubitat's documented OAuth "stateredirect" pattern with
 * SignalBricks as the OAuth provider: "Connect to SignalBricks" opens the
 * SignalBricks portal, the user picks the organization/property/hub, and
 * Hubitat cloud forwards the authorization code to this app's /oauthCallback
 * mapping, which exchanges it for an ingestion token. The app is a public
 * OAuth client and uses PKCE (S256); there is no client secret.
 *
 * Runs alongside the SignalBricks Integration (lock-code) app; the two are
 * independent.
 *
 * Copyright (c) 2026 Spinrag, LLC. MIT License.
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String APP_VERSION = '1.1.1'
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

// Lock code management (#472)
@Field static final int MAX_SLOT = 1000
@Field static final int MAX_ADOPTED_PER_LOCK = 250
@Field static final int MAX_NAME_LENGTH = 64
// Default hub-local ceiling for admin + managed ranges (layout: admin 1-10,
// managed 11-25, bookings from 26). Only the app UI can change it.
@Field static final int DEFAULT_MAX_MANAGED_SLOT = 25
@Field static final int REVEAL_LIMIT_PER_MIN = 10
@Field static final String ADMIN_NAME_PREFIX = 'SBA:'
@Field static final String MANAGED_NAME_PREFIX = 'SBM:'

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
    path('/locks/:lockId/codes') {
        action: [POST: 'apiSetCode']
    }
    path('/locks/:lockId/codes/:slot') {
        action: [DELETE: 'apiDeleteCode']
    }
    path('/locks/:lockId/codes/:slot/reveal') {
        action: [GET: 'apiRevealCode']
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

        section('<b>Connection</b>') {
            if (!ensureAccessToken()) {
                paragraph '<b>OAuth is not enabled for this app.</b> In <i>Apps Code</i>, open SignalBricks Telemetry, click <i>OAuth</i> → <i>Enable OAuth in App</i> → <i>Update</i>, then reopen this app.'
            } else if (state.sbToken) {
                paragraph "<b>Status:</b> Connected${state.connectedAt ? " since ${state.connectedAt}" : ''}"
                paragraph "<b>Sending to:</b> ${state.ingestUrl}"
                if (state.lastError) {
                    paragraph "<b>Last error:</b> ${state.lastError}"
                }
                if (lockManagementEnabled) {
                    // The connect URL carries this app's access token in the
                    // OAuth state, and while lock management is on that token
                    // is the one SignalBricks holds for lock control.
                    paragraph '<small>To reconnect, first turn off lock code management below (this rotates the access token), reconnect, then turn it back on.</small>'
                } else {
                    href(name: 'hrefReconnect', title: 'Reconnect to SignalBricks', description: 'Issue a new token (replaces the current one)', style: 'external', url: connectUrl())
                }
                input(name: 'btnDisconnect', type: 'button', title: 'Disconnect')
                paragraph '<small>Disconnect stops sending immediately. To also revoke the token, remove it under Tokens in the SignalBricks portal.</small>'
            } else {
                paragraph '<b>Status:</b> Not connected'
                if (state.lastError) {
                    paragraph "<b>Last error:</b> ${state.lastError}"
                }
                // Not connected: SignalBricks holds no valid token for this app
                // (Disconnect rotated it), so the token in this URL controls nothing.
                href(name: 'hrefConnect', title: 'Connect to SignalBricks', description: 'Sign in and choose the property this hub belongs to', style: 'external', url: connectUrl())
            }
        }

        section('<b>Lock code management (optional)</b>') {
            paragraph 'Lets SignalBricks list code slots and manage admin (<code>SBA:</code>) and managed (<code>SBM:</code>) codes on the locks selected here. Off by default. SignalBricks can only touch slots up to the ceiling you set here, plus existing codes you approve below; booking codes (<code>ORB…</code>) are always refused. Codes are never logged or sent as telemetry.'
            input(name: 'lockManagementEnabled', type: 'bool', title: 'Enable lock code management', defaultValue: false, submitOnChange: true)
            if (lockManagementEnabled) {
                input(name: 'codeLocks', type: 'capability.lockCodes', title: 'Locks SignalBricks may manage codes on', multiple: true, required: false, submitOnChange: true)
                input(name: 'maxManagedSlot', type: 'number', title: "Highest slot SignalBricks may manage (admin + managed codes; keep it below the booking apps' minimum code position)", range: "1..${MAX_SLOT}", defaultValue: DEFAULT_MAX_MANAGED_SLOT, required: true, submitOnChange: true)
                Map pending = pendingAdoptedOptions()
                if (pending) {
                    paragraph '<b>Existing codes above the ceiling that SignalBricks asks to manage.</b> Approve only codes you recognise as admin or managed codes; never booking codes.'
                    input(name: 'approvedAdoptedSlots', type: 'enum', title: 'Approve these slots', options: pending, multiple: true, required: false, submitOnChange: true)
                }
                if (!state.sbToken) {
                    paragraph '<b>Connect to SignalBricks first.</b> Lock code management registers with SignalBricks after Connect.'
                } else {
                    paragraph "<b>Registration:</b> ${state.lockMgmtRegisteredAt ? "registered ${state.lockMgmtRegisteredAt}" : 'not registered yet (click Done)'}"
                    if (state.lockMgmtRegisterError) {
                        paragraph "<b>Last registration error:</b> ${state.lockMgmtRegisterError}"
                    }
                    paragraph "<b>Slot rules:</b> ${state.lockConfig ? 'set by SignalBricks' : 'not set yet (all code writes are refused until SignalBricks sends them)'}"
                    input(name: 'btnRotateLockToken', type: 'button', title: 'Rotate access token and re-register')
                }
            }
        }

        section('<b>Logging</b>') {
            input(name: 'debugLogging', type: 'bool', title: 'Enable debug logging (turns off after 30 minutes)', defaultValue: false, submitOnChange: true)
            paragraph "<small>Version ${APP_VERSION} · <a href='${DOCS_URL}' target='_blank'>Documentation</a></small>"
        }
    }
}

void installed() {
    log.info 'SignalBricks Telemetry installed'
    ensureAccessToken()
    initialize()
}

void updated() {
    logDebug 'updated'
    initialize()
    if (!syncLockManagementEnabled()) {
        // Re-register on every save, so enabling and app upgrades reach the
        // platform. Cheap: one request, only when connected.
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
    state.remove('lockConfig')
    state.remove('revealTimes')
    app.removeSetting('approvedAdoptedSlots')
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
    if (debugLogging) {
        runIn(1800, 'logsOff')
    }
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
        state.remove('lockConfig')
        app.removeSetting('approvedAdoptedSlots')
        state.remove('sbToken')
        state.remove('ingestUrl')
        state.remove('connectedAt')
        state.remove('lastError')
        state.remove('lockMgmtRegisteredAt')
        state.remove('lockMgmtRegisterError')
    } else if (btn == 'btnRotateLockToken') {
        log.info 'SignalBricks Telemetry: access token rotated'
        createAccessToken()
        registerWithPlatform()
    }
}

void eventHandler(evt) {
    if (!state.sbToken || !isHttps(state.ingestUrl)) return

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
    ]
    logDebug "sending ${evt.displayName} ${evt.name}=${evt.value}"

    Map params = [
        uri: state.ingestUrl,
        requestContentType: 'application/json',
        contentType: 'application/json',
        headers: [Authorization: "Bearer ${state.sbToken}"],
        body: JsonOutput.toJson(payload),
        timeout: 15,
    ]
    asynchttpPost('postCallback', params, [name: evt.name, deviceId: evt.deviceId])
}

// Door codes (PINs) must never leave the hub. `evt.data` is a JSON string;
// parse it and drop secret keys at any depth (slot numbers, code names, error
// and status fields stay). Anything that isn't parseable JSON is dropped
// entirely rather than sent raw. Applied to every event, not just locks.
String sanitizeData(String raw, String eventName) {
    if (!raw) return null
    try {
        Boolean lockContext = LOCK_EVENT_NAMES.contains(eventName?.toLowerCase())
        return JsonOutput.toJson(stripSecrets(parseJson(raw), lockContext))
    } catch (Exception e) {
        return null
    }
}

def stripSecrets(obj, Boolean lockContext) {
    if (obj instanceof Map) {
        Boolean lockHere = lockContext || isLockCodeMap(obj)
        return obj.findAll { k, v -> !isSecretKey(k, lockHere) }
            .collectEntries { k, v -> [(k): stripSecrets(v, lockHere)] }
    }
    if (obj instanceof List) return obj.collect { stripSecrets(it, lockContext) }
    return obj
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
    Map page = handleOauthCallback()
    // The access token travelled in the authorize URL's `state`, so copies can
    // sit in proxy/CDN access logs. Replace it once the callback has run
    // (success or failure): createAccessToken() invalidates the previous token,
    // the same behaviour signalbricksIntegration's "Refresh Access Token"
    // button relies on. The only other inbound calls are the lock-management
    // endpoints, which use the new token: it is registered with SignalBricks
    // below and never appears in a URL (Reconnect is hidden while lock
    // management is on, and Connect only shows when no token is registered).
    createAccessToken()
    registerWithPlatform()
    return page
}

Map handleOauthCallback() {
    String code = params.code
    String verifier = state.pkceVerifier
    // Single use: the next connect attempt gets a fresh verifier.
    state.remove('pkceVerifier')
    if (params.error || !code) {
        String reason = params.error ?: 'no authorization code returned'
        log.warn "SignalBricks connect cancelled: ${reason}"
        return renderPage('Not connected', "SignalBricks did not authorize this hub (${escapeHtml(reason)}). You can close this window and try again from the app.")
    }
    if (!verifier) {
        state.lastError = 'Connect session expired; start again from the app'
        return renderPage('Not connected', 'This connect link is no longer valid. Close this window and click Connect to SignalBricks again in the app.')
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
            } else {
                state.lastError = "Token exchange returned HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        // Do not log the exception body; it may echo request data.
        state.lastError = "Token exchange failed: ${e.class.simpleName}"
        log.warn "SignalBricks token exchange failed: ${e.message}"
    }

    if (state.sbToken && !state.lastError) {
        log.info 'Connected to SignalBricks'
        subscribeToEvents()
        return renderPage('Connected', 'This hub is now connected to SignalBricks. You can close this window and return to the app.')
    }
    return renderPage('Not connected', "Could not complete the connection (${escapeHtml(state.lastError ?: 'unknown error')}). Close this window and try again from the app.")
}

Map renderPage(String title, String message) {
    String html = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>SignalBricks Telemetry</title></head>
<body style="font-family:sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem">
<h2>SignalBricks Telemetry: ${title}</h2><p>${message}</p></body></html>"""
    return render(contentType: 'text/html', data: html, status: 200)
}

// --- Lock code management (#472) ---

// Tokens (ingestion and app access token) only ever go to an https URL.
Boolean isHttps(def url) {
    return url?.toString()?.startsWith('https://')
}

Boolean lockManagementActive() {
    return lockManagementEnabled == true && state.sbToken
}

// Registers this app's cloud endpoint and access token with SignalBricks,
// authenticated with the ingestion token. The access token is sent only while
// lock management is on; with it off, the platform drops what it stored.
// SECURITY: the body carries the app access token; never log it.
void registerWithPlatform(Boolean enabledOverride = null) {
    if (!state.sbToken || !isHttps(state.ingestUrl)) return
    Boolean enabled = enabledOverride != null ? enabledOverride : lockManagementEnabled == true
    if (enabled && !state.accessToken) ensureAccessToken()
    Map body = [
        cloudEndpointBase: getFullApiServerUrl(),
        appAccessToken: enabled ? state.accessToken : null,
        hubUid: getHubUID(),
        appId: app.id.toString(),
        version: APP_VERSION,
        lockManagement: enabled,
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
}

void registerCallback(resp, Map data) {
    if (resp.hasError()) {
        int status = resp.getStatus()
        String msg = status == 401 ? 'SignalBricks rejected the token. Reconnect to SignalBricks.' : "HTTP ${status}"
        log.warn "SignalBricks Telemetry: lock management registration failed: ${msg}"
        state.lockMgmtRegisterError = "${new Date().format('yyyy-MM-dd HH:mm')} ${msg}"
        return
    }
    state.remove('lockMgmtRegisterError')
    state.lockMgmtRegisteredAt = new Date().format('yyyy-MM-dd HH:mm')
    log.info "SignalBricks Telemetry: registered with SignalBricks (lock management ${data?.enabled ? 'on' : 'off'})"
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
// upper-case "ORB" at the start of the name or right after whitespace,
// case-sensitive. Covers "ORB<id>-Guest" (signalbricksIntegration's default
// with an empty prefix) and "<prefix> ORB<id>…"; ordinary words such as
// "Forbes" or "Orbit" don't match. Known gap: the idAtEnd forms
// "Guest-ORB<id>" and "Guest -ORB<id>" don't match either.
@Field static final java.util.regex.Pattern BOOKING_NAME = ~/(^|\s)ORB/

Boolean isBookingName(String name) {
    return name != null && BOOKING_NAME.matcher(name).find()
}

// Validates a /lockConfig body. Returns [config: Map] or [error: String].
//   { admin: {from, to}, managed: {from, to},
//     locks: { "<lockId>": { admin?: {from, to}, managed?: {from, to}, adopted?: [slot, ...] } } }
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
        locksOut[lockId] = out
    }
    return [config: [admin: admin, managed: managed, locks: locksOut]]
}

List approvedAdoptedKeys() {
    def v = approvedAdoptedSlots
    if (!v) return []
    return (v instanceof Collection ? v : [v]).collect { it.toString() }
}

// Adopted slots above the ceiling, as "<lockId>:<slot>" keys.
List adoptedAboveCeiling() {
    Map cfg = state.lockConfig
    if (!cfg) return []
    Integer ceiling = managedCeiling()
    List out = []
    (cfg.locks ?: [:]).each { lockId, l ->
        (l.adopted ?: []).collect { it as Integer }.findAll { it > ceiling }.each { out << "${lockId}:${it}".toString() }
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
        if (isBookingName(name)) label += ' - booking code, always refused'
        options[key] = label
    }
    return options
}

// Which class a slot belongs to on this lock: 'adopted', 'admin', 'managed',
// or null when the hub must refuse it. No config yet = nothing allowed.
// Nothing above the ceiling is allowed unless it is an adopted slot the
// operator approved in the app UI.
String slotClass(String lockId, Integer slot) {
    Map cfg = state.lockConfig
    if (!cfg || slot == null) return null
    Integer ceiling = managedCeiling()
    Map lockCfg = cfg.locks?.get(lockId) ?: [:]
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

Integer maxCodesOf(lock) {
    return parseIntLoose(lock.currentValue('maxCodes'))
}

Map apiGetLockConfig() {
    if (!lockManagementActive()) return notEnabled()
    return jsonResponse([config: state.lockConfig ?: null, ceiling: managedCeiling(), pendingApproval: pendingAdoptedKeys()])
}

Map apiSetLockConfig() {
    if (!lockManagementActive()) return notEnabled()
    Map result = validateLockConfig(request.JSON)
    if (result.error) return jsonResponse([error: 'invalid_config', message: result.error], 400)
    state.lockConfig = result.config
    // Approvals only cover adopted slots the current config still lists.
    List stillAdopted = adoptedAboveCeiling()
    List kept = approvedAdoptedKeys().findAll { stillAdopted.contains(it) }
    if (kept.size() != approvedAdoptedKeys().size()) {
        if (kept) app.updateSetting('approvedAdoptedSlots', [type: 'enum', value: kept])
        else app.removeSetting('approvedAdoptedSlots')
    }
    log.info 'SignalBricks Telemetry: lock slot rules updated'
    return jsonResponse([config: result.config, ceiling: managedCeiling(), pendingApproval: pendingAdoptedKeys()])
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
        [slot: slot, name: name, set: set, allowedAs: cls]
    }.sort { it.slot }
    return jsonResponse([lockId: lock.id.toString(), maxCodes: maxCodesOf(lock), slots: slots])
}

// Common checks for write/delete/reveal. Returns [lock, slot, slotClass,
// codes, currentName] or [response]. Refuses:
//   - slots outside admin/managed/approved-adopted (and above the ceiling)
//   - any slot whose current name is a booking code (isBookingName)
//   - in the admin/managed ranges, a slot holding a code without that
//     range's SBA:/SBM: marker (existing codes must be adopted first);
//     an empty slot may be written (`allowEmpty`), not deleted or revealed.
Map resolveAllowedSlot(def lockIdRaw, def slotRaw, Boolean allowEmpty) {
    def lock = findCodeLock(lockIdRaw)
    if (!lock) return [response: jsonResponse([error: 'lock_not_found'], 404)]
    Integer slot = parseSlot(slotRaw)
    if (slot == null) return [response: jsonResponse([error: 'invalid_slot'], 400)]
    Integer maxCodes = maxCodesOf(lock)
    if (maxCodes != null && slot > maxCodes) return [response: jsonResponse([error: 'invalid_slot', message: 'slot exceeds maxCodes'], 400)]
    String cls = slotClass(lock.id.toString(), slot)
    if (!cls) return [response: jsonResponse([error: 'slot_not_allowed'], 403)]
    Map codes = readLockCodes(lock)
    if (codes == null) return [response: jsonResponse([error: 'lock_codes_unreadable'], 409)]
    def entry = codes[slot.toString()]
    String currentName = entry instanceof Map ? entry.name?.toString() : null
    Boolean occupied = entry instanceof Map && entry.code != null && entry.code.toString() != ''
    if (isBookingName(currentName)) return [response: jsonResponse([error: 'booking_code', message: 'slot holds a booking code'], 403)]
    String prefix = classPrefix(cls)
    if (prefix && occupied && !(currentName?.startsWith(prefix))) {
        return [response: jsonResponse([error: 'slot_not_managed', message: "slot holds a code without the ${prefix} marker; adopt it first"], 403)]
    }
    if (!occupied && !allowEmpty) return [response: jsonResponse([error: 'slot_empty'], 404)]
    return [lock: lock, slot: slot, slotClass: cls, codes: codes, currentName: currentName]
}

// Body {slot, code, name}. The lock confirms asynchronously; poll /slots.
Map apiSetCode() {
    if (!lockManagementActive()) return notEnabled()
    def body = request.JSON
    if (!(body instanceof Map)) return jsonResponse([error: 'invalid_body'], 400)
    String code = body.code?.toString()
    if (!(code ==~ /\d{4,10}/)) return jsonResponse([error: 'invalid_code', message: 'code must be 4-10 digits'], 400)
    String name = body.name?.toString()?.trim()
    if (!name || name.length() > MAX_NAME_LENGTH || !(name ==~ /[\x20-\x7E]+/)) {
        return jsonResponse([error: 'invalid_name', message: "name must be 1-${MAX_NAME_LENGTH} printable ASCII characters"], 400)
    }
    // Never create a name the booking guard (and the booking apps) would read as a booking code.
    if (isBookingName(name)) return jsonResponse([error: 'name_not_allowed', message: 'names must not have an upper-case ORB at the start or after a space'], 403)
    Map r = resolveAllowedSlot(params.lockId, body.slot, true)
    if (r.response) return r.response
    // Created codes carry their class marker; adopted slots keep their name.
    String prefix = classPrefix(r.slotClass)
    if (prefix && !name.startsWith(prefix)) return jsonResponse([error: 'name_not_allowed', message: "${r.slotClass} slots need a ${prefix} name"], 403)
    r.lock.setCode(r.slot, code, name)
    log.info "SignalBricks Telemetry: setCode requested on ${r.lock.displayName} slot ${r.slot}"
    return jsonResponse([lockId: r.lock.id.toString(), slot: r.slot, status: 'pending'], 202)
}

Map apiDeleteCode() {
    if (!lockManagementActive()) return notEnabled()
    Map r = resolveAllowedSlot(params.lockId, params.slot, false)
    if (r.response) return r.response
    r.lock.deleteCode(r.slot)
    log.info "SignalBricks Telemetry: deleteCode requested on ${r.lock.displayName} slot ${r.slot}"
    return jsonResponse([lockId: r.lock.id.toString(), slot: r.slot, status: 'pending'], 202)
}

// One slot, allowed slots only (SBA:/SBM: names in their ranges, or approved
// adopted slots), at most REVEAL_LIMIT_PER_MIN per minute across the app.
// The code is returned to the caller and never logged.
Map apiRevealCode() {
    if (!lockManagementActive()) return notEnabled()
    long nowMs = now()
    List recent = (state.revealTimes ?: []).findAll { (nowMs - (it as long)) < 60000L }
    if (recent.size() >= REVEAL_LIMIT_PER_MIN) {
        state.revealTimes = recent
        return jsonResponse([error: 'rate_limited'], 429)
    }
    recent << nowMs
    state.revealTimes = recent
    Map r = resolveAllowedSlot(params.lockId, params.slot, false)
    if (r.response) return r.response
    String code = r.codes[r.slot.toString()].code.toString()
    log.info "SignalBricks Telemetry: code revealed for ${r.lock.displayName} slot ${r.slot}"
    return jsonResponse([lockId: r.lock.id.toString(), slot: r.slot, name: r.currentName, code: code])
}

String escapeHtml(String s) {
    return s?.replace('&', '&amp;')?.replace('<', '&lt;')?.replace('>', '&gt;')?.replace('"', '&quot;')
}

void logDebug(String msg) {
    if (debugLogging) log.debug "SignalBricks Telemetry: ${msg}"
}
