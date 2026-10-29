// Connect to SignalBricks (#34) for SignalBricks Integration with a stubbed Hubitat
// sandbox: the authorize URL (PKCE S256, stateredirect), the OAuth callback (token
// exchange, access token replaced, registration), reconnect, disconnect (revoke), a
// portal-side disconnect, re-registering after an update, the app page in each state
// (one Status line, no legacy registration block, header-style section titles on the
// main and debug pages) and while the hub saves the code, the callback page for each outcome
// (#40), and that no secret is ever logged.
// Fake values only; nothing is sent to a hub or to SignalBricks.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
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
    List sectionTitles = []
    List hrefs = []
    Map inputs = [:]
    List http = []
    // Responses for the next httpPost calls, by path suffix: status or a Map body (200)
    Map replies = [:]
    def app = [id: 42]
    def location = [name: 'Ross Farm House', hub: [name: 'Main Hub', localIP: '192.168.1.10'], timeZone: TimeZone.getTimeZone('UTC')]
    void definition(Map m) {}
    void mappings(Closure c) {}
    void preferences(Closure c) { pages = c }
    Closure pages
    void page(Map m, Closure c = null) { if (c) c() }
    def dynamicPage(Map m, Closure c) { c() }
    void section(Object... args) {
        def m = args.find { it instanceof Map }
        if (m?.title) sectionTitles << m.title
        args.find { it instanceof Closure }?.call()
    }
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
        scheduled << [secs: secs, handler: handler]
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
def mainPage = { app.paragraphs.clear(); app.sectionTitles.clear(); app.hrefs.clear(); app.inputs.clear(); app.pages(); app.paragraphs.join('\n') }
// Exactly one Status line, and none of the legacy sbId registration block (#36)
def oneStatus = { String p -> p.count('<b>Status:</b>') == 1 && !p.contains('SignalBricks:</b>') && !p.contains('add-hub') && !p.contains('Not registered') && !p.contains('App Version') && !app.inputs.btnDisconnect }
def qs = { String url -> new URI(url).rawQuery.split('&').collectEntries { it.split('=', 2).with { [(it[0]): URLDecoder.decode(it[1], 'UTF-8')] } } }
def b64url = { String v -> MessageDigest.getInstance('SHA-256').digest(v.getBytes('US-ASCII')).encodeBase64().toString().replace('+', '-').replace('/', '_').replace('=', '') }
def body = { Map p -> new JsonSlurper().parseText(p.body) }
def CREDENTIAL = 'sbhi.00000000-0000-4000-8000-000000000001.fakecredentialfakecredentialfakecredential00'
List secrets = []

check('version is 1.4.4', app.sbAppVersion == '1.4.4')

// Saving the app code runs the main page with no state and no settings (#28)
def savedState = app.state
app.state = null; app.atomicState = null; app.settings = null
String saveError = null
try { mainPage() } catch (e) { saveError = e.toString() }
check('save: main page renders without state or settings', saveError == null)
app.settings = [locks: ['1'] as String[]]
try { mainPage() } catch (e) { saveError = e.toString() }
check('save: main page renders with settings but no state', saveError == null)
app.state = savedState; app.atomicState = [:]

// --- Not connected: Connect link and the manual block ---
app.settings = [locks: ['1'] as String[], codeNamePrefix: 'GT:']
app.createAccessToken()
String firstToken = app.state.accessToken
secrets << firstToken
String page = mainPage()
def connect = app.hrefs.find { it.name == 'hrefSbConnect' }
check('page: Not connected status', page.contains('<b>Status:</b> Not connected') && oneStatus(page))
check('page: every main page section title is a header', app.sectionTitles == ['<h2>Configuration</h2>', '<h2>Door code refresh</h2>', '<h2>Connection</h2>', '<h2>Diagnostics</h2>'])
app.sectionTitles.clear(); app.hrefs.clear(); app.debug()
check('debug page: API links with the token while not connected', app.hrefs.any { it.url?.contains("access_token=${firstToken}") })
check('debug page: every section title is a header', app.sectionTitles == ['<h2>Actions</h2>', '<h2>Door code refresh</h2>', '<h2>Links</h2>'])
check('page: Connect to SignalBricks link', connect?.title == 'Connect to SignalBricks' && connect?.style == 'external')
check('page: manual URLs and token still shown while not connected', page.contains('<b>Cloud URL:</b>') && page.contains('<b>Local URL:</b> http://192.168.1.10/apps/api/42') && page.contains(firstToken))
check('page: no Disconnect while not connected', !app.inputs.btnSbDisconnect)

