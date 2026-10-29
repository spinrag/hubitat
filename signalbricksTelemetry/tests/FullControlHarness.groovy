// Registered slots and full lock control (signalbricks/platform#684), with a
// stubbed Hubitat sandbox. Covers config validation, the booking-slot rule
// (G:/GT:/ORB names, lower-case orb, unnamed slots, empty slots above the
// ceiling) on every new path with full control off and on, custom names in
// and out of the class ranges, the hub-local full-control switch, the rename
// handshake (old or new name until confirmed), old configs behaving as
// before, and that no code reaches a log line, response or event.
// Fake values only; nothing is sent to a hub.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    Map getAtomicState() { state }
    Map settings = [:]
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
    List logged = []
    List posts = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, error: { logged << it.toString() }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177']]
    void definition(Map m) {}
    Closure prefs
    void preferences(Closure c) { prefs = c }
    List pages = []
    void page(Map m, Closure c = null) { pages << m.name; if (c) c() }
    void mappings(Closure c) {}
    List paragraphs = []
    Map inputs = [:]
    def dynamicPage(Map m, Closure c) { c(); m }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) {}
    Map render(Map m) { m }
    Long fakeNow = null
    long now() { fakeNow ?: System.currentTimeMillis() }
    List scheduled = []
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite != false) scheduled.removeAll { it.handler == handler }
        scheduled << [secs: secs, handler: handler, data: opts?.data]
    }
    void schedule(String cron, String handler) { scheduled.removeAll { it.handler == handler }; scheduled << [cron: cron, handler: handler] }
    void unschedule(String handler = null) { if (handler) scheduled.removeAll { it.handler == handler } else scheduled.clear() }
    void subscribe(Object... args) {}
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
    void removeSetting(String n) { owner.setProperty(n, null) }
    void updateSetting(String n, Map v) { owner.setProperty(n, v.value) }
}

