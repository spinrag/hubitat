// One-click safe Reconnect (#46) for SignalBricks Telemetry with a stubbed Hubitat
// sandbox: Reconnect is one link to the app's own /reconnect, shown while lock code
// management is on; viewing the page starts nothing, and the sign-in link (which carries the access token SignalBricks uses for lock control)
// exists only for 10 minutes after a click. Checks click -> callback (success and
// failure), click -> expiry (token replaced and registered with the ingestion token
// already held), expiry racing the callback, the expiry surviving initialize()'s
// unschedule(), a lost timer, registration failure -> retries on the app page, that the
// slot rules, fingerprint key, approvals, full control and pending code changes survive
// with lock management on, and that no token is logged or rendered outside the open link.
// Fake values only; nothing is sent to a hub or to SignalBricks.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    // Same store as state here; the hub writes atomicState through immediately.
    Map getAtomicState() { state }
    Map params = [:]
    Map request = [:]
    def codeLocks = []
    def locks = null, contactSensors = null, temperatureSensors = null, humiditySensors = null
    Boolean lockManagementEnabled = true
    Boolean includeDoorCodes = false
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    def fullControlLocks = null
    List removedSettings = []
    List logged = []
    List posts = []
    List http = []
    def tokenReply = null
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, error: { logged << it.toString() }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177', localIP: '192.168.1.10'], timeZone: TimeZone.getTimeZone('UTC')]
    void definition(Map m) {}
    void preferences(Closure c) {}
    void mappings(Closure c) {}
    List paragraphs = []
    List hrefs = []
    Map inputs = [:]
    def dynamicPage(Map m, Closure c) { c(); m }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) { hrefs << m }
    Map render(Map m) { m }
    long clock = 1_800_000_000_000L
    long now() { clock }
    List scheduled = []
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite != false) scheduled.removeAll { it.handler == handler }
        scheduled << [secs: secs, handler: handler, data: opts?.data]
    }
    void schedule(String cron, String handler) {
        scheduled.removeAll { it.handler == handler }
        scheduled << [cron: cron, handler: handler]
    }
    void unschedule(String handler = null) { if (handler) scheduled.removeAll { it.handler == handler } else scheduled.clear() }
    void subscribe(Object... args) {}
    void unsubscribe() {}
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    int tokenSeq = 0
    String createAccessToken() { tokenSeq++; state.accessToken = "fake-app-token-000${tokenSeq}"; state.accessToken }
    Boolean throwOnPost = false
    void asynchttpPost(String cb, Map p, Map d) {
        if (throwOnPost) throw new IllegalStateException('post failed 13572468')
        posts << [cb: cb, params: p, data: d]
    }
    void httpPost(Map p, Closure c) {
        http << p
        def r = tokenReply
        if (r instanceof Exception) throw r
        c([status: r instanceof Map ? 200 : r, data: r instanceof Map ? r : [:]])
    }
}

// Hubitat AsyncResponse: getData() on success, getErrorData() on error.
class FakeResp {
    int status; String body
    Boolean hasError() { status >= 400 }
    int getStatus() { status }
    String getData() { if (hasError()) throw new IllegalStateException('no data on error'); body }
    String getErrorData() { body }
}

class FakeApp {
    def owner
    def id = 42
    FakeApp(o) { owner = o }
    void removeSetting(String n) { owner.removedSettings << n; owner.setProperty(n, null) }
    void updateSetting(String n, Map v) { owner.setProperty(n, v.value) }
}