Map q = qs(connect.url)
check('authorize: portal integration endpoint', connect.url.startsWith('https://app.signalbricks.co/oauth/hubitat-integration/authorize?'))
check('authorize: client id, redirect uri, response type', q.client_id == 'signalbricks-hubitat-integration' && q.redirect_uri == 'https://cloud.hubitat.com/oauth/stateredirect' && q.response_type == 'code')
String verifier = app.state.sbPkceVerifier
secrets << verifier
check('authorize: PKCE S256 challenge of the stored verifier', q.code_challenge_method == 'S256' && q.code_challenge == b64url(verifier) && verifier ==~ /[0-9a-f]{96}/)
check('authorize: hub uid, app id, version, location', q.hub_uid == app.hubUID && q.app_id == '42' && q.version == '1.4.4' && q.location == 'Ross Farm House')
check('authorize: state is the stateredirect callback with the access token', q.state == "${app.hubUID}/apps/42/oauthCallback?access_token=${firstToken}".toString())
mainPage()
check('authorize: the verifier survives page re-renders', app.state.sbPkceVerifier == verifier)

// --- Callback: exchange, replace the token, register ---
app.replies = [
    '/oauth/hubitat-integration/token': [access_token: CREDENTIAL, token_type: 'Bearer', organization_name: 'Ross Farm House', hub_name: 'Main'],
    '/oauth/hubitat-integration/register': [status: 'connected', organization_name: 'Ross Farm House', hub_name: 'Main', slug: 'main'],
]
secrets << CREDENTIAL << 'fake-auth-code-1'
app.params = [code: 'fake-auth-code-1', access_token: firstToken]
def result = app.oauthCallback()
def tokenCall = app.http.find { it.uri.endsWith('/token') }
def registerCall = app.http.find { it.uri.endsWith('/register') }
check('callback: renders Hub authorized (registration runs in the background)', result.data.contains('Hub authorized') && !result.data.contains('Not connected'))
Map callbackPages = [authorized: result.data]
check('callback: token exchange with the code and the verifier over https', tokenCall.uri.startsWith('https://') && body(tokenCall) == [grant_type: 'authorization_code', code: 'fake-auth-code-1', redirect_uri: 'https://cloud.hubitat.com/oauth/stateredirect', client_id: 'signalbricks-hubitat-integration', code_verifier: verifier])
check('callback: the access token from the URL is replaced', app.state.accessToken != firstToken)
secrets << app.state.accessToken
Map reg = body(registerCall)
check('register: in the background (SignalBricks calls back /info), Bearer credential over https',
    registerCall.async == 'sbRegisterCallback' && registerCall.uri.startsWith('https://') && registerCall.headers.Authorization == "Bearer ${CREDENTIAL}".toString())
check('register: hub uid, app id, version, hub name, URLs and the new token', reg == [hubUid: app.hubUID, appId: '42', version: '1.4.4', hubName: 'Main Hub', localUrl: 'http://192.168.1.10/apps/api/42', cloudUrl: app.fullApiServerUrl, appAccessToken: app.state.accessToken])
check('callback: verifier is single use', app.state.sbPkceVerifier == null)
page = mainPage()
check('callback: authorized, not connected until the registration answers', !app.state.sbConnected && page.contains('<b>Status:</b> Authorized, not registered yet') && oneStatus(page))
app.sbRegisterCallback([status: 200, data: JsonOutput.toJson([status: 'connected', organization_name: 'Ross Farm House', hub_name: 'Main', slug: 'main'])], null)
check('register 200: state connected with names', app.state.sbConnected == true && app.state.sbOrgName == 'Ross Farm House' && app.state.sbHubName == 'Main' && app.state.sbRegisteredVersion == '1.4.4')