// Applies setCode/deleteCode to lockCodes, except the next dropSets /
// dropDeletes commands (a lock that silently drops them). unreadableReads:
// the next reads of lockCodes return an undecryptable value. storedName: the
// driver stores every set under this name instead. throwOnSet: the driver
// throws with the code in its message.
class FakeLock {
    String id; String displayName; String name; String label
    Map attrs = [:]
    List calls = []
    int dropSets = 0, dropDeletes = 0, unreadableReads = 0
    String storedName = null
    Boolean throwOnSet = false
    def currentValue(String a, Boolean skipCache = false) {
        if (a == 'lockCodes' && unreadableReads > 0) { unreadableReads--; return 'ENCRYPTEDBLOB' }
        attrs[a]
    }
    void setCode(Integer slot, String code, String name) {
        calls << ['setCode', slot, name]
        if (throwOnSet) throw new IllegalStateException("setCode(${slot}, ${code}, ${name}) failed")
        if (dropSets > 0) { dropSets--; return }
        edit { it[slot.toString()] = [name: storedName ?: name, code: code] }
    }
    void deleteCode(Integer slot) {
        calls << ['deleteCode', slot]
        if (dropDeletes > 0) { dropDeletes--; return }
        edit { it.remove(slot.toString()) }
    }
    void edit(Closure c) {
        Map codes = new JsonSlurper().parseText(attrs.lockCodes) as Map
        c(codes)
        attrs.lockCodes = JsonOutput.toJson(codes)
    }
    Map codes() { new JsonSlurper().parseText(attrs.lockCodes) as Map }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
def body = { Map r -> r.data ? new JsonSlurper().parseText(r.data) : null }

// Fake codes only. Layout: admin 1-10, managed 11-20, ceiling 25, maxCodes 40.
// Booking codes (SignalBricks Integration GT:, OwnerRez fork G:, lower-case,
// unnamed) sit both inside the ranges (a booking app with minCodePosition 0)
// and above the ceiling.
Map initial = [
    '3': [name: 'Cleaner', code: '60600003'],
    '5': [name: 'GT:ORB88-Guest', code: '60600005'],
    '6': [code: '60600006'],
    '12': [name: 'SBM: Chris', code: '60600012'],
    '26': [name: 'GT: ORB123-Guest', code: '60600026'],
    '27': [name: 'G: Guest -ORB456', code: '60600027'],
    '28': [name: 'Owner', code: '60600028'],
    '29': [name: null, code: '60600029'],
    '30': [name: 'g:orb7-guest', code: '60600030'],
    '31': [name: 'G: Gardener', code: '60600031'],
    '32': [name: 'orb9 guest', code: '60600032'],
    '34': [name: 'Guest-ORB777', code: '60600034'],
    '35': [name: 'ORBIT', code: '60600035'],
]
List fakeCodes = (initial.values()*.code) + ['60600099', '60600098', '60600097', '60600096']
def lock = new FakeLock(id: '9', displayName: 'Side', name: 'Side', label: 'Side')
lock.attrs.maxCodes = '40'
lock.attrs.lockCodes = JsonOutput.toJson(initial)
def lock2 = new FakeLock(id: '8', displayName: 'Back', name: 'Back', label: 'Back')
lock2.attrs.lockCodes = JsonOutput.toJson([:])
app.codeLocks = [lock, lock2]
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.state.lockMgmtLastEnabled = true
app.fakeNow = 1_800_000_000_000L
byte[] keyBytes = (1..32).collect { (byte) (it * 7) } as byte[]
String KEY = keyBytes.encodeBase64().toString()

List responses = []
def call = { Closure c -> def r = c(); responses << r; r }
def push = { Map locks -> app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 20], locks: locks, fingerprintKey: KEY]]; call { app.apiSetLockConfig() } }
def setCode = { def slot, String name, String code, String lockId = '9' -> app.params = [lockId: lockId]; app.request = [JSON: [slot: slot, code: code, name: name]]; call { app.apiSetCode() } }
def deleteCode = { def slot, String lockId = '9' -> app.params = [lockId: lockId, slot: slot.toString()]; app.request = [JSON: null]; call { app.apiDeleteCode() } }
def reveal = { def slot, String lockId = '9' -> app.params = [lockId: lockId, slot: slot.toString()]; app.state.revealTimes = []; call { app.apiRevealCode() } }
def relocate = { def slot, Map b, String lockId = '9' -> app.params = [lockId: lockId, slot: slot.toString()]; app.request = [JSON: b]; call { app.apiRelocateCode() } }
def slots = { app.params = [lockId: '9']; body(call { app.apiListSlots() }).slots.collectEntries { [(it.slot): it] } }
def fps = { app.params = [lockId: '9']; app.state.fingerprintTimes = []; body(call { app.apiListFingerprints() }).slots*.slot }
def verifyJobs = { app.scheduled.findAll { it.handler == 'verifyCodeChanges' } }
def settle = {
    while (true) {
        def job = verifyJobs()[0]
        if (!job) break
        app.scheduled.remove(job)
        app.fakeNow += (job.secs as long) * 1000L
        app.verifyCodeChanges()
    }
}
def slotOf = { Integer s -> lock.codes()[s.toString()] }
def putSlot = { Integer s, String name, def code -> lock.edit { if (code == null) it.remove(s.toString()) else it[s.toString()] = [name: name, code: code] } }
def fresh = { app.state.writeTimes = []; app.state.remove('pendingCodeChanges'); lock.calls.clear(); app.scheduled.removeAll { it.handler == 'verifyCodeChanges' } }
def lockCfg = { app.state.lockConfig.locks['9'] }

// --- Old config: nothing changes ---
def r0 = push(['9': [adopted: [28]]])
check('old config: accepted', r0.status == 200)
check('old config: stored exactly as before (no registered/fullControl keys)', app.state.lockConfig == [admin: [from: 1, to: 10], managed: [from: 11, to: 20], locks: ['9': [adopted: [28]]]])
check('old config: reply keeps its keys and adds features + fullControlAllowed', body(r0).keySet() == ['config', 'ceiling', 'pendingApproval', 'fingerprintKeySet', 'features', 'fullControlAllowed'] as Set &&
    body(r0).features == ['registered_slots', 'full_control'] && body(r0).fullControlAllowed == [])
