// Behavioural smoke test for the lock-management handlers with a stubbed
// Hubitat sandbox. Fake values only.
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    Map params = [:]
    Map request = [:]
    def codeLocks = []
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
    long now() { System.currentTimeMillis() }
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
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void setCode(Integer slot, String code, String name) { calls << ['setCode', slot, name] }
    void deleteCode(Integer slot) { calls << ['deleteCode', slot] }
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
check('guard: Guest-ORB123 not matched (known gap)', !app.isBookingName('Guest-ORB123'))
check('guard: Guest -ORB123 not matched (known gap)', !app.isBookingName('Guest -ORB123'))
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
lock.attrs.lockCodes = lock.attrs.lockCodes.replace('"name":"GT: ORB123-Guest"', '"name":"Old"')
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
int before = app.posts.size()
check('sync handled disable', app.syncLockManagementEnabled() == true)
check('token rotated on disable', app.state.accessToken != oldToken)
check('rules cleared on disable', app.state.lockConfig == null && app.approvedAdoptedSlots == null)
def reg = new JsonSlurper().parseText(app.posts[-1].params.body)
check('disable notified', app.posts.size() == before + 1 && reg.lockManagement == false && reg.appAccessToken == null)
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

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