class FakeLock {
    String id; String displayName; String name; String label
    Map attrs = [:]
    List calls = []
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void setCode(Integer slot, String code, String name) { calls << ['setCode', slot, name] }
    void deleteCode(Integer slot) { calls << ['deleteCode', slot] }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
def mainPage = { app.paragraphs.clear(); app.hrefs.clear(); app.inputs.clear(); app.mainPage()
    (app.paragraphs + app.inputs.values().collect { "${it.title}" } + app.hrefs.collect { "${it.title} ${it.description} ${it.url}" }).join('\n') }
def link = { app.hrefs.find { it.name == 'hrefReconnect' } }
// The page without link URLs: titles, descriptions, paragraphs
def pageText = { mainPage(); (app.paragraphs + app.inputs.values().collect { "${it.title}" } + app.hrefs.collect { "${it.title} ${it.description}" }).join('\n') }
def entryUrl = { "${app.getFullApiServerUrl()}/reconnect?access_token=${app.state.accessToken}".toString() }
def registers = { app.posts.findAll { it.cb == 'registerCallback' } }
def regBody = { Map p -> new JsonSlurper().parseText(p.params.body) }
def expiryJob = { app.scheduled.find { it.handler == 'expireConnectLink' } }
def fireExpiry = { Map data -> app.scheduled.removeAll { it.handler == 'expireConnectLink' }; app.expireConnectLink(data) }
def retryJob = { app.scheduled.find { it.handler == 'registerRetryRun' } }
def ok = { boolean enabled -> new FakeResp(status: 200, body: JsonOutput.toJson([lock_management: enabled, connected: enabled])) }
String INGEST = 'sbit_fakeingesttokenfakeingesttoken00'
String INGEST_2 = 'sbit_fakeingesttokenfakeingesttoken22'
List tokens = []
def track = { tokens << app.state.accessToken; app.state.accessToken }

check('version is 1.6.2', app.appNameAndVersion() == 'SignalBricks Telemetry 1.6.2')

// --- A connected hub with lock code management on: slot rules, fingerprint key,
// approvals, full control and a pending code change ---
def lock = new FakeLock(id: '7', displayName: 'Front', name: 'Front', label: 'Front')
lock.attrs.maxCodes = '250.0'
lock.attrs.lockCodes = '{"1":{"name":"SBA: Owner","code":"11111111"},"40":{"name":"Legacy","code":"44444444"}}'
app.codeLocks = [lock]
app.createAccessToken(); track()
app.state.sbToken = INGEST
app.state.ingestUrl = 'https://ingest.example.invalid/v1/webhooks/hubitat'
app.state.connectedAt = '2026-10-01 12:00'
app.state.lockMgmtLastEnabled = true
app.state.lockMgmtRegistered = true
app.state.lockMgmtRegisteredAt = '2026-10-01 12:00'
app.state.registeredVersion = '1.6.2'
String KEY = ((1..32).collect { it as byte } as byte[]).encodeBase64().toString()
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], fingerprintKey: KEY, locks: ['7': [adopted: [40]]]]]
check('setup: slot rules and fingerprint key accepted', app.apiSetLockConfig().status == 200 && app.state.fingerprintKey == KEY)
app.approvedAdoptedSlots = ['7:40']
app.fullControlLocks = ['7']
app.params = [lockId: '7']
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: Cleaner']]
check('setup: a code change pending on the lock', app.apiSetCode().status == 202 && app.state.pendingCodeChanges?.size() == 1)
// A pending change as Reconnect must leave it (Done re-times its next check, by design)
def pendingNow = { (app.state.pendingCodeChanges ?: [:]).collectEntries { k, v -> [(k): v.findAll { kk, vv -> !(kk in ['check', 'checkAt']) }] } }
def snapshot = { JsonOutput.toJson([app.state.lockConfig, app.state.fingerprintKey, pendingNow(), app.approvedAdoptedSlots, app.fullControlLocks, app.lockManagementEnabled, app.state.lockMgmtRegistered]) }
String keep = snapshot()
def survived = { String when ->
    check("${when}: slot rules, fingerprint key, pending code change, approvals, full control and lock management unchanged",
        snapshot() == keep && pendingNow().size() == 1)
    check("${when}: no setting removed, no code change abandoned", app.removedSettings.isEmpty() && !app.posts.any { it.cb != 'registerCallback' && it.params.body?.contains('lock_code_result') })
    check("${when}: the pending change is still being verified", app.scheduled.any { it.handler == 'verifyCodeChanges' })
}
def lastRegisterLockMgmt = { Map p -> def b = regBody(p); b.lockManagement == true && b.appAccessToken == app.state.accessToken && p.params.headers.Authorization == "Bearer ${app.state.sbToken}".toString() }

// --- Before a click: one Reconnect link, shown with lock management on ---
String page = mainPage()
check('page: one Reconnect link, to the app\'s own /reconnect, shown while lock code management is on', link()?.title == 'Reconnect to SignalBricks' && link().style == 'external' &&
    link().url == entryUrl() && !page.contains('/oauth/hubitat/authorize') && !app.inputs.btnReconnect)