fresh()
check('old config: unmarked code in range still refused (slot_not_managed)', body(reveal(3)).error == 'slot_not_managed' && body(deleteCode(3)).error == 'slot_not_managed')
check('old config: unapproved adopted above ceiling still refused', body(reveal(28)).error == 'slot_not_allowed')
check('old config: booking in range still refused', body(reveal(5)).error == 'booking_code' && body(deleteCode(5)).error == 'booking_code')
check('old config: SBM: code still works', reveal(12).status == 200)
check('old config: no lock commands', lock.calls.isEmpty())

// fullControl alone (nothing registered) reaches nothing new
def r1 = push(['9': [fullControl: true]])
app.fullControlLocks = ['9']
check('fullControl alone: accepted, stored', r1.status == 200 && lockCfg() == [adopted: [], fullControl: true])
check('fullControl alone: unregistered slots stay refused', [3, 28, 22, 33].every { body(reveal(it)).error in ['slot_not_managed', 'slot_not_allowed', 'slot_empty'] } &&
    body(setCode(22, 'Anything', '60600099')).error == 'slot_not_allowed' && body(setCode(33, 'Anything', '60600099')).error == 'slot_not_allowed')
check('fullControl alone: no lock commands', lock.calls.isEmpty())
app.fullControlLocks = null

// fullControl: false grants nothing and isn't stored
check('fullControl false: accepted, not stored', push(['9': [registered: [[slot: 22, name: 'Dog Walker']], fullControl: false]]).status == 200 &&
    lockCfg() == [adopted: [], registered: [[slot: 22, name: 'Dog Walker']]])
app.fullControlLocks = ['9']
check('fullControl false: empty registered slot outside the ranges refused', body(setCode(22, 'Dog Walker', '60600099')).error == 'slot_not_allowed' && slotOf(22) == null)
app.fullControlLocks = null
check('fullControl false: no lock commands', lock.calls.isEmpty())

// --- Config validation ---
Map before = app.state.lockConfig
def bad = { String label, Map lockEntry ->
    def r = push(['9': lockEntry])
    check("config refused: ${label}", r.status == 400 && body(r).error == 'invalid_config' && app.state.lockConfig == before)
}
['GT:ORB1-x', 'G:ORB2-x', 'Guest -ORB3', 'orb4', 'Orb5 x', 'G: Gardener', 'gt : x', 'g:x', 'ORBIT', 'x ORBy'].each { n ->
    bad("booking-looking registered name '${n}'", [registered: [[slot: 3, name: n]]])
}
bad('blank name', [registered: [[slot: 3, name: '']]])
bad('null name', [registered: [[slot: 3, name: null]]])
bad('name with a leading space', [registered: [[slot: 3, name: ' Cleaner']]])
bad('non-ASCII name', [registered: [[slot: 3, name: 'Cléaner']]])
bad('name over 64', [registered: [[slot: 3, name: 'x' * 65]]])
bad('pendingName with SBM:', [registered: [[slot: 3, name: 'Cleaner', pendingName: 'SBM: Cleaner']]])
bad('pendingName with lower-case sba:', [registered: [[slot: 3, name: 'Cleaner', pendingName: 'sba : Cleaner']]])
bad('pendingName booking', [registered: [[slot: 3, name: 'Cleaner', pendingName: 'GT:ORB9-x']]])
bad('pendingName same as name', [registered: [[slot: 3, name: 'Cleaner', pendingName: 'Cleaner']]])
bad('slot both adopted and registered', [adopted: [3], registered: [[slot: 3, name: 'Cleaner']]])
bad('slot registered twice', [registered: [[slot: 3, name: 'Cleaner'], [slot: 3, name: 'Other']]])
bad('unknown entry key', [registered: [[slot: 3, name: 'Cleaner', code: '60600003']]])
bad('slot 0', [registered: [[slot: 0, name: 'Cleaner']]])
bad('slot 1001', [registered: [[slot: 1001, name: 'Cleaner']]])
bad('slot as text', [registered: [[slot: '3', name: 'Cleaner']]])
bad('fractional slot', [registered: [[slot: 3.5, name: 'Cleaner']]])
bad('registered not a list', [registered: [slot: 3, name: 'Cleaner']])
bad('fullControl not a boolean', [fullControl: 'yes'])
bad('more than 250 adopted + registered', [adopted: (1..200).toList(), registered: (201..251).collect { [slot: it, name: "Code ${it}".toString()] }])
def so = push(['9': [surveyOnly: true, registered: [[slot: 3, name: 'Cleaner']]]])
check('config refused: surveyOnly with registered', so.status == 400 && app.state.lockConfig == before)
check('config refusal messages never echo a code', responses.findAll { it.status == 400 }.every { r -> !fakeCodes.any { r.data.contains(it) } })

