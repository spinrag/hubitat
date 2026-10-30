// Hub report (signalbricks/platform#692) for SignalBricks Telemetry with a stubbed
// Hubitat sandbox: collecting the signalbricksAppInfo announcements (contract keys
// only, latest per app, capped and aged out), and the report posted to
// <ingest>/report on install, Done, Connect and every day. The body is checked
// against a strict copy of the platform's zod schema (packages/shared/src/schemas/
// hub-report.ts) and its field names against the JSON examples copied verbatim
// from docs/hub-reporting.md (tests/fixtures). Locks report counts only: no code,
// code name, guest name or token in the body or any log line; encrypted and plain
// lockCodes are counted, labels truncated, string attributes coerced; failed
// posts are retried like registrations; the 64 KB limit is respected.
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
    Boolean lockManagementEnabled = false
    Boolean includeDoorCodes = false
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    def fullControlLocks = null
    List removedSettings = []
    List logged = []
    List posts = []
    List subscriptions = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, error: { logged << it.toString() }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.3.152', uptime: 864000L], timeZone: TimeZone.getTimeZone('America/New_York')]
    void definition(Map m) {}
    void preferences(Closure c) {}
    void mappings(Closure c) {}
    List paragraphs = []
    def dynamicPage(Map m, Closure c) { c(); m }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) {}
    void href(Map m) {}
    Map render(Map m) { m }
    long clock = 1_793_318_400_000L
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
    void subscribe(Object... args) { subscriptions << [target: args[0], attribute: args[1], handler: args[2]] }
    void unsubscribe() { subscriptions.clear() }
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { state.accessToken = 'fake-app-token-0001'; state.accessToken }
    Boolean throwOnPost = false
    void asynchttpPost(String cb, Map p, Map d) {
        if (throwOnPost) throw new IllegalArgumentException("post failed: ${p.headers} ${p.body}")
        posts << [cb: cb, params: p, data: d]
    }
    // Like the hub: decrypts what it encrypted (here "ENC:" + base64), null for anything else
    String decrypt(String s) { s?.startsWith('ENC:') ? new String(s.substring(4).decodeBase64(), 'UTF-8') : null }
}

class FakeResp {
    int status; String body
    Boolean hasError() { status >= 400 }
    int getStatus() { status }
    String getData() { body }
    String getErrorData() { body }
    String getErrorMessage() { 'error' }
}

class FakeApp {
    def owner
    def id = 42
    FakeApp(o) { owner = o }
    void removeSetting(String n) { owner.setProperty(n, null) }
    void updateSetting(String n, Map v) { owner.setProperty(n, v.value) }
}

class FakeLock {
    String id; String displayName; String typeName
    Map attrs = [:]
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

File root = new File(args[0]).absoluteFile.parentFile
if (root.name == 'beta') root = root.parentFile
def slurp = { String f -> new JsonSlurper().parseText(new File(root, "tests/fixtures/${f}").text) }
Map docReport = slurp('hub-report.example.json')
Map docAppInfo = slurp('hub-app-info.example.json')

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }

// --- A strict copy of the platform schema (hub-report.ts): every violation is an error,
// where the platform would silently drop the field ---
def isNum = { v -> v instanceof Number }
def isInt = { v -> v instanceof Number && new BigDecimal(v.toString()).stripTrailingZeros().scale() <= 0 }
def str = { int max -> { v -> v instanceof String && v.trim().length() >= 1 && v.trim().length() <= max } }
def intIn = { int min, int max -> { v -> isInt(v) && (v as long) >= min && (v as long) <= max } }
def bool = { v -> v instanceof Boolean }
def epochMs = { v -> isInt(v) && (v as long) >= 0 }
Map APP_INFO_RULES = [schemaVersion: intIn(1, 1000), appId: str(32), name: str(64), version: str(32), minCodePosition: intIn(0, 1000),
    codeNamePrefix: { v -> v instanceof String && v.length() <= 16 }, manageDoorCodes: bool]
Map APP_RULES = APP_INFO_RULES + [lastSeenAt: epochMs]
Map LOCK_RULES = [deviceId: str(32), name: str(64), battery: { v -> isNum(v) && (v as BigDecimal) >= 0 && (v as BigDecimal) <= 100 },
    lock: { v -> v instanceof String && v.trim().toLowerCase() in ['locked', 'unlocked', 'unlocked with timeout', 'unknown', 'jammed'] },
    jammed: bool, maxCodes: intIn(0, 1000), codeLength: intIn(0, 32), driver: str(64), occupiedSlots: intIn(0, 1000)]