check('page: says what to do after a Hubitat error, and that an unused link can\'t be reused', link().description.contains('If you see a Hubitat error after signing in, click Reconnect again.') && page.contains("an unused link can't be reused"))
check('page: no "turn lock code management off" instructions', !page.toLowerCase().contains('turn lock code management off') && !page.contains('first turn off'))
check('page: says the link works for 10 minutes and lock management stays on', page.contains('works for 10 minutes') && page.contains('Lock code management stays on'))
check('page: the token is only in the Reconnect URL, never in text', !pageText().contains(app.state.accessToken))
5.times { mainPage() }
check('page: viewing the page opens no window, schedules nothing and changes no token', app.state.connectLink == null && !expiryJob() && registers().isEmpty() && app.state.accessToken == tokens[0])
survived('page render')

// --- Click -> callback success ---
String t0 = app.state.accessToken
def entry = app.apiReconnect()
String fwd = entry.data
Map l = app.state.connectLink
check('click: /reconnect forwards to the SignalBricks sign-in with no script and no referrer', entry.status == 200 && entry.contentType == 'text/html' &&
    fwd.contains('<meta name="referrer" content="no-referrer">') && fwd.contains('<meta http-equiv="refresh" content="0;url=https://app.signalbricks.co/oauth/hubitat/authorize?') &&
    fwd.contains('>Continue to SignalBricks</a>') && !(fwd =~ /<script|src=/))
check('click: the forward carries the current token in the sign-in state, HTML-escaped', fwd.contains(URLEncoder.encode("access_token=${t0}", 'UTF-8')) && !fwd.contains('&state=') && fwd.contains('&amp;state='))
check('click: link opened for 10 minutes', l?.issuedAt == app.clock && l?.expiresAt == app.clock + 600_000L)
check('click: expiry scheduled in 10 minutes with the link id', expiryJob()?.secs == 600 && expiryJob()?.data?.issuedAt == l.issuedAt)
check('click: token not changed by the click', app.state.accessToken == t0 && registers().isEmpty())
page = mainPage()
check('click: the app page shows until when the sign-in works', link()?.title == 'Reconnect to SignalBricks' && link().description.contains('works until 08:10:00'))
check('click: the token is only in the Reconnect URL', link().url == entryUrl() && !pageText().contains(t0))
app.tokenReply = [access_token: INGEST_2, ingest_url: 'https://ingest.example.invalid/v1/webhooks/hubitat']
app.params = [code: 'fake-auth-code-1', access_token: t0]
app.clock += 60_000
def result = app.oauthCallback()
String t1 = track()
check('callback: Connected', result.data.contains('Connected') && app.state.sbToken == INGEST_2)
check('callback: link closed and its expiry cancelled', app.state.connectLink == null && !expiryJob())
check('callback: token replaced and registered for lock management with the new ingestion token', t1 != t0 && registers().size() == 1 && lastRegisterLockMgmt(registers()[0]))
check('callback: page back to plain Reconnect with the new token\'s entry URL, no old token anywhere', mainPage().with { !it.contains(t0) } && !pageText().contains(t1) && link().url == entryUrl() && !link().description.contains('works until'))
app.registerCallback(ok(true), [enabled: true])
survived('callback success')
// Expiry racing the callback: the job fires anyway (queued just before the callback cancelled it)
app.posts.clear()
fireExpiry([issuedAt: l.issuedAt])
check('race (callback first): the stale expiry does nothing', app.state.accessToken == t1 && registers().isEmpty())

// --- Click -> callback failure (sign-in cancelled): still rotated and registered ---
app.apiReconnect()
app.posts.clear()
app.params = [error: 'access_denied']
result = app.oauthCallback()
String t2 = track()
check('callback failure: Not connected page', result.data.contains('Not connected'))
check('callback failure: link closed, expiry cancelled', app.state.connectLink == null && !expiryJob())
check('callback failure: token replaced and registered with the ingestion token held', t2 != t1 && app.state.sbToken == INGEST_2 && registers().size() == 1 && lastRegisterLockMgmt(registers()[0]))
app.registerCallback(ok(true), [enabled: true])
survived('callback failure')

