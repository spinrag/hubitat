// One-click safe Reconnect (#46) for SignalBricks Integration with a stubbed Hubitat
// sandbox: Reconnect is a button and the sign-in link (which carries the access token
// SignalBricks holds) exists only for 10 minutes after a click. Checks click -> callback
// (success and failure), click -> expiry (token replaced and registered with the
// credential already held), expiry racing the callback, the expiry surviving an
// app-wide unschedule(), a lost timer, registration failure -> retries on the app page,
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
def body = { Map p -> new JsonSlurper().parseText(p.body) }
def registers = { app.http.findAll { it.async == 'sbRegisterCallback' } }
def expiryJob = { app.scheduled.find { it.handler == 'sbConnectLinkExpire' } }
// The hub scheduler runs the job (and it is no longer pending)
def fireExpiry = { Map data -> app.scheduled.removeAll { it.handler == 'sbConnectLinkExpire' }; app.sbConnectLinkExpire(data) }
def CREDENTIAL = 'sbhi.00000000-0000-4000-8000-000000000001.fakecredentialfakecredentialfakecredential00'
def CREDENTIAL_2 = CREDENTIAL.replace('00', '22')
List tokens = []
def track = { tokens << app.state.accessToken; app.state.accessToken }

check('version is 1.4.4', app.sbAppVersion == '1.4.4')

// --- A connected hub with bookings, slot assignments and a pending code write ---
app.settings = [locks: ['1'] as String[], codeNamePrefix: 'GT:', minCodePosition: 50]
app.createAccessToken(); track()
app.state.sbCredential = CREDENTIAL
app.state.sbConnected = true
app.state.sbOrgName = 'Test Org'
app.state.sbHubName = 'Main'
app.state.sbRegisteredVersion = '1.4.4'
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

// --- Before a click: a button, no link, no token on the page ---
String page = mainPage()
check('page: Reconnect is a button, not a link', app.inputs.btnSbReconnect?.type == 'button' && !link())
check('page: says the link works for 10 minutes and door codes keep syncing', page.contains('works for 10 minutes') && page.contains('Door codes keep syncing'))
check('page: no access token anywhere before the click', !page.contains(app.state.accessToken) && !page.contains('access_token'))

// --- Click -> callback success ---
String t0 = app.state.accessToken
app.appButtonHandler('btnSbReconnect')
Map l = app.atomicState.sbConnectLink
check('click: link opened for 10 minutes', l?.issuedAt == app.clock && l?.expiresAt == app.clock + 600_000L)
check('click: expiry scheduled in 10 minutes with the link id', expiryJob()?.secs == 600 && expiryJob()?.data?.issuedAt == l.issuedAt)
check('click: token not changed by the click', app.state.accessToken == t0 && registers().isEmpty())
page = mainPage()
check('click: page shows Continue to SignalBricks with the expiry time', link()?.title == 'Continue to SignalBricks' && link().style == 'external' && link().description.contains('until 08:10:00') && !app.inputs.btnSbReconnect)
check('click: the token is only in the link URL', link().url.contains(URLEncoder.encode("access_token=${t0}", 'UTF-8')) &&
    !(app.paragraphs + app.inputs.values().collect { "${it.title}" } + app.hrefs.collect { "${it.title} ${it.description}" }).join('\n').contains(t0))
app.replies = ['/oauth/hubitat-integration/token': [access_token: CREDENTIAL_2, organization_name: 'Test Org', hub_name: 'Main']]
app.params = [code: 'fake-auth-code-1', access_token: t0]
app.clock += 60_000
def result = app.oauthCallback()
String t1 = track()
check('callback: Hub authorized', result.data.contains('Hub authorized'))
check('callback: link closed and its expiry cancelled', app.atomicState.sbConnectLink == null && !expiryJob())
check('callback: token replaced and registered with the new credential', t1 != t0 && registers().size() == 1 &&
    body(registers()[0]).appAccessToken == t1 && registers()[0].headers.Authorization == "Bearer ${CREDENTIAL_2}".toString())
check('callback: page shows the Reconnect button again, no token', mainPage().with { !it.contains(t0) && !it.contains(t1) && !it.contains('access_token') } && app.inputs.btnSbReconnect && !link())
app.sbRegisterCallback([status: 200, data: JsonOutput.toJson([organization_name: 'Test Org', hub_name: 'Main'])], null)
survived('callback success')
// Expiry racing the callback: the job fires anyway (queued just before the callback cancelled it)
app.http.clear()
fireExpiry([issuedAt: l.issuedAt])
check('race (callback first): the stale expiry does nothing', app.state.accessToken == t1 && registers().isEmpty() && app.state.sbCredential == CREDENTIAL_2)

// --- Click -> callback failure (sign-in cancelled): still rotated, registered with the credential held ---
app.appButtonHandler('btnSbReconnect')
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
app.appButtonHandler('btnSbReconnect')
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
check('expiry: page shows the button again, no token', mainPage().with { !it.contains(t2) && !it.contains(t3) && !it.contains('access_token') } && app.inputs.btnSbReconnect && !link())
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
app.appButtonHandler('btnSbReconnect')
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
check('lost timer: no link, no token on that page', !link() && !page.contains(t4) && !page.contains(t5))

// --- Registration failure after an expiry: automatic retries, shown on the app page ---
app.appButtonHandler('btnSbReconnect')
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
app.sbRegisterCallback([status: 200, data: '{}'], null)
check('register failure: success after a retry clears it', !app.atomicState.sbRegisterRetry && !mainPage().contains('Registration retries'))

// --- Disconnect with a link open: link closed (the token is replaced) ---
app.appButtonHandler('btnSbReconnect')
app.replies['/oauth/hubitat-integration/revoke'] = [:]
app.appButtonHandler('btnSbDisconnect')
track()
check('disconnect: link closed and no expiry left', app.atomicState.sbConnectLink == null && !expiryJob())
// Not connected: the button does nothing
app.appButtonHandler('btnSbReconnect')
check('not connected: no Reconnect link', app.atomicState.sbConnectLink == null && !expiryJob())

// Refresh Access Token (Debug) closes an open link: it carried the old token
app.state.sbCredential = CREDENTIAL_2
app.state.sbConnected = true
app.appButtonHandler('btnSbReconnect')
app.appButtonHandler('btbAccessToken')
track()
check('refresh access token: link closed, no expiry left', app.atomicState.sbConnectLink == null && !expiryJob())

// --- Never a token in the logs ---
String logs = app.logged.join('\n')
check('logs: no access token, credential or code', !tokens.any { it && logs.contains(it) } && !logs.contains(CREDENTIAL) && !logs.contains(CREDENTIAL_2) && !logs.contains('fake-auth-code') && !logs.contains('9876'))
check('logs: the link was logged as opened, without a token', app.logged.any { it.contains('Reconnect link opened for 10 minutes') })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