Map TELEMETRY_RULES = [version: str(32), maxManagedSlot: intIn(0, 1000)]
Map HUB_RULES = [firmwareVersion: str(32), uptimeSec: { v -> isInt(v) && (v as long) >= 0 }, timeZone: str(64)]
def validateObject = { String where, def obj, Map rules, List errors ->
    if (!(obj instanceof Map)) { errors << "${where}: not an object"; return }
    obj.each { k, v ->
        if (!rules.containsKey(k)) errors << "${where}.${k}: not in the schema"
        else if (!rules[k](v)) errors << "${where}.${k}: invalid value ${v}"
    }
}
def validateReport = { def r ->
    List errors = []
    if (!(r instanceof Map)) return ['not an object']
    Set top = ['schemaVersion', 'sentAt', 'reason', 'telemetry', 'apps', 'hub', 'locks'] as Set
    r.keySet().findAll { !(it in top) }.each { errors << "${it}: not in the schema" }
    if (r.containsKey('schemaVersion') && !intIn(1, 1000)(r.schemaVersion)) errors << 'schemaVersion'
    if (r.containsKey('sentAt') && !epochMs(r.sentAt)) errors << 'sentAt'
    if (r.containsKey('reason') && !(r.reason in ['daily', 'installed', 'updated'])) errors << 'reason'
    if (r.containsKey('telemetry')) validateObject('telemetry', r.telemetry, TELEMETRY_RULES, errors)
    if (r.containsKey('hub')) validateObject('hub', r.hub, HUB_RULES, errors)
    if (r.containsKey('apps')) {
        if (!(r.apps instanceof List) || r.apps.size() > 20) errors << 'apps: not a list of at most 20'
        else r.apps.eachWithIndex { a, i -> validateObject("apps[${i}]", a, APP_RULES, errors) }
    }
    if (r.containsKey('locks')) {
        if (!(r.locks instanceof List) || r.locks.size() > 200) errors << 'locks: not a list of at most 200'
        else r.locks.eachWithIndex { l, i -> validateObject("locks[${i}]", l, LOCK_RULES, errors) }
    }
    return errors
}
// Every key at every depth, for the secret checks
def allKeys
allKeys = { def v -> v instanceof Map ? v.keySet().collect { it.toString() } + v.values().collectMany { allKeys(it) } : (v instanceof List ? v.collectMany { allKeys(it) } : []) }

// The validator agrees with the documented examples
check("doc example report validates (${validateReport(docReport)})", validateReport(docReport).isEmpty())
List appInfoErrors = []
validateObject('appInfo', docAppInfo, APP_INFO_RULES, appInfoErrors)
check('doc example app info validates', appInfoErrors.isEmpty())
List bad = validateReport(docReport + [extra: 1, locks: [[deviceId: '1', battery: '85', lockCodes: '{}']]])
check('validator rejects extra keys and string numbers', bad.size() == 3)

check('version is 1.6.0', app.appNameAndVersion() == 'SignalBricks Telemetry 1.6.0')

// --- Fake hub: connected, two booking apps, locks with codes ---
String INGEST = 'sbit_fakeingesttokenfakeingesttoken00'
String ACCESS = 'fake-app-token-0001'
List CODES = ['24681357', '97531864', '11223344', '55667788']
List GUESTS = ['Zelda Fakeguest', 'Yorick Fakeguest']
def codeMap = { int n -> (1..n).collectEntries { i -> [(String.valueOf(25 + i)): [name: "G: ORB10${i}-${GUESTS[i % 2]}", code: CODES[i % CODES.size()]]] } }
String plain12 = JsonOutput.toJson(codeMap(12))
String enc3 = 'ENC:' + JsonOutput.toJson(codeMap(3)).bytes.encodeBase64().toString()
String longLabel = 'Back door ' + ('x' * 70)
def front = new FakeLock(id: '57', displayName: 'Front door', typeName: 'Generic Z-Wave Plus Lock',
    attrs: [battery: '85', lock: 'locked', maxCodes: '250', codeLength: '4', lockCodes: plain12])
def back = new FakeLock(id: '58', displayName: longLabel, typeName: 'Schlage BE469',
    attrs: [battery: 15.5, lock: 'Jammed', maxCodes: '30.0', codeLength: 6, lockCodes: enc3])
def odd = new FakeLock(id: '59', displayName: 'Shed', typeName: null,
    attrs: [battery: 'n/a', lock: 'weird', maxCodes: 'lots', lockCodes: 'not json'])
