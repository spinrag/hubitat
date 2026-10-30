// One-click safe Reconnect (#46) for SignalBricks Integration with a stubbed Hubitat
// sandbox: Reconnect is one link to the app's own /reconnect, which opens a 10-minute
// window and forwards to the sign-in (whose link carries the access token SignalBricks
// holds); viewing the page starts nothing. Checks click -> callback
// (success and failure), click -> expiry (token replaced and registered with the
// credential already held), expiry racing the callback, the expiry surviving an
// app-wide unschedule(), a lost timer, registration failure -> quick then 30-minute
// retries on the app page,
// that bookings, slot assignments, pending code writes and settings survive, and that
// no token is logged or rendered outside the open link.
// Fake values only; nothing is sent to a hub or to SignalBricks.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

class FakeHttpError extends Exception {
    Map response
    FakeHttpError(int status) { super("HTTP ${status}"); response = [status: status] }
}

abstract class HubitatStub extends Script {
    Map state = [:]
    Map atomicState = [:]
    Map settings = [:]
    def locks = []
    def minCodePosition = 0
    def idAtEnd = false
    def codeNamePrefix = 'GT:'
    def params = [:]
    def request = [JSON: [:]]
    long clock = 1_800_000_000_000L
    int tokenSeq = 0
    List logged = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, trace: { logged << it.toString() }, error: { logged << it.toString() }]
    List paragraphs = []
    List hrefs = []
    Map inputs = [:]
    List http = []
    Map replies = [:]
    def app = [id: 42]
    def location = [name: 'Test Farm', hub: [name: 'Main Hub', localIP: '192.168.1.10'], timeZone: TimeZone.getTimeZone('UTC')]
    void definition(Map m) {}
    void mappings(Closure c) {}
    void preferences(Closure c) { pages = c }
    Closure pages
    void page(Map m, Closure c = null) { if (c) c() }
    def dynamicPage(Map m, Closure c) { c() }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) { hrefs << m }
    long now() { clock }
    String getHubUID() { '00000000-0000-4000-8000-0000000000aa' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${hubUID}/apps/42" }
    String getFullLocalApiServerUrl() { 'http://192.168.1.10/apps/api/42' }
    List scheduled = []
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite) scheduled.removeAll { it.handler == handler }
        scheduled << [secs: secs, handler: handler, data: opts?.data]
    }
    void runOnce(Date d, String handler, Map opts = [:]) {}
    void schedule(String cron, String handler) {}
    void unschedule() { scheduled.clear() }
    void unschedule(String handler) { scheduled.removeAll { it.handler == handler } }
    void subscribe(Object... args) {}
    List locationEvents = []
    void sendLocationEvent(Map m) { locationEvents << m }
    void unsubscribe() {}
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { tokenSeq++; state.accessToken = "fake-app-token-000${tokenSeq}"; return state.accessToken }
    def render(Map m) { m }
    void httpPost(Map p, Closure c) {
        http << p
        def reply = replies.find { k, v -> p.uri.endsWith(k) }?.value
        if (reply instanceof Integer && reply != 200) throw new FakeHttpError(reply)
        c([status: 200, data: reply instanceof Map ? reply : [:]])
    }
    void asynchttpPost(String callback, Map p, Map data = null) { http << p + [async: callback] }
    void httpPostJson(Map p, Closure c) { throw new IllegalStateException('no network in the harness') }
    def propertyMissing(String name) {
        if (name.endsWith('Handler')) return name
        throw new MissingPropertyException(name, getClass())
    }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
// Everything the page shows: paragraphs, input titles, and href titles, descriptions and URLs
def mainPage = { app.paragraphs.clear(); app.hrefs.clear(); app.inputs.clear(); app.pages()
    (app.paragraphs + app.inputs.values().collect { "${it.title}" } + app.hrefs.collect { "${it.title} ${it.description} ${it.url}" }).join('\n') }
