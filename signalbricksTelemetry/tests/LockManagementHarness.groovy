// Behavioural smoke test for the lock-management handlers with a stubbed
// Hubitat sandbox. Fake values only.
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
    List subscriptions = []
    void subscribe(def devices, String attribute, String handler) { subscriptions << attribute }
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    List removedSettings = []
    List logged = []
    List posts = []
    def log = [info: { logged << it }, warn: { logged << it }, debug: { logged << it }, error: { logged << it }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177']]
    void definition(Map m) {}
    void preferences(Closure c) {}
    void mappings(Closure c) {}
    Map render(Map m) { m }
    Long fakeNow = null
    long now() { fakeNow ?: System.currentTimeMillis() }
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
    void unsubscribe() {}
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { state.accessToken = 'tok-' + UUID.randomUUID(); state.accessToken }
    Boolean throwOnPost = false
    void asynchttpPost(String cb, Map p, Map d) {
        if (throwOnPost) throw new IllegalArgumentException("post failed: ${p.body}")
        posts << [cb: cb, params: p, data: d]
    }
    String decrypt(String s) { throw new RuntimeException('no decrypt') }
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
    // Off: commands are only recorded. On: they update lockCodes, except the
    // next `dropCommands` ones (a lock that silently drops a change).
    Boolean applyCommands = false
    int dropCommands = 0
    Boolean throwOnSet = false
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void setCode(Integer slot, String code, String name) {
        calls << ['setCode', slot, name]
        if (throwOnSet) throw new IllegalStateException("setCode(${slot}, ${code}, ${name}) failed")
        apply { it[slot.toString()] = [name: name, code: code] }
    }
    void deleteCode(Integer slot) { calls << ['deleteCode', slot]; apply { it.remove(slot.toString()) } }
    void apply(Closure c) {
        if (!applyCommands) return
        if (dropCommands > 0) { dropCommands--; return }
        Map codes = new JsonSlurper().parseText(attrs.lockCodes) as Map
        c(codes)
        attrs.lockCodes = groovy.json.JsonOutput.toJson(codes)
    }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

def lock = new FakeLock(id: '7', displayName: 'Front', name: 'Front', label: 'Front')
lock.attrs.maxCodes = '250.0'
lock.attrs.lockCodes = '{"1":{"name":"SBA: Owner","code":"11111111"},"5":{"name":"Old","code":"55555555"},"12":{"name":"SBM: Cleaner","code":"22222222"},"30":{"name":"ORB123","code":"33333333"},"40":{"name":"Legacy","code":"44444444"}}'
// A second selected lock that the slot rules never list (not onboarded).
def lock2 = new FakeLock(id: '8', displayName: 'Back', name: 'Back', label: 'Back')
lock2.attrs.maxCodes = 250
lock2.attrs.lockCodes = '{"1":{"name":"SBA: Owner","code":"66666666"},"12":{"name":"SBM: Cleaner","code":"77777777"}}'
app.codeLocks = [lock, lock2]
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'

def body = { Map r -> r.data ? new JsonSlurper().parseText(r.data) : null }
int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }

app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: x']]
app.params = [lockId: '7']
check('write refused before config', app.apiSetCode().status == 403)

app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 10, to: 25]]]
check('overlap rejected', app.apiSetLockConfig().status == 400)
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 30]]]
check('range above ceiling rejected', app.apiSetLockConfig().status == 400)
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [managed: [from: 11, to: 200]]]]]
check('per-lock range above ceiling rejected', app.apiSetLockConfig().status == 400)
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['99': [adopted: [40]]]]]
check('unknown lock rejected', app.apiSetLockConfig().status == 400)
app.request = [JSON: [admin: [from: 0, to: 10], managed: [from: 11, to: 25]]]
check('slot 0 rejected', app.apiSetLockConfig().status == 400)

app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [adopted: [30, 40]]]]]
def cfgResp = app.apiSetLockConfig()
check('valid config accepted', cfgResp.status == 200)
check('adopted above ceiling pending', body(cfgResp).pendingApproval == ['7:30', '7:40'])

def slotsResp = app.apiListSlots()
check('slots carry no codes', !slotsResp.data.contains('1111') && !slotsResp.data.contains('"code"'))
check('slot classes before approval', body(slotsResp).slots.collect { it.allowedAs } == ['admin', 'admin', 'managed', null, null])

app.request = [JSON: [slot: 40, code: '12345678', name: 'Legacy']]
check('unapproved adopted refused', app.apiSetCode().status == 403)
check('approval options list both', (app.pendingAdoptedOptions().keySet() as List) == ['7:30', '7:40'])
check('booking option flagged', app.pendingAdoptedOptions()['7:30'].contains('booking code'))
app.approvedAdoptedSlots = ['7:30', '7:40']
check('approved adopted keeps name', app.apiSetCode().status == 202)

app.request = [JSON: [slot: 30, code: '12345678', name: 'Legacy 2']]
check('ORB slot write refused', app.apiSetCode().status == 403)
app.params = [lockId: '7', slot: '30']
check('ORB slot delete refused', app.apiDeleteCode().status == 403)
check('ORB slot reveal refused', app.apiRevealCode().status == 403)
app.params = [lockId: '7']
check('ORB slot not listed as allowed', body(app.apiListSlots()).slots.find { it.slot == 30 }.allowedAs == null)

app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: Owner 2']]
check('admin write to empty slot ok', app.apiSetCode().status == 202)
app.request = [JSON: [slot: 2, code: '12345678', name: 'Owner 2']]
check('admin write needs SBA:', app.apiSetCode().status == 403)
app.request = [JSON: [slot: 12, code: '12345678', name: 'SBA: wrong class']]
check('managed write needs SBM:', app.apiSetCode().status == 403)
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: Forbes']]
check('write name Forbes allowed', app.apiSetCode().status == 202)
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: Orbit']]
check('write name Orbit allowed', app.apiSetCode().status == 202)
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: ORB1']]
check('write name with " ORB" refused', app.apiSetCode().status == 403)
['SBA: FORBES', 'SBA: orb123'].each { n ->
    app.request = [JSON: [slot: 2, code: '12345678', name: n]]
    check("write name '${n}' allowed", app.apiSetCode().status == 202)
}
// signalbricksIntegration claims ORB<digits> anywhere: refuse it in written names
[[2, 'SBA:ORB12'], [2, 'SBA: Guest-ORB12'], [12, 'SBM:x-ORB5']].each { slot, n ->
    app.request = [JSON: [slot: slot, code: '12345678', name: n]]
    check("write name '${n}' refused", app.apiSetCode().status == 403)
}
app.request = [JSON: [slot: 40, code: '12345678', name: 'Guest-ORB12']]
check('adopted write with ORB<digit> name refused', app.apiSetCode().status == 403)
// Guard matrix
check('guard: Forbes not booking', !app.isBookingName('Forbes'))
check('guard: ORB123-Guest booking', app.isBookingName('ORB123-Guest'))
check('guard: GT: ORB123-Guest booking', app.isBookingName('GT: ORB123-Guest'))
check('guard: orb123 not booking', !app.isBookingName('orb123'))
check('guard: Guest-ORB12345 booking (idAtEnd)', app.isBookingName('Guest-ORB12345'))
check('guard: Guest -ORB12345 booking (OwnerRez fork idAtEnd)', app.isBookingName('Guest -ORB12345'))
check('guard: Orbit not booking', !app.isBookingName('Orbit'))
check('guard: Re-Orbit not booking', !app.isBookingName('Re-Orbit'))
// platform#581: ORB followed by a digit anywhere, whatever comes before it
['GT:ORB123-x', 'G:ORB1-x', 'Name -ORB123', 'GT: ORB123-x', 'xORB9', 'Guest-ORB12345'].each { n ->
    check("guard: '${n}' booking", app.isBookingName(n))
}
['ORBIT', 'Corbin', 'ORB', 'ORB-Guest', 'orb123', 'Orb1', 'SBA: ORBIT'].each { n ->
    check("guard: '${n}' not booking", !app.isBookingName(n))
}
['SBA: ORBIT', 'SBA: Corbin'].each { n ->
    app.request = [JSON: [slot: 2, code: '12345678', name: n]]
    check("write name '${n}' allowed", app.apiSetCode().status == 202)
}
['SBA: GT:ORB123-x', 'SBA: G:ORB1-x', 'SBA: Name -ORB123'].each { n ->
    app.request = [JSON: [slot: 2, code: '12345678', name: n]]
    check("write name '${n}' refused", app.apiSetCode().status == 403)
}
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: Café']]
check('non-ASCII name refused', app.apiSetCode().status == 400)
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: a\nb']]
check('control char name refused', app.apiSetCode().status == 400)
app.request = [JSON: [slot: 5, code: '12345678', name: 'SBA: take over']]
check('overwrite of unmarked code in admin range refused', app.apiSetCode().status == 403)
app.request = [JSON: [slot: 26, code: '12345678', name: 'SBM: x']]
check('booking range refused', app.apiSetCode().status == 403)
app.request = [JSON: [slot: 2, code: '12ab', name: 'SBA: x']]
check('non-digit code refused', app.apiSetCode().status == 400)

app.maxManagedSlot = 1000
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 300]]]
check('raised local ceiling allows wider range', app.apiSetLockConfig().status == 200)
app.request = [JSON: [slot: 300, code: '12345678', name: 'SBM: x']]
check('slot beyond maxCodes "250.0" refused', app.apiSetCode().status == 400)
app.maxManagedSlot = null
check('lowering ceiling refuses old wide range', app.slotClass('7', 100) == null)
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [adopted: [40]]]]]
app.apiSetLockConfig()

app.approvedAdoptedSlots = ['7:30', '7:40']
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [adopted: [40]]]]]
app.apiSetLockConfig()
check('approvals pruned to listed adopted slots', app.approvedAdoptedSlots == ['7:40'])
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25]]]
app.apiSetLockConfig()
check('approvals removed when none listed', app.approvedAdoptedSlots == null)
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [adopted: [40]]]]]
app.apiSetLockConfig()
app.approvedAdoptedSlots = ['7:40']