// --- Click -> expiry: no callback within 10 minutes ---
app.apiReconnect()
l = app.state.connectLink
mainPage()
check('expiry: the link had a verifier', app.state.pkceVerifier != null)
app.posts.clear()
app.clock += 600_000
fireExpiry(expiryJob().data)
String t3 = track()
check('expiry: token replaced', t3 != t2)
check('expiry: new token registered for lock management with the ingestion token held', registers().size() == 1 && lastRegisterLockMgmt(registers()[0]) && app.state.sbToken == INGEST_2)
check('expiry: link closed, verifier dropped', app.state.connectLink == null && app.state.pkceVerifier == null)
check('expiry: page back to plain Reconnect, the old token nowhere', mainPage().with { !it.contains(t2) } && !pageText().contains(t3) && link().url == entryUrl() && !link().description.contains('works until'))
check('expiry: logged without a token', app.logged.any { it.contains('Reconnect link expired unused; access token replaced') })
app.registerCallback(ok(true), [enabled: true])
survived('expiry')
// Expiry racing the callback: a callback after the expiry finds the verifier gone
app.posts.clear(); app.http.clear()
app.params = [code: 'fake-auth-code-late']
result = app.oauthCallback()
String t4 = track()
check('race (expiry first): a late callback finds the link expired, no exchange', result.data.contains('Connect link expired') && app.http.isEmpty())
check('race (expiry first): still rotated and registered, never the old token', t4 != t3 && registers().size() == 1 && lastRegisterLockMgmt(registers()[0]))
app.registerCallback(ok(true), [enabled: true])
survived('late callback')

// A newer link: an older link's expiry must not close it
app.apiReconnect()
Map newer = app.state.connectLink
app.posts.clear()
fireExpiry([issuedAt: l.issuedAt])
check('older link id: the newer link stays open, token unchanged', app.state.connectLink == newer && app.state.accessToken == t4 && registers().isEmpty())
fireExpiry(null)
check('no link id: nothing happens', app.state.connectLink == newer && app.state.accessToken == t4)

// --- The expiry survives initialize()'s unschedule() (Done) ---
app.clock += 240_000
app.updated()
check('Done: expiry re-armed for the time left with the same link id', expiryJob()?.secs == 360 && expiryJob()?.data?.issuedAt == newer.issuedAt)
app.registerCallback(ok(true), [enabled: true])
survived('Done with a link open')

// --- A lost timer: the page expires the link when it is rendered after the expiry ---
app.scheduled.removeAll { it.handler == 'expireConnectLink' }
app.clock += 400_000
app.posts.clear()
page = mainPage()
String t5 = track()
check('lost timer: rendering the page expires the link and rotates the token', t5 != t4 && app.state.connectLink == null && registers().size() == 1 && lastRegisterLockMgmt(registers()[0]))
check('lost timer: the old token nowhere on that page', !page.contains(t4) && !pageText().contains(t5) && !link().description.contains('works until'))
app.registerCallback(ok(true), [enabled: true])