def link = { app.hrefs.find { it.name == 'hrefSbReconnect' } }
// The page without link URLs: titles, descriptions, paragraphs
def pageText = { mainPage(); (app.paragraphs + app.inputs.values().collect { "${it.title}" } + app.hrefs.collect { "${it.title} ${it.description}" }).join('\n') }
def entryUrl = { "${app.fullApiServerUrl}/reconnect?access_token=${app.state.accessToken}".toString() }
def body = { Map p -> new JsonSlurper().parseText(p.body) }
def registers = { app.http.findAll { it.async == 'sbRegisterCallback' } }
def expiryJob = { app.scheduled.find { it.handler == 'sbConnectLinkExpire' } }
// The hub scheduler runs the job (and it is no longer pending)
def fireExpiry = { Map data -> app.scheduled.removeAll { it.handler == 'sbConnectLinkExpire' }; app.sbConnectLinkExpire(data) }
def CREDENTIAL = 'sbhi.00000000-0000-4000-8000-000000000001.fakecredentialfakecredentialfakecredential00'
def CREDENTIAL_2 = CREDENTIAL.replace('00', '22')
List tokens = []
def track = { tokens << app.state.accessToken; app.state.accessToken }

check('version is 1.4.6', app.sbAppVersion == '1.4.6')

// --- A connected hub with bookings, slot assignments and a pending code write ---
app.settings = [locks: ['1'] as String[], codeNamePrefix: 'GT:', minCodePosition: 50]
app.createAccessToken(); track()
app.state.sbCredential = CREDENTIAL
app.state.sbConnected = true
app.state.sbOrgName = 'Test Org'
app.state.sbHubName = 'Main'
app.state.sbRegisteredVersion = '1.4.6'
app.atomicState.bookings = ['101': [id: 101, guest: 'Fake Guest', code: '9876', lockId: '1', checkIn: '2030-01-01T16:00:00Z', checkOut: '2030-01-03T11:00:00Z']]
app.atomicState.codeAssignments = ['1': ['101': 51]]
app.atomicState.lastSync = [at: app.clock, source: 'PUT /sync']
app.runIn(60, 'trySetCode', [overwrite: false, data: [lockId: '1', codePosition: 51, codeName: 'GT:ORB101-Fake Guest']])
String keep = JsonOutput.toJson([app.atomicState.bookings, app.atomicState.codeAssignments, app.atomicState.lastSync, app.settings])
def survived = { String when ->
    check("${when}: bookings, slot assignments, last sync and settings unchanged", JsonOutput.toJson([app.atomicState.bookings, app.atomicState.codeAssignments, app.atomicState.lastSync, app.settings]) == keep)
    check("${when}: the pending code write is still scheduled", app.scheduled.count { it.handler == 'trySetCode' } == 1)
    check("${when}: still connected with the same credential", app.state.sbCredential != null && app.state.sbConnected == true)
}

// --- Before a click: one Reconnect link to the app's own /reconnect; viewing arms nothing ---
String page = mainPage()
check('page: Reconnect is one link to the app\'s own /reconnect, never to SignalBricks directly', link()?.title == 'Reconnect to SignalBricks' && link().style == 'external' &&
    link().url == entryUrl() && !page.contains('/oauth/hubitat-integration/authorize') && !app.inputs.btnSbReconnect)
check('page: says the link works for 10 minutes, an unused link can\'t be reused, and what to do after a Hubitat error',
    page.contains('works for 10 minutes') && page.contains("an unused link can't be reused") && page.contains('Door codes keep syncing') &&
    link().description.contains('If you see a Hubitat error after signing in, click Reconnect again.'))
check('page: the token is only in the Reconnect URL, never in text', !pageText().contains(app.state.accessToken))
5.times { mainPage() }
check('page: viewing the page opens no window, schedules nothing and changes no token', app.atomicState.sbConnectLink == null && !expiryJob() && registers().isEmpty() && app.state.accessToken == tokens[0])