def side = new FakeLock(id: '60', displayName: 'Side door', typeName: 'Kwikset 916', attrs: [lock: 'unlocked with timeout', lockCodes: '{}'])
app.locks = [front, back, odd]
app.codeLocks = [front, side]
app.maxManagedSlot = '25'
app.state.sbToken = INGEST
app.state.ingestUrl = 'https://ingest.example.invalid/v1/webhooks/hubitat'
app.state.accessToken = ACCESS
app.state.lockMgmtLastEnabled = false

def reports = { app.posts.findAll { it.cb == 'reportCallback' } }
def bodyOf = { p -> new JsonSlurper().parseText(p.params.body) }
def lastReport = { bodyOf(reports()[-1]) }

// --- Subscription ---
app.initialize()
check('initialize: subscribes to signalbricksAppInfo location events', app.subscriptions.any { it.target.is(app.location) && it.attribute == 'signalbricksAppInfo' && it.handler == 'appInfoHandler' })
check('initialize: never subscribes to lockCodes', !app.subscriptions.any { it.attribute == 'lockCodes' })
check('initialize: daily check-in scheduled', app.scheduled.any { it.handler == 'dailyCheckIn' && it.cron })

// --- Collecting announcements ---
def announce = { Map v -> app.appInfoHandler([name: 'signalbricksAppInfo', value: JsonOutput.toJson(v)]) }
app.clock = 1_793_314_800_000L
announce(docAppInfo + [code: CODES[0], lockCodes: plain12, guest: GUESTS[0], token: INGEST])
app.clock = 1_793_311_200_000L
announce([schemaVersion: 1, appId: '388', name: 'SignalBricks Integration', version: '1.4.6', minCodePosition: 50, codeNamePrefix: 'GT: '])
Map infos = app.state.appInfos
check('collect: one entry per app id', infos.keySet() == ['412', '388'] as Set)
check('collect: fork entry has exactly the contract keys + lastSeenAt', infos['412'].keySet() as List == ['schemaVersion', 'appId', 'name', 'version', 'minCodePosition', 'codeNamePrefix', 'manageDoorCodes', 'lastSeenAt'])
check('collect: extra keys (code, lockCodes, guest, token) dropped', !allKeys(infos).any { it in ['code', 'lockCodes', 'guest', 'token'] } && !JsonOutput.toJson(infos).contains(CODES[0]) && !JsonOutput.toJson(infos).contains(INGEST))
check('collect: lastSeenAt is when Telemetry got it', infos['412'].lastSeenAt == 1_793_314_800_000L && infos['388'].lastSeenAt == 1_793_311_200_000L)
app.clock = 1_793_316_000_000L
announce([schemaVersion: 1, appId: '388', name: 'SignalBricks Integration', version: '1.4.7', minCodePosition: 50, codeNamePrefix: 'GT: '])
check('collect: latest per app id replaces the older one', app.state.appInfos['388'].version == '1.4.7' && app.state.appInfos['388'].lastSeenAt == 1_793_316_000_000L && app.state.appInfos.size() == 2)
announce([name: 'No id', version: '1'])
app.appInfoHandler([name: 'signalbricksAppInfo', value: 'not json'])
app.appInfoHandler([name: 'signalbricksAppInfo', value: '[1,2]'])
check('collect: no appId, bad JSON or a non-object ignored', app.state.appInfos.size() == 2)
announce([appId: 77, schemaVersion: '1', name: 'X' * 65, version: 3, minCodePosition: '30', codeNamePrefix: 'P' * 17, manageDoorCodes: 'yes'])
check('collect: bad field types and lengths dropped, numeric appId as a string', app.state.appInfos['77'] == [appId: '77', lastSeenAt: app.clock])
app.state.appInfos.remove('77')
// Changes go out within a minute; a repeat of the same info doesn't
app.scheduled.removeAll { it.handler == 'sendAppInfoReport' }
announce([schemaVersion: 1, appId: '388', name: 'SignalBricks Integration', version: '1.4.7', minCodePosition: 50, codeNamePrefix: 'GT: '])
check('collect: unchanged daily announcement schedules no extra report', !app.scheduled.any { it.handler == 'sendAppInfoReport' })
announce([schemaVersion: 1, appId: '388', name: 'SignalBricks Integration', version: '1.4.7', minCodePosition: 20, codeNamePrefix: 'GT: '])
def changedJob = app.scheduled.find { it.handler == 'sendAppInfoReport' }
check('collect: a changed announcement sends a report in 60 s', changedJob?.secs == 60)
app.posts.clear()
app.sendAppInfoReport()
check('collect: that report is reason "updated" and has the new setting', reports().size() == 1 && lastReport().reason == 'updated' && lastReport().apps.find { it.appId == '388' }.minCodePosition == 20)
announce([schemaVersion: 1, appId: '388', name: 'SignalBricks Integration', version: '1.4.6', minCodePosition: 50, codeNamePrefix: 'GT: '])
app.clock = 1_793_318_400_000L
app.state.appInfos['388'].lastSeenAt = 1_793_311_200_000L