// Un-listed lock: nothing allowed, no fallback to the default ranges
app.state.revealTimes = []
app.params = [lockId: '8']
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: New']]
check('un-listed lock: set refused', app.apiSetCode().status == 403)
app.params = [lockId: '8', slot: '1']
check('un-listed lock: delete refused', app.apiDeleteCode().status == 403)
check('un-listed lock: reveal refused', app.apiRevealCode().status == 403)
app.params = [lockId: '8']
def slots8 = body(app.apiListSlots()).slots
check('un-listed lock: slots allowedAs none', slots8.size() == 2 && slots8.every { it.allowedAs == null })
app.params = [lockId: '7']
check('listed lock unchanged', body(app.apiListSlots()).slots.find { it.slot == 1 }.allowedAs == 'admin')
app.params = [lockId: '7', slot: '1']
check('listed lock: reveal still ok', app.apiRevealCode().status == 200)
// Listing it with {} gives it the default ranges
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [adopted: [40]], '8': [:]]]]
check('config listing lock 8 accepted', app.apiSetLockConfig().status == 200)
app.params = [lockId: '8']
app.request = [JSON: [slot: 2, code: '12345678', name: 'SBA: New']]
check('listed-with-defaults lock: set ok', app.apiSetCode().status == 202)
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['7': [adopted: [40]]]]]
app.apiSetLockConfig()
app.approvedAdoptedSlots = ['7:40']
app.state.revealTimes = []

// Booking guard on current names: Forbes passes, "ORB123-Guest" / "GT: ORB123-Guest" refused
app.state.revealTimes = []
app.params = [lockId: '7', slot: '5']
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"Old"', '"name":"SBA: Forbes"')
check('reveal SBA: Forbes allowed', app.apiRevealCode().status == 200)
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"SBA: Forbes"', '"name":"ORB123-Guest"')
check('ORB123-Guest refused', app.apiDeleteCode().status == 403)
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"ORB123-Guest"', '"name":"GT: ORB123-Guest"')
check('GT: ORB123-Guest refused', app.apiDeleteCode().status == 403)
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"GT: ORB123-Guest"', '"name":"Guest-ORB12345"')
check('Guest-ORB12345 refused', app.apiDeleteCode().status == 403)
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"Guest-ORB12345"', '"name":"Guest -ORB12345"')
check('Guest -ORB12345 refused', app.apiDeleteCode().status == 403)
['GT:ORB123-x', 'G:ORB1-x', 'Name -ORB123'].inject('Guest -ORB12345') { prev, n ->
    lock.attrs.lockCodes = lock.attrs.lockCodes.replace("\"name\":\"${prev}\"", "\"name\":\"${n}\"")
    check("current name '${n}' refused (write, delete, reveal)", app.apiDeleteCode().status == 403 && app.apiRevealCode().status == 403 &&
        { app.request = [JSON: [slot: 5, code: '12345678', name: 'SBA: x']]; app.apiSetCode().status == 403 }())
    n
}
['SBA: ORBIT', 'SBA: Corbin'].inject('Name -ORB123') { prev, n ->
    lock.attrs.lockCodes = lock.attrs.lockCodes.replace("\"name\":\"${prev}\"", "\"name\":\"${n}\"")
    app.state.revealTimes = []
    check("current name '${n}' not a booking code (reveal allowed)", app.apiRevealCode().status == 200)
    n
}
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"SBA: Corbin"', '"name":"Old"')
app.state.revealTimes = []

app.params = [lockId: '7', slot: '12']
check('delete managed ok', app.apiDeleteCode().status == 202)
app.params = [lockId: '7', slot: '5']
check('delete unmarked admin-range code refused', app.apiDeleteCode().status == 403)
check('reveal unmarked admin-range code refused', app.apiRevealCode().status == 403)

app.state.revealTimes = []
app.params = [lockId: '7', slot: '1']
def rev = app.apiRevealCode()
check('reveal SBA ok', rev.status == 200 && body(rev).code == '11111111')
app.params = [lockId: '7', slot: '40']
check('reveal approved adopted ok', app.apiRevealCode().status == 200)
app.params = [lockId: '7', slot: '1']
(1..8).each { app.apiRevealCode() }
check('reveal rate-limited', app.apiRevealCode().status == 429)

check('no codes logged', !app.logged.any { it.toString() =~ /1111|2222|3333|4444|5555|12345678/ })

app.lockManagementEnabled = false
check('disabled -> 404', app.apiListLocks().status == 404)

// Off -> on flip rotates the token before any registration
app.state.lockMgmtLastEnabled = false
app.lockManagementEnabled = true
app.state.accessToken = 'tok-abandoned-reconnect'
int postsBefore = app.posts.size()
check('enable handled without registering', app.syncLockManagementEnabled() == false && app.posts.size() == postsBefore)
check('token rotated on enable', app.state.accessToken != 'tok-abandoned-reconnect')

// Disable without Done (submitOnChange): rotate, clear rules, notify
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.lockMgmtRegistered = true
String oldToken = 'tok-old'
app.state.accessToken = oldToken
app.lockManagementEnabled = false
int before = app.posts.findAll { it.cb == 'registerCallback' }.size()
check('in-flight changes exist before disable', app.state.pendingCodeChanges?.values()?.any { it.status == 'pending' })
check('sync handled disable', app.syncLockManagementEnabled() == true)
check('pending changes (and codes) dropped on disable', app.state.pendingCodeChanges == null && !app.scheduled.any { it.handler == 'verifyCodeChanges' })
check('in-flight changes reported failed on disable', app.posts.any { it.params.body?.contains('lock_management_disabled') })
check('token rotated on disable', app.state.accessToken != oldToken)
check('rules cleared on disable', app.state.lockConfig == null && app.approvedAdoptedSlots == null)
def reg = new JsonSlurper().parseText(app.posts[-1].params.body)
check('disable notified', app.posts.findAll { it.cb == 'registerCallback' }.size() == before + 1 && reg.lockManagement == false && reg.appAccessToken == null)
check('second sync no-op', app.syncLockManagementEnabled() == false)

app.lockManagementEnabled = true
lock.attrs.lockCodes = 'ENCRYPTEDBLOB'
app.params = [lockId: '7']
check('encrypted unreadable -> 409', app.apiListSlots().status == 409)

app.registerWithPlatform()
reg = new JsonSlurper().parseText(app.posts[-1].params.body)
check('register url', app.posts[-1].params.uri == 'https://ingest.test/v1/webhooks/hubitat/register')
check('register payload', reg.lockManagement == true && reg.appAccessToken && reg.appId == '42')
app.state.ingestUrl = 'http://ingest.test/v1/webhooks/hubitat'
before = app.posts.size()
app.registerWithPlatform()
check('no token over http', app.posts.size() == before)

// --- Register only when needed; reconnect instead of a raw 403 (#522) ---
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
def registers = { app.posts.findAll { it.cb == 'registerCallback' } }
def regBody = { new JsonSlurper().parseText(registers()[-1].params.body) }
app.posts.clear(); app.logged.clear()
app.lockManagementEnabled = false
app.state.lockMgmtLastEnabled = false
app.state.remove('lockMgmtRegistered'); app.state.remove('lockMgmtRegisteredAt')
app.updated()
check('off and never registered: Done sends no register', registers().isEmpty())
app.registerWithPlatform(false)
check('off and never registered: disconnect-style disable not sent', registers().isEmpty())
app.state.lockMgmtRegisteredAt = '2026-10-20 10:00'
app.updated()
check('off, registered on 1.2.0 (registeredAt only): disable sent', registers().size() == 1 && regBody().lockManagement == false && regBody().appAccessToken == null)
app.posts.clear()
app.state.lockMgmtRegistered = true
app.state.remove('lockMgmtRegisteredAt')
app.updated()
check('off, previously registered: disable sent', registers().size() == 1 && regBody().lockManagement == false)
app.posts.clear()
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.lockMgmtRegistered = false
app.updated()
check('on: register sent even if never registered', registers().size() == 1 && regBody().lockManagement == true && regBody().appAccessToken)

// 403 TOKEN_NOT_BOUND_TO_APP on enable: reconnect message, no raw 403, no retry
app.posts.clear(); app.logged.clear(); app.scheduled.clear()
app.state.remove('lockMgmtRegisterError')
app.registerCallback(new FakeResp(status: 403, body: '{"error":"Forbidden","code":"TOKEN_NOT_BOUND_TO_APP"}'), [enabled: true])
check('403 unbound: reconnect required', app.state.lockMgmtReconnectRequired == true)
check('403 unbound: no raw error shown', app.state.lockMgmtRegisterError == null)
check('403 unbound: logs the reconnect message', app.logged.any { it.toString().contains('Reconnect to SignalBricks to enable lock management') } && !app.logged.any { it.toString().contains('403') })
check('403 unbound: no retry (no post, nothing scheduled)', app.posts.isEmpty() && app.scheduled.isEmpty())
check('403 unbound: not marked registered', app.state.lockMgmtRegistered == false)
app.logged.clear()
app.registerCallback(new FakeResp(status: 403, body: '{"error":"Forbidden","code":"TOKEN_NOT_BOUND_TO_APP"}'), [enabled: true])
check('403 unbound again: not logged again', app.logged.isEmpty())

// 200 reconnect_required on a disable: same message, registration state kept
app.state.remove('lockMgmtReconnectRequired')
app.state.lockMgmtRegistered = true
app.state.remove('lockMgmtRegisteredAt')
app.registerCallback(new FakeResp(status: 200, body: '{"lock_management":false,"connected":false,"reconnect_required":true}'), [enabled: false])
check('200 reconnect_required: reconnect required', app.state.lockMgmtReconnectRequired == true && app.state.lockMgmtRegisterError == null)
check('200 reconnect_required: not counted as registered or cleared', app.state.lockMgmtRegistered == true && app.state.lockMgmtRegisteredAt == null)
check('200 reconnect_required: no retry', app.posts.isEmpty() && app.scheduled.isEmpty())

// Other errors still show the status
app.registerCallback(new FakeResp(status: 403, body: '{"error":"Forbidden"}'), [enabled: true])
check('other 403: error shown', app.state.lockMgmtRegisterError?.endsWith('HTTP 403'))
app.registerCallback(new FakeResp(status: 500, body: 'not json'), [enabled: true])
check('unparseable error body: error shown', app.state.lockMgmtRegisterError?.endsWith('HTTP 500'))

