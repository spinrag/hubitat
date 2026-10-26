// Behavioural smoke test for the lock-management handlers with a stubbed
// Hubitat sandbox. Fake values only.
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    Map params = [:]
    Map request = [:]
    def codeLocks = []
    def locks = null, contactSensors = null, temperatureSensors = null, humiditySensors = null
    Boolean lockManagementEnabled = true
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    List removedSettings = []
    List logged = []
    List posts = []
    def log = [info: { logged << it }, warn: { logged << it }, debug: { logged << it }, error: { logged << it }]
    def app = new FakeApp(this)
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
    void unschedule(String handler = null) { if (handler) scheduled.removeAll { it.handler == handler } else scheduled.clear() }
    void unsubscribe() {}
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { state.accessToken = 'tok-' + UUID.randomUUID(); state.accessToken }
    void asynchttpPost(String cb, Map p, Map d) { posts << [cb: cb, params: p, data: d] }
    String decrypt(String s) { throw new RuntimeException('no decrypt') }
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
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void setCode(Integer slot, String code, String name) { calls << ['setCode', slot, name]; apply { it[slot.toString()] = [name: name, code: code] } }
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
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"Guest -ORB12345"', '"name":"Old"')
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
List codes = ['91911111', '91913333', '91914444', '91915555', '91916666', '91917777', '01818', '90903333', '90903334', '90903335', '90904444', '90905555', '90906666', '90907777', '90907778', '90908888', '90909999', '90901010']
String everything = (app.posts.collect { it.params.body } + app.logged.collect { it.toString() } + responses.collect { it.data } + app.scheduled.collect { it.data?.toString() }).join('\n')
check('no code in any event, log, response or scheduled job', !codes.any { everything.contains(it) })
check('result events carry only contract keys', results().every { (it.d.keySet() - ['lockId', 'slot', 'action', 'result', 'attempts', 'reason', 'requestId', 'name']).isEmpty() })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
