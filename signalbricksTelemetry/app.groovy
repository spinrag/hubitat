/**
 * SignalBricks Telemetry
 *
 * Read-only telemetry sender for SignalBricks. Forwards lock, contact,
 * temperature, humidity and battery events to the SignalBricks ingestion API.
 * It never sends device commands and never reads or sends lock codes (PINs):
 * the `lockCodes` attribute is deliberately not subscribed to.
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

@Field static final String APP_VERSION = '1.0.0'
@Field static final String SB_PORTAL_URL = 'https://app.signalbricks.co'
// Public OAuth client (this source is public): no client secret. Each connect
// is protected by PKCE (S256) plus a signed-in SignalBricks admin's consent.
@Field static final String OAUTH_CLIENT_ID = 'signalbricks-hubitat-telemetry'
@Field static final String HUBITAT_STATE_REDIRECT = 'https://cloud.hubitat.com/oauth/stateredirect'
@Field static final String DOCS_URL = 'https://github.com/spinrag/hubitat/blob/main/signalbricksTelemetry/README.md'

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
}

Map mainPage() {
    dynamicPage(name: 'mainPage', title: 'SignalBricks Telemetry', install: true, uninstall: true) {
        section() {
            paragraph 'Sends device events to SignalBricks. Read-only: this app never controls devices and never sends lock codes (PINs).'
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
                href(name: 'hrefReconnect', title: 'Reconnect to SignalBricks', description: 'Issue a new token (replaces the current one)', style: 'external', url: connectUrl())
                input(name: 'btnDisconnect', type: 'button', title: 'Disconnect')
                paragraph '<small>Disconnect stops sending immediately. To also revoke the token, remove it under Tokens in the SignalBricks portal.</small>'
            } else {
                paragraph '<b>Status:</b> Not connected'
                if (state.lastError) {
                    paragraph "<b>Last error:</b> ${state.lastError}"
                }
                href(name: 'hrefConnect', title: 'Connect to SignalBricks', description: 'Sign in and choose the property this hub belongs to', style: 'external', url: connectUrl())
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
        state.remove('sbToken')
        state.remove('ingestUrl')
        state.remove('connectedAt')
        state.remove('lastError')
    }
}

void eventHandler(evt) {
    if (!state.sbToken || !state.ingestUrl) return

    Map payload = [
        id: evt.id,
        deviceId: evt.deviceId,
        name: evt.name,
        displayName: evt.displayName,
        source: evt.source,
        value: evt.value,
        data: evt.data,
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
            if (resp.status == 200 && body.access_token && body.ingest_url?.toString()?.startsWith('http')) {
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

String escapeHtml(String s) {
    return s?.replace('&', '&amp;')?.replace('<', '&lt;')?.replace('>', '&gt;')?.replace('"', '&quot;')
}

void logDebug(String msg) {
    if (debugLogging) log.debug "SignalBricks Telemetry: ${msg}"
}