// Bound token: enable then disable clear the message and track registration
app.registerCallback(new FakeResp(status: 200, body: '{"lock_management":true,"connected":true,"registered_at":"2026-10-26T12:00:00.000Z"}'), [enabled: true])
check('bound enable: registered, message cleared', app.state.lockMgmtRegistered == true && app.state.lockMgmtReconnectRequired == null && app.state.lockMgmtRegisterError == null && app.state.lockMgmtRegisteredAt)
app.registerCallback(new FakeResp(status: 200, body: '{"lock_management":false,"connected":false,"registered_at":"2026-10-26T12:00:00.000Z"}'), [enabled: false])
check('bound disable: no longer registered', app.state.lockMgmtRegistered == false)
app.posts.clear()
app.lockManagementEnabled = false
app.state.lockMgmtLastEnabled = false
app.updated()
check('after a registered disable: Done sends nothing', registers().isEmpty())

// Disconnect clears the flags
app.state.lockMgmtReconnectRequired = true
app.state.lockMgmtRegistered = true
app.appButtonHandler('btnDisconnect')
check('disconnect sends the disable when registered', registers().size() == 1 && regBody().lockManagement == false)
check('disconnect clears registration flags', app.state.lockMgmtRegistered == null && app.state.lockMgmtReconnectRequired == null)
app.state.sbToken = 'ingest-fake'
app.posts.clear(); app.logged.clear(); app.scheduled.clear()

// --- Set/delete verification, retry and result events (#520) ---
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
def lock3 = new FakeLock(id: '9', displayName: 'Side', name: 'Side', label: 'Side', applyCommands: true)
lock3.attrs.maxCodes = 30
lock3.attrs.lockCodes = '{"1":{"name":"SBA: Owner","code":"80801111"},"20":{"name":"Legacy","code":"80802020"}}'
app.codeLocks = [lock, lock2, lock3]
def cfg9 = [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['9': [adopted: [20]]]]
app.request = [JSON: cfg9]
check('config with lock 9 accepted', app.apiSetLockConfig().status == 200)
app.posts.clear(); app.logged.clear(); app.scheduled.clear()
app.fakeNow = System.currentTimeMillis()
app.state.remove('pendingCodeChanges'); app.state.retryTimes = []
List responses = []
def call = { String handler -> def r = app."${handler}"(); responses << r; r }
def results = {
    app.posts.findAll { it.params.body?.contains('"lock_code_result"') }.collect {
        def b = new JsonSlurper().parseText(it.params.body)
        b + [d: new JsonSlurper().parseText(b.data)]
    }
}
// Moves the clock to the scheduled verification job and runs it; returns the job.
def tick = {
    def job = app.scheduled.find { it.handler == 'verifyCodeChanges' }
    if (!job) return null
    app.scheduled.remove(job)
    app.fakeNow += (job.secs as long) * 1000L
    app.verifyCodeChanges()
    job
}
def verifyJobs = { app.scheduled.findAll { it.handler == 'verifyCodeChanges' } }
def pendingFor = { Integer slot -> app.state.pendingCodeChanges?.get("9:${slot}".toString()) }
String R1 = '3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b'

// Confirmed on the first check, platform requestId echoed
app.params = [lockId: '9']
app.request = [JSON: [slot: 3, code: '90903333', name: 'SBA: Cleaner', requestId: R1.toUpperCase()]]
def setResp = call('apiSetCode')
check('set 202 echoes platform requestId (lower-cased)', setResp.status == 202 && body(setResp).requestId == R1)
check('set response has no code', !setResp.data.contains('90903333') && !body(setResp).containsKey('code'))
check('pending kept with code in state', pendingFor(3)?.code == '90903333' && pendingFor(3).attempts == 1 && pendingFor(3).requestId == R1)
check('first check scheduled at 30s', verifyJobs()*.secs == [30])
check('expiry scheduled ~24h', app.scheduled.find { it.handler == 'expirePendingCodeChanges' }?.secs >= 86399)
tick()
def r1 = results()[-1]
check('confirmed result event', r1.name == 'lock_code_result' && r1.value == 'confirmed' && r1.deviceId == '9')
check('confirmed payload', r1.d == [lockId: '9', slot: 3, action: 'set', result: 'confirmed', attempts: 1, requestId: R1, name: 'SBA: Cleaner'])
check('confirmed change cleared', pendingFor(3) == null && !verifyJobs())
check('result sent to ingest URL with ingestion token', app.posts[-1].params.uri == 'https://ingest.test/v1/webhooks/hubitat' && app.posts[-1].params.headers.Authorization == 'Bearer ingest-fake')

// Request ids
app.request = [JSON: [slot: 3, code: '90903334', name: 'SBA: Cleaner', requestId: 'not-a-uuid']]
check('invalid requestId -> 400', call('apiSetCode').status == 400)
app.request = [JSON: [slot: 3, code: '90903334', name: 'SBA: Cleaner']]
def genResp = call('apiSetCode')
check('missing requestId -> hub UUID', body(genResp).requestId ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)
tick()
check('changed code under the same name confirmed by code match', results()[-1].value == 'confirmed')
app.request = [JSON: [slot: 3, code: '90903335', name: 'SBA: Cleaner']]
lock3.dropCommands = 1
call('apiSetCode')
tick()
check('same name but old code is not confirmed', results()[-1].value == 'confirmed' && results().size() == 2 && pendingFor(3)?.status == 'pending')
tick(); tick()
check('...then confirmed after re-send', results()[-1].d.attempts == 2 && results()[-1].value == 'confirmed')

// Retry then confirmed, with backoff
lock3.dropCommands = 1
app.request = [JSON: [slot: 4, code: '90904444', name: 'SBA: Plumber']]
call('apiSetCode')
int n = results().size()
tick()
check('not applied at 30s: final check at 120s', pendingFor(4).check == 2 && verifyJobs()*.secs == [90] && results().size() == n)
tick()
check('re-sent after attempt 1', lock3.calls.count { it == ['setCode', 4, 'SBA: Plumber'] } == 2 && pendingFor(4).attempts == 2)
tick()
check('confirmed on attempt 2', results()[-1].value == 'confirmed' && results()[-1].d.attempts == 2 && pendingFor(4) == null)

// Failed after max attempts, backoff on later attempts
lock3.dropCommands = 99
app.request = [JSON: [slot: 5, code: '90905555', name: 'SBA: Painter']]
call('apiSetCode')
List finalDelays = []
while (true) {
    def job = tick()
    if (!job) break
    if (pendingFor(5)?.status == 'pending' && pendingFor(5).check == 2) finalDelays << verifyJobs()[0].secs
}
def failed = results()[-1]
check('failed after 3 attempts', failed.value == 'failed' && failed.d.attempts == 3 && failed.d.reason == 'not_applied')
check('exactly 3 sends', lock3.calls.count { it == ['setCode', 5, 'SBA: Painter'] } == 3)
check('final checks back off (120s, 150s, 180s after send)', finalDelays == [90, 120, 150])
check('failed change kept for retry', pendingFor(5)?.status == 'failed' && pendingFor(5).code == '90905555')

// Retry endpoint
lock3.dropCommands = 0
app.params = [lockId: '9', slot: '5']
app.request = [JSON: [:]]
def retryResp = call('apiRetryCode')
check('retry 202 with same requestId', retryResp.status == 202 && body(retryResp).requestId == failed.d.requestId && body(retryResp).action == 'set')
check('retry response has no code', !retryResp.data.contains('90905555'))
check('retry resets attempts', pendingFor(5).attempts == 1 && pendingFor(5).status == 'pending')
tick()
check('retry confirmed', results()[-1].value == 'confirmed' && results()[-1].d.attempts == 1 && pendingFor(5) == null)
check('retry with nothing pending -> 404', call('apiRetryCode').status == 404)
app.params = [lockId: '9', slot: '9']
check('retry on a never-changed slot -> 404', call('apiRetryCode').status == 404)
app.params = [lockId: '9', slot: 'x']
check('retry invalid slot -> 400', call('apiRetryCode').status == 400)
app.state.retryTimes = []
app.params = [lockId: '9', slot: '9']
(1..10).each { call('apiRetryCode') }
check('retry rate-limited (10/min)', call('apiRetryCode').status == 429)
app.state.retryTimes = []

// Auto-retry stops once the slot is no longer allowed
lock3.dropCommands = 99
app.params = [lockId: '9']
app.request = [JSON: [slot: 6, code: '90906666', name: 'SBA: Guard']]
call('apiSetCode')
tick()
app.request = [JSON: [admin: [from: 1, to: 5], managed: [from: 11, to: 25], locks: ['9': [adopted: [20]]]]]
app.apiSetLockConfig()
check('narrowed rules: in-flight change reported failed', results()[-1].value == 'failed' && results()[-1].d.reason == 'slot_not_allowed' && results()[-1].d.attempts == 1)
check('narrowed rules: change (and code) dropped', pendingFor(6) == null)
tick()
check('only one send', lock3.calls.count { it[0] == 'setCode' && it[1] == 6 } == 1)
app.params = [lockId: '9', slot: '6']
check('retry after narrowing -> 404', call('apiRetryCode').status == 404)
app.request = [JSON: cfg9]
app.apiSetLockConfig()

// Superseded (set by set, then set by delete)
String RA = 'aaaaaaaa-0000-4000-8000-000000000001'
String RB = 'bbbbbbbb-0000-4000-8000-000000000002'
String RC = 'cccccccc-0000-4000-8000-000000000003'
lock3.dropCommands = 0
lock3.applyCommands = false
app.params = [lockId: '9']
app.request = [JSON: [slot: 7, code: '90907777', name: 'SBM: First', requestId: RA]]
check('refused request creates no pending change', call('apiSetCode').status == 403 && pendingFor(7) == null)
app.request = [JSON: [slot: 7, code: '90907777', name: 'SBA: First', requestId: RA]]
call('apiSetCode')
app.request = [JSON: [slot: 7, code: '90907778', name: 'SBA: Second', requestId: RB]]
call('apiSetCode')
def sup = results()[-1]
check('superseded result for the old request', sup.value == 'superseded' && sup.d.requestId == RA && sup.d.slot == 7 && sup.d.attempts == 1)
check('new request is the pending one', pendingFor(7).requestId == RB && pendingFor(7).code == '90907778')
lock3.applyCommands = true
int before7 = results().size()
tick()
check('stale check for superseded request does nothing', results().size() == before7)
lock3.attrs.lockCodes = groovy.json.JsonOutput.toJson((new JsonSlurper().parseText(lock3.attrs.lockCodes) as Map) + ['7': [name: 'SBA: Second', code: '90907778']])
app.params = [lockId: '9', slot: '7', requestId: RC]
app.request = [:]
def delResp = call('apiDeleteCode')
check('delete 202 echoes query requestId', delResp.status == 202 && body(delResp).requestId == RC)
check('delete supersedes the set', results()[-1].value == 'superseded' && results()[-1].d.requestId == RB)
check('delete pending holds no code', pendingFor(7).action == 'delete' && !pendingFor(7).containsKey('code'))
while (tick()) {}
def del = results()[-1]
check('delete confirmed', del.value == 'confirmed' && del.d.action == 'delete' && del.d.requestId == RC && del.d.name == 'SBA: Second')
app.params = [lockId: '9', slot: '7', requestId: 'zzz']
check('delete invalid requestId -> 400', call('apiDeleteCode').status == 400)