// --- Click -> callback success ---
String t0 = app.state.accessToken
def entry = app.apiReconnect()
String fwd = entry.data
Map l = app.atomicState.sbConnectLink
check('click: /reconnect forwards to the SignalBricks sign-in with no script and no referrer', entry.status == 200 && entry.contentType == 'text/html' &&
    fwd.contains('<meta name="referrer" content="no-referrer">') && fwd.contains('<meta http-equiv="refresh" content="0;url=https://app.signalbricks.co/oauth/hubitat-integration/authorize?') &&
    fwd.contains('>Continue to SignalBricks</a>') && !(fwd =~ /<script|src=/))
check('click: the forward carries the current token in the sign-in state, HTML-escaped', fwd.contains(URLEncoder.encode("access_token=${t0}", 'UTF-8')) && !fwd.contains('&state=') && fwd.contains('&amp;state='))
check('click: link opened for 10 minutes', l?.issuedAt == app.clock && l?.expiresAt == app.clock + 600_000L)
check('click: expiry scheduled in 10 minutes with the link id', expiryJob()?.secs == 600 && expiryJob()?.data?.issuedAt == l.issuedAt)
check('click: token not changed by the click', app.state.accessToken == t0 && registers().isEmpty())
page = mainPage()
check('click: the app page shows until when the sign-in works', link()?.title == 'Reconnect to SignalBricks' && link().description.contains('works until 08:10:00'))
check('click: the token is only in the Reconnect URL', link().url == entryUrl() && !pageText().contains(t0))
app.replies = ['/oauth/hubitat-integration/token': [access_token: CREDENTIAL_2, organization_name: 'Test Org', hub_name: 'Main']]
app.params = [code: 'fake-auth-code-1', access_token: t0]
app.clock += 60_000
def result = app.oauthCallback()
String t1 = track()
check('callback: Hub authorized', result.data.contains('Hub authorized'))
check('callback: link closed and its expiry cancelled', app.atomicState.sbConnectLink == null && !expiryJob())
check('callback: token replaced and registered with the new credential', t1 != t0 && registers().size() == 1 &&
    body(registers()[0]).appAccessToken == t1 && registers()[0].headers.Authorization == "Bearer ${CREDENTIAL_2}".toString())
check('callback: page back to plain Reconnect with the new token\'s entry URL, no old token anywhere', mainPage().with { !it.contains(t0) } && !pageText().contains(t1) && link().url == entryUrl() && !link().description.contains('works until'))
app.sbRegisterCallback([status: 200, data: JsonOutput.toJson([organization_name: 'Test Org', hub_name: 'Main'])], null)
survived('callback success')
// Expiry racing the callback: the job fires anyway (queued just before the callback cancelled it)
app.http.clear()
fireExpiry([issuedAt: l.issuedAt])
check('race (callback first): the stale expiry does nothing', app.state.accessToken == t1 && registers().isEmpty() && app.state.sbCredential == CREDENTIAL_2)

// --- Click -> callback failure (sign-in cancelled): still rotated, registered with the credential held ---
app.apiReconnect()
app.http.clear()
app.params = [error: 'access_denied']
result = app.oauthCallback()
String t2 = track()
check('callback failure: Not connected page', result.data.contains('Not connected'))
check('callback failure: link closed, expiry cancelled', app.atomicState.sbConnectLink == null && !expiryJob())
check('callback failure: token replaced and registered with the credential held', t2 != t1 && registers().size() == 1 &&
    body(registers()[0]).appAccessToken == t2 && registers()[0].headers.Authorization == "Bearer ${CREDENTIAL_2}".toString())
app.sbRegisterCallback([status: 200, data: '{}'], null)
survived('callback failure')