// --- Booking codes survive every new path, full control off and on ---
// Each booking slot is registered under an innocent name (the hub can't see
// the lock when the config arrives): the lock's name still decides.
List bookingSlots = [5, 6, 26, 27, 29, 30, 31, 32, 34, 35]
Map bookingRegs = ['9': [registered: bookingSlots.collect { [slot: it, name: "Innocent ${it}".toString()] }]]
[false, true].each { Boolean fc ->
    String mode = fc ? 'full control on + allowed on the hub' : 'full control off'
    Map regs = ['9': bookingRegs['9'] + (fc ? [fullControl: true] : [:])]
    check("${mode}: booking registrations stored", push(regs).status == 200)
    app.fullControlLocks = fc ? ['9'] : null
    if (!fc) app.approvedAdoptedSlots = bookingSlots.findAll { it > 25 }.collect { "9:${it}".toString() }
    fresh()
    bookingSlots.each { Integer s ->
        String innocent = "Innocent ${s}".toString()
        def rv = reveal(s), dl = deleteCode(s), st = setCode(s, innocent, '60600099'), rl = relocate(s, [name: innocent])
        check("${mode}: slot ${s} (${initial[s.toString()].name ?: 'unnamed'}) reveal/delete/set/rename refused",
            [rv, dl, st, rl].every { it.status in [403, 409] } && [rv, dl, st, rl].every { body(it).error in ['booking_code', 'name_mismatch'] })
        check("${mode}: slot ${s} not allowed in /slots", slots()[s].allowedAs == null)
    }
    check("${mode}: no booking slot fingerprinted", fps().intersect(bookingSlots).isEmpty())
    check("${mode}: no lock command sent", lock.calls.isEmpty())
    check("${mode}: every booking code still on the lock as it was", bookingSlots.every { slotOf(it) == initial[it.toString()] })
    app.approvedAdoptedSlots = null
}
app.fullControlLocks = null

// A booking app wrote into a registered slot after SignalBricks deleted its code: refused.
push(['9': [registered: [[slot: 22, name: 'Cleaner Two']], fullControl: true]])
putSlot(22, 'GT:ORB55-Guest', '60600097')
fresh()
check('booking code in a registered empty slot: refused on every path', [reveal(22), deleteCode(22), setCode(22, 'Cleaner Two', '60600099'), relocate(22, [name: 'Cleaner Two'])].every { body(it).error == 'booking_code' } && lock.calls.isEmpty())
putSlot(22, null, null)

