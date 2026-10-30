// App info announcement (signalbricks/platform#692) for SignalBricks Integration
// with a stubbed Hubitat sandbox: the hub-local signalbricksAppInfo location
// event carries exactly the contract keys (docs/hub-reporting.md) and no code,
// code name, token, booking id or guest name; it is sent on install, on Done and
// by a daily job at a random 04:00-23:59 time that survives every app-wide
// unschedule() (install, Reset State, booking syncs and reconcile, DELETE
// /register); announcing makes no network call. Fake values only.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import org.codehaus.groovy.control.CompilerConfiguration

// atomicState and runIn data are stored as JSON on the hub: every read returns a fresh copy
class JsonStateMap extends LinkedHashMap {
    Object put(Object k, Object v) { super.put(k, v == null ? null : JsonOutput.toJson(v)) }
    Object get(Object k) { def s = super.get(k); s == null ? null : new JsonSlurper().parseText(s) }
}

abstract class HubitatStub extends Script {
    Map state = [:]
    Map atomicState = new JsonStateMap()
    Map settings = [:]
    def locks = []
    def minCodePosition = 50
    def idAtEnd = false
    def codeNamePrefix = 'GT:'
    def request = [:]
    def params = [:]
    long clock = 1_800_000_000_000L
    List logged = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, trace: { logged << it.toString() }, error: { logged << it.toString() }]
    List scheduled = []
    List paragraphs = []
    Map inputs = [:]
    def app = [id: 388]
    def location = [name: 'Test', hub: [name: 'Hub']]
    List locationEvents = []
    List network = []
    List lockCommands = []
    List subscriptions = []
    void definition(Map m) {}
    void mappings(Closure c) {}
    void preferences(Closure c) { pages = c }
    Closure pages
    void page(Map m, Closure c = null) { if (c) c() }
    def dynamicPage(Map m, Closure c) { c() }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) {}
    long now() { clock }
    String getHubUID() { 'hub-fake' }
    String getFullApiServerUrl() { 'https://cloud.test/api/hub/apps/388' }
    String getFullLocalApiServerUrl() { 'http://hub.local/apps/api/388' }
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite) scheduled.removeAll { it.handler == handler }
        def data = opts?.data == null ? null : new JsonSlurper().parseText(JsonOutput.toJson(opts.data))
        scheduled << [secs: secs, handler: handler, data: data]
    }
    // runOnce jobs also go to onces: a later runIn(overwrite) of the same handler hides them in scheduled
    List onces = []
    void runOnce(Date d, String handler, Map opts = [:]) { scheduled << [at: d, handler: handler]; onces << [at: d, handler: handler] }
    Boolean throwOnAppInfoSchedule = false
    void schedule(String cron, String handler) {
        if (throwOnAppInfoSchedule && handler == 'sbAppInfoDaily') throw new IllegalStateException('schedule failed 73915246')
        scheduled.removeAll { it.handler == handler }; scheduled << [cron: cron, handler: handler]
    }
    void unschedule() { scheduled.clear() }
    void unschedule(String handler) { scheduled.removeAll { it.handler == handler } }
    void subscribe(Object... args) { subscriptions << args.toList() }
    void unsubscribe() { subscriptions.clear() }
    Boolean throwOnLocationEvent = false
    void sendLocationEvent(Map m) {
        if (throwOnLocationEvent) throw new IllegalStateException('event failed 73915246')
        locationEvents << m
    }
    void httpPostJson(Map p, Closure c) { network << p }
    void httpPost(Map p, Closure c) { network << p }
    void asynchttpPost(String cb, Map p, Map d = null) { network << p }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { 'tok-fake-access-0001' }
    Date toDateTime(String s) { new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(s) }
    def propertyMissing(String name) {
        if (name.endsWith('Handler')) return name
        throw new MissingPropertyException(name, getClass())
    }
}