// --- Click -> expiry: no callback within 10 minutes ---
app.apiReconnect()
l = app.atomicState.sbConnectLink
mainPage()
String verifier = app.state.sbPkceVerifier
check('expiry: the link had a verifier', verifier != null)
app.http.clear()
app.clock += 600_000
fireExpiry(expiryJob().data)
String t3 = track()
check('expiry: token replaced', t3 != t2)
check('expiry: new token registered with the credential already held', registers().size() == 1 && body(registers()[0]).appAccessToken == t3 &&
    registers()[0].headers.Authorization == "Bearer ${CREDENTIAL_2}".toString())
check('expiry: link closed, verifier dropped', app.atomicState.sbConnectLink == null && app.state.sbPkceVerifier == null)
check('expiry: page back to plain Reconnect, the old token nowhere', mainPage().with { !it.contains(t2) } && !pageText().contains(t3) && link().url == entryUrl() && !link().description.contains('works until'))
check('expiry: logged without a token', app.logged.any { it.contains('Reconnect link expired unused; access token replaced, registering it with SignalBricks') })
app.sbRegisterCallback([status: 200, data: '{}'], null)
survived('expiry')
// Expiry racing the callback: a callback after the expiry (Hubitat cloud would refuse the old
// token; if one got through, the verifier is gone): no exchange, token replaced again, registered
app.http.clear()
app.params = [code: 'fake-auth-code-late']
result = app.oauthCallback()
String t4 = track()
check('race (expiry first): a late callback finds the link expired, no exchange', result.data.contains('Connect link expired') && !app.http.any { it.uri.endsWith('/token') })
check('race (expiry first): still rotated and registered, never the old token', t4 != t3 && body(registers()[0]).appAccessToken == t4)
app.sbRegisterCallback([status: 200, data: '{}'], null)
survived('late callback')

// A newer link: an older link's expiry must not close it
app.apiReconnect()
Map newer = app.atomicState.sbConnectLink
app.http.clear()
fireExpiry([issuedAt: l.issuedAt])
check('older link id: the newer link stays open, token unchanged', app.atomicState.sbConnectLink == newer && app.state.accessToken == t4 && registers().isEmpty())
fireExpiry(null)
check('no link id: nothing happens', app.atomicState.sbConnectLink == newer && app.state.accessToken == t4)

// --- The expiry survives an app-wide unschedule() (booking sync, Reset State) ---
app.clock += 240_000
app.request = [JSON: [:]]
app.apiSync()
check('booking sync: expiry re-armed for the time left with the same link id', expiryJob()?.secs == 360 && expiryJob()?.data?.issuedAt == newer.issuedAt)
app.appButtonHandler('btnReset')
check('Reset State: expiry re-armed too', expiryJob()?.secs == 360)
app.atomicState.bookings = new JsonSlurper().parseText(keep)[0]
app.atomicState.codeAssignments = new JsonSlurper().parseText(keep)[1]
app.atomicState.lastSync = new JsonSlurper().parseText(keep)[2]
app.updated()
check('Done: expiry re-armed too', expiryJob()?.secs == 360)

// --- A lost timer: the page expires the link when it is rendered after the expiry ---
app.scheduled.removeAll { it.handler == 'sbConnectLinkExpire' }
app.clock += 400_000
app.http.clear()
page = mainPage()
String t5 = track()
check('lost timer: rendering the page expires the link and rotates the token', t5 != t4 && app.atomicState.sbConnectLink == null && registers().size() == 1 && body(registers()[0]).appAccessToken == t5)
check('lost timer: the old token nowhere on that page', !page.contains(t4) && !pageText().contains(t5) && !link().description.contains('works until'))