// --- Custom names in the class ranges (full control off) ---
push(['9': [registered: [[slot: 18, name: 'Cleaner Two'], [slot: 22, name: 'Dog Walker'], [slot: 33, name: 'Painter']]]])
fresh()
check('custom name in range: other names refused', body(setCode(18, 'Someone', '60600099')).error == 'name_not_allowed' && body(setCode(18, 'SBM: Cleaner Two', '60600099')).error == 'name_not_allowed')
def cn = setCode(18, 'Cleaner Two', '60600098')
check('custom name in range: 202', cn.status == 202 && lock.calls == [['setCode', 18, 'Cleaner Two']])
settle()
check('custom name in range: confirmed on the lock', slotOf(18) == [name: 'Cleaner Two', code: '60600098'])
check('custom name in range: listed as adopted + registered', slots()[18] == [slot: 18, name: 'Cleaner Two', set: true, allowedAs: 'adopted', registered: true])
check('custom name in range: fingerprinted', fps().contains(18))
check('custom name in range: reveal ok', body(reveal(18)).code == '60600098')
check('empty registered slot outside the ranges: refused without full control', body(setCode(22, 'Dog Walker', '60600097')).error == 'slot_not_allowed')
check('empty registered slot above the ceiling: refused', body(setCode(33, 'Painter', '60600097')).error == 'slot_not_allowed')

// --- Empty slot holding only a strict-only booking name (no code): refused ---
// isBookingName misses these (lower case, G: without ORB); the strict rule must catch them.
['g: guest', 'orb5', 'G: Gardener'].each { String n ->
    push(['9': [registered: [[slot: 19, name: 'Helper']], fullControl: true]])
    lock.edit { it['19'] = [name: n, code: ''] }
    fresh()
    check("empty slot named '${n}': set refused as booking_code", body(setCode(19, 'Helper', '60600099')).error == 'booking_code' && lock.calls.isEmpty() && slotOf(19) == [name: n, code: ''])
    check("empty slot named '${n}': not allowed in /slots", slots()[19].allowedAs == null)
}
lock.edit { it.remove('19') }

// --- A booking code at or under the ceiling blocks writes outside the ranges (fail closed) ---
push(['9': [registered: [[slot: 22, name: 'Dog Walker'], [slot: 18, name: 'Cleaner Two']], fullControl: true]])
app.fullControlLocks = ['9']
fresh()
def ov = setCode(22, 'Dog Walker', '60600097')
check('overlap: booking code in slot 5 refuses empty writes outside the ranges', ov.status == 409 && body(ov).error == 'booking_region_overlap' && slotOf(22) == null && lock.calls.isEmpty())
check('overlap: /slots shows the slot not allowed', slots()[22] == null || slots()[22].allowedAs == null)
check('overlap: in-range registered writes still allowed', setCode(18, 'Cleaner Two', '60600096').status == 202)
settle()
putSlot(5, null, null)
lock.edit { it['7'] = [name: 'g: guest two', code: '60600094'] }
fresh()
check('overlap: a strict-only booking name at or under the ceiling also blocks', body(setCode(22, 'Dog Walker', '60600097')).error == 'booking_region_overlap')
lock.edit { it.remove('7') }

// --- Full control: empty slots outside the ranges, never above the ceiling ---
push(['9': [registered: [[slot: 18, name: 'Cleaner Two'], [slot: 22, name: 'Dog Walker'], [slot: 33, name: 'Painter']], fullControl: true]])
app.fullControlLocks = ['9']
fresh()
check('full control: empty registered slot outside the ranges writable', setCode(22, 'Dog Walker', '60600097').status == 202)
settle()
check('full control: confirmed', slotOf(22) == [name: 'Dog Walker', code: '60600097'])
check('full control: empty slot above the ceiling still never written', body(setCode(33, 'Painter', '60600096')).error == 'slot_not_allowed' && slotOf(33) == null)
check('full control: /slots says so for the empty slot above the ceiling', slots()[33] == null || slots()[33].allowedAs == null)

