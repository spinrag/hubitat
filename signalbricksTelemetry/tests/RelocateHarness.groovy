// Relocate (#31): rename a code or move it to another slot on the hub, with a
// stubbed Hubitat sandbox. Covers rename, move (confirm before delete), an
// unconfirmed move leaving the source alone, every refusal, the write limit
// and pending cap, approvals, the registration capability, the app page with
// no state, and that no code reaches a log line, response or event.
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

// --- The app page with no state (as when the hub saves the code, #28) ---
def savedState = app.state, savedSettings = app.settings
app.state = null; app.settings = null
String saveError = null
try { app.prefs.call() } catch (e) { saveError = e.toString() }
check('page: preferences evaluate with no state or settings', saveError == null && app.pages == ['mainPage'])
app.state = savedState; app.settings = savedSettings

// Fake codes only. Layout: admin 1-10, managed 11-25, ceiling 25.
Map initial = [
    '2': [name: 'SBA: Owner', code: '50500002'],
    '5': [name: 'Unmarked', code: '50500005'],
    '12': [name: 'SBM: Chris', code: '50500012'],
    '13': [name: 'SBM: Zero', code: '05050013'],
    '14': [name: 'SBM: Numeric', code: 50500014],
    '15': [name: 'SBM: Busy', code: '50500015'],
    '16': [name: 'SBM: Taken', code: '50500016'],
    '24': [name: 'SBM: Short', code: '123'],
    '25': [name: 'SBM: Letters', code: '5050ab25'],
    '27': [name: 'Legacy Unapproved', code: '50500027'],
    '28': [name: 'Legacy', code: '50500028'],
    '29': [name: 'SBM: Above', code: '50500029'],
    '30': [name: 'GT:ORB123-x', code: '50500030'],
]
List fakeCodes = ['5050ab25', '50500031', '50500032', '50500033', '50500002', '50500005', '50500012', '05050013', '50500014', '50500015', '50500016', '50500027', '50500028', '50500029', '50500030', '50500099', '50500098', '50500097', '50500021', '50500022']
def lock = new FakeLock(id: '9', displayName: 'Side', name: 'Side', label: 'Side')
lock.attrs.maxCodes = '30'
lock.attrs.lockCodes = JsonOutput.toJson(initial)
def lock2 = new FakeLock(id: '8', displayName: 'Back', name: 'Back', label: 'Back')
lock2.attrs.lockCodes = JsonOutput.toJson(['2': [name: 'SBA: Owner', code: '50500002']])
app.codeLocks = [lock, lock2]
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.state.lockMgmtLastEnabled = true
app.fakeNow = 1_800_000_000_000L
byte[] keyBytes = (1..32).collect { (byte) (it * 5) } as byte[]
String KEY = keyBytes.encodeBase64().toString()
Map lockCfg = [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['9': [adopted: [27, 28, 30]]], fingerprintKey: KEY]
app.request = [JSON: lockCfg]
check('config accepted', app.apiSetLockConfig().status == 200)
app.approvedAdoptedSlots = ['9:28', '9:30']

List responses = []
def relocate = { def slot, Map b, String lockId = '9' ->
    app.params = [lockId: lockId, slot: slot.toString()]
    app.request = [JSON: b]
    def r = app.apiRelocateCode()
    responses << r
    r
}
def results = {
    app.posts.findAll { it.params.body?.contains('"relocate_result"') }.collect {
        def b = new JsonSlurper().parseText(it.params.body)
        b + [d: new JsonSlurper().parseText(b.data)]
    }
}
def verifyJobs = { app.scheduled.findAll { it.handler == 'verifyCodeChanges' } }
def pending = { app.state.pendingCodeChanges ?: [:] }
def slotOf = { Integer s -> lock.codes()[s.toString()] }
def putSlot = { Integer s, String name, def code -> lock.edit { if (name == null) it.remove(s.toString()) else it[s.toString()] = [name: name, code: code] } }
// Runs the next verification job; after each one the code must be in one of
// the relocate's slots (never in neither).
boolean neverNeither = true
def tick = { String code = null, List slots = [] ->
    def job = verifyJobs()[0]
    if (!job) return null
    app.scheduled.remove(job)
    app.fakeNow += (job.secs as long) * 1000L
    app.verifyCodeChanges()
    if (code && !slots.any { slotOf(it)?.code == code }) neverNeither = false
    job
}
def settle = { String code = null, List slots = [] -> while (tick(code, slots)) {} }
def fresh = { app.state.writeTimes = []; lock.calls.clear(); lock.dropSets = 0; lock.dropDeletes = 0; lock.unreadableReads = 0; lock.storedName = null; lock.throwOnSet = false }
def fp = { String code -> app.codeFingerprint(keyBytes, code) }
String RID = '3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b'