// --- Registration failure after an expiry: automatic retries, shown on the app page ---
app.apiReconnect()
app.http.clear()
app.clock += 600_000
fireExpiry(expiryJob().data)
String t6 = track()
app.sbRegisterCallback([status: 403, data: '{"error":"unverified"}'], null)
def retryJob = app.scheduled.find { it.handler == 'sbRegisterRetryRun' }
check('register failure: retry 1 scheduled after 30 s', retryJob?.secs == 30)
page = mainPage()
check('register failure: retry and the error shown on the app page', page.contains('<b>Registration retries:</b> retry 1 of 3') && page.contains('could not verify this hub'))
app.http.clear()
app.scheduled.removeAll { it.handler == 'sbRegisterRetryRun' }
app.sbRegisterRetryRun()
check('register failure: the retry sends the new token, never the old one', registers().size() == 1 && body(registers()[0]).appAccessToken == t6 && !tokens[0..-2].contains(body(registers()[0]).appAccessToken))
// The 3 quick retries fail too: no one is watching, so it keeps trying every 30 minutes
app.sbRegisterCallback([status: 503, data: null], null)
[120, 300].each { secs ->
    app.scheduled.removeAll { it.handler == 'sbRegisterRetryRun' }
    app.sbRegisterRetryRun()
    app.sbRegisterCallback([status: 503, data: null], null)
}
retryJob = app.scheduled.find { it.handler == 'sbRegisterRetryRun' }
check('unattended: after the quick retries, retry 4 in 30 minutes, shown on the page', retryJob?.secs == 1800 && mainPage().contains('retry 4 at') && mainPage().contains('every 30 minutes for 24 hours'))
app.clock += 600_000
app.request = [JSON: [:]]
app.apiSync()
check('unattended: a booking sync re-arms the 30-minute retry for the time left', app.scheduled.find { it.handler == 'sbRegisterRetryRun' }?.secs == 1200)
app.apiUnregister()
check('unattended: the legacy DELETE /register (app-wide unschedule) re-arms it too', app.scheduled.find { it.handler == 'sbRegisterRetryRun' }?.secs == 1200)
app.state.accessToken = t6
app.clock += 1_200_000
app.http.clear()
app.scheduled.removeAll { it.handler == 'sbRegisterRetryRun' }
app.sbRegisterRetryRun()
check('unattended: the 30-minute retry sends the new token', registers().size() == 1 && body(registers()[0]).appAccessToken == t6)
app.sbRegisterCallback([status: 200, data: '{}'], null)
check('register failure: success after a retry clears it', !app.atomicState.sbRegisterRetry && !mainPage().contains('Registration retries'))

// --- Disconnect with a link open: link closed (the token is replaced) ---
app.apiReconnect()
app.replies['/oauth/hubitat-integration/revoke'] = [:]
app.appButtonHandler('btnSbDisconnect')
track()
check('disconnect: link closed and no expiry left', app.atomicState.sbConnectLink == null && !expiryJob())
// Not connected: /reconnect opens nothing
def notConnected = app.apiReconnect()
check('not connected: /reconnect says so and opens no window', notConnected.data.contains('Not connected') && !notConnected.data.contains('http-equiv') && app.atomicState.sbConnectLink == null && !expiryJob())

// The legacy DELETE /register (app-wide unschedule) keeps an open window's expiry
app.state.sbCredential = CREDENTIAL_2
app.state.sbConnected = true
app.apiReconnect()
app.clock += 60_000
app.apiUnregister()
check('legacy unregister: the open window\'s expiry is re-armed for the time left', expiryJob()?.secs == 540)
app.state.accessToken = app.createAccessToken(); track()
app.sbConnectLinkClear()

// Refresh Access Token (Debug) closes an open link: it carried the old token
app.state.sbCredential = CREDENTIAL_2
app.state.sbConnected = true
app.apiReconnect()
app.appButtonHandler('btbAccessToken')
track()
check('refresh access token: link closed, no expiry left', app.atomicState.sbConnectLink == null && !expiryJob())

// --- Never a token in the logs ---
String logs = app.logged.join('\n')
check('logs: no access token, credential or code', !tokens.any { it && logs.contains(it) } && !logs.contains(CREDENTIAL) && !logs.contains(CREDENTIAL_2) && !logs.contains('fake-auth-code') && !logs.contains('9876'))
check('logs: the link was logged as opened, without a token', app.logged.any { it.contains('Reconnect link opened for 10 minutes') })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