// --- The daily report ---
app.posts.clear()
app.dailyCheckIn()
check('daily: one report posted', reports().size() == 1)
def post = reports()[0]
check('daily: POST <ingest>/report', post.params.uri == 'https://ingest.example.invalid/v1/webhooks/hubitat/report')
check('daily: Bearer ingestion token, JSON', post.params.headers == [Authorization: "Bearer ${INGEST}".toString()] && post.params.requestContentType == 'application/json')
Map r = lastReport()
check("daily: body validates against the schema (${validateReport(r)})", validateReport(r).isEmpty())
check('daily: top-level field names match the doc example exactly', r.keySet() == docReport.keySet())
check('daily: telemetry field names match the doc', r.telemetry.keySet() == docReport.telemetry.keySet())
check('daily: hub field names match the doc (no model)', r.hub.keySet() == docReport.hub.keySet() && !r.hub.containsKey('model'))
check('daily: fork app field names match the doc', r.apps.find { it.appId == '412' }.keySet() == docReport.apps[0].keySet())
check('daily: Integration app field names match the doc', r.apps.find { it.appId == '388' }.keySet() == docReport.apps[1].keySet())
check('daily: full lock entry field names match the doc', r.locks.find { it.deviceId == '57' }.keySet() == docReport.locks[0].keySet())
check('daily: schemaVersion 1, sentAt now, reason daily', r.schemaVersion == 1 && r.sentAt == app.clock && r.reason == 'daily')
check('daily: telemetry version and ceiling as a number', r.telemetry == [version: '1.6.0', maxManagedSlot: 25])
check('daily: hub firmware, uptime, time zone', r.hub == [firmwareVersion: '2.4.3.152', uptimeSec: 864000, timeZone: 'America/New_York'])
check('daily: apps as collected, with lastSeenAt', r.apps.find { it.appId == '412' } == docReport.apps[0] && r.apps.find { it.appId == '388' } == docReport.apps[1].findAll { k, v -> k != 'version' } + [version: '1.4.6'])
// The doc's example rebuilt from this fake hub: same apps, the same front door
check('daily: front door entry equals the doc example', r.locks.find { it.deviceId == '57' } == docReport.locks[0] + [occupiedSlots: 12])
Map backL = r.locks.find { it.deviceId == '58' }
Map oddL = r.locks.find { it.deviceId == '59' }
Map sideL = r.locks.find { it.deviceId == '60' }
check('locks: one entry per selected lock (telemetry + code management, once each)', r.locks*.deviceId == ['57', '58', '59', '60'])
check('locks: label truncated to 64', backL.name == longLabel.take(64) && backL.name.length() == 64)
check('locks: string attributes coerced to numbers', r.locks[0].battery == 85 && r.locks[0].maxCodes == 250 && r.locks[0].codeLength == 4 && backL.maxCodes == 30 && backL.battery == 15.5)
check('locks: lock lower-cased; jammed exactly when lock is jammed', backL.lock == 'jammed' && backL.jammed == true && r.locks[0].jammed == false)
check('locks: unknown lock value dropped, not jammed', !oddL.containsKey('lock') && oddL.jammed == false)
check('locks: unparseable numbers, no driver, unreadable lockCodes left out', oddL == [deviceId: '59', name: 'Shed', jammed: false])
check('locks: plain lockCodes counted', r.locks[0].occupiedSlots == 12)
check('locks: encrypted lockCodes decrypted and counted', backL.occupiedSlots == 3)
check('locks: empty lockCodes is 0, unlocked with timeout kept', sideL.occupiedSlots == 0 && sideL.lock == 'unlocked with timeout' && !sideL.containsKey('battery'))
check('locks: counts only, no slot or code keys', !allKeys(r.locks).any { it in ['code', 'codes', 'lockCodes', 'codeName', 'slots', 'pin'] || it ==~ /\d+/ })