// --- Rename keeps the code ---
fresh()
def rn = relocate(2, [name: 'SBA: Owner Renamed', requestId: RID.toUpperCase()])
check('rename: 202 {lockId, fromSlot, toSlot, status, requestId}', rn.status == 202 && body(rn) == [lockId: '9', fromSlot: 2, toSlot: 2, status: 'pending', requestId: RID])
check('rename: one setCode on the same slot with the new name, no delete', lock.calls == [['setCode', 2, 'SBA: Owner Renamed']])
check('rename: pending keyed by the slot, set phase', pending()['9:2']?.action == 'relocate' && pending()['9:2'].phase == 'set' && pending()['9:2'].fromSlot == 2)
check('rename: first check at 30s', verifyJobs()*.secs == [30])
settle('50500002', [2])
def rr = results()[-1]
check('rename: confirmed relocate_result event', rr.name == 'relocate_result' && rr.value == 'confirmed' && rr.deviceId == '9' && rr.id == "relocate_result:${RID}:${rr.id.split(':')[2]}:confirmed".toString())
check('rename: result data', rr.d == [lockId: '9', slot: 2, action: 'relocate', result: 'confirmed', attempts: 1, requestId: RID, name: 'SBA: Owner Renamed',
    fromSlot: 2, toSlot: 2, targetConfirmed: true, fingerprint: fp('50500002')])
check('rename: same code, new name on the lock', slotOf(2) == [name: 'SBA: Owner Renamed', code: '50500002'])
check('rename: change cleared', pending().isEmpty() && !verifyJobs())

// toSlot equal to the slot is a rename too
fresh()
def rn2 = relocate(2, [name: 'SBA: Owner', toSlot: 2])
check('rename via toSlot == slot: 202, toSlot 2', rn2.status == 202 && body(rn2).toSlot == 2)
settle('50500002', [2])
check('rename via toSlot == slot: confirmed, no delete', results()[-1].value == 'confirmed' && !lock.calls.any { it[0] == 'deleteCode' } && slotOf(2) == [name: 'SBA: Owner', code: '50500002'])

// Rename the lock doesn't apply: source left as it was
fresh()
lock.dropSets = 99
relocate(2, [name: 'SBA: Never'])
settle('50500002', [2])
check('rename not applied: failed not_applied after 3 sends, slot untouched', results()[-1].d.reason == 'not_applied' && results()[-1].d.targetConfirmed == false &&
    !results()[-1].d.containsKey('fingerprint') && lock.calls.count { it[0] == 'setCode' } == 3 && slotOf(2) == [name: 'SBA: Owner', code: '50500002'])
check('rename not applied: not held for retry (no code kept)', pending().isEmpty())

// --- A move confirms the new slot before deleting the old one ---
fresh()
def mv = relocate(12, [name: 'SBA: Chris', toSlot: 3])
check('move: 202 fromSlot 12 toSlot 3', mv.status == 202 && body(mv).fromSlot == 12 && body(mv).toSlot == 3 && body(mv).requestId ==~ /[0-9a-f-]{36}/)
check('move: only the new slot written at first', lock.calls == [['setCode', 3, 'SBA: Chris']] && slotOf(12)?.code == '50500012')
check('move: pending keyed by the target', pending()['9:3']?.fromSlot == 12 && pending()['9:3'].phase == 'set')
tick('50500012', [3, 12])
check('move: delete sent only after the target confirmed', lock.calls == [['setCode', 3, 'SBA: Chris'], ['deleteCode', 12]] && pending()['9:3'].phase == 'delete' && pending()['9:3'].attempts == 1)
check('move: no result yet', !results().any { it.d.toSlot == 3 })
settle('50500012', [3, 12])
def mr = results()[-1]
check('move: confirmed with target fingerprint', mr.value == 'confirmed' && mr.d.fromSlot == 12 && mr.d.toSlot == 3 && mr.d.slot == 3 && mr.d.targetConfirmed == true &&
    mr.d.fingerprint == fp('50500012') && mr.d.name == 'SBA: Chris')