// Adopted slot: name not SBA:/SBM:, so not reported
app.params = [lockId: '9', slot: '20']
call('apiDeleteCode')
tick()
check('adopted delete confirmed without name', results()[-1].value == 'confirmed' && !results()[-1].d.containsKey('name'))

// Initialize (Done) reschedules in-flight checks
lock3.dropCommands = 99
app.params = [lockId: '9']
app.request = [JSON: [slot: 8, code: '90908888', name: 'SBA: Resume']]
call('apiSetCode')
app.initialize()
check('initialize resumes verification', verifyJobs()*.secs == [30] && pendingFor(8).check == 1 && app.scheduled.any { it.handler == 'expirePendingCodeChanges' })

// Expiry: a failed change goes after 24h; one still in flight is reported failed
while (tick()) {}
check('slot 8 failed', pendingFor(8)?.status == 'failed')
app.request = [JSON: [slot: 9, code: '90909999', name: 'SBA: Late']]
call('apiSetCode')
app.fakeNow += 24L * 60 * 60 * 1000 + 1000
app.expirePendingCodeChanges()
check('expired in-flight change reported failed', results()[-1].value == 'failed' && results()[-1].d.reason == 'expired' && results()[-1].d.slot == 9)
check('expired changes (and codes) cleared', app.state.pendingCodeChanges == null && !app.scheduled.any { it.handler == 'expirePendingCodeChanges' })
app.params = [lockId: '9', slot: '8']
check('retry after expiry -> 404', call('apiRetryCode').status == 404)

// Slot taken over since the request (#520 review): no re-send, change dropped
def putSlot = { Integer slot, String name, def code ->
    Map m = new JsonSlurper().parseText(lock3.attrs.lockCodes) as Map
    if (name == null) m.remove(slot.toString()) else m[slot.toString()] = [name: name, code: code]
    lock3.attrs.lockCodes = groovy.json.JsonOutput.toJson(m)
}
app.state.writeTimes = []
lock3.dropCommands = 99
app.params = [lockId: '9']
app.request = [JSON: [slot: 11, code: '91911111', name: 'SBM: Temp']]
call('apiSetCode')
tick()
putSlot(11, 'SBM: Other', '91919999')
tick()
check('set: slot taken by another name -> failed slot_changed', results()[-1].value == 'failed' && results()[-1].d.reason == 'slot_changed' && results()[-1].d.slot == 11)
check('set: not re-sent, change dropped', lock3.calls.count { it[0] == 'setCode' && it[1] == 11 } == 1 && pendingFor(11) == null)
putSlot(12, 'SBM: Old', '91912222')
app.params = [lockId: '9', slot: '12']
call('apiDeleteCode')
tick()
putSlot(12, 'SBM: New', '91912223')
tick()
check('delete: slot renamed since request -> failed slot_changed', results()[-1].d.reason == 'slot_changed' && results()[-1].d.action == 'delete' && pendingFor(12) == null)
check('delete: not re-sent', lock3.calls.count { it == ['deleteCode', 12] } == 1)
// Set over an existing SBM: code: the slot may still hold its earlier name
putSlot(14, 'SBM: Before', '91914440')
lock3.dropCommands = 1
app.params = [lockId: '9']
app.request = [JSON: [slot: 14, code: '91914444', name: 'SBM: After']]
call('apiSetCode')
tick(); tick(); tick()
check('set: slot still holding its earlier name is re-sent and confirmed', results()[-1].value == 'confirmed' && results()[-1].d.attempts == 2)
// Retry endpoint refuses (and drops) a failed change whose slot changed
lock3.dropCommands = 99
app.request = [JSON: [slot: 13, code: '91913333', name: 'SBM: Retry']]
call('apiSetCode')
while (tick()) {}
check('slot 13 failed and kept', pendingFor(13)?.status == 'failed')
putSlot(13, 'SBM: Someone', '91913334')
int nRes = results().size()
app.params = [lockId: '9', slot: '13']
def chResp = call('apiRetryCode')
check('retry on changed slot -> 409 slot_changed', chResp.status == 409 && body(chResp).error == 'slot_changed')
check('retry on changed slot: dropped, failure not re-reported', pendingFor(13) == null && results().size() == nRes)
lock3.dropCommands = 0

// Lock removed from lock code management: failed and dropped
lock3.dropCommands = 99
app.params = [lockId: '9']
app.request = [JSON: [slot: 15, code: '91915555', name: 'SBM: Gone']]
call('apiSetCode')
app.codeLocks = [lock, lock2]
tick()
check('lock no longer selected -> failed lock_not_found, dropped', results()[-1].d.reason == 'lock_not_found' && pendingFor(15) == null)
app.codeLocks = [lock, lock2, lock3]
lock3.dropCommands = 0

// Cap on pending changes
Map full = [:]
(1..99).each { full["9:${200 + it}".toString()] = [lockId: '9', slot: 200 + it, action: 'delete', requestId: R1, attempts: 3, createdAt: app.now(), status: 'failed', seq: 1] }
full['9:16'] = [lockId: '9', slot: 16, action: 'delete', requestId: R1, attempts: 3, createdAt: app.now(), status: 'failed', seq: 1]
app.state.pendingCodeChanges = full
app.request = [JSON: [slot: 17, code: '91917777', name: 'SBM: Cap']]
def capResp = call('apiSetCode')
check('100 pending -> 429 too_many_pending', capResp.status == 429 && body(capResp).error == 'too_many_pending')
app.request = [JSON: [slot: 16, code: '91916666', name: 'SBM: Replace']]
check('replacing a pending slot is allowed at the cap', call('apiSetCode').status == 202 && pendingFor(16).code == '91916666')
app.state.remove('pendingCodeChanges')
app.scheduled.clear()

// Write rate limit (30/min, separate from reveal/retry)
lock3.applyCommands = false
app.state.writeTimes = []
app.request = [JSON: [slot: 17, code: '91917777', name: 'SBM: Rate']]
(1..30).each { call('apiSetCode') }
check('31st set in a minute -> 429', call('apiSetCode').status == 429)
check('one pending change and one check job per slot after 30 sets', app.state.pendingCodeChanges.size() == 1 && verifyJobs().size() == 1)
app.params = [lockId: '9', slot: '17']
check('retry has its own bucket', call('apiRetryCode').status == 202)
app.fakeNow += 61000L
app.params = [lockId: '9']
check('write limit resets after a minute', call('apiSetCode').status == 202)
app.state.remove('pendingCodeChanges')
app.scheduled.clear()
lock3.applyCommands = true

// An unnamed code appearing in a slot that was empty is not ours (adopted
// slot 20, where no SBA:/SBM: marker rule applies)
lock3.dropCommands = 99
app.params = [lockId: '9']
app.request = [JSON: [slot: 20, code: '91922222', name: 'Fresh Legacy']]
call('apiSetCode')
check('empty slot: prevName is null', pendingFor(20).prevName == null)
tick()
lock3.attrs.lockCodes = groovy.json.JsonOutput.toJson((new JsonSlurper().parseText(lock3.attrs.lockCodes) as Map) + ['20': [code: '91922229']])
tick()
check('unnamed before and now -> slot_unnamed, not re-sent', results()[-1].d.reason == 'slot_unnamed' && results()[-1].d.slot == 20 && lock3.calls.count { it[0] == 'setCode' && it[1] == 20 } == 1)
putSlot(20, null, null)

// A driver that throws on re-send fails only that change, never logging the message
app.request = [JSON: [slot: 19, code: '91919191', name: 'SBM: Throw']]
call('apiSetCode')
app.request = [JSON: [slot: 21, code: '91912121', name: 'SBM: Bystander']]
call('apiSetCode')
tick()
lock3.throwOnSet = true
app.logged.clear()
tick()
lock3.throwOnSet = false
def thrown = results().find { it.d.slot == 19 && it.d.reason == 'send_error' }
check('driver exception -> failed send_error, dropped', thrown != null && thrown.value == 'failed' && pendingFor(19) == null)
check('exception logged by class name only', app.logged.any { it.toString().contains('IllegalStateException') } && !app.logged.any { it.toString().contains('91919191') || it.toString().contains('failed)') && it.toString().contains('setCode(') })
check('other due change also handled and job rescheduled', results().any { it.d.slot == 21 && it.d.reason == 'send_error' } || (pendingFor(21) != null && verifyJobs().size() == 1))
app.state.remove('pendingCodeChanges'); app.scheduled.clear()

// Both the check and its cleanup throw: the change is still dropped, nothing rethrown
lock3.dropCommands = 99
app.request = [JSON: [slot: 19, code: '91919192', name: 'SBM: Twice']]
call('apiSetCode')
tick()
lock3.throwOnSet = true
app.throwOnPost = true
app.logged.clear()
boolean rethrown = false
try { tick() } catch (Exception e) { rethrown = true }
lock3.throwOnSet = false
app.throwOnPost = false
check('double failure: not rethrown, change (and code) dropped', !rethrown && pendingFor(19) == null && !verifyJobs())
check('double failure: logs key + class names only', app.logged.any { it.toString().contains('9:19') && it.toString().contains('IllegalArgumentException') } && !app.logged.any { it.toString().contains('91919192') || it.toString().contains('post failed') })