// --- Registration failure after an expiry: automatic retries, shown on the app page ---
app.apiReconnect()
app.posts.clear()
app.clock += 600_000
fireExpiry(expiryJob().data)
String t6 = track()
app.registerCallback(new FakeResp(status: 503, body: '{"error":"Lock management unavailable"}'), [enabled: true])
check('register failure: retry 1 scheduled after 30 s', retryJob()?.secs == 30)
page = mainPage()
check('register failure: retry and the error shown on the app page', page.contains('<b>Registration retries:</b> retry 1 of 3 at 08:') && page.contains('<b>Last registration error:</b>') && page.contains('HTTP 503'))
// initialize() (Done) during the retry window keeps the retry
app.clock += 10_000
app.initialize()
check('register failure: Done re-arms the retry for the time left', retryJob()?.secs == 20)
app.posts.clear()
app.scheduled.removeAll { it.handler == 'registerRetryRun' }
app.registerRetryRun()
check('register failure: the retry sends the new token, never the old one', registers().size() == 1 && regBody(registers()[0]).appAccessToken == t6 && !tokens[0..-2].contains(regBody(registers()[0]).appAccessToken))
check('register failure: in progress on the page', mainPage().contains('retry 1 of 3 in progress'))
app.initialize()
check('register failure: an in-flight retry is not re-armed', !retryJob())
app.registerCallback(new FakeResp(status: 500, body: 'not json'), [enabled: true])
check('register failure: retry 2 after 2 min', retryJob()?.secs == 120)
app.scheduled.removeAll { it.handler == 'registerRetryRun' }
app.registerRetryRun()
app.registerCallback(new FakeResp(status: 408, body: ''), [enabled: true])
check('register failure: a timeout is retried, retry 3 after 5 min', retryJob()?.secs == 300)
app.scheduled.removeAll { it.handler == 'registerRetryRun' }
app.registerRetryRun()
app.registerCallback(new FakeResp(status: 502, body: ''), [enabled: true])
check('unattended: after the quick retries, every 30 minutes, shown on the page (#46)', retryJob()?.secs == 1800 && mainPage().contains('retry 4 at') && mainPage().contains('every 30 minutes for 24 hours'))
app.clock += 600_000
app.initialize()
check('unattended: Done re-arms the 30-minute retry for the time left', retryJob()?.secs == 1200)
app.posts.clear()
app.clock += 1_200_000
app.scheduled.removeAll { it.handler == 'registerRetryRun' }
app.registerRetryRun()
check('unattended: the 30-minute retry sends the current token', registers().size() == 1 && regBody(registers()[0]).appAccessToken == app.state.accessToken)
app.registerCallback(new FakeResp(status: 503, body: ''), [enabled: true])
check('unattended: retry 5 in another 30 minutes', retryJob()?.secs == 1800 && app.state.registerRetry?.attempt == 5)
// Fast-forward the 24-hour window (moving the clock a day would also age out the pending code change)
app.state.registerRetry.since = app.clock - 24L * 3600 * 1000 + 3_700_000L
int slow = 0
while (retryJob() && slow < 100) {
    app.clock += 1_800_000L
    app.scheduled.removeAll { it.handler == 'registerRetryRun' }
    app.registerRetryRun()
    app.registerCallback(new FakeResp(status: 503, body: ''), [enabled: true])
    slow++
}
check('unattended: the 30-minute retries stop after 24 hours and say so', slow == 2 && mainPage().contains('automatic retries failed for 24 hours'))
app.posts.clear()
app.registerRetryRun()
check('register failure: no registration once retries are exhausted', registers().isEmpty())
app.updated()
app.registerCallback(new FakeResp(status: 429, body: ''), [enabled: true])
check('register failure: a fresh registration (Done) starts over; 429 is retried', retryJob()?.secs == 30 && app.state.registerRetry?.attempt == 1)
app.scheduled.removeAll { it.handler == 'registerRetryRun' }
app.registerRetryRun()
app.registerCallback(ok(true), [enabled: true])
check('register failure: success clears the retry and the error', !retryJob() && !app.state.registerRetry && !app.state.lockMgmtRegisterError && !mainPage().contains('Registration retries'))
for (int st : [400, 401, 404]) {
    app.registerWithPlatform()
    app.registerCallback(new FakeResp(status: st, body: '{}'), [enabled: true])
    check("register failure: HTTP ${st} is not retried".toString(), !retryJob() && !app.state.registerRetry)
}
// A retry answered with reconnect required: no more retries
app.registerWithPlatform()
app.registerCallback(new FakeResp(status: 500, body: ''), [enabled: true])
app.scheduled.removeAll { it.handler == 'registerRetryRun' }
app.registerRetryRun()
app.registerCallback(new FakeResp(status: 403, body: '{"error":"Forbidden","code":"TOKEN_NOT_BOUND_TO_APP"}'), [enabled: true])
check('register failure: reconnect required is not retried', !retryJob() && !app.state.registerRetry && app.state.lockMgmtReconnectRequired == true)
check('reconnect required: the page points at Reconnect', mainPage().contains('click Reconnect to SignalBricks above') && link())
app.state.remove('lockMgmtReconnectRequired')
// A retry with nothing left to send (disconnected meanwhile) clears itself
app.registerWithPlatform()
app.registerCallback(new FakeResp(status: 500, body: ''), [enabled: true])
String sb = app.state.sbToken
app.state.remove('sbToken')
app.registerRetryRun()
check('register failure: a retry with nothing to send clears itself', !app.state.registerRetry)
app.state.sbToken = sb
survived('retries')