check('move: code now only in the new slot', slotOf(3) == [name: 'SBA: Chris', code: '50500012'] && slotOf(12) == null)
check('move: never in neither slot', neverNeither)

// Leading-zero code moves as a string
fresh()
relocate(13, [name: 'SBM: Zero', toSlot: 17])
settle('05050013', [13, 17])
check('move: leading-zero code kept exactly', results()[-1].value == 'confirmed' && slotOf(17)?.code == '05050013' && slotOf(13) == null)

// --- An unconfirmed move leaves the source untouched ---
fresh()
lock.dropSets = 99
relocate(15, [name: 'SBM: Busy', toSlot: 18])
settle('50500015', [15, 18])
def um = results()[-1]
check('unconfirmed move: failed not_applied, target not confirmed, no fingerprint', um.value == 'failed' && um.d.reason == 'not_applied' && um.d.targetConfirmed == false && !um.d.containsKey('fingerprint') && um.d.attempts == 3)
check('unconfirmed move: never deleted the source', !lock.calls.any { it[0] == 'deleteCode' } && lock.calls.count { it == ['setCode', 18, 'SBM: Busy'] } == 3)
check('unconfirmed move: source as it was', slotOf(15) == [name: 'SBM: Busy', code: '50500015'] && slotOf(18) == null)
check('unconfirmed move: dropped, retry -> 404', pending().isEmpty() && { app.params = [lockId: '9', slot: '18']; app.state.retryTimes = []; app.apiRetryCode().status == 404 }())

// The target applied but the source delete is dropped: code in both, reported
fresh()
lock.dropDeletes = 99
relocate(15, [name: 'SBM: Busy Two', toSlot: 18])
settle('50500015', [15, 18])
def nd = results()[-1]
check('source not deleted: failed source_not_deleted, target confirmed + fingerprint', nd.d.reason == 'source_not_deleted' && nd.d.targetConfirmed == true && nd.d.fingerprint == fp('50500015') && nd.d.attempts == 3)
check('source not deleted: code in both slots', slotOf(18)?.code == '50500015' && slotOf(15)?.code == '50500015' && lock.calls.count { it == ['deleteCode', 15] } == 3)
putSlot(18, null, null)

// Target overwritten during the delete phase: stop deleting
fresh()
lock.dropDeletes = 1
relocate(15, [name: 'SBM: Busy Three', toSlot: 19])
tick()
putSlot(19, 'SBM: Intruder', '50500099')
settle()
def tc = results()[-1]
check('target changed: failed target_changed, no further delete', tc.d.reason == 'target_changed' && lock.calls.count { it == ['deleteCode', 15] } == 1)
check('target changed: source still holds the code', slotOf(15) == [name: 'SBM: Busy', code: '50500015'])
putSlot(19, null, null)

// Source changed before the delete: not deleted
fresh()
relocate(15, [name: 'SBM: Busy Four', toSlot: 19])
putSlot(15, 'SBM: Someone', '50500098')
settle()
def sc = results()[-1]
check('source changed: failed source_changed, target confirmed, no delete', sc.d.reason == 'source_changed' && sc.d.targetConfirmed == true && !lock.calls.any { it[0] == 'deleteCode' })
check('source changed: someone else\'s code left alone', slotOf(15) == [name: 'SBM: Someone', code: '50500098'] && slotOf(19)?.code == '50500015')
putSlot(15, 'SBM: Busy', '50500015'); putSlot(19, null, null)

// Source emptied by someone else before the delete: confirmed, nothing deleted
fresh()
relocate(15, [name: 'SBM: Busy', toSlot: 19])
putSlot(15, null, null)
settle()
check('source already empty: confirmed without a delete', results()[-1].value == 'confirmed' && !lock.calls.any { it[0] == 'deleteCode' } && slotOf(19)?.code == '50500015')
putSlot(15, 'SBM: Busy', '50500015'); putSlot(19, null, null)