// Done/update drops changes for locks no longer selected
lock3.dropCommands = 99
app.request = [JSON: [slot: 23, code: '91923232', name: 'SBM: Kept']]
call('apiSetCode')
while (tick()) {}
check('slot 23 failed and kept', pendingFor(23)?.status == 'failed')
app.request = [JSON: [slot: 24, code: '91924242', name: 'SBM: Flight']]
call('apiSetCode')
int beforeSweep = results().size()
app.codeLocks = [lock, lock2]
app.initialize()
def swept = results().drop(beforeSweep)
check('sweep: both changes dropped', app.state.pendingCodeChanges == null && !verifyJobs())
check('sweep: only the in-flight one reported (lock_not_found)', swept.size() == 1 && swept[0].d.slot == 24 && swept[0].d.reason == 'lock_not_found')
app.codeLocks = [lock, lock2, lock3]
lock3.dropCommands = 0

// Code comparison: strings, numbers only as the driver reported them
check('sameCode: string match', app.sameCode('01234', '01234'))
check('sameCode: string leading zero differs', !app.sameCode('1234', '01234'))
check('sameCode: numeric report vs leading-zero code', app.sameCode(1234, '01234'))
check('sameCode: numeric report exact', app.sameCode(1234, '1234') && !app.sameCode(12345, '1234'))
app.params = [lockId: '9']
app.request = [JSON: [slot: 18, code: '01818', name: 'SBM: Zero']]
call('apiSetCode')
putSlot(18, 'SBM: Zero', 1818)
tick()
check('numeric lockCodes value confirms a leading-zero code', results()[-1].value == 'confirmed' && results()[-1].d.slot == 18)
app.fakeNow = null

// Disconnect drops pending changes too
app.params = [lockId: '9']
app.request = [JSON: [slot: 10, code: '90901010', name: 'SBA: Bye']]
call('apiSetCode')
app.appButtonHandler('btnDisconnect')
check('disconnect reports and clears', results()[-1].d.reason == 'disconnected' && app.state.pendingCodeChanges == null)

// No code anywhere outside app state
List codes = ['91919192', '91922222', '91922229', '91919191', '91912121', '91923232', '91924242', '91911111', '91913333', '91914444', '91915555', '91916666', '91917777', '01818', '90903333', '90903334', '90903335', '90904444', '90905555', '90906666', '90907777', '90907778', '90908888', '90909999', '90901010']
String everything = (app.posts.collect { it.params.body } + app.logged.collect { it.toString() } + responses.collect { it.data } + app.scheduled.collect { it.data?.toString() }).join('\n')
check('no code in any event, log, response or scheduled job', !codes.any { everything.contains(it) })
check('result events carry only contract keys', results().every { (it.d.keySet() - ['lockId', 'slot', 'action', 'result', 'attempts', 'reason', 'requestId', 'name']).isEmpty() })

// --- Fingerprints (signalbricks/platform#532) ---
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.approvedAdoptedSlots = null
app.maxManagedSlot = null
app.posts.clear(); app.logged.clear(); app.scheduled.clear()
List fpResponses = []
def fcall = { String handler -> def r = app."${handler}"(); fpResponses << r; r }
byte[] keyBytes = (1..32).collect { (byte) (it * 7) } as byte[]
String KEY = keyBytes.encodeBase64().toString()
byte[] key2Bytes = (1..32).collect { (byte) (255 - it) } as byte[]
String KEY2 = key2Bytes.encodeBase64().toString()
// Independent RFC 2104 HMAC-SHA256 (MessageDigest only), first 16 hex chars
def hmac16 = { byte[] k, String msg ->
    def sha = { byte[] b -> java.security.MessageDigest.getInstance('SHA-256').digest(b) }
    byte[] kk = new byte[64]
    System.arraycopy(k.length > 64 ? sha(k) : k, 0, kk, 0, Math.min(k.length > 64 ? 32 : k.length, 64))
    byte[] ipad = kk.collect { (byte) (it ^ 0x36) } as byte[]
    byte[] opad = kk.collect { (byte) (it ^ 0x5c) } as byte[]
    sha((opad as List) + (sha(((ipad as List) + (msg.getBytes('UTF-8') as List)) as byte[]) as List) as byte[]).encodeHex().toString().substring(0, 16)
}
check('fp: RFC 4231 case 2 vector', app.codeFingerprint('Jefe'.getBytes('UTF-8'), 'what do ya want for nothing?') == '5bdcc146bf60754e')
check('fp: matches independent HMAC', app.codeFingerprint(keyBytes, '12340001') == hmac16(keyBytes, '12340001'))

def lockA = new FakeLock(id: '10', displayName: 'FpA', name: 'FpA', label: 'FpA')
lockA.attrs.maxCodes = 250
lockA.attrs.lockCodes = groovy.json.JsonOutput.toJson([
    '1': [name: 'SBA: Owner', code: '12340001'],
    '2': [name: 'Old', code: '55550002'],          // unmarked in admin range: included
    '3': [name: 'SBA: ORB5', code: '55550003'],     // booking-guard name: excluded
    '4': [name: 'SBA: Empty', code: ''],            // empty: excluded
    '12': [name: 'SBM: Cleaner', code: '12340012'],
    '26': [name: 'Above', code: '55550026'],        // above ceiling, not adopted: excluded
    '30': [name: 'ORB123', code: '55550030'],       // booking, adopted+approved: excluded
    '40': [name: 'Legacy', code: '55550040'],       // adopted, not approved: excluded
    '41': [name: 'Legacy 2', code: '12340001'],     // adopted + approved: included
])
def lockB = new FakeLock(id: '11', displayName: 'FpB', name: 'FpB', label: 'FpB')
lockB.attrs.maxCodes = 250
lockB.attrs.lockCodes = groovy.json.JsonOutput.toJson([
    '1': [name: 'Owner renamed', code: '12340001'],
    '12': [name: 'SBM: Cleaner', code: '99990012'],
    '13': [name: 'SBM: Numeric', code: 12340012],
])
def lockC = new FakeLock(id: '12', displayName: 'FpC', name: 'FpC', label: 'FpC')
lockC.attrs.lockCodes = groovy.json.JsonOutput.toJson(['1': [name: 'SBA: Owner', code: '12340001']])
app.codeLocks = [lockA, lockB, lockC]
List fpCodes = ['12340001', '55550002', '55550003', '12340012', '55550026', '55550030', '55550040', '99990012']
Map fpCfg = [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['10': [adopted: [30, 40, 41]], '11': [:]]]
app.state.remove('fingerprintKey'); app.state.fingerprintTimes = []

app.params = [lockId: '10']
app.request = [JSON: fpCfg]
check('fp: config without key accepted', fcall('apiSetLockConfig').status == 200)
def missing = fcall('apiListFingerprints')
check('fp: no key -> 409 fingerprint_key_missing', missing.status == 409 && body(missing).error == 'fingerprint_key_missing')
check('fp: GET lockConfig says no key', body(fcall('apiGetLockConfig')).fingerprintKeySet == false)
[['bad', 'not base64'], [(new byte[16]).encodeBase64().toString(), '16 bytes'], [(new byte[33]).encodeBase64().toString(), '33 bytes'],
 [KEY.replace('=', ''), 'unpadded'], [' ' + KEY, 'leading space'], [KEY + '\n', 'trailing newline'], [KEY.substring(0, 42) + '_=', 'base64url char'], [12345, 'number']].each { k, why ->
    app.request = [JSON: fpCfg + [fingerprintKey: k]]
    def r = fcall('apiSetLockConfig')
    check("fp: invalid key (${why}) -> 400, nothing stored", r.status == 400 && body(r).error == 'invalid_config' && app.state.fingerprintKey == null && !r.data.contains(k.toString()))
}
app.request = [JSON: fpCfg + [fingerprintKey: KEY, locks: ['99': [:]]]]
check('fp: invalid config with valid key -> 400, key not stored', fcall('apiSetLockConfig').status == 400 && app.state.fingerprintKey == null)
app.request = [JSON: fpCfg + [fingerprintKey: KEY]]
def setKey = fcall('apiSetLockConfig')
check('fp: key accepted, stored, not echoed', setKey.status == 200 && app.state.fingerprintKey == KEY && !setKey.data.contains(KEY) && body(setKey).fingerprintKeySet == true && !body(setKey).config.containsKey('fingerprintKey'))
def getCfg = fcall('apiGetLockConfig')
check('fp: GET lockConfig never returns the key', !getCfg.data.contains(KEY) && !getCfg.data.contains('"fingerprintKey"') && body(getCfg).fingerprintKeySet == true)
app.request = [JSON: fpCfg]
fcall('apiSetLockConfig')
check('fp: POST without key keeps it', app.state.fingerprintKey == KEY)
app.request = [JSON: fpCfg + [fingerprintKey: null]]
fcall('apiSetLockConfig')
check('fp: POST with null key keeps it', app.state.fingerprintKey == KEY)
app.approvedAdoptedSlots = ['10:30', '10:41']

def fpA = fcall('apiListFingerprints')
def slotsA = body(fpA).slots
check('fp: 200 {lockId, slots}', fpA.status == 200 && body(fpA).lockId == '10' && body(fpA).keySet() == ['lockId', 'slots'] as Set)
check('fp: allowed occupied slots only (no booking, empty, unapproved, above ceiling)', slotsA*.slot == [1, 2, 12, 41])
check('fp: each entry is {slot, fp} with 16 lower-case hex', slotsA.every { it.keySet() == ['slot', 'fp'] as Set && it.fp ==~ /[0-9a-f]{16}/ })
check('fp: value is HMAC-SHA256(key, code) truncated', slotsA.find { it.slot == 1 }.fp == hmac16(keyBytes, '12340001'))
check('fp: same code, same lock -> same fp', slotsA.find { it.slot == 1 }.fp == slotsA.find { it.slot == 41 }.fp)
check('fp: different codes -> different fp', slotsA*.fp.unique().size() == 3)
app.params = [lockId: '11']
def slotsB = body(fcall('apiListFingerprints')).slots
check('fp: same code across locks -> same fp (name ignored)', slotsB.find { it.slot == 1 }.fp == slotsA.find { it.slot == 1 }.fp)
check('fp: different code in same slot on another lock -> different fp', slotsB.find { it.slot == 12 }.fp != slotsA.find { it.slot == 12 }.fp)
check('fp: numeric lockCodes value fingerprints as its digits', slotsB.find { it.slot == 13 }.fp == slotsA.find { it.slot == 12 }.fp)
app.params = [lockId: '12']
def fpC = fcall('apiListFingerprints')
check('fp: un-listed lock -> no slots', fpC.status == 200 && body(fpC).slots == [])
app.params = [lockId: '999']
check('fp: unknown lock -> 404', fcall('apiListFingerprints').status == 404)
app.params = [lockId: '10']
app.approvedAdoptedSlots = null
check('fp: approval withdrawn -> adopted slot above ceiling dropped', body(fcall('apiListFingerprints')).slots*.slot == [1, 2, 12])
app.approvedAdoptedSlots = ['10:30', '10:41']
String savedCodes = lockA.attrs.lockCodes
lockA.attrs.lockCodes = 'ENCRYPTEDBLOB'
def unread = fcall('apiListFingerprints')
check('fp: unreadable lockCodes -> 409 lock_codes_unreadable', unread.status == 409 && body(unread).error == 'lock_codes_unreadable')
lockA.attrs.lockCodes = savedCodes