page = mainPage()
check('page: Connected to org and hub', page.contains('<b>Status:</b> Connected to Ross Farm House &rarr; Main') && oneStatus(page))
app.paragraphs.clear(); app.hrefs.clear(); app.debug()
String debugPage = (app.paragraphs + app.hrefs.collect { "${it.title} ${it.url}" }).join('\n')
check('debug page: no access token while connected', !debugPage.contains(app.state.accessToken) && !debugPage.contains('access_token=') && debugPage.contains('Hidden while connected'))
// Legacy /register (#36): logs no request body; Done and Reset State clear the sbId
app.logged.clear()
app.request = [JSON: [sbId: 'sb-legacy-fake', accessToken: 'fake-legacy-token']]
app.apiRegister()
check('legacy register: sbId stored, request body not logged', app.atomicState.sbId == 'sb-legacy-fake' && !app.logged.any { it.contains('sb-legacy-fake') || it.contains('fake-legacy-token') })
app.state.sbId = 'sb-legacy-fake'
check('page: a legacy sbId adds no second status or Disconnect', oneStatus(mainPage()))
app.updated()
check('legacy sbId: cleared by Done (updated)', app.state.sbId == null && app.atomicState.sbId == null)
app.state.sbId = 'sb-legacy-fake'; app.atomicState.sbId = 'sb-legacy-fake'
app.appButtonHandler('btnReset')
check('legacy sbId: cleared by Reset State', app.state.sbId == null && app.atomicState.sbId == null)
mainPage()
check('page: Reconnect button (no link until clicked, #46) and Disconnect when connected', app.inputs.btnSbReconnect && !app.hrefs.any { it.url?.contains('access_token') } && app.inputs.btnSbDisconnect)
check('page: no access token or credential shown when connected', !page.contains(app.state.accessToken) && !page.contains(CREDENTIAL))

// --- Reconnect: new credential, token rotated again, registered again ---
String beforeReconnect = app.state.accessToken
app.appButtonHandler('btnSbReconnect')
mainPage()
String reconnectUrl = app.hrefs.find { it.name == 'hrefSbReconnect' }.url
check('reconnect: a fresh verifier', app.state.sbPkceVerifier && app.state.sbPkceVerifier != verifier)
secrets << app.state.sbPkceVerifier
app.http.clear()
String CREDENTIAL_2 = CREDENTIAL.replace('00', '11')
secrets << CREDENTIAL_2
app.replies['/oauth/hubitat-integration/token'] = [access_token: CREDENTIAL_2, organization_name: 'Ross Farm House', hub_name: 'Main']
app.params = [code: 'fake-auth-code-2']
app.oauthCallback()
check('reconnect: token rotated and registered with the new credential',
    app.state.accessToken != beforeReconnect && body(app.http.find { it.uri.endsWith('/register') }).appAccessToken == app.state.accessToken &&
    app.http.find { it.uri.endsWith('/register') }.headers.Authorization == "Bearer ${CREDENTIAL_2}".toString())
secrets << app.state.accessToken

// --- A failed exchange keeps the old credential and registers the new token with it ---
app.http.clear()
app.replies['/oauth/hubitat-integration/token'] = 500
app.state.sbPkceVerifier = 'f' * 96
app.params = [code: 'fake-auth-code-3']
result = app.oauthCallback()
check('failed exchange: Connection failed page with the HTTP status', result.data.contains('Connection failed') && result.data.contains('HTTP 500'))
callbackPages.failed = result.data
check('failed exchange: the replaced token is registered with the credential held before',
    app.http.find { it.uri.endsWith('/register') }?.headers?.Authorization == "Bearer ${CREDENTIAL_2}".toString())
secrets << app.state.accessToken

// --- Bound to another org ---
app.sbForgetConnection()
app.http.clear()
app.replies['/oauth/hubitat-integration/token'] = 409
app.state.sbPkceVerifier = 'e' * 96
app.params = [code: 'fake-auth-code-4']
result = app.oauthCallback()
check('409: explains the other organization, nothing registered', result.data.contains('another organization') && !app.http.any { it.uri.endsWith('/register') })
secrets << app.state.accessToken