// Source changed while the set is still unconfirmed: no re-send
fresh()
lock.dropSets = 1
relocate(15, [name: 'SBM: Busy', toSlot: 20])
tick(); putSlot(15, 'SBM: Other', '50500097'); tick()
check('set phase, source changed: failed slot_changed, not re-sent', results()[-1].d.reason == 'slot_changed' && lock.calls.count { it[0] == 'setCode' } == 1 && results()[-1].d.targetConfirmed == false)
putSlot(15, 'SBM: Busy', '50500015')

// --- Approved adopted slots ---
fresh()
def ad = relocate(28, [name: 'Legacy Renamed'])
check('adopted rename needs SBA:/SBM:', ad.status == 403 && body(ad).error == 'name_not_allowed')
relocate(28, [name: 'SBA: Legacy'])
settle('50500028', [28])
check('adopted rename: confirmed, approval kept', results()[-1].value == 'confirmed' && slotOf(28) == [name: 'SBA: Legacy', code: '50500028'] && app.approvedAdoptedSlots == ['9:28', '9:30'])
fresh()
relocate(28, [name: 'SBA: Legacy', toSlot: 6])
settle('50500028', [6, 28])
check('adopted move: confirmed into the admin range', results()[-1].value == 'confirmed' && slotOf(6)?.code == '50500028' && slotOf(28) == null)
check('adopted move: the old slot\'s approval goes with the code', app.approvedAdoptedSlots == ['9:30'])
fresh()
def again = relocate(28, [name: 'SBA: x', toSlot: 7])
check('adopted move: emptied slot no longer allowed', again.status == 403)

// --- Refusals: no command sent, nothing held ---
fresh()
int resultsBefore = results().size()
def refused = { String label, def slot, Map b, int status, String error, String lockId = '9' ->
    def r = relocate(slot, b, lockId)
    check("refused: ${label} -> ${status} ${error}", r.status == status && body(r)?.error == error && lock.calls.isEmpty() && lock2.calls.isEmpty() && pending().isEmpty())
}
refused('source holds a booking code (approved adopted)', 30, [name: 'SBA: x'], 403, 'booking_code')
refused('new name is a booking name', 2, [name: 'SBA: ORB12'], 403, 'name_not_allowed')
refused('new name is a booking name on a move', 2, [name: 'SBA: GT:ORB1-x', toSlot: 7], 403, 'name_not_allowed')
refused('new name without SBA:/SBM: (admin target)', 2, [name: 'Owner'], 403, 'name_not_allowed')
refused('new name class differs from the target range', 15, [name: 'SBA: Busy', toSlot: 21], 403, 'name_not_allowed')
refused('new name class differs on rename', 15, [name: 'SBA: Busy'], 403, 'name_not_allowed')
refused('target occupied', 2, [name: 'SBM: Owner', toSlot: 16], 409, 'slot_occupied')
refused('target above the ceiling', 2, [name: 'SBA: Owner', toSlot: 26], 403, 'slot_not_allowed')
refused('target an approved adopted slot above the ceiling', 2, [name: 'SBA: Owner', toSlot: 30], 403, 'slot_not_allowed')
refused('target outside every range', 2, [name: 'SBA: Owner', toSlot: 1000], 403, 'slot_not_allowed')
refused('toSlot not a number', 2, [name: 'SBA: Owner', toSlot: 'abc'], 400, 'invalid_slot')
refused('toSlot 0', 2, [name: 'SBA: Owner', toSlot: 0], 400, 'invalid_slot')
refused('toSlot fractional', 2, [name: 'SBA: Owner', toSlot: 3.5], 400, 'invalid_slot')
lock.attrs.maxCodes = '20'
refused('target above maxCodes', 2, [name: 'SBM: Owner', toSlot: 22], 400, 'invalid_slot')
lock.attrs.maxCodes = '30'
refused('source unmarked in the admin range', 5, [name: 'SBA: Mine'], 403, 'slot_not_managed')
refused('source empty', 7, [name: 'SBA: x'], 404, 'slot_empty')
refused('source an unapproved adopted slot', 27, [name: 'SBA: x', toSlot: 7], 403, 'slot_not_allowed')
refused('source above the ceiling, not adopted', 29, [name: 'SBM: x', toSlot: 21], 403, 'slot_not_allowed')
refused('source slot invalid', 'x', [name: 'SBA: x'], 400, 'invalid_slot')
refused('numeric code (leading zeros unknown)', 14, [name: 'SBM: Numeric 2'], 409, 'code_not_copyable')
refused('code shorter than 4 digits', 24, [name: 'SBM: Short 2'], 409, 'code_not_copyable')
refused('code not all digits', 25, [name: 'SBM: Letters 2'], 409, 'code_not_copyable')
refused('empty name', 2, [name: '  '], 400, 'invalid_name')
refused('missing name', 2, [toSlot: 7], 400, 'invalid_name')
refused('non-ASCII name', 2, [name: 'SBA: Café'], 400, 'invalid_name')
refused('name too long', 2, [name: 'SBA: ' + 'x' * 60], 400, 'invalid_name')
refused('invalid requestId', 2, [name: 'SBA: x', requestId: 'nope'], 400, 'invalid_request_id')
refused('unknown lock', 2, [name: 'SBA: x'], 404, 'lock_not_found', '99')
refused('lock not listed in the slot rules', 2, [name: 'SBA: x'], 403, 'slot_not_allowed', '8')
app.params = [lockId: '9', slot: '2']; app.request = [JSON: 'not an object']
check('refused: body not an object -> 400', app.apiRelocateCode().status == 400)
check('refusals: no result events', results().size() == resultsBefore)
app.lockManagementEnabled = false
check('refused: lock management off -> 404', relocate(2, [name: 'SBA: x']).status == 404)
app.lockManagementEnabled = true
app.state.remove('sbToken')
check('refused: not connected -> 404', relocate(2, [name: 'SBA: x']).status == 404)
app.state.sbToken = 'ingest-fake'