// --- signalbricks/platform#699 H2: opening /reconnect again doesn't extend an open window ---
app.apiReconnect()
Map first = app.state.connectLink.clone()
app.clock += 300_000
String again = app.apiReconnect().data
check('again within the window: same window and expiry', app.state.connectLink == first)
check('again within the window: its timer keeps the first link id', expiryJob()?.data?.issuedAt == first.issuedAt)
mainPage()
check('again within the window: the page shows the first expiry', link().description.contains("works until ${new Date(first.expiresAt as long).format('HH:mm:ss', TimeZone.getTimeZone('UTC'))}"))
check('again within the window: still forwards to the sign-in', again.contains('http-equiv="refresh"') && again.contains('/oauth/hubitat/authorize?'))
app.clock += 300_000
app.posts.clear()
fireExpiry(expiryJob().data)
track()
check('again within the window: it still ends 10 minutes after the first click', app.state.connectLink == null && registers().size() == 1)
app.registerCallback(ok(true), [enabled: true])
app.apiReconnect()
check('after the window ended: a click opens a new one', app.state.connectLink?.issuedAt == app.clock && app.state.connectLink != first)
app.clearConnectLink()

// --- signalbricks/platform#699 P1: the sign-in link reports held lock state ---
def lockStateParam = { String url -> url.split('\\?', 2)[1].split('&').any { it == 'lock_state=1' } }
check('lock state held: the sign-in link carries lock_state=1', lockStateParam(app.connectUrl()) && again.contains('&amp;lock_state=1&amp;'))
Map held = [lockConfig: app.state.lockConfig, fingerprintKey: app.state.fingerprintKey, approved: app.approvedAdoptedSlots, full: app.fullControlLocks]
def onlyHolding = { String which ->
    app.state.lockConfig = which == 'lockConfig' ? held.lockConfig : null
    app.state.fingerprintKey = which == 'fingerprintKey' ? held.fingerprintKey : null
    app.approvedAdoptedSlots = which == 'approved' ? held.approved : null
    app.fullControlLocks = which == 'full' ? held.full : null
    lockStateParam(app.connectUrl())
}
check('lock state: any one of slot rules, fingerprint key, approvals or full control is enough', ['lockConfig', 'fingerprintKey', 'approved', 'full'].every { onlyHolding(it) })
check('lock state: none held (lock management on but nothing received yet), no lock_state', !onlyHolding('none'))
app.state.lockConfig = held.lockConfig; app.state.fingerprintKey = held.fingerprintKey; app.approvedAdoptedSlots = held.approved; app.fullControlLocks = held.full
// Turning lock code management off wipes it on the hub even when SignalBricks can't be reached
app.throwOnPost = true
app.logged.clear()
app.lockManagementEnabled = false
String offPage = mainPage()
track()
check('off with SignalBricks unreachable: the page still renders', offPage.contains('Enable lock code management'))
check('off with SignalBricks unreachable: slot rules, fingerprint key, approvals and full control wiped', app.state.lockConfig == null && app.state.fingerprintKey == null &&
    app.approvedAdoptedSlots == null && app.fullControlLocks == null && !app.holdsLockState() && (app.state.pendingCodeChanges ?: [:]).isEmpty())
check('off with SignalBricks unreachable: the next sign-in link has no lock_state', !lockStateParam(app.connectUrl()))
check('off with SignalBricks unreachable: failures logged by class only', app.logged.any { it.contains('registering lock code management off failed (IllegalStateException)') } &&
    app.logged.any { it.contains('reporting abandoned code changes failed (IllegalStateException)') } && !app.logged.any { it.contains('13572468') })
app.throwOnPost = false
app.lockManagementEnabled = true
app.syncLockManagementEnabled()
track()
app.removedSettings.clear()