// --- Above the ceiling: the hub-local switch or an approval ---
push(['9': [registered: [[slot: 28, name: 'Owner']]]])
app.fullControlLocks = ['9']
fresh()
check('above ceiling, full control off: refused even with the hub switch', body(reveal(28)).error == 'slot_not_allowed' && body(lockConfigCall = { app.apiGetLockConfig() }()).pendingApproval == ['9:28'])
push(['9': [registered: [[slot: 28, name: 'Owner']], fullControl: true]])
app.fullControlLocks = null
check('above ceiling, full control on but not allowed on the hub: refused, needs approval', body(reveal(28)).error == 'slot_not_allowed' && body(app.apiGetLockConfig()).pendingApproval == ['9:28'])
app.approvedAdoptedSlots = ['9:28']
check('above ceiling, slot approved instead: reachable', reveal(28).status == 200)
app.approvedAdoptedSlots = null
app.fullControlLocks = ['9', '404']
def gc = body(app.apiGetLockConfig())
check('above ceiling, full control on + allowed: no approval asked, unknown lock ids ignored', gc.pendingApproval == [] && gc.fullControlAllowed == ['9'])
check('above ceiling, full control on + allowed: reveal ok', body(reveal(28)).code == '60600028')
check('above ceiling: a move is refused', body(relocate(28, [name: 'Owner', toSlot: 21])).error == 'move_not_supported')
check('above ceiling: fingerprinted', fps().contains(28))
def od = deleteCode(28)
check('above ceiling: delete 202', od.status == 202)
settle()
check('above ceiling: deleted', slotOf(28) == null)
check('above ceiling: the emptied slot is never written again', body(setCode(28, 'Owner', '60600096')).error == 'slot_not_allowed' && slotOf(28) == null)
putSlot(28, 'Owner', '60600028')

// --- A registered slot holding another name is never taken over ---
push(['9': [registered: [[slot: 3, name: 'Cleaner']]]])
fresh()
check('registered: matching name reachable', reveal(3).status == 200)
putSlot(3, 'Somebody Else', '60600096')
check('registered: other name refused on every path', [reveal(3), deleteCode(3), setCode(3, 'Cleaner', '60600099'), relocate(3, [name: 'Cleaner'])].every { body(it).error == 'name_mismatch' })
check('registered: other name not allowed in /slots, not fingerprinted', slots()[3].allowedAs == null && !fps().contains(3))
putSlot(3, null, null)
lock.edit { it['3'] = [code: '60600096'] }
check('registered: unnamed code in the slot refused', body(reveal(3)).error == 'name_mismatch' && body(deleteCode(3)).error == 'name_mismatch')
check('registered: no lock command for a slot that isn\'t ours', lock.calls.isEmpty())
putSlot(3, 'Cleaner', '60600003')

// --- Rename handshake ---
push(['9': [registered: [[slot: 3, name: 'Cleaner', pendingName: 'Cleaner Main']]]])
fresh()
check('handshake: old name accepted while the rename is pending', reveal(3).status == 200)
check('handshake: a third name refused', body(relocate(3, [name: 'Cleaner Other'])).error == 'name_not_allowed')
def hr = relocate(3, [name: 'Cleaner Main'])
check('handshake: rename in place 202', hr.status == 202 && lock.calls == [['setCode', 3, 'Cleaner Main']])
check('handshake: both names still accepted until confirmed', lockCfg().registered == [[slot: 3, name: 'Cleaner', pendingName: 'Cleaner Main']])
settle()
check('handshake: lock holds the same code under the new name', slotOf(3) == [name: 'Cleaner Main', code: '60600003'])
check('handshake: hub promoted the registration', lockCfg().registered == [[slot: 3, name: 'Cleaner Main']])
def ev = app.posts.findAll { it.params.body?.contains('"relocate_result"') }.collect { new JsonSlurper().parseText(new JsonSlurper().parseText(it.params.body).data) }[-1]
check('handshake: relocate_result confirmed (no custom name in the event)', ev.result == 'confirmed' && ev.slot == 3 && !ev.containsKey('name'))
putSlot(3, 'Cleaner', '60600003')
check('handshake: old name no longer accepted after the confirm', body(reveal(3)).error == 'name_mismatch')
// A stale push still carrying both names (sent before SignalBricks saw the result) is harmless.
push(['9': [registered: [[slot: 3, name: 'Cleaner', pendingName: 'Cleaner Main']]]])
check('handshake: stale push accepts both again', reveal(3).status == 200)
putSlot(3, 'Cleaner Main', '60600003')
check('handshake: stale push, new name reachable', reveal(3).status == 200)
push(['9': [registered: [[slot: 3, name: 'Cleaner Main']]]])
check('handshake: final push, new name only', reveal(3).status == 200)
putSlot(3, 'Cleaner', '60600003')
check('handshake: final push, old name refused', body(reveal(3)).error == 'name_mismatch')