// --- 409 while a change is pending on either slot ---
fresh()
lock.dropSets = 99
app.params = [lockId: '9']
app.request = [JSON: [slot: 7, code: '50500021', name: 'SBA: Pending Set']]
check('pending: a set on slot 7', app.apiSetCode().status == 202)
lock.calls.clear()
def pt = relocate(2, [name: 'SBA: Owner', toSlot: 7])
check('refused: set pending on the target -> 409 change_pending', pt.status == 409 && body(pt).error == 'change_pending' && lock.calls.isEmpty() && pending().keySet() == ['9:7'] as Set)
app.state.remove('pendingCodeChanges')
putSlot(21, 'SBM: Pending Set', '50500022')
app.params = [lockId: '9']
app.request = [JSON: [slot: 21, code: '50500022', name: 'SBM: Pending Set']]
app.apiSetCode()
lock.calls.clear()
def ps = relocate(21, [name: 'SBM: Moved', toSlot: 22])
check('refused: set pending on the source -> 409 change_pending', ps.status == 409 && body(ps).error == 'change_pending' && lock.calls.isEmpty() && pending().keySet() == ['9:21'] as Set)
app.state.remove('pendingCodeChanges'); app.scheduled.clear(); putSlot(21, null, null)
fresh()
lock.dropSets = 99
check('pending: a move 15 -> 22', relocate(15, [name: 'SBM: Busy', toSlot: 22]).status == 202)
lock.calls.clear()
def again2 = relocate(15, [name: 'SBM: Busy', toSlot: 23])
check('second relocate on the source -> 409', again2.status == 409 && body(again2).error == 'change_pending')
check('second relocate onto the target -> 409', relocate(16, [name: 'SBM: Taken', toSlot: 22]).status == 409)
check('rename of the source while it moves -> 409', relocate(15, [name: 'SBM: Busy 2']).status == 409)
app.params = [lockId: '9']
app.request = [JSON: [slot: 22, code: '50500021', name: 'SBM: Over']]
def setOnTarget = app.apiSetCode()
check('set on the target while it moves -> 409', setOnTarget.status == 409 && body(setOnTarget).error == 'change_pending')
app.request = [JSON: [slot: 15, code: '50500021', name: 'SBM: Over']]
check('set on the source while it moves -> 409', app.apiSetCode().status == 409)
app.params = [lockId: '9', slot: '15']; app.request = [:]
check('delete of the source while it moves -> 409', app.apiDeleteCode().status == 409)
app.params = [lockId: '9', slot: '22']; app.state.retryTimes = []
check('retry on the relocate -> 409', app.apiRetryCode().status == 409)
check('nothing sent and the move still pending', lock.calls.isEmpty() && pending()['9:22']?.status == 'pending' && pending().size() == 1)
check('page: a relocate counts as a change awaiting the lock', { app.paragraphs.clear(); app.mainPage(); app.paragraphs.any { it.contains('Code changes awaiting the lock:</b> 1 in progress') } }())
app.state.remove('pendingCodeChanges'); app.scheduled.clear()