class FakeLock {
    String id; String name; String label
    Map attrs = [:]
    List calls
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void getCodes() { calls << ['getCodes', id] }
    void setCode(Object... a) { calls << ['setCode', id] }
    void deleteCode(Object... a) { calls << ['deleteCode', id] }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
def job = { app.scheduled.find { it.handler == 'sbAppInfoDaily' } }
def jobs = { app.scheduled.findAll { it.handler == 'sbAppInfoDaily' } }
def lastInfo = { new JsonSlurper().parseText(app.locationEvents[-1].value) }
def fire = { String handler ->
    def j = app.scheduled.find { it.handler == handler }
    if (!j) return false
    app.scheduled.remove(j)
    if (j.data == null) app."$handler"() else app."$handler"(j.data)
    return true
}

// Fake secrets and guest data that must never be in an announcement
String CODE = '73915246'
String GUEST = 'Zelda Fakeguest'
String BOOKING = '987654'
String CREDENTIAL = 'sbcred-fake-credential-0001'
List calls = []
def lock = new FakeLock(id: '57', name: 'Front', label: 'Front door', calls: calls,
    attrs: [maxCodes: 250, lockCodes: JsonOutput.toJson(['51': [name: "GT:ORB${BOOKING}-Zelda", code: CODE]])])
app.locks = [lock]
app.settings = [locks: ['57'] as String[]]
Date now = new Date(app.clock)
Map bookings = [(BOOKING): [id: BOOKING, guest: GUEST, code: CODE, lockId: ['57'], checkIn: new Date(now.time + 86400_000), checkOut: new Date(now.time + 2 * 86400_000)]]

check('version is 1.4.8', app.sbAppVersion == '1.4.8')

// --- Install ---
app.installed()
check('install: one announcement', app.locationEvents.size() == 1)
Map ev = app.locationEvents[0]
check('install: event name signalbricksAppInfo, isStateChange true', ev.name == 'signalbricksAppInfo' && ev.isStateChange == true && ev.value instanceof String)
Map info = lastInfo()
check("payload: exactly the contract keys (${info.keySet()})", info.keySet() as List == ['schemaVersion', 'appId', 'name', 'version', 'minCodePosition', 'codeNamePrefix'])
check('payload: schemaVersion 1, appId a string, name and version', info.schemaVersion == 1 && info.appId == '388' && info.name == 'SignalBricks Integration' && info.version == '1.4.8')
check('payload: minCodePosition a number, prefix the setting', info.minCodePosition == 50 && info.minCodePosition instanceof Integer && info.codeNamePrefix == 'GT:')
check('payload: no manageDoorCodes (OwnerRez fork only)', !info.containsKey('manageDoorCodes'))
check('payload: under 255 characters', ev.value.length() < 255)
check('install: daily job scheduled', jobs().size() == 1)
def m = (job().cron =~ /^(\d+) (\d+) (\d+) \* \* \?$/)
check("install: daily cron at a fixed time of day (${job().cron})", m.matches())
int hour = m.matches() ? (m.group(3) as int) : -1
check('daily time 04:00-23:59, clear of the 01:00-03:59 refresh', hour >= 4 && hour <= 23)
String cron = job().cron

// --- Setting coercion ---
def infoWith = { pos, prefix -> app.minCodePosition = pos; app.codeNamePrefix = prefix; app.sbAppInfo() }
check('minCodePosition "30" (string setting) -> 30', infoWith('30', 'G: ').minCodePosition == 30)
check('minCodePosition 30.0 -> 30', infoWith(new BigDecimal('30.0'), 'G: ').minCodePosition == 30)
check('minCodePosition 30L -> 30', infoWith(30L, 'G: ').minCodePosition == 30)
check('minCodePosition unset -> 0', infoWith(null, 'G: ').minCodePosition == 0)
check('minCodePosition blank -> 0', infoWith('', 'G: ').minCodePosition == 0)
check('minCodePosition not a number -> left out', !infoWith('abc', 'G: ').containsKey('minCodePosition'))
check('prefix unset -> empty string', infoWith(50, null).codeNamePrefix == '')
check('prefix capped at 16 characters', infoWith(50, 'ABCDEFGHIJKLMNOPQRS').codeNamePrefix == 'ABCDEFGHIJKLMNOP')
app.minCodePosition = 50
app.codeNamePrefix = 'GT:'

// --- Done ---
app.state.bookings = bookings
app.atomicState.bookings = bookings
app.state.accessToken = 'tok-fake-access-0001'
app.locationEvents.clear()
app.updated()
check('Done: announced once', app.locationEvents.size() == 1 && lastInfo().version == '1.4.8')
check('Done: daily job kept, same time', jobs().size() == 1 && job().cron == cron)
app.minCodePosition = 60
app.locationEvents.clear()
app.updated()
check('Done: the saved settings are announced', lastInfo().minCodePosition == 60)
app.minCodePosition = 50

// --- Daily ---
app.locationEvents.clear()
check('daily: the job fires and announces', fire('sbAppInfoDaily') && app.locationEvents.size() == 1 && lastInfo().appId == '388')

// --- Survives every app-wide unschedule() ---
def rearmed = { String label, Closure path ->
    app.scheduled.clear()
    app.schedule(cron, 'sbAppInfoDaily')
    app.scheduled << [secs: 99, handler: 'someOtherJob']
    path()
    check("re-armed after ${label}", jobs().size() == 1 && job().cron == cron)
}
rearmed('a booking sync (PUT /sync)') { app.request = [JSON: [(BOOKING): [id: BOOKING, guest: GUEST, code: CODE, lockId: ['57'], checkIn: now.time + 86400_000, checkOut: now.time + 2 * 86400_000]]]; app.apiSync() }
rearmed('PATCH /sync') { app.request = [JSON: [(BOOKING): [id: BOOKING, guest: GUEST, code: CODE, lockId: ['57'], checkIn: now.time + 86400_000, checkOut: now.time + 2 * 86400_000]]]; app.apiSyncPatch() }
rearmed('one booking sync') { app.params = [bookingId: BOOKING, lockId: '57']; app.request = [JSON: [id: BOOKING, guest: GUEST, code: CODE, checkIn: now.time + 86400_000, checkOut: now.time + 2 * 86400_000]]; app.apiSyncBookingByLock() }
rearmed('a booking delete') { app.params = [bookingId: BOOKING, lockId: '57']; app.apiDeleteBookingByLock() }
rearmed('scheduleEvents (reconcile done)') { app.scheduleEvents([:]) }
rearmed('Reset State') { app.appButtonHandler('btnReset') }
rearmed('POST /register') { app.request = [JSON: [sbId: 'sb-fake']]; app.apiRegister() }
rearmed('DELETE /register') { app.apiUnregister() }
rearmed('Done') { app.updated() }
rearmed('install') { app.installed() }
check('install again: time picked once per install', job().cron == cron)

// Random time: spread out, always 04:00-23:59
Set hours = [] as Set
Set seconds = [] as Set
boolean inWindow = true
300.times {
    app.state.remove('appInfoSecond')
    app.sbScheduleAppInfo()
    def mm = (job().cron =~ /^(\d+) (\d+) (\d+) \* \* \?$/)
    if (!mm.matches()) { inWindow = false; return }
    int h = mm.group(3) as int
    hours << h
    seconds << ((h * 3600) + (mm.group(2) as int) * 60 + (mm.group(1) as int))
    if (h < 4 || h > 23 || (mm.group(2) as int) > 59 || (mm.group(1) as int) > 59) inWindow = false
}
check("random time: always 04:00-23:59 (hours ${hours.sort()})", inWindow)
check('random time: spread out', seconds.size() > 250 && hours.size() > 12)
check('random time: still a single job', jobs().size() == 1)

// --- After an HPM update (no Done): the first re-arm announces the new version once ---
app.state.appInfoVersion = '1.4.5'
app.locationEvents.clear()
app.scheduleEvents([:])
check('HPM update: first re-arm announces the new version', app.locationEvents.size() == 1 && lastInfo().version == '1.4.8')
app.scheduleEvents([:])
check('HPM update: not again on the next re-arm', app.locationEvents.size() == 1)

// --- No network, no lock, no subscription change ---
app.network.clear(); calls.clear()
app.state.sbCredential = CREDENTIAL
def subsBefore = app.subscriptions.collect { it }
Map stateBefore = new LinkedHashMap(app.state)
app.sbAnnounceAppInfo()
app.sbAppInfoDaily()
check('announce: no network call', app.network.isEmpty())
check('announce: no lock touched', calls.isEmpty())
check('announce: subscriptions unchanged', app.subscriptions == subsBefore)
check('announce: only appInfoVersion in state changes', app.state.findAll { k, v -> k != 'appInfoVersion' } == stateBefore.findAll { k, v -> k != 'appInfoVersion' })
app.state.remove('sbCredential')

// --- Nothing secret in any announcement ---
String all = app.locationEvents.collect { it.value }.join('\n')
check('no code in any announcement', !all.contains(CODE))
check('no guest name in any announcement', !all.contains(GUEST) && !all.contains('Zelda'))
check('no booking id in any announcement', !all.contains(BOOKING))
check('no token or credential in any announcement', !all.contains('tok-fake') && !all.contains(CREDENTIAL))
check('no code name in any announcement', !all.contains('ORB'))
check('only signalbricksAppInfo location events', app.locationEvents.every { it.name == 'signalbricksAppInfo' })
check('announce logs nothing with a code or token', !app.logged.any { it.contains(CODE) || it.contains('tok-fake') || it.contains(CREDENTIAL) })
check('every announcement has exactly the contract keys', app.locationEvents.every { new JsonSlurper().parseText(it.value).keySet() == ['schemaVersion', 'appId', 'name', 'version', 'minCodePosition', 'codeNamePrefix'] as Set })

// --- A failed announcement never stops booking scheduling or a save (review F1) ---
def reconciles = { app.onces.findAll { it.handler == 'reconcileDoorCodes' } }
Map syncBody = [(BOOKING): [id: BOOKING, guest: GUEST, code: CODE, lockId: ['57'], checkIn: now.time + 86400_000, checkOut: now.time + 2 * 86400_000]]
[['sendLocationEvent throws', { app.throwOnLocationEvent = true }], ['schedule() of the daily job throws', { app.throwOnAppInfoSchedule = true }]].each { label, Closure breakIt ->
    app.throwOnLocationEvent = false; app.throwOnAppInfoSchedule = false
    app.scheduled.clear(); app.onces.clear(); app.logged.clear()
    app.state.appInfoVersion = '1.4.5' // an HPM update: the re-arm also announces
    breakIt()
    String err = null
    try { app.request = [JSON: syncBody]; app.apiSync() } catch (e) { err = e.class.simpleName }
    check("${label}: PUT /sync still schedules the check-in reconcile", err == null && reconciles().size() == 1 && reconciles()[0].at == new Date(now.time + 86400_000))
    app.scheduled.clear(); app.onces.clear(); app.subscriptions.clear()
    err = null
    try { app.updated() } catch (e) { err = e.class.simpleName }
    check("${label}: Done still runs SyncState (subscriptions and reconcile)", err == null && reconciles().size() == 1 && app.subscriptions.any { it[1] == 'codeChanged' })
    err = null
    try { app.installed(); app.appButtonHandler('btnReset'); app.apiUnregister(); app.sbAppInfoDaily() } catch (e) { err = e.class.simpleName }
    check("${label}: install, Reset State, DELETE /register and the daily job don't throw", err == null)
    check("${label}: logged by class name only", app.logged.any { it.contains('(IllegalStateException)') } && !app.logged.any { it.contains(CODE) })
}
app.throwOnLocationEvent = false; app.throwOnAppInfoSchedule = false

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