// --- platform#699 K2: management off with lock state left over: wiped, no lock_state ---
app.lockManagementEnabled = false
app.syncLockManagementEnabled()
track()
app.state.lockConfig = held.lockConfig
app.fullControlLocks = held.full
check('leftover: setup holds lock state with management off', app.holdsLockState() && app.state.lockMgmtLastEnabled == false)
mainPage()
check('leftover: rendering the app page wipes it, so the sign-in link has no lock_state', !app.holdsLockState() && !lockStateParam(app.connectUrl()))
app.state.fingerprintKey = held.fingerprintKey
app.approvedAdoptedSlots = held.approved
app.updated()
track()
check('leftover: Done wipes it too', !app.holdsLockState())
app.lockManagementEnabled = true
app.syncLockManagementEnabled()
track()
app.state.lockConfig = held.lockConfig; app.state.fingerprintKey = held.fingerprintKey; app.approvedAdoptedSlots = held.approved; app.fullControlLocks = held.full
mainPage()
check('management on: lock state kept and reported', app.holdsLockState() && lockStateParam(app.connectUrl()))
app.removedSettings.clear()

// --- Lock management off: Reconnect still works; nothing to register ---
app.lockManagementEnabled = false
app.state.lockMgmtLastEnabled = false
app.state.lockMgmtRegistered = false
app.apiReconnect()
app.posts.clear()
app.clock += 600_000
fireExpiry(expiryJob().data)
track()
check('lock management off: expiry rotates the token, no registration needed', app.state.connectLink == null && registers().isEmpty())
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.lockMgmtRegistered = true

// --- Turning lock code management on or off rotates the token: an open window is closed ---
app.apiReconnect()
app.lockManagementEnabled = false
app.syncLockManagementEnabled()
check('lock management off: an open Reconnect window is closed (its token was rotated)', app.state.connectLink == null && !expiryJob())
app.apiReconnect()
app.lockManagementEnabled = true
app.syncLockManagementEnabled()
check('lock management on: an open Reconnect window is closed too', app.state.connectLink == null && !expiryJob())
track()
app.removedSettings.clear()

// --- Disconnect with a link open: link closed and no retry left ---
// platform#699 K1: with SignalBricks unreachable (every post throws), Disconnect still
// rotates the token and wipes the lock state
app.apiReconnect()
app.state.lockConfig = held.lockConfig; app.state.fingerprintKey = held.fingerprintKey; app.approvedAdoptedSlots = held.approved; app.fullControlLocks = held.full
app.state.pendingCodeChanges = ['7:2': [status: 'pending', lockId: '7', slot: 2, action: 'set', attempts: 1, name: 'SBA: Cleaner', createdAt: app.clock,
    requestId: '00000000-0000-4000-8000-000000000001']]
String beforeDisconnect = app.state.accessToken
app.state.lockMgmtRegistered = true
app.throwOnPost = true
int logMark = app.logged.size()
app.appButtonHandler('btnDisconnect')
app.throwOnPost = false
track()
check('disconnect, SignalBricks unreachable: token rotated, disconnected', app.state.accessToken != beforeDisconnect && app.state.sbToken == null)
check('disconnect, SignalBricks unreachable: lock state and pending changes wiped', !app.holdsLockState() && (app.state.pendingCodeChanges ?: [:]).isEmpty())
List disconnectLogs = app.logged.drop(logMark)
check('disconnect, SignalBricks unreachable: failures logged by class only', disconnectLogs.any { it.contains('registering the disconnect failed (IllegalStateException)') } &&
    disconnectLogs.any { it.contains('reporting abandoned code changes failed (IllegalStateException)') } && !app.logged.any { it.contains('13572468') })
check('disconnect: link closed, no expiry or retry left', app.state.connectLink == null && !expiryJob() && !retryJob())
def notConnected = app.apiReconnect()
check('not connected: /reconnect says so and opens no window', notConnected.data.contains('Not connected') && !notConnected.data.contains('http-equiv') && app.state.connectLink == null && !expiryJob())

// --- Never a token in the logs ---
String logs = app.logged.join('\n')
check('logs: no access token, ingestion token, fingerprint key, code or auth code',
    !tokens.any { it && logs.contains(it) } && !logs.contains(INGEST) && !logs.contains(INGEST_2) && !logs.contains(KEY) && !logs.contains('12345678') && !logs.contains('fake-auth-code'))
check('logs: the link was logged as opened, without a token', app.logged.any { it.contains('Reconnect link opened for 10 minutes') })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