// --- Write rate limit and pending cap (shared with set and delete) ---
fresh()
app.state.writeTimes = (1..29).collect { app.now() }
lock.dropSets = 99
check('limit: 30th write in a minute ok', relocate(2, [name: 'SBA: Owner']).status == 202 && app.state.writeTimes.size() == 30)
app.state.remove('pendingCodeChanges'); app.scheduled.clear()
def lim = relocate(2, [name: 'SBA: Owner'])
check('limit: 31st -> 429 rate_limited, nothing sent', lim.status == 429 && body(lim).error == 'rate_limited' && lock.calls.size() == 1 && pending().isEmpty())
app.params = [lockId: '9']; app.request = [JSON: [slot: 7, code: '50500021', name: 'SBA: x']]
check('limit: set shares the bucket', app.apiSetCode().status == 429)
app.fakeNow += 61000L
fresh()
Map full = [:]
(1..100).each { full["9:${300 + it}".toString()] = [lockId: '9', slot: 300 + it, action: 'delete', requestId: RID, attempts: 3, createdAt: app.now(), status: 'failed', seq: 1] }
app.state.pendingCodeChanges = full
def cap = relocate(2, [name: 'SBA: Owner'])
check('cap: 100 held -> 429 too_many_pending', cap.status == 429 && body(cap).error == 'too_many_pending' && lock.calls.isEmpty())
full.remove('9:301')
full['9:7'] = [lockId: '9', slot: 7, action: 'set', name: 'SBA: Old', code: '50500021', requestId: RID, attempts: 3, createdAt: app.now(), status: 'failed', seq: 1]
app.state.pendingCodeChanges = full
check('cap: a move replacing a held failed change on its target is allowed', relocate(2, [name: 'SBA: Owner', toSlot: 7]).status == 202 && pending()['9:7'].action == 'relocate' && !pending()['9:7'].containsKey('reason'))
app.state.remove('pendingCodeChanges'); app.scheduled.clear()

// --- Rules narrowed mid-move, disable and disconnect ---
fresh()
lock.dropSets = 99
relocate(15, [name: 'SBM: Busy', toSlot: 22])
app.request = [JSON: lockCfg + [managed: [from: 11, to: 20]]]
app.apiSetLockConfig()
check('narrowed rules: in-flight move reported failed slot_not_allowed, dropped', results()[-1].d.reason == 'slot_not_allowed' && results()[-1].d.toSlot == 22 && pending().isEmpty())
app.request = [JSON: lockCfg]
app.apiSetLockConfig()
fresh()
lock.dropSets = 99
relocate(15, [name: 'SBM: Busy', toSlot: 22])
app.request = [JSON: lockCfg + [managed: [from: 16, to: 25]]]
app.apiSetLockConfig()
check('narrowed rules: source no longer allowed -> failed, dropped', results()[-1].d.reason == 'slot_not_allowed' && results()[-1].d.fromSlot == 15 && pending().isEmpty())
app.request = [JSON: lockCfg]
app.apiSetLockConfig()
app.approvedAdoptedSlots = ['9:30']
fresh()
lock.dropSets = 99
relocate(15, [name: 'SBM: Busy', toSlot: 22])
app.appButtonHandler('btnDisconnect')
check('disconnect: in-flight move reported failed, code dropped', results()[-1].d.reason == 'disconnected' && results()[-1].name == 'relocate_result' && app.state.pendingCodeChanges == null)