// Rotating the key changes every fingerprint
app.state.fingerprintTimes = []
app.request = [JSON: fpCfg + [fingerprintKey: KEY2]]
fcall('apiSetLockConfig')
def slotsA2 = body(fcall('apiListFingerprints')).slots
check('fp: new key replaces old', app.state.fingerprintKey == KEY2 && slotsA2.find { it.slot == 1 }.fp == hmac16(key2Bytes, '12340001') && slotsA2.find { it.slot == 1 }.fp != slotsA.find { it.slot == 1 }.fp)

// Rate limit: own bucket of 10/min
app.state.fingerprintTimes = []
app.state.revealTimes = []
(1..10).each { fcall('apiListFingerprints') }
def limited = fcall('apiListFingerprints')
check('fp: 11th call in a minute -> 429', limited.status == 429 && body(limited).error == 'rate_limited')
app.params = [lockId: '10', slot: '1']
check('fp: reveal has its own bucket', app.apiRevealCode().status == 200)
app.state.fingerprintTimes = []

// Survey-only locks: listed for reading (slots, fingerprints), never written
def lockD = new FakeLock(id: '13', displayName: 'FpD', name: 'FpD', label: 'FpD')
lockD.attrs.maxCodes = 250
lockD.attrs.lockCodes = groovy.json.JsonOutput.toJson([
    '0': [name: 'Zero', code: '55550000'],
    '1': [name: 'Old', code: '12340001'],
    '2': [name: 'Someone', code: '55550002'],
    '5': [name: 'ORB9-Guest', code: '55550005'],
    '6': [name: 'Empty', code: ''],
    '7': [name: 'GT:ORB7-x', code: '55550007'],
    '8': [name: 'G:ORB1-x', code: '55550008'],
    '9': [name: 'Name -ORB123', code: '55550009'],
    '10': [name: 'Corbin', code: '55550010'],
    '12': [name: 'SBM: Cleaner', code: '12340012'],
    '30': [name: 'Above', code: '55550030'],
])
app.codeLocks = [lockA, lockB, lockC, lockD]
fpCodes += ['55550000', '55550005', '55550007', '55550008', '55550009', '55550010', '91930003', '91930004']
app.state.fingerprintTimes = []; app.state.writeTimes = []; app.state.revealTimes = []; app.state.retryTimes = []
[[surveyOnly: true, adopted: [1]], [surveyOnly: true, admin: [from: 1, to: 10]], [surveyOnly: 'yes'], [surveyOnly: 1]].each { e ->
    app.request = [JSON: fpCfg + [locks: fpCfg.locks + ['13': e]]]
    check("survey: invalid entry ${e} -> 400", fcall('apiSetLockConfig').status == 400 && app.state.lockConfig.locks['13'] == null)
}
// A normal lock with a change in flight, then switched to survey-only
app.request = [JSON: fpCfg + [locks: fpCfg.locks + ['13': [:]]]]
fcall('apiSetLockConfig')
app.params = [lockId: '13']
app.request = [JSON: [slot: 3, code: '91930003', name: 'SBA: Pending']]
check('survey: normal lock accepts a set', fcall('apiSetCode').status == 202 && app.state.pendingCodeChanges['13:3'])
int dSets = lockD.calls.size()
Map surveyCfg = fpCfg + [locks: fpCfg.locks + ['13': [surveyOnly: true]]]
app.request = [JSON: surveyCfg]
def sv = fcall('apiSetLockConfig')
check('survey: entry accepted and stored as {surveyOnly: true}', sv.status == 200 && body(sv).config.locks['13'] == [surveyOnly: true])
check('survey: switching drops the in-flight change, reported failed', app.state.pendingCodeChanges?.get('13:3') == null && results().any { it.d.lockId == '13' && it.d.slot == 3 && it.d.reason == 'slot_not_allowed' })
app.verifyCodeChanges()
check('survey: verify sends nothing to it', lockD.calls.size() == dSets)
def svSlots = fcall('apiListSlots')
check('survey: /slots readable, surveyOnly flag, nothing allowed', svSlots.status == 200 && body(svSlots).surveyOnly == true && body(svSlots).slots.every { it.allowedAs == null } && !svSlots.data.contains('12340001'))
app.params = [lockId: '10']
check('survey: other locks report surveyOnly false', body(fcall('apiListSlots')).surveyOnly == false)
app.params = [lockId: '13']
def svFp = fcall('apiListFingerprints')
check('survey: fingerprints for occupied in-ceiling slots, no booking/empty/above', svFp.status == 200 && body(svFp).slots*.slot == [1, 2, 10, 12])
check('survey: GT:ORB / G:ORB / -ORB<digit> names excluded, Corbin included', !(body(svFp).slots*.slot).any { it in [5, 7, 8, 9] } && 10 in body(svFp).slots*.slot)
check('survey: info log names lock and count, no fp or code', app.logged.any { it.toString() == 'SignalBricks Telemetry: fingerprints listed for FpD (4 slots)' } && !app.logged.any { it.toString().contains(body(svFp).slots[0].fp) || it.toString().contains('12340001') })
check('survey: fp comparable with onboarded locks', body(svFp).slots.find { it.slot == 1 }.fp == hmac16(key2Bytes, '12340001'))
app.request = [JSON: [slot: 2, code: '91930004', name: 'SBA: Take']]
def svSet = fcall('apiSetCode')
check('survey: set on occupied slot -> 403 slot_not_allowed', svSet.status == 403 && body(svSet).error == 'slot_not_allowed')
app.request = [JSON: [slot: 3, code: '91930004', name: 'SBA: New']]
check('survey: set on empty slot -> 403', fcall('apiSetCode').status == 403)
app.request = [JSON: [slot: 12, code: '91930004', name: 'SBM: Cleaner']]
check('survey: set over SBM: code -> 403', fcall('apiSetCode').status == 403)
app.params = [lockId: '13', slot: '12']
app.request = [:]
check('survey: delete -> 403', body(fcall('apiDeleteCode')).error == 'slot_not_allowed')
def svRev = app.apiRevealCode()
check('survey: reveal -> 403, no code', svRev.status == 403 && !svRev.data.contains('12340012'))
// A held failed change (e.g. from before the switch) can't be retried
app.state.pendingCodeChanges = ['13:12': [lockId: '13', slot: 12, action: 'set', name: 'SBM: Cleaner', prevName: 'SBM: Cleaner', code: '91930004', requestId: R1, attempts: 3, createdAt: app.now(), status: 'failed', seq: 1]]
def svRetry = fcall('apiRetryCode')
check('survey: retry -> 403 and dropped, nothing sent', svRetry.status == 403 && app.state.pendingCodeChanges == null && lockD.calls.size() == dSets)
// Onboarding replaces the entry with a normal one: writes allowed again
app.request = [JSON: fpCfg + [locks: fpCfg.locks + ['13': [:]]]]
fcall('apiSetLockConfig')
app.params = [lockId: '13']
check('survey: switched to normal -> slots allowed', body(fcall('apiListSlots')).slots.find { it.slot == 12 }.allowedAs == 'managed' && body(fcall('apiListSlots')).surveyOnly == false)
app.request = [JSON: [slot: 3, code: '91930003', name: 'SBA: Onboarded']]
check('survey: switched to normal -> set ok', fcall('apiSetCode').status == 202)
check('survey: normal lock fingerprints follow the ranges', body(fcall('apiListFingerprints')).slots*.slot == [1, 2, 10, 12])
app.state.remove('pendingCodeChanges'); app.scheduled.clear()
app.codeLocks = [lockA, lockB, lockC]
app.request = [JSON: fpCfg]
fcall('apiSetLockConfig')
app.state.fingerprintTimes = []

// --- Batch fingerprints (#17): GET /fingerprints[?ids=] ---
// Same rules as the per-lock endpoint: compare against it lock by lock,
// including a survey-only lock and an un-listed one.
app.codeLocks = [lockA, lockB, lockC, lockD]
app.request = [JSON: fpCfg + [locks: fpCfg.locks + ['13': [surveyOnly: true]]]]
fcall('apiSetLockConfig')
app.state.fingerprintTimes = []; app.state.fingerprintBatchTimes = []
def batch = { Map p = [:] -> app.params = p; app.state.fingerprintBatchTimes = []; fcall('apiListFingerprintsBatch') }
def perLock = { String id -> app.params = [lockId: id]; app.state.fingerprintTimes = []; body(fcall('apiListFingerprints')).slots }
def all = batch()
List allLocks = body(all).locks
check('batch: 200 {locks} in selection order', all.status == 200 && body(all).keySet() == ['locks'] as Set && allLocks*.id == ['10', '11', '12', '13'])
check('batch: each entry is {id, slots}', allLocks.every { it.keySet() == ['id', 'slots'] as Set })
check('batch: rules parity with per-lock endpoint (ranges, adopted+approved, un-listed, survey-only)',
    ['10', '11', '12', '13'].every { id -> allLocks.find { it.id == id }.slots == perLock(id) })
