// Connect confirmation page (#44), with a stubbed Hubitat sandbox: the page
// oauthCallback returns for each outcome (connected, failed exchange, denied,
// missing code, expired link), styled like SignalBricks Integration's (#40):
// headline and marker, inline CSS only, no-referrer, no back link (the page
// opens in its own window, #46), escaped text, and no token, code, verifier or request param on
// the page. Also checks the token is still rotated on every callback, and that
// the app page's Last error is escaped.
// Fake values only; nothing is sent to a hub or to SignalBricks.
// Optional second argument: a directory to write each outcome page to as
// <outcome>.html, to open in a browser (see the README's Development section).
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    Map getAtomicState() { state }
    Map settings = [:]
    Map params = [:]
    def locks = null, contactSensors = null, temperatureSensors = null, humiditySensors = null, codeLocks = null
    Boolean lockManagementEnabled = false
    Boolean includeDoorCodes = false
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    def fullControlLocks = null
    List logged = []
    List posts = []
    List http = []
    // Reply to the next token exchange: an HTTP status, or a Map body (200)
    def tokenReply = null
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, error: { logged << it.toString() }]
    def app = new FakeApp()
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177', localIP: '192.168.1.10']]
    void definition(Map m) {}
    Closure prefs
    void preferences(Closure c) { prefs = c }
    void page(Map m, Closure c = null) { if (c) c() }
    void mappings(Closure c) {}
    List paragraphs = []
    def dynamicPage(Map m, Closure c) { c(); m }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) {}
    void href(Map m) {}
    Map render(Map m) { m }
    long now() { System.currentTimeMillis() }
    void runIn(Number secs, String handler, Map opts = [:]) {}
    void schedule(String cron, String handler) {}
    void unschedule(String handler = null) {}
    void subscribe(Object... args) {}
    void unsubscribe() {}
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { state.accessToken = 'fake-app-token-' + UUID.randomUUID(); state.accessToken }
    void asynchttpPost(String cb, Map p, Map d) { posts << [cb: cb, params: p, data: d] }
    void httpPost(Map p, Closure c) {
        http << p
        def r = tokenReply
        if (r instanceof Exception) throw r
        c([status: r instanceof Map ? 200 : r, data: r instanceof Map ? r : [:]])
    }
}