// --- Registration advertises the capability ---
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.posts.clear()
app.registerWithPlatform()
def reg = new JsonSlurper().parseText(app.posts.find { it.cb == 'registerCallback' }.params.body)
check('register: capabilities [relocate, registered_slots, full_control], version 1.6.0', reg.capabilities == ['relocate', 'registered_slots', 'full_control'] && reg.version == '1.6.0' && reg.lockManagement == true)
app.posts.clear()
app.lockManagementEnabled = false
app.state.lockMgmtRegistered = true
app.registerWithPlatform()
reg = new JsonSlurper().parseText(app.posts.find { it.cb == 'registerCallback' }.params.body)
check('register: disable still well-formed', reg.lockManagement == false && reg.appAccessToken == null)
app.lockManagementEnabled = true

// No fingerprint without a key
app.request = [JSON: lockCfg - [fingerprintKey: KEY]]
app.state.remove('fingerprintKey')
app.apiSetLockConfig()
fresh()
relocate(2, [name: 'SBA: Owner'])
settle()
check('no fingerprint key: confirmed without fingerprint', results()[-1].value == 'confirmed' && !results()[-1].d.containsKey('fingerprint'))

// --- Review fixes (#32) ---
// A source delete is never re-sent unless a fresh read shows the target
// holding the code and the source unchanged.
fresh()
lock.dropDeletes = 1
relocate(15, [name: 'SBM: Busy', toSlot: 19])
tick()
check('resend guard: delete phase started, first delete dropped', pending()['9:19']?.phase == 'delete' && lock.calls.count { it == ['deleteCode', 15] } == 1)
putSlot(19, 'SBM: Intruder', '50500099')
lock.unreadableReads = 1
tick()
lock.unreadableReads = 1
tick()
check('resend guard: target lost behind an unreadable read -> failed target_changed', results()[-1].d.reason == 'target_changed' && pending().isEmpty())
check('resend guard: no second delete, source keeps the code', lock.calls.count { it == ['deleteCode', 15] } == 1 && slotOf(15) == [name: 'SBM: Busy', code: '50500015'])
putSlot(19, null, null)
fresh()
lock.dropDeletes = 1
relocate(15, [name: 'SBM: Busy', toSlot: 19])
tick()
putSlot(15, 'SBM: Someone', '50500098')
lock.unreadableReads = 2
tick(); tick()
check('resend guard: source changed behind an unreadable read -> failed source_changed, no second delete',
    results()[-1].d.reason == 'source_changed' && lock.calls.count { it == ['deleteCode', 15] } == 1 && slotOf(15)?.code == '50500098')
putSlot(15, 'SBM: Busy', '50500015'); putSlot(19, null, null)
fresh()
lock.dropDeletes = 1
relocate(15, [name: 'SBM: Busy', toSlot: 19])
tick()
putSlot(15, null, null)
lock.unreadableReads = 2
tick(); tick()
check('resend guard: source found empty on the fresh read -> confirmed, no second delete', results()[-1].value == 'confirmed' && lock.calls.count { it == ['deleteCode', 15] } == 1)
putSlot(15, 'SBM: Busy', '50500015'); putSlot(19, null, null)

// A driver that throws on the first send: 502, nothing held, write refunded, code not logged
fresh()
lock.throwOnSet = true
app.state.writeTimes = [app.now()]
int logMark = app.logged.size()
def thrown = relocate(15, [name: 'SBM: Busy', toSlot: 19])
check('first send throws: 502 send_error', thrown.status == 502 && body(thrown).error == 'send_error')
check('first send throws: nothing pending, no job, write refunded', pending().isEmpty() && !verifyJobs() && app.state.writeTimes.size() == 1)
check('first send throws: logged by class name only', app.logged.drop(logMark).any { it.contains('IllegalStateException') } && !app.logged.any { it.contains('50500015') || it.contains('setCode(') })
check('first send throws: source untouched', slotOf(15) == [name: 'SBM: Busy', code: '50500015'])