check('batch: expected slots per lock', allLocks*.slots.collect { it*.slot } == [[1, 2, 12, 41], [1, 12, 13], [], [1, 2, 10, 12]])
check('batch: same code across locks -> same fp', allLocks[0].slots.find { it.slot == 1 }.fp == allLocks[3].slots.find { it.slot == 1 }.fp && allLocks[0].slots[0].fp == hmac16(key2Bytes, '12340001'))
app.approvedAdoptedSlots = null
check('batch: approval withdrawn -> adopted slot above ceiling dropped', body(batch()).locks[0].slots*.slot == [1, 2, 12])
app.approvedAdoptedSlots = ['10:30', '10:41']
check('batch: info log names counts only', app.logged.any { it.toString() == 'SignalBricks Telemetry: fingerprints listed for 4 locks (10 slots)' })

// ids filter
check('batch: ids filter keeps the requested order', body(batch(ids: '11,10')).locks*.id == ['11', '10'])
check('batch: ids with spaces and duplicates', body(batch(ids: ' 13 , 13,10')).locks*.id == ['13', '10'])
check('batch: ids as a list', body(batch(ids: ['12', '11'])).locks*.id == ['12', '11'])
def unknown = batch(ids: '10,999')
check('batch: unselected id -> per-lock lock_not_found, call still 200', unknown.status == 200 && body(unknown).locks == [[id: '10', slots: allLocks[0].slots], [id: '999', error: 'lock_not_found']])
['', 'abc', '10,,11', '10;11', '-1', '1234567890123', (1..101).join(',')].each { ids ->
    def r = batch(ids: ids)
    check("batch: invalid ids '${ids.size() > 20 ? ids.take(20) + '...' : ids}' -> 400 invalid_ids", r.status == 400 && body(r).error == 'invalid_ids')
}
check('batch: 100 ids accepted', batch(ids: (1..100).join(',')).status == 200)

// A lock whose codes can't be read fails on its own, not the whole call
String savedB = lockB.attrs.lockCodes
lockB.attrs.lockCodes = 'ENCRYPTEDBLOB'
def partial = batch()
List partialLocks = body(partial).locks
check('batch: unreadable lock -> its entry has lock_codes_unreadable, call 200', partial.status == 200 &&
    partialLocks.find { it.id == '11' } == [id: '11', error: 'lock_codes_unreadable', message: 'lockCodes could not be read or decrypted'])
check('batch: other locks still listed', partialLocks*.id == ['10', '11', '12', '13'] && partialLocks.find { it.id == '10' }.slots == allLocks[0].slots && partialLocks.find { it.id == '13' }.slots == allLocks[3].slots)
app.params = [lockId: '11']; app.state.fingerprintTimes = []
check('batch: per-lock endpoint still 409 for that lock', fcall('apiListFingerprints').status == 409)
lockB.attrs.lockCodes = savedB

// Rate limit: own bucket of 4/min; one batch = one read; per-lock bucket separate
app.params = [:]
app.state.fingerprintBatchTimes = []; app.state.fingerprintTimes = []
List okBatch = (1..4).collect { fcall('apiListFingerprintsBatch').status }
def limitedBatch = fcall('apiListFingerprintsBatch')
check('batch: 4 calls a minute ok, 5th -> 429 rate_limited', okBatch == [200] * 4 && limitedBatch.status == 429 && body(limitedBatch).error == 'rate_limited')
check('batch: calls leave the per-lock bucket alone', (app.state.fingerprintTimes ?: []).isEmpty())
app.params = [lockId: '10']
check('batch: per-lock endpoint still works while batch is limited', fcall('apiListFingerprints').status == 200)
app.state.fingerprintBatchTimes = []
app.state.fingerprintTimes = (1..10).collect { app.now() }
app.params = [:]
check('batch: per-lock limit reached -> batch still works', fcall('apiListFingerprintsBatch').status == 200 && fcall('apiListFingerprints').status == 429)
app.state.fingerprintTimes = []

// Edge cases
app.codeLocks = []
check('batch: no selected locks -> {locks: []}', body(batch()).locks == [])
app.codeLocks = [lockA, lockB, lockC]
app.request = [JSON: fpCfg]
fcall('apiSetLockConfig')
String heldKey = app.state.fingerprintKey
app.state.remove('fingerprintKey')
def noKey = batch()
check('batch: no key -> 409 fingerprint_key_missing', noKey.status == 409 && body(noKey).error == 'fingerprint_key_missing')
app.state.fingerprintKey = heldKey
check('batch: no code or key in any batch response', !fpResponses.findAll { it.data }.any { r -> fpCodes.any { r.data.contains(it) } || r.data.contains(KEY2) })
app.state.fingerprintBatchTimes = []

// Off -> 404
app.lockManagementEnabled = false
app.params = [lockId: '10']
check('fp: lock management off -> 404', fcall('apiListFingerprints').status == 404)
app.params = [:]
check('batch: lock management off -> 404', fcall('apiListFingerprintsBatch').status == 404)
app.state.fingerprintBatchTimes = [app.now()]
app.params = [lockId: '10']

// Disable clears the key; re-enabling needs a new one
app.state.lockMgmtLastEnabled = true
app.syncLockManagementEnabled()
check('fp: key cleared on disable', app.state.fingerprintKey == null && app.state.fingerprintTimes == null && app.state.fingerprintBatchTimes == null)
app.lockManagementEnabled = true
app.syncLockManagementEnabled()
app.request = [JSON: fpCfg]
fcall('apiSetLockConfig')
check('fp: after re-enable -> 409 fingerprint_key_missing', body(fcall('apiListFingerprints')).error == 'fingerprint_key_missing')
app.params = [:]
check('batch: after re-enable -> 409 fingerprint_key_missing', body(fcall('apiListFingerprintsBatch')).error == 'fingerprint_key_missing')
app.params = [lockId: '10']

// Disconnect clears the key
app.request = [JSON: fpCfg + [fingerprintKey: KEY]]
fcall('apiSetLockConfig')
check('fp: key set again', app.state.fingerprintKey == KEY)
app.appButtonHandler('btnDisconnect')
check('fp: key cleared on disconnect', app.state.fingerprintKey == null)

// Neither key nor codes anywhere outside app state
List keyForms = [KEY, KEY2, keyBytes.encodeHex().toString(), key2Bytes.encodeHex().toString()]
String fpEverything = (app.posts.collect { it.params.body } + app.logged.collect { it.toString() } + fpResponses.collect { it.data } + app.scheduled.collect { it.data?.toString() }).join('\n')
check('fp: key never in responses, logs or posts', !keyForms.any { fpEverything.contains(it) })
check('fp: no code in any fingerprint-section response or log', !fpCodes.any { fpEverything.contains(it) })

// --- Re-register after an update that didn't run updated() (#539) ---
String VERSION = '1.4.0'
def evt = [id: 1, deviceId: 7, name: 'contact', displayName: 'Door', value: 'open', isStateChange: true, type: 'physical']
def event = { app.eventHandler(evt) }
app.posts.clear(); app.logged.clear(); app.scheduled.clear()
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.remove('pendingCodeChanges')
app.state.registeredVersion = '1.3.0'
app.state.remove('reregisterAttemptAt')
app.fakeNow = 1_800_000_000_000L
event()
def evBody = new JsonSlurper().parseText(app.posts.find { it.cb == 'postCallback' }.params.body)
check('version: event carries the app and firmware versions', evBody.version == VERSION && evBody.hubFirmwareVersion == '2.4.1.177')
check('version: changed version re-registers on the next event', registers().size() == 1 && regBody().version == VERSION && regBody().lockManagement == true)
check('version: registration carries the hub firmware version', regBody().hubFirmwareVersion == '2.4.1.177')
check('version: app name shown with the version', app.appNameAndVersion() == "SignalBricks Telemetry ${VERSION}")
check('version: re-register log names the app and version', app.logged.any { it.toString() == "SignalBricks Telemetry: re-registering after update to SignalBricks Telemetry ${VERSION}" })
check('version: registration sends the bare version', regBody().version == VERSION && !regBody().containsKey('appName'))
event()
check('version: rate-limited, no second register before the callback', registers().size() == 1)
app.registerCallback(new FakeResp(status: 500, body: 'not json'), [enabled: true])
check('version: failed register leaves the version unregistered', app.state.registeredVersion == '1.3.0')
app.fakeNow += 29L * 60 * 1000
event()
check('version: no retry inside 30 minutes', registers().size() == 1)
app.fakeNow += 2L * 60 * 1000
app.verifyCodeChanges()
check('version: scheduled check retries after 30 minutes', registers().size() == 2)
app.registerCallback(new FakeResp(status: 200, body: '{"lock_management":true,"connected":true}'), [enabled: true])
check('version: success records the registered version', app.state.registeredVersion == VERSION)
check('version: registered log names the app and version', app.logged.any { it.toString() == "SignalBricks Telemetry: registered SignalBricks Telemetry ${VERSION} with SignalBricks (lock management on)" })
app.fakeNow += 60L * 60 * 1000
event()
check('version: same version never re-registers', registers().size() == 2)

// Reconnect-required answer: recorded, so it isn't retried every 30 minutes
app.state.registeredVersion = '1.3.0'
app.fakeNow += 60L * 60 * 1000
event()
app.registerCallback(new FakeResp(status: 403, body: '{"error":"Forbidden","code":"TOKEN_NOT_BOUND_TO_APP"}'), [enabled: true])
app.fakeNow += 60L * 60 * 1000
event()
check('version: reconnect-required answer not retried', registers().size() == 3 && app.state.registeredVersion == VERSION)
app.state.remove('lockMgmtReconnectRequired')

// Off and never registered (telemetry only): never registers
app.posts.clear()
app.lockManagementEnabled = false
app.state.lockMgmtLastEnabled = false
app.state.lockMgmtRegistered = false
app.state.remove('lockMgmtRegisteredAt')
app.state.registeredVersion = '1.2.1'
app.fakeNow += 60L * 60 * 1000
event()
check('version: lock management off and unregistered: no register', registers().isEmpty())
// Off but registered: the disable goes out, as on Done
app.state.lockMgmtRegistered = true
event()
check('version: off but registered: disable sent', registers().size() == 1 && regBody().lockManagement == false && regBody().appAccessToken == null)