// Crash before the rename is sent: the lock keeps the old name, the hub keeps both.
push(['9': [registered: [[slot: 3, name: 'Cleaner', pendingName: 'Cleaner Main']]]])
fresh()
check('crash before rename: nothing sent, old name still reachable', reveal(3).status == 200 && lock.calls.isEmpty())
// A rename the lock never applies: failed, source untouched, both names kept.
lock.dropSets = 99
relocate(3, [name: 'Cleaner Main'])
settle()
lock.dropSets = 0
check('rename not applied: lock as it was, registration still holds both names', slotOf(3) == [name: 'Cleaner', code: '60600003'] &&
    lockCfg().registered == [[slot: 3, name: 'Cleaner', pendingName: 'Cleaner Main']])

// A prefix code renamed to a custom name: registered under its SBM: name first.
push(['9': [registered: [[slot: 12, name: 'SBM: Chris', pendingName: 'Chris']]]])
fresh()
check('prefix to custom: rename 202', relocate(12, [name: 'Chris']).status == 202)
settle()
check('prefix to custom: confirmed and promoted', slotOf(12) == [name: 'Chris', code: '60600012'] && lockCfg().registered == [[slot: 12, name: 'Chris']])
// Without the registration the custom name is refused as before.
push(['9': [:]])
putSlot(12, 'SBM: Chris', '60600012')
check('prefix code without registration: custom name refused', body(relocate(12, [name: 'Chris'])).error == 'name_not_allowed')
// A set with the pending name (Change code during a rename) promotes too.
push(['9': [registered: [[slot: 18, name: 'Cleaner Two', pendingName: 'Cleaner 2']]]])
fresh()
setCode(18, 'Cleaner 2', '60600096')
settle()
check('set with the pending name: confirmed and promoted', slotOf(18) == [name: 'Cleaner 2', code: '60600096'] && lockCfg().registered == [[slot: 18, name: 'Cleaner 2']])

// --- A registration removed while a change is in flight: dropped, reported failed ---
def lastResult = { app.posts.findAll { it.params.body?.contains('"lock_code_result"') }.collect { new JsonSlurper().parseText(new JsonSlurper().parseText(it.params.body).data) }[-1] }
push(['9': [registered: [[slot: 21, name: 'Gardener Two']], fullControl: true]])
fresh()
lock.dropSets = 99
setCode(21, 'Gardener Two', '60600095')
push(['9': [:]])
lock.dropSets = 0
check('registration removed: pending change dropped as slot_not_allowed', !app.state.pendingCodeChanges && lastResult().result == 'failed' && lastResult().reason == 'slot_not_allowed' && slotOf(21) == null)
// In a class range the slot stays allowed, but the custom name is never re-sent.
push(['9': [registered: [[slot: 18, name: 'Cleaner 2']]]])
fresh()
lock.dropSets = 99
setCode(18, 'Cleaner 2', '60600095')
push(['9': [:]])
lock.dropSets = 0
settle()
check('registration removed in range: the custom name is not re-sent, failed', lock.calls == [['setCode', 18, 'Cleaner 2']] &&
    lastResult().result == 'failed' && lastResult().reason in ['slot_not_managed', 'name_not_allowed'] && slotOf(18) == [name: 'Cleaner 2', code: '60600096'])