// The lock stores the code under another name: distinct reason, source kept
fresh()
lock.storedName = 'Driver Name'
relocate(15, [name: 'SBM: Busy', toSlot: 19])
settle('50500015', [15, 19])
check('name mismatch: failed target_name_mismatch on the first check, no delete, source kept', results()[-1].d.reason == 'target_name_mismatch' && results()[-1].d.targetConfirmed == false && results()[-1].d.attempts == 1 &&
    !lock.calls.any { it[0] == 'deleteCode' } && slotOf(15) == [name: 'SBM: Busy', code: '50500015'])
putSlot(19, null, null)

// Retry on either slot of a move in flight -> 409
fresh()
lock.dropSets = 99
relocate(15, [name: 'SBM: Busy', toSlot: 19])
app.state.retryTimes = []
app.params = [lockId: '9', slot: '15']
def rs = app.apiRetryCode()
check('retry on a moving source -> 409 change_pending', rs.status == 409 && body(rs).error == 'change_pending')
app.params = [lockId: '9', slot: '19']
check('retry on a moving target -> 409 change_pending', app.apiRetryCode().status == 409)
app.state.remove('pendingCodeChanges'); app.scheduled.clear()

// Rules narrowed after the source delete was sent: the emptied source's approval goes
lock.attrs.maxCodes = '40'
putSlot(31, 'Legacy Three', '50500031')
Map cfg31 = lockCfg - [fingerprintKey: KEY]
cfg31.locks = ['9': [adopted: [27, 28, 30, 31]]]
app.request = [JSON: cfg31]
app.apiSetLockConfig()
app.approvedAdoptedSlots = ['9:30', '9:31']
fresh()
relocate(31, [name: 'SBA: Legacy Three', toSlot: 8])
tick()
check('narrowed after delete: delete sent and applied', pending()['9:8']?.phase == 'delete' && slotOf(31) == null)
app.request = [JSON: cfg31 + [admin: [from: 1, to: 5]]]
app.apiSetLockConfig()
check('narrowed after delete: failed slot_not_allowed', results()[-1].d.reason == 'slot_not_allowed' && pending().isEmpty())
check('narrowed after delete: emptied source approval withdrawn', app.approvedAdoptedSlots == ['9:30'])
// ...but not when the source still holds the code
app.request = [JSON: cfg31]
app.apiSetLockConfig()
putSlot(32, 'Legacy Four', '50500032')
cfg31.locks = ['9': [adopted: [27, 28, 30, 31, 32]]]
app.request = [JSON: cfg31]
app.apiSetLockConfig()
app.approvedAdoptedSlots = ['9:30', '9:32']
fresh()
lock.dropDeletes = 99
relocate(32, [name: 'SBA: Legacy Four', toSlot: 9])
tick()
app.request = [JSON: cfg31 + [admin: [from: 1, to: 5]]]
app.apiSetLockConfig()
check('narrowed after delete, source still holds the code: approval kept', results()[-1].d.reason == 'slot_not_allowed' && app.approvedAdoptedSlots == ['9:30', '9:32'] && slotOf(32)?.code == '50500032')

// --- No code anywhere outside app state and the lock ---
String everything = (app.posts.collect { it.params.body } + app.logged + responses.collect { it.data } + app.scheduled.collect { it.data?.toString() } + app.paragraphs).join('\n')
check('no fake code in any log line, response, event or page', !fakeCodes.any { everything.contains(it) })
check('log lines name lock and slots only', app.logged.any { it == 'SignalBricks Telemetry: relocate requested on Side slot 12 -> 3' } &&
    app.logged.any { it == 'SignalBricks Telemetry: code relocate confirmed on lock 9 slot 12 -> 3 after 1 attempt(s)' })
check('relocate_result data carries only contract keys', results().every {
    (it.d.keySet() - ['lockId', 'slot', 'fromSlot', 'toSlot', 'action', 'result', 'attempts', 'reason', 'requestId', 'name', 'targetConfirmed', 'fingerprint']).isEmpty()
})
check('responses carry only contract keys', responses.findAll { it.status == 202 }.every { body(it).keySet() == ['lockId', 'fromSlot', 'toSlot', 'status', 'requestId'] as Set })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