// --- Nothing secret in the body or the log ---
String raw = post.params.body
check('secret: no door code in the body', !CODES.any { raw.contains(it) })
check('secret: no code name or guest name in the body', !raw.contains('ORB') && !GUESTS.any { raw.contains(it) } && !raw.contains('Fakeguest'))
check('secret: no token in the body', !raw.contains(INGEST) && !raw.contains(ACCESS))
check('secret: no lockCodes or code key anywhere', !allKeys(r).any { it.toLowerCase() in ['code', 'lockcodes', 'pin', 'passcode', 'usedcode'] })

// --- Every day, whether or not anything changed ---
app.posts.clear()
3.times { app.clock += 24L * 3600 * 1000; app.dailyCheckIn() }
check('daily: a report every day, version unchanged', reports().size() == 3 && reports().every { bodyOf(it).reason == 'daily' })
check('daily: no registration on a telemetry-only hub', !app.posts.any { it.cb == 'registerCallback' })

// --- Install, Done, Connect ---
app.posts.clear()
app.installed()
check('install: report with reason installed', reports().size() == 1 && lastReport().reason == 'installed')
app.updated()
check('Done: report with reason updated', reports().size() == 2 && lastReport().reason == 'updated')
check('Done: subscription and daily job kept', app.subscriptions.any { it.attribute == 'signalbricksAppInfo' } && app.scheduled.any { it.handler == 'dailyCheckIn' })
app.state.remove('sbToken')
app.posts.clear()
app.updated()
app.dailyCheckIn()
check('not connected: no report', reports().isEmpty())
app.state.sbToken = INGEST
app.state.ingestUrl = 'http://ingest.example.invalid/v1/webhooks/hubitat'
app.dailyCheckIn()
check('not https: no report (the token only goes to https)', reports().isEmpty())
app.state.ingestUrl = 'https://ingest.example.invalid/v1/webhooks/hubitat'

// --- Retries: like registration (30 s, 2 min, 5 min), then the next day's report ---
def retryJob = { app.scheduled.find { it.handler == 'reportRetryRun' } }
app.posts.clear(); app.logged.clear()
app.dailyCheckIn()
app.reportCallback(new FakeResp(status: 503, body: '{"error":"unavailable"}'), [reason: 'daily'])
check('503: retried after 30 s', retryJob()?.secs == 30)
check('503: logged with the status only', app.logged.any { it == 'SignalBricks Telemetry: hub report not accepted (HTTP 503)' })
app.scheduled.remove(retryJob()); app.reportRetryRun()
check('retry: the report is sent again, same reason', reports().size() == 2 && lastReport().reason == 'daily')
check('retry: not re-armed while in flight', { app.initialize(); retryJob() == null }())
app.reportCallback(new FakeResp(status: 429, body: ''), [reason: 'daily'])
check('429: retried after 2 min', retryJob()?.secs == 120)
app.clock += 60_000
app.initialize()
check('retry survives initialize()\'s unschedule() with the time left', retryJob()?.secs == 60)
app.scheduled.remove(retryJob()); app.reportRetryRun()
app.reportCallback(new FakeResp(status: 500, body: ''), [reason: 'daily'])
check('500: retried after 5 min', retryJob()?.secs == 300)
app.scheduled.remove(retryJob()); app.reportRetryRun()
app.reportCallback(new FakeResp(status: 502, body: ''), [reason: 'daily'])
check('after 3 retries: no more, until the next daily report', retryJob() == null && app.state.reportRetry == null && reports().size() == 4)
check('page: last error shown', app.reportStatus()?.contains('HTTP 502'))
app.posts.clear()
app.dailyCheckIn()
app.reportCallback(new FakeResp(status: 400, body: ''), [reason: 'daily'])
check('400: not retried', retryJob() == null)
app.dailyCheckIn()
app.reportCallback(new FakeResp(status: 401, body: ''), [reason: 'daily'])
check('401: not retried', retryJob() == null)
app.dailyCheckIn()
app.reportCallback(new FakeResp(status: 408, body: ''), [reason: 'daily'])
check('408 (timeout): retried', retryJob()?.secs == 30)
app.reportCallback(new FakeResp(status: 202, body: '{"received":true}'), [reason: 'daily'])
check('202: retry cleared, sent time and version recorded', retryJob() == null && app.state.reportRetry == null && app.state.lastReportAt == app.clock && app.state.reportedVersion == '1.6.0' && app.state.lastReportError == null)
check('page: last sent shown', app.reportStatus()?.contains('last sent'))
app.throwOnPost = true
app.sendHubReport('daily')
app.throwOnPost = false
check('a post that throws is logged by class only', app.logged.any { it.contains('failed to send the hub report (IllegalArgumentException)') })
check('logs: no token in any log line', !app.logged.any { it.contains(INGEST) || it.contains(ACCESS) || it.contains('sbit_') })
check('logs: no code or guest name in any log line', !app.logged.any { line -> CODES.any { line.contains(it) } || GUESTS.any { line.contains(it) } })