// --- A rename cancelled while a set under the new name is in flight (H1) ---
push(['9': [registered: [[slot: 22, name: 'Dog Walker', pendingName: 'Dog Walker Two']], fullControl: true]])
fresh()
lock.dropSets = 99
check('cancelled rename: set under the pending name accepted', setCode(22, 'Dog Walker Two', '60600093').status == 202)
push(['9': [registered: [[slot: 22, name: 'Dog Walker']], fullControl: true]])
check('cancelled rename: the set is dropped by the push itself (not at its next check)', !app.state.pendingCodeChanges && lastResult().reason == 'name_not_allowed' && !verifyJobs())
lock.dropSets = 0
settle()
check('cancelled rename: in-flight change dropped as name_not_allowed, never re-sent', !app.state.pendingCodeChanges && lastResult().result == 'failed' &&
    lastResult().reason == 'name_not_allowed' && lock.calls.count { it[0] == 'setCode' } == 1 && slotOf(22) == [name: 'Dog Walker', code: '60600097'])
// Same for a relocate (rename) in flight.
push(['9': [registered: [[slot: 22, name: 'Dog Walker', pendingName: 'Dog Walker Two']], fullControl: true]])
fresh()
lock.dropSets = 99
check('cancelled rename: relocate accepted', relocate(22, [name: 'Dog Walker Two']).status == 202)
push(['9': [registered: [[slot: 22, name: 'Dog Walker']], fullControl: true]])
check('cancelled rename: the relocate is dropped by the push itself', !app.state.pendingCodeChanges && !verifyJobs())
lock.dropSets = 0
settle()
def lastRelocate = app.posts.findAll { it.params.body?.contains('"relocate_result"') }.collect { new JsonSlurper().parseText(new JsonSlurper().parseText(it.params.body).data) }[-1]
check('cancelled rename: relocate dropped as name_not_allowed, slot keeps the old name', !app.state.pendingCodeChanges && lastRelocate.reason == 'name_not_allowed' &&
    lock.calls.count { it[0] == 'setCode' } == 1 && slotOf(22) == [name: 'Dog Walker', code: '60600097'])
check('cancelled rename: a delete in flight is kept', { push(['9': [registered: [[slot: 22, name: 'Dog Walker']], fullControl: true]]); fresh(); lock.dropDeletes = 99
    deleteCode(22); push(['9': [registered: [[slot: 22, name: 'Dog Walker']], fullControl: true]]); lock.dropDeletes = 0
    boolean kept = app.state.pendingCodeChanges?.containsKey('9:22'); settle(); kept && slotOf(22) == null }())

// --- Turning lock management off, or disconnecting, drops the hub switch ---
app.fullControlLocks = ['9']
app.lockManagementEnabled = false
app.syncLockManagementEnabled()
check('disable: full control switch cleared with the approvals', app.fullControlLocks == null && app.state.lockConfig == null)
app.lockManagementEnabled = true
app.syncLockManagementEnabled()
app.fullControlLocks = ['9']
app.appButtonHandler('btnDisconnect')
check('disconnect: full control switch cleared', app.fullControlLocks == null)

// --- The app page offers the switch per selected lock ---
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.inputs.clear(); app.paragraphs.clear()
app.mainPage()
check('page: full control switch lists the selected locks, nothing selected by default', app.inputs.fullControlLocks?.options == ['9': 'Side', '8': 'Back'] &&
    app.inputs.fullControlLocks.multiple == true && app.inputs.fullControlLocks.defaultValue == null)
app.codeLocks = []
app.inputs.clear()
app.mainPage()
check('page: no switch without locks', !app.inputs.containsKey('fullControlLocks'))

// --- No code anywhere it shouldn't be ---
def leaks = { String s -> fakeCodes.findAll { s?.contains(it) } }
List revealResponses = responses.findAll { it.status == 200 && body(it)?.containsKey('code') }
check('codes only in reveal responses', (responses - revealResponses).every { leaks(it.data?.toString()).isEmpty() })
check('no code in any log line', app.logged.every { leaks(it).isEmpty() })
check('no code in any event or registration', app.posts.every { leaks(it.params.body).isEmpty() })

println(fails ? "${fails} FAILED" : 'ALL PASS')
if (fails) System.exit(1)