class FakeApp {
    def id = 42
    void removeSetting(String n) {}
    void updateSetting(String n, Map v) {}
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
// Fake secrets: anything here must never appear on a page
List secrets = []
String INGEST_TOKEN = 'sbit_fakeingesttokenfakeingesttoken00'
secrets << INGEST_TOKEN

check('version is 1.6.2', app.appNameAndVersion() == 'SignalBricks Telemetry 1.6.2')

def callback = { Map p ->
    String before = app.state.accessToken
    if (before) secrets << before
    app.params = p
    def r = app.oauthCallback()
    check("callback ${p.keySet()}: access token replaced", app.state.accessToken && app.state.accessToken != before)
    secrets << app.state.accessToken
    r
}
def withVerifier = { String c -> app.state.pkceVerifier = c * 96; secrets << app.state.pkceVerifier }
Map pages = [:]

// --- Connected ---
app.createAccessToken()
withVerifier('a')
app.tokenReply = [access_token: INGEST_TOKEN, ingest_url: 'https://ingest.example.invalid/hubitat']
def result = callback([code: 'fake-auth-code-1', access_token: app.state.accessToken])
pages.connected = result.data
check('connected: token exchange with the code and the verifier', app.http.size() == 1 && new JsonSlurper().parseText(app.http[0].body).code == 'fake-auth-code-1')
check('connected: state connected, verifier single use', app.state.sbToken == INGEST_TOKEN && app.state.pkceVerifier == null && !app.state.lastError)
check('connected: html 200', result.contentType == 'text/html' && result.status == 200)

// --- Connection failed: the exchange returns an error ---
app.http.clear()
withVerifier('b')
app.tokenReply = 500
result = callback([code: 'fake-auth-code-2'])
pages.failed = result.data
check('failed: Connection failed with the HTTP status', result.data.contains('Connection failed') && result.data.contains('HTTP 500'))
check('failed: reason kept in lastError', app.state.lastError == 'Token exchange returned HTTP 500')

// --- Not connected: denied, or no code ---
app.http.clear()
result = callback([error: 'access_denied'])
pages.denied = result.data
check('denied: fixed text, no HTTP call', result.data.contains('Sign-in was cancelled, or SignalBricks denied access.') && app.http.isEmpty())
check('denied: the error param itself is not rendered', !result.data.contains('access_denied'))
check('denied: the raw error is kept in lastError', app.state.lastError == 'SignalBricks did not authorize this hub (access_denied)')
result = callback([error: 'server_error'])
check('other error: generic text, param not rendered', result.data.contains('SignalBricks did not authorize this hub.') && !result.data.contains('server_error') && app.state.lastError.contains('server_error'))
result = callback([:])
pages.missing = result.data
check('missing code: Not connected', result.data.contains('Not connected') && result.data.contains('did not authorize') && app.state.lastError.contains('no authorization code'))

// --- Connect link expired: no verifier ---
result = callback([code: 'fake-auth-code-5'])
pages.expired = result.data
check('expired: no verifier, no HTTP call', result.data.contains('Connect link expired') && result.data.contains('no longer valid') && app.http.isEmpty())

// --- The page for each outcome ---
Map headlines = [connected: 'Connected', failed: 'Connection failed', denied: 'Not connected', missing: 'Not connected', expired: 'Connect link expired']
headlines.each { k, h ->
    String html = pages[k]
    String status = k == 'connected' ? 'ok' : 'err'
    check("page ${k}: title and headline '${h}' with its marker", html.contains("<title>SignalBricks Telemetry: ${h}</title>") && html.contains("<main class=\"${status}\">") && html =~ /<h1><span class="mark"[^>]*>[^<]+<\/span>${h}<\/h1>/)
    check("page ${k}: app name and version", html.contains('<p class="app">SignalBricks Telemetry 1.6.2</p>'))
    check("page ${k}: inline CSS only, light and dark, wraps long text", html.contains('<style>') && html.contains('prefers-color-scheme:dark') && html.contains('overflow-wrap:anywhere') && !(html =~ /<script|<link|<img|@import|url\(|src=/))
    check("page ${k}: no back link (it opened in its own window); says to close it and return to the app", !html.contains('<a ') && !html.contains('installedapp') && !html.contains('Back to the app') &&
        html.contains("If this window doesn't close, close it yourself and return to the app; it's still open in your other tab."))
    check("page ${k}: a Close this window button, whose static window.close() is the only script", html.contains('<button type="button" class="btn" onclick="window.close()">Close this window</button>') &&
        (html =~ /(?i)\son[a-z]+\s*=/).collect { it }.size() == 1 && !html.toLowerCase().contains('javascript:') && !html.toLowerCase().contains('<script'))
    check("page ${k}: no-referrer", html.contains('<meta name="referrer" content="no-referrer">'))
    check("page ${k}: no token, code or verifier", !secrets.any { it && html.contains(it) } && !html.contains('access_token') && !html.contains('fake-auth-code') && !html.contains('fake-app-token') && !html.contains('code_verifier'))
}
check('page connected: says Connected on reopen', pages.connected.contains('<b>Status: Connected</b>'))
['failed', 'denied', 'missing', 'expired'].each { check("page ${it}: says what to do next", pages[it].contains('connect again from its <b>Connection</b> section')) }

// --- Dynamic text is escaped, request params are never rendered ---
withVerifier('c')
app.tokenReply = new IllegalStateException('<script>alert(0)</script>')
app.logged.clear()
result = callback([code: '<script>fake-code</script>', access_token: '<script>fake-token</script>'])
check('escape: exception message not on the page', result.data.contains('Connection failed') && !result.data.contains('alert(0)'))
check('escape: no request param on the page', !result.data.contains('fake-code') && !result.data.contains('fake-token') && !result.data.contains('<script'))
check('logs: token exchange failure logs the exception class only', app.logged.any { it.contains('token exchange failed: IllegalStateException') } && !app.logged.any { it.contains('alert(0)') })
// The real failed-exchange path with markup in the reason: "HTTP <script>..."
withVerifier('d')
app.tokenReply = '<script>alert(1)</script> & "x"'
result = callback([code: 'fake-auth-code-6'])
check('escape: the error text is escaped', app.state.lastError == 'Token exchange returned HTTP <script>alert(1)</script> & "x"' && result.data.contains('HTTP &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;') && !result.data.contains('<script'))
// Connected app page: Last error escaped
app.paragraphs.clear()
app.mainPage()
String connectedHtml = app.paragraphs.join('\n')
check('app page connected: Last error escaped', app.state.sbToken && connectedHtml.contains('<b>Status:</b> Connected') && connectedHtml.contains('<b>Last error:</b> Token exchange returned HTTP &lt;script&gt;alert(1)&lt;/script&gt;') && !connectedHtml.contains('<script'))
app.state.sbToken = null
app.logged.clear()
callback([error: 'access_denied'])
check('logs: access_denied logged as fixed text', app.logged.any { it == 'SignalBricks connect cancelled: access denied' } && !app.logged.any { it.contains('access_denied') })
app.logged.clear()
result = callback([error: '<script>alert(2)</script>'])
check('escape: the error param is not rendered', !result.data.contains('alert(2)') && !result.data.contains('<script') && result.data.contains('Not connected'))
check('logs: the error param is not logged, fixed text instead', app.logged.any { it == 'SignalBricks connect cancelled: other error' } && !app.logged.any { it.contains('alert(2)') })

// The raw error is only on the app page, escaped there
app.paragraphs.clear()
app.mainPage()
String mainHtml = app.paragraphs.join('\n')
check('app page not connected: Last error shows the raw error escaped', mainHtml.contains('<b>Last error:</b> SignalBricks did not authorize this hub (&lt;script&gt;alert(2)&lt;/script&gt;)') && !mainHtml.contains('<script'))

// --- No back link whatever the hub reports (the URL helper is gone) ---
app.location = null
result = callback([:])
check('no location: still renders, no link', result.data.contains('Not connected') && !result.data.contains('<a '))

// --- Nothing secret logged ---
check('logs: no token, verifier or code', !app.logged.any { l -> secrets.any { it && l.contains(it) } || l.contains('fake-auth-code') })

if (args.length > 1) {
    pages.each { k, html -> new File(args[1], "${k}.html").text = html }
    println "Wrote ${pages.keySet().join(', ')} to ${args[1]}"
}

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
