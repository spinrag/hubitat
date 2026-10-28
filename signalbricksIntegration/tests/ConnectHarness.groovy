// Connect to SignalBricks (#34) for SignalBricks Integration with a stubbed Hubitat
// sandbox: the authorize URL (PKCE S256, stateredirect), the OAuth callback (token
// exchange, access token replaced, registration), reconnect, disconnect (revoke), a
// portal-side disconnect, re-registering after an update, the app page in each state
// and while the hub saves the code, and that no secret is ever logged.
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
    List hrefs = []
    Map inputs = [:]
    List http = []
    // Responses for the next httpPost calls, by path suffix: status or a Map body (200)
    Map replies = [:]
    def app = [id: 42]
    def location = [name: 'Ross Farm House', hub: [name: 'Main Hub'], timeZone: TimeZone.getTimeZone('UTC')]
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
    String getFullLocalApiServerUrl() { 'http://192.0.2.10/apps/api/42' }
    void runIn(Number secs, String handler, Map opts = [:]) {}
    void runOnce(Date d, String handler, Map opts = [:]) {}
    void schedule(String cron, String handler) {}
    void unschedule() {}
    void unschedule(String handler) {}
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
def mainPage = { app.paragraphs.clear(); app.hrefs.clear(); app.inputs.clear(); app.pages(); app.paragraphs.join('\n') }
def qs = { String url -> new URI(url).rawQuery.split('&').collectEntries { it.split('=', 2).with { [(it[0]): URLDecoder.decode(it[1], 'UTF-8')] } } }
def b64url = { String v -> MessageDigest.getInstance('SHA-256').digest(v.getBytes('US-ASCII')).encodeBase64().toString().replace('+', '-').replace('/', '_').replace('=', '') }
def body = { Map p -> new JsonSlurper().parseText(p.body) }
def CREDENTIAL = 'sbhi.00000000-0000-4000-8000-000000000001.fakecredentialfakecredentialfakecredential00'
List secrets = []

check('version is 1.4.0', app.sbAppVersion == '1.4.0')

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
check('page: Not connected status', page.contains('<b>SignalBricks:</b> Not connected'))
check('page: Connect to SignalBricks link', connect?.title == 'Connect to SignalBricks' && connect?.style == 'external')
check('page: manual URLs and token still shown while not connected', page.contains('<b>Cloud URL:</b>') && page.contains('<b>Local URL:</b> http://192.0.2.10/apps/api/42') && page.contains(firstToken))
check('page: no Disconnect while not connected', !app.inputs.btnSbDisconnect)

Map q = qs(connect.url)
check('authorize: portal integration endpoint', connect.url.startsWith('https://app.signalbricks.co/oauth/hubitat-integration/authorize?'))
check('authorize: client id, redirect uri, response type', q.client_id == 'signalbricks-hubitat-integration' && q.redirect_uri == 'https://cloud.hubitat.com/oauth/stateredirect' && q.response_type == 'code')
String verifier = app.state.sbPkceVerifier
secrets << verifier
check('authorize: PKCE S256 challenge of the stored verifier', q.code_challenge_method == 'S256' && q.code_challenge == b64url(verifier) && verifier ==~ /[0-9a-f]{96}/)
check('authorize: hub uid, app id, version, location', q.hub_uid == app.hubUID && q.app_id == '42' && q.version == '1.4.0' && q.location == 'Ross Farm House')
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
check('callback: renders Connected', result.data.contains('Connected') && !result.data.contains('Not connected'))
check('callback: token exchange with the code and the verifier over https', tokenCall.uri.startsWith('https://') && body(tokenCall) == [grant_type: 'authorization_code', code: 'fake-auth-code-1', redirect_uri: 'https://cloud.hubitat.com/oauth/stateredirect', client_id: 'signalbricks-hubitat-integration', code_verifier: verifier])
check('callback: the access token from the URL is replaced', app.state.accessToken != firstToken)
secrets << app.state.accessToken
Map reg = body(registerCall)
check('register: Bearer credential over https', registerCall.uri.startsWith('https://') && registerCall.headers.Authorization == "Bearer ${CREDENTIAL}".toString())
check('register: hub uid, app id, version, hub name, URLs and the new token', reg == [hubUid: app.hubUID, appId: '42', version: '1.4.0', hubName: 'Main Hub', localUrl: 'http://192.0.2.10/apps/api/42', cloudUrl: app.fullApiServerUrl, appAccessToken: app.state.accessToken])
check('callback: verifier is single use', app.state.sbPkceVerifier == null)
check('callback: state connected with names', app.state.sbConnected == true && app.state.sbOrgName == 'Ross Farm House' && app.state.sbHubName == 'Main' && app.state.sbRegisteredVersion == '1.4.0')

page = mainPage()
check('page: Connected to org and hub', page.contains('<b>SignalBricks:</b> Connected to Ross Farm House &rarr; Main'))
check('page: Reconnect and Disconnect when connected', app.hrefs.any { it.name == 'hrefSbReconnect' } && app.inputs.btnSbDisconnect)
check('page: no access token or credential shown when connected', !page.contains(app.state.accessToken) && !page.contains(CREDENTIAL))

// --- Reconnect: new credential, token rotated again, registered again ---
String beforeReconnect = app.state.accessToken
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
check('failed exchange: Not connected page with the HTTP status', result.data.contains('Not connected') && result.data.contains('HTTP 500'))
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
check('denied: Not connected, no HTTP call, token still replaced', result.data.contains('access_denied') && app.http.isEmpty() && app.state.accessToken != beforeDeny)
app.params = [code: 'fake-auth-code-5']
result = app.oauthCallback()
check('expired: no verifier, no HTTP call', result.data.contains('no longer valid') && app.http.isEmpty())
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
check('disconnect: page shows Not connected again', mainPage().contains('<b>SignalBricks:</b> Not connected'))
app.replies['/oauth/hubitat-integration/revoke'] = 503
app.state.sbCredential = CREDENTIAL
app.appButtonHandler('btnSbDisconnect')
check('disconnect: a failed revoke still disconnects here', !app.state.sbCredential && app.logged.any { it.contains('revoke failed (HTTP 503)') })
secrets << app.state.accessToken

// --- Portal-side disconnect: the next registration gets 401 ---
app.state.sbCredential = CREDENTIAL
app.state.sbConnected = true
app.sbRegistered(401, [:])
check('401: connection forgotten, page explains', !app.state.sbCredential && mainPage().contains('SignalBricks disconnected this hub'))

// --- Re-register: on Done, and once after an update ---
app.state.sbCredential = CREDENTIAL
app.state.sbRegisteredVersion = '1.4.0'
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
check('register callback: version recorded', app.state.sbRegisteredVersion == '1.4.0' && app.state.sbConnected == true)

// --- Existing endpoints are still mapped ---
String src = new File(args[0]).text
check('mappings: /oauthCallback added, /register and /sync kept', src.contains("path('/oauthCallback')") && src.contains("path('/register')") && src.contains("path('/sync')"))

// --- Nothing secret in any log line ---
String logs = app.logged.join('\n')
List leaked = secrets.findAll { it && logs.contains(it) }
check('logs: no access token, verifier, code or credential', leaked.isEmpty())

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