// --- Denied and expired ---
String beforeDeny = app.state.accessToken
app.http.clear()
app.params = [error: 'access_denied']
result = app.oauthCallback()
check('denied: Not connected, no HTTP call, token still replaced', result.data.contains('Sign-in was cancelled') && app.http.isEmpty() && app.state.accessToken != beforeDeny)
check('denied: the error param itself is not rendered', !result.data.contains('access_denied'))
callbackPages.denied = result.data
app.params = [:]
result = app.oauthCallback()
check('missing code: Not connected', result.data.contains('Not connected') && result.data.contains('did not authorize'))
callbackPages.missing = result.data
app.params = [code: 'fake-auth-code-5']
result = app.oauthCallback()
check('expired: no verifier, no HTTP call', result.data.contains('Connect link expired') && result.data.contains('no longer valid') && app.http.isEmpty())
callbackPages.expired = result.data
secrets << app.state.accessToken

// --- The callback page (#40): one self-contained page per outcome ---
Map headlines = [authorized: 'Hub authorized', failed: 'Connection failed', denied: 'Not connected', missing: 'Not connected', expired: 'Connect link expired']
headlines.each { k, h ->
    String html = callbackPages[k]
    String status = k == 'authorized' ? 'ok' : 'err'
    check("page ${k}: title and headline '${h}' with its marker", html.contains("<title>SignalBricks Integration: ${h}</title>") && html.contains("<main class=\"${status}\">") && html =~ /<h1><span class="mark"[^>]*>[^<]+<\/span>${h}<\/h1>/)
    check("page ${k}: inline CSS only, light and dark", html.contains('<style>') && html.contains('prefers-color-scheme:dark') && !(html =~ /<script|<link|<img|@import|url\(|src=/))
    check("page ${k}: back link to the app on the hub, with guidance", html.contains('<a class="btn" href="http://192.168.1.10/installedapp/configure/42">Back to the app</a>') && html.contains('Apps</b> &rarr; <b>SignalBricks Integration'))
    check("page ${k}: no-referrer, so the back link doesn't send the callback URL", html.contains('<meta name="referrer" content="no-referrer">'))
    check("page ${k}: no token, code or verifier", !secrets.any { it && html.contains(it) } && !html.contains('access_token') && !html.contains('fake-auth-code') && !html.contains('fake-app-token'))
}
check('page authorized: says Connected on reopen', callbackPages.authorized.contains('<b>Connected</b>') && callbackPages.authorized.contains('finishing registration'))
['failed', 'denied', 'missing', 'expired'].each { check("page ${it}: says what to do next", callbackPages[it].contains('connect again')) }

// Dynamic text is escaped, request params are never rendered
app.metaClass.sbExchangeCode = { String c, String v -> delegate.state.sbLastError = '<script>alert(1)</script> & "x"' }
app.state.sbPkceVerifier = 'c' * 96
app.params = [code: '<script>fake-code</script>', access_token: '<script>fake-token</script>', error: null]
result = app.oauthCallback()
check('escape: the error text is escaped', result.data.contains('&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;') && !result.data.contains('<script'))
check('escape: no request param on the page', !result.data.contains('fake-code') && !result.data.contains('fake-token'))
app.params = [error: '<script>alert(2)</script>']
result = app.oauthCallback()
check('escape: the error param is not rendered', !result.data.contains('alert(2)') && !result.data.contains('<script'))
app.metaClass = null
app.state.remove('sbLastError')

// No reliable local address: guidance instead of the link
String goodIp = app.location.hub.localIP
app.location.hub.localIP = '"><script>x</script>'
app.params = [:]
result = app.oauthCallback()
check('no local IP: no link, guidance on where to find the app', !result.data.contains('Back to the app</a>') && result.data.contains('Apps</b> &rarr; <b>SignalBricks Integration') && !result.data.contains('<script'))
app.location.hub.remove('localIP')
check('no local IP at all: still renders', !app.oauthCallback().data.contains('installedapp'))
app.location.hub.localIP = goodIp
secrets << app.state.accessToken

// --- Disconnect: revoke, forget, replace the token ---
app.replies['/oauth/hubitat-integration/token'] = [access_token: CREDENTIAL, organization_name: 'Ross Farm House', hub_name: 'Main']
app.state.sbPkceVerifier = 'd' * 96
app.params = [code: 'fake-auth-code-6']
app.oauthCallback()
secrets << app.state.accessToken
String beforeDisconnect = app.state.accessToken
app.http.clear()
app.appButtonHandler('btnSbDisconnect')
def revokeCall = app.http.find { it.uri.endsWith('/oauth/hubitat-integration/revoke') }
check('disconnect: revoke with the Bearer credential', revokeCall?.headers?.Authorization == "Bearer ${CREDENTIAL}".toString())
check('disconnect: state forgotten and token replaced', !app.state.sbCredential && !app.state.sbConnected && app.state.accessToken != beforeDisconnect)
check('disconnect: page shows Not connected again', mainPage().contains('<b>Status:</b> Not connected'))
app.replies['/oauth/hubitat-integration/revoke'] = 503
app.state.sbCredential = CREDENTIAL
app.appButtonHandler('btnSbDisconnect')
check('disconnect: a failed revoke still disconnects here', !app.state.sbCredential && app.logged.any { it.contains('revoke failed (HTTP 503)') })
check('disconnect: a failed revoke tells the user to disconnect on the SignalBricks hub page too', mainPage().contains("also click Disconnect on this hub's SignalBricks hub page"))
secrets << app.state.accessToken

// --- Portal-side disconnect: the next registration gets 401 ---
app.state.sbCredential = CREDENTIAL
app.state.sbConnected = true
app.sbRegistered(401, [:])
page = mainPage()
int statusAt = app.paragraphs.findIndexOf { it.startsWith('<b>Status:</b>') }
check('401: connection forgotten, page explains under the one Status line', !app.state.sbCredential && oneStatus(page) &&
    app.paragraphs[statusAt + 1].startsWith('<b>Last connect error:</b> SignalBricks disconnected this hub'))
app.state.sbCredential = CREDENTIAL
app.sbRegisterCallback([status: 403, data: '{"error":"unverified"}'], null)
check('403: could not verify through Hubitat cloud, credential kept for a retry', app.state.sbCredential && mainPage().contains('could not verify this hub'))
app.sbRegisterCallback([status: 409, data: '{"error":"hub_bound_elsewhere"}'], null)
check('409 on register: another organization', mainPage().contains('another organization'))

// --- Automatic registration retries: 30 s, 2 min, 5 min; stop on 200 / 401 / 409 ---
def retryJob = { app.scheduled.find { it.handler == 'sbRegisterRetryRun' } }
// The hub scheduler runs the job (and it is no longer pending)
def fireRetry = { app.scheduled.removeAll { it.handler == 'sbRegisterRetryRun' }; app.sbRegisterRetryRun() }
app.state.sbCredential = CREDENTIAL
app.sbRegister()
check('retry: a fresh registration clears any retry state', !app.state.sbRegisterRetry && !retryJob())
app.sbRegisterCallback([status: 403, data: '{"error":"unverified"}'], null)
check('retry: 403 schedules retry 1 after 30 s', retryJob()?.secs == 30 && mainPage().contains('retry 1 of 3'))
app.http.clear()
fireRetry()
check('retry: the retry registers again in the background', app.http.count { it.async == 'sbRegisterCallback' } == 1)
// A booking sync during the retry window runs scheduleEvents (app-wide unschedule()): the retry survives
app.sbRegisterCallback([status: 403, data: '{"error":"unverified"}'], null)
check('retry: a second failure schedules retry 2 after 2 min', retryJob()?.secs == 120)
app.clock += 40_000
app.request = [JSON: [:]]
app.apiSync()
check('retry: a PUT /sync during the retry window re-arms the retry for the time left', retryJob()?.secs == 80 && app.atomicState.sbRegisterRetry?.attempt == 2)
app.appButtonHandler('btnReset')
check('retry: Reset State keeps the pending retry too', retryJob() != null)
// A retry in flight: a sync before its answer must not schedule a duplicate registration
fireRetry()
app.http.clear()
app.apiSync()
check('retry: in flight, a PUT /sync does not re-arm a duplicate', !retryJob() && app.atomicState.sbRegisterRetry?.inFlight && mainPage().contains('retry 2 of 3 in progress'))
app.sbRegisterCallback([status: 403, data: '{"error":"unverified"}'], null)
check('retry: the in-flight answer schedules the next retry', retryJob()?.secs == 300 && !app.atomicState.sbRegisterRetry?.inFlight)
// Any 4xx other than 403 and 429 (and 408, a timeout) won't change by retrying
for (int st : [400, 404, 422]) {
    app.sbRegister()
    app.sbRegisterCallback([status: st, data: '{}'], null)
    check("retry: HTTP ${st} stops without retrying".toString(), !retryJob() && !app.atomicState.sbRegisterRetry)
}
app.sbRegister()
app.sbRegisterCallback([status: 429, data: '{"error":"slow_down"}'], null)
check('retry: 429 is retried', retryJob()?.secs == 30)
app.sbRegister()
app.clock -= 40_000
app.scheduled.removeAll { it.handler == 'sbRegisterRetryRun' }
app.atomicState.sbRegisterRetry = [attempt: 1, at: app.clock + 30_000L]
app.sbRegisterCallback([status: 502, data: null], null)
check('retry: 5xx schedules retry 2 after 2 min', retryJob()?.secs == 120 && mainPage().contains('retry 2 of 3'))
fireRetry()
app.sbRegisterCallback([status: 408, data: null], null)
check('retry: a timeout schedules retry 3 after 5 min', retryJob()?.secs == 300 && mainPage().contains('retry 3 of 3'))
fireRetry()
app.sbRegisterCallback([status: 503, data: null], null)
check('retry: after 3 retries it stops and says so', !retryJob() && mainPage().contains('all 3 automatic retries failed'))
app.http.clear()
fireRetry()
check('retry: no registration once retries are exhausted', app.http.isEmpty())
app.sbRegister()
app.sbRegisterCallback([status: 403, data: '{"error":"unverified"}'], null)
fireRetry()
app.sbRegisterCallback([status: 200, data: JsonOutput.toJson([organization_name: 'Ross Farm House', hub_name: 'Main'])], null)
check('retry: 200 stops the retries and connects', !retryJob() && !app.state.sbRegisterRetry && app.state.sbConnected && !mainPage().contains('Registration retries'))
app.sbRegisterCallback([status: 500, data: null], null)
app.sbRegisterCallback([status: 409, data: '{"error":"hub_bound_elsewhere"}'], null)
check('retry: 409 stops the retries', !retryJob() && !app.state.sbRegisterRetry)
app.sbRegisterCallback([status: 500, data: null], null)
app.sbRegisterCallback([status: 401, data: '{}'], null)
check('retry: 401 stops the retries and forgets the connection', !retryJob() && !app.state.sbRegisterRetry && !app.state.sbCredential)
app.state.sbCredential = CREDENTIAL

// --- Refresh Access Token (Debug) re-registers the new token ---
app.http.clear()
String beforeRefresh = app.state.accessToken
app.appButtonHandler('btbAccessToken')
def refreshed = app.http.find { it.async == 'sbRegisterCallback' }
check('refresh access token: new token registered', app.state.accessToken != beforeRefresh && refreshed && body(refreshed).appAccessToken == app.state.accessToken)
secrets << app.state.accessToken

// --- Re-register: on Done, and once after an update ---
app.state.sbCredential = CREDENTIAL
app.state.sbRegisteredVersion = '1.4.4'
app.http.clear()
app.updated()
check('updated: re-registers in the background', app.http.any { it.async == 'sbRegisterCallback' && it.uri.endsWith('/register') })
app.http.clear()
app.state.sbRegisteredVersion = '1.3.7'
app.clock += 31L * 60 * 1000
app.request = [JSON: [:]]
app.apiSync()
app.apiSync()
check('apiSync: re-registers once after a version change', app.http.count { it.async == 'sbRegisterCallback' } == 1)
app.sbRegisterCallback([status: 200, data: JsonOutput.toJson([organization_name: 'Ross Farm House', hub_name: 'Main'])], null)
check('register callback: version recorded', app.state.sbRegisteredVersion == '1.4.4' && app.state.sbConnected == true)

// --- Existing endpoints are still mapped ---
String src = new File(args[0]).text
check('mappings: /oauthCallback added, /register and /sync kept', src.contains("path('/oauthCallback')") && src.contains("path('/register')") && src.contains("path('/sync')"))

// --- Nothing secret in any log line ---
String logs = app.logged.join('\n')
List leaked = secrets.findAll { it && logs.contains(it) }
check('logs: no access token, verifier, code or credential', leaked.isEmpty())

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