// Not connected: nothing
app.posts.clear()
app.lockManagementEnabled = true
app.state.remove('reregisterAttemptAt')
app.state.remove('sbToken')
app.verifyCodeChanges()
check('version: not connected: no register', registers().isEmpty())
app.state.sbToken = 'ingest-fake'

// A post that throws is logged by class name only (the body has the access token)
app.throwOnPost = true
app.logged.clear()
app.reregisterIfVersionChanged()
app.throwOnPost = false
check('version: failed post logged without the token', app.logged.any { it.toString().contains('IllegalArgumentException') } && !app.logged.any { it.toString().contains(app.state.accessToken) })

// No firmware reported: the field is null, registration still goes out
app.location = null
app.posts.clear()
app.registerWithPlatform()
check('version: no location -> hubFirmwareVersion null', registers().size() == 1 && regBody().hubFirmwareVersion == null)

app.appButtonHandler('btnDisconnect')
check('version: disconnect clears the registered version', app.state.registeredVersion == null && app.state.reregisterAttemptAt == null)
check('version: no tokens logged', !app.logged.any { it.toString().contains('ingest-fake') || it.toString().contains('tok-') })

// --- Daily check-in (#13) ---
def checkInJobs = { app.scheduled.findAll { it.handler == 'dailyCheckIn' } }
def cronSecond = { String cron -> def f = cron.split(' '); (f[2] as int) * 3600 + (f[1] as int) * 60 + (f[0] as int) }
app.location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177']]
app.scheduled.clear(); app.posts.clear(); app.logged.clear()
check('check-in: disconnected, nothing scheduled', checkInJobs().isEmpty() && app.state.checkInSecond == null)
app.initialize()
check('check-in: initialize while disconnected schedules nothing', checkInJobs().isEmpty())

app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.lockMgmtRegistered = true
app.initialize()
def job = checkInJobs()
check('check-in: initialize schedules one daily job', job.size() == 1 && job[0].cron ==~ /\d{1,2} \d{1,2} \d{1,2} \* \* \?/)
int sec = cronSecond(job[0].cron)
check('check-in: cron matches the stored time', sec == (app.state.checkInSecond as int))
check('check-in: time shown on the app page', app.checkInTime() == String.format('%02d:%02d', sec.intdiv(3600), (sec.intdiv(60)) % 60))
app.initialize()
app.updated()
check('check-in: Done keeps the same time and a single job', checkInJobs().size() == 1 && checkInJobs()[0].cron == job[0].cron)

// Random per hub, never 02:00-03:59
Set seen = []
boolean outsideWindow = true
500.times {
    app.state.remove('checkInSecond')
    app.scheduleDailyCheckIn()
    int t = cronSecond(checkInJobs()[0].cron)
    seen << t
    if (t >= 2 * 3600 && t < 4 * 3600) outsideWindow = false
    if (t < 0 || t >= 86400) outsideWindow = false
}
check('check-in: times are spread out', seen.size() > 400)
check('check-in: never between 02:00 and 03:59', outsideWindow)
check('check-in: still a single job', checkInJobs().size() == 1)

// The job runs the version check, with its guards and backoff
app.posts.clear()
app.state.registeredVersion = VERSION
app.state.remove('reregisterAttemptAt')
app.dailyCheckIn()
check('check-in: same version sends nothing', app.posts.isEmpty())
app.state.registeredVersion = '1.3.1'
app.fakeNow += 24L * 3600 * 1000
app.dailyCheckIn()
check('check-in: changed version re-registers', registers().size() == 1 && regBody().version == VERSION)
app.dailyCheckIn()
check('check-in: backoff respected', registers().size() == 1)
app.registerCallback(new FakeResp(status: 200, body: '{"lock_management":true,"connected":true}'), [enabled: true])
app.fakeNow += 24L * 3600 * 1000
app.dailyCheckIn()
check('check-in: registered version, nothing on the next day', registers().size() == 1)
app.lockManagementEnabled = false
app.state.lockMgmtLastEnabled = false
app.state.lockMgmtRegistered = false
app.state.registeredVersion = '1.3.1'
app.posts.clear()
app.dailyCheckIn()
check('check-in: telemetry-only hub never registers', app.posts.isEmpty())
app.lockManagementEnabled = true
app.state.lockMgmtLastEnabled = true
app.state.lockMgmtRegistered = true

// Hub updated by HPM from 1.3.1 (initialize never ran): the next event schedules it
app.scheduled.clear()
app.state.remove('checkInSecond')
app.posts.clear()
app.eventHandler(evt)
check('check-in: first event after an HPM update schedules the job', checkInJobs().size() == 1 && app.state.checkInSecond != null)
def cronBefore = checkInJobs()[0].cron
app.eventHandler(evt)
check('check-in: later events leave it alone', checkInJobs().size() == 1 && checkInJobs()[0].cron == cronBefore)

// Disconnect removes the job; reconnect schedules a new one
app.appButtonHandler('btnDisconnect')
check('check-in: disconnect unschedules', checkInJobs().isEmpty() && app.state.checkInSecond == null)
app.posts.clear()
app.state.registeredVersion = '1.3.1'
app.dailyCheckIn()
app.eventHandler(evt)
check('check-in: disconnected job run or event sends nothing', app.posts.isEmpty())
check('check-in: nothing rescheduled while disconnected', checkInJobs().isEmpty())
check('check-in: no tokens logged', !app.logged.any { it.toString().contains('ingest-fake') || it.toString().contains('tok-') })

// --- Door codes in telemetry: "Include door codes" (signalbricks/platform#388) ---
// Made-up PINs only.
String PIN = '61937'
def lockEvt = { Map extra -> [id: 900, deviceId: 7, name: 'lock', displayName: 'Front', value: 'unlocked', isStateChange: true, type: 'physical'] + extra }
def lastEventPost = { app.posts.findAll { it.params.uri == 'https://ingest.test/v1/webhooks/hubitat' }.last() }
def sentData = { new JsonSlurper().parseText(new JsonSlurper().parseText(lastEventPost().params.body).data ?: 'null') }
def allSent = { (app.posts.collect { it.params.body } + app.logged.collect { it.toString() }).join('\n') }
app.appButtonHandler('btnDisconnect')
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.debugLogging = true
app.posts.clear(); app.logged.clear()

// Off (the default): the code is stripped, slot and name kept
check('codes: setting defaults to off', app.includeDoorCodes == false)
app.eventHandler(lockEvt(data: '{"3":{"name":"Guest Smith","code":"' + PIN + '"}}'))
check('codes off: lock event code stripped, slot and name kept', sentData() == ['3': [name: 'Guest Smith']])
app.eventHandler(lockEvt(data: '{"usedCode":"' + PIN + '","codeName":"Cleaner","codeNumber":4}'))
check('codes off: usedCode variant stripped', sentData() == [codeName: 'Cleaner', codeNumber: 4])
check('codes off: no PIN in any post or log', !allSent().contains(PIN))

// On: only the `code` of lock events passes; everything else is still stripped
app.includeDoorCodes = true
app.posts.clear(); app.logged.clear()
app.eventHandler(lockEvt(data: '{"3":{"name":"Guest Smith","code":"' + PIN + '"}}'))
check('codes on: lock event code sent with slot and name', sentData() == ['3': [name: 'Guest Smith', code: PIN]])
app.eventHandler(lockEvt(data: '{"3":{"name":"Guest Smith","code":' + PIN + '}}'))
check('codes on: numeric code sent as is', sentData() == ['3': [name: 'Guest Smith', code: PIN as Integer]])
app.eventHandler(lockEvt(data: '{"3":{"name":"Guest","code":{"x":"' + PIN + '"}}}'))
check('codes on: non-scalar code still stripped', sentData() == ['3': [name: 'Guest']])
app.eventHandler(lockEvt(data: '{"usedCode":"' + PIN + '","pin":"' + PIN + '","passcode":"' + PIN + '","lockCodes":{"1":{"code":"' + PIN + '"}},"codeName":"Cleaner"}'))
check('codes on: usedCode, pin, passcode, lockCodes still stripped', sentData() == [codeName: 'Cleaner'])
app.eventHandler([id: 901, deviceId: 7, name: 'codeChanged', value: 'changed', data: '{"codeNumber":4,"code":"' + PIN + '"}'])
check('codes on: codeChanged code still stripped', sentData() == [codeNumber: 4])
app.eventHandler([id: 902, deviceId: 7, name: 'lastCodeName', value: 'Guest Smith', data: '{"code":"' + PIN + '"}'])
check('codes on: lastCodeName data code still stripped', sentData() == [:])
app.eventHandler([id: 903, deviceId: 9, name: 'contact', value: 'open', data: '{"pin":"' + PIN + '","code":"E101"}'])
check('codes on: non-lock event keeps error code, strips pin', sentData() == [code: 'E101'])
List lockPosts = app.posts.findAll { new JsonSlurper().parseText(it.params.body).name == 'lock' }
check('codes on: sent over the ingestion URL with the bearer token', lockPosts.every { it.params.uri == 'https://ingest.test/v1/webhooks/hubitat' && it.params.headers.Authorization == 'Bearer ingest-fake' })
check('codes on: never in the logs (debug logging on)', !app.logged.any { it.toString().contains(PIN) })
check('codes on: debug log line still written', app.logged.any { it.toString().contains('sending Front lock=unlocked') })

// A post that throws is logged by class name only, never the body (which carries the code)
app.throwOnPost = true
app.logged.clear()
app.eventHandler(lockEvt(data: '{"3":{"name":"Guest Smith","code":"' + PIN + '"}}'))
app.throwOnPost = false
check('codes on: failed post logged without the code', app.logged.any { it.toString().contains('IllegalArgumentException') } && !app.logged.any { it.toString().contains(PIN) })

// Never subscribes to lockCodes, with the setting on or off
app.locks = [new FakeLock(id: '7', displayName: 'Front', name: 'Front', label: 'Front')]
app.subscriptions.clear()
app.subscribeToEvents()
check('codes on: subscriptions unchanged, never lockCodes', app.subscriptions.sort() == ['battery', 'lastCodeName', 'lock'] && !app.subscriptions.contains('lockCodes'))
app.includeDoorCodes = false
app.subscriptions.clear()
app.subscribeToEvents()
check('codes off: never lockCodes', !app.subscriptions.contains('lockCodes'))
app.locks = null
app.debugLogging = false

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