// --- After an HPM update (no initialize): the next event subscribes and reports ---
app.subscriptions.clear()
app.state.appInfoSubscribed = '1.5.3'
app.state.reportedVersion = '1.5.3'
app.state.remove('reportAttemptAt')
app.state.checkInSecond = 5 * 3600
app.posts.clear()
def evt = [id: 1, deviceId: 7, name: 'contact', displayName: 'Door', value: 'open', isStateChange: true, type: 'physical']
app.eventHandler(evt)
check('HPM update: first event subscribes to announcements', app.subscriptions.any { it.attribute == 'signalbricksAppInfo' } && app.subscriptions.any { it.attribute == 'lock' })
check('HPM update: first event sends an "updated" report', reports().size() == 1 && lastReport().reason == 'updated')
app.eventHandler(evt)
check('HPM update: at most every 30 minutes until accepted', reports().size() == 1)
app.reportCallback(new FakeResp(status: 202, body: ''), [reason: 'updated'])
app.clock += 3_600_000
app.eventHandler(evt)
check('HPM update: once accepted, events send no report', reports().size() == 1)
app.subscriptions.clear()
app.state.appInfoSubscribed = '1.5.3'
app.dailyCheckIn()
check('HPM update: the daily check-in subscribes too', app.subscriptions.any { it.attribute == 'signalbricksAppInfo' })

// --- Apps: at most 20, the most recent; gone after 7 days ---
app.state.appInfos = [:]
25.times { i -> app.clock += 1000; announce([schemaVersion: 1, appId: "a${i}".toString(), name: 'Other app', version: '1.0.0']) }
check('apps: capped at 20, the most recently seen', app.state.appInfos.size() == 20 && !app.state.appInfos.containsKey('a0') && app.state.appInfos.containsKey('a24'))
app.clock += 8L * 24 * 3600 * 1000
announce([schemaVersion: 1, appId: '388', name: 'SignalBricks Integration', version: '1.4.6', minCodePosition: 50, codeNamePrefix: 'GT: '])
app.posts.clear()
app.dailyCheckIn()
check('apps: not seen for 7 days dropped', lastReport().apps*.appId == ['388'])

// --- 64 KB: 200 locks with long non-ASCII names stay under the limit ---
List many = (1..230).collect { i -> new FakeLock(id: "${100000 + i}".toString(), displayName: ('é中' * 40), typeName: ('ü' * 70),
    attrs: [battery: '100', lock: 'unlocked with timeout', maxCodes: '1000', codeLength: '32', lockCodes: plain12]) }
app.locks = many
app.codeLocks = []
app.posts.clear(); app.logged.clear()
app.dailyCheckIn()
String bigBody = reports()[-1].params.body
Map big = new JsonSlurper().parseText(bigBody)
check("size: body under 64 KB (${bigBody.getBytes('UTF-8').length} bytes)", bigBody.getBytes('UTF-8').length <= 60000)
check("size: locks left out from the end (${big.locks.size()} of 200)", big.locks.size() > 50 && big.locks.size() < 200 && big.locks[0].deviceId == '100001')
check('size: still valid, and the trimming is logged', validateReport(big).isEmpty() && app.logged.any { it.contains('hub report too large') })
// Small locks: the size limit isn't reached, the 200-lock cap is
app.locks = (1..230).collect { i -> new FakeLock(id: "${i}".toString(), displayName: "L${i}".toString(), typeName: 'Z', attrs: [battery: 90]) }
app.posts.clear()
app.dailyCheckIn()
check('locks: at most 200, the first selected', { Map b = new JsonSlurper().parseText(reports()[-1].params.body); b.locks.size() == 200 && b.locks[-1].deviceId == '200' }())

// --- Disconnect ---
app.appButtonHandler('btnDisconnect')
check('disconnect: report state cleared, no retry pending', app.state.reportedVersion == null && app.state.lastReportAt == null && retryJob() == null)
app.posts.clear()
app.dailyCheckIn()
check('disconnect: no report', reports().isEmpty())

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
