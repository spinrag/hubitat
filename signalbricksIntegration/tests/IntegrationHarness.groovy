// Smoke test for SignalBricks Integration with a stubbed Hubitat sandbox:
// app name/version display, code naming, which codes the app claims, and
// which slots reconcile schedules. Fake values only; nothing is sent to a hub.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    Map atomicState = [:]
    Map settings = [:]
    def locks = []
    def minCodePosition = 0
    def idAtEnd = false
    def codeNamePrefix = ''
    List logged = []
    List warned = []
    def log = [info: { logged << it }, warn: { logged << it; warned << it.toString() }, debug: { logged << it }, trace: { logged << it }, error: { logged << it }]
    List scheduled = []
    List paragraphs = []
    void definition(Map m) {}
    void mappings(Closure c) {}
    // Renders the static pages: collects paragraph text.
    void preferences(Closure c) { pages = c }
    Closure pages
    void page(Map m, Closure c = null) { if (c) c() }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) {}
    void href(Map m) {}
    def app = [id: 1]
    def location = [name: 'Test', hub: [name: 'Hub']]
    String getHubUID() { 'hub-fake' }
    String getFullApiServerUrl() { 'https://cloud.test/api/hub/apps/1' }
    String getFullLocalApiServerUrl() { 'http://hub.local/apps/api/1' }
    void runIn(Number secs, String handler, Map opts = [:]) { scheduled << [secs: secs, handler: handler, data: opts?.data] }
    void runOnce(Date d, String handler, Map opts = [:]) { scheduled << [at: d, handler: handler] }
    void schedule(String cron, String handler) { scheduled << [cron: cron, handler: handler] }
    void unschedule() { scheduled.clear() }
    void unschedule(String handler) { scheduled.removeAll { it.handler == handler } }
    void subscribe(Object... args) {}
    List locationEvents = []
    void sendLocationEvent(Map m) { locationEvents << m }
    void unsubscribe() {}
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    // Hubitat's decrypt(): ciphers maps a fake encrypted value to its plain text; 'undecryptable'
    // throws with the value in the message (it must never be logged); anything else gives null
    Map ciphers = [:]
    List decrypted = []
    String decrypt(String s) {
        decrypted << s
        if (s.startsWith('undecryptable')) throw new IllegalStateException("cannot decrypt ${s}")
        return ciphers[s]
    }
    String createAccessToken() { 'tok-fake' }
    // Moves the app's clock (timers in these tests are fired by hand)
    long clockOffset = 0
    long now() { System.currentTimeMillis() + clockOffset }
}

class FakeLock {
    String id; String name; String label
    Map attrs = [:]
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    List deleted = []
    void deleteCode(def pos) { deleted << pos }
    List setCalls = []
    void setCode(def pos, def code, def name) { setCalls << pos }
    int getCodesCalls = 0
    def lastActivity
    def getLastActivity() { lastActivity }
    void getCodes() { getCodesCalls++ }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }

// --- Name next to the version (#20) ---
String version = app.sbAppVersion
check('name: version is dotted numbers', version ==~ /\d+\.\d+\.\d+/)
check('name: app name with version', app.sbAppNameAndVersion == "SignalBricks Integration ${version}")
app.settings = [locks: ['x'] as String[], codeNamePrefix: 'GT:']
app.state.accessToken = 'tok-fake'
app.pages()
check('page: header shows name and version', app.paragraphs.contains("<b>SignalBricks Integration ${version}</b>".toString()))
check('page: name and version shown once, in the header (#36)', app.paragraphs.count { it.contains("SignalBricks Integration ${version}".toString()) } == 1 && !app.paragraphs.any { it.contains('App Version') })
check('page: no prefix warning with a prefix set', !app.paragraphs.any { it.contains('Set a code name prefix') })
app.paragraphs.clear()
app.settings = [locks: ['x'] as String[], codeNamePrefix: '']
app.pages()
check('page: prefix warning when the prefix is empty', app.paragraphs.any { it.contains('Set a code name prefix') })
app.installed()
check('log: installed names app and version', app.logged.any { it.toString() == "SignalBricks Integration ${version} installed" })
check('api: info still carries the bare version', app.sbHttpResponseJson([version: app.sbAppVersion]).headers['X-Hubitat-SB-Version'] == version)

// --- Code names ---
app.codeNamePrefix = 'GT:'
check('naming: <prefix>ORB<id>-<guest>', app.helperGetCodeName([id: '123', guest: 'Ann']) == 'GT:ORB123-Ann')
check('naming: no guest', app.helperGetCodeName([id: '123']) == 'GT:ORB123')
app.idAtEnd = true
check('naming: idAtEnd', app.helperGetCodeName([id: '123', guest: 'Ann']) == 'GT:Ann-ORB123')
app.idAtEnd = false

// --- Which codes the app claims ---
Map codes = [
    '1': [name: 'SBA: Owner', code: '10000001'],
    '12': [name: 'SBM: Cleaner', code: '10000012'],
    '31': [name: 'G:ORB55-Bob', code: '10000031'],
    '40': [name: 'Front desk', code: '10000040'],
    '51': [name: 'GT:ORB77-Cy', code: '10000051'],
]
Map claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(codes)))
check('claims: only its own prefixed ORB codes', claimed.keySet() == ['77'] as Set)
app.codeNamePrefix = ''
claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(codes)))
check('claims: empty prefix claims every ORB code (why a prefix is required)', claimed.keySet() == ['55', '77'] as Set)
app.codeNamePrefix = 'GT:'

// --- Reconcile: delete its stale code, add the current booking above the minimum ---
def lock = new FakeLock(id: '7', name: 'Front', label: 'Front')
lock.attrs.lockCodes = JsonOutput.toJson(codes)
lock.attrs.maxCodes = 60
app.locks = [lock]
app.minCodePosition = 50
Date now = new Date()
Map bookings = ['88': [id: '88', guest: 'Ann', code: '20000088', lockId: ['7'], checkIn: new Date(now.time - 3600_000), checkOut: new Date(now.time + 3600_000)]]
app.scheduled.clear()
app.atomicState.codeAssignments = [:]
app.reconcileDoorCodes(bookings)
def deletes = app.scheduled.findAll { it.handler == 'tryDeleteCode' }
def sets = app.scheduled.findAll { it.handler == 'trySetCode' }
check('reconcile: deletes only its stale code', deletes.collect { it.data.codePosition } == [51])
check('reconcile: sets the booking in the first free slot >= minimum', sets.size() == 1 && sets[0].data.codePosition == 50 && sets[0].data.codeName == 'GT:ORB88-Ann')

// A lock with no free slot from the minimum up to maxCodes gets no code
lock.attrs.maxCodes = 40
app.scheduled.clear()
app.logged.clear()
app.reconcileDoorCodes(bookings)
check('reconcile: no free position -> no set, warning logged', !app.scheduled.any { it.handler == 'trySetCode' } && app.logged.any { it.toString().contains('no free code position') })

// --- Changing the code name prefix (GT: -> G:) and the guest name ---
// Fake booking ids, guests and codes only.
def prefixRun = { Map lockCodes, Map assignments, Map bks ->
    lock.attrs.lockCodes = JsonOutput.toJson(lockCodes)
    lock.attrs.maxCodes = 60
    app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(assignments))
    app.scheduled.clear()
    app.reconcileDoorCodes(bks)
    [sets: app.scheduled.findAll { it.handler == 'trySetCode' }.collect { it.data },
     deletes: app.scheduled.findAll { it.handler == 'tryDeleteCode' }.collect { it.data.codePosition },
     jobs: app.scheduled.collect { it }]
}
def booking77 = { Map extra = [:] -> ['77': [id: '77', guest: 'Cy', code: '20000077', lockId: ['7'], checkIn: new Date(now.time - 3600_000), checkOut: new Date(now.time + 3600_000)] + extra] }
Map tracked51 = ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'GT:ORB77-Cy', attempt: 1, confirmed: true]]]
Map ourCode = ['51': [name: 'GT:ORB77-Cy', code: '20000077']]
// The other booking app's code left on the lock after that app was removed
Map leftover = ['31': [name: 'G:ORB55-Bob', code: '10000031']]
app.minCodePosition = 50
app.codeNamePrefix = 'G:'
app.settings = [:]

def r = prefixRun(leftover + ourCode, tracked51, booking77())
check('prefix change: a tracked old-prefix code stays in its slot (no new slot, no rewrite)', r.sets.isEmpty() && !r.deletes.contains(51))
check('prefix change: a leftover code with the new prefix and no current booking is removed', r.deletes == [31])
r = prefixRun(ourCode, tracked51, booking77(guest: 'VIP Cy'))
check('guest name change only: no rewrite, the code keeps its old name until checkout', r.sets.isEmpty() && r.deletes.isEmpty())
r = prefixRun(ourCode, tracked51, booking77(code: '20000078'))
check('prefix change: a new code for the booking goes into the same slot with the new name', r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.sets[0].codeName == 'G:ORB77-Cy' && r.deletes.isEmpty())
r = prefixRun(ourCode, tracked51, [:])
check('prefix change: a tracked old-prefix code is removed after checkout', r.deletes == [51] && r.sets.isEmpty())

// Untracked old-prefix codes (state reset or app reinstalled): only the previous prefix keeps them
r = prefixRun(ourCode, [:], [:])
check('untracked old-prefix code without a previous prefix: not this app\'s (left on the lock)', r.deletes.isEmpty())
r = prefixRun(ourCode, [:], booking77())
check('untracked old-prefix code without a previous prefix: the booking gets a second slot', r.sets.size() == 1 && r.sets[0].codePosition == 50)
app.settings = [previousCodeNamePrefix: 'GT:']
r = prefixRun(ourCode, [:], [:])
check('previous prefix: an untracked old-prefix code is removed after checkout', r.deletes == [51])
r = prefixRun(ourCode, [:], booking77())
check('previous prefix: an untracked old-prefix code for a current booking is kept, no second slot', r.sets.isEmpty() && r.deletes.isEmpty())
claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(codes)))
check('previous prefix: claims codes with either prefix, never SBA:/SBM: or unprefixed names', claimed.keySet() == ['55', '77'] as Set)
app.settings = [:]

// The removed app's code and this app's tracked code for the same booking
Map sameBooking = ['31': [name: 'G:ORB77-Cy', code: '10000031']] + ourCode
r = prefixRun(sameBooking, tracked51, booking77())
check('same booking in two slots: the tracked slot wins, the other slot is not rewritten', r.sets.isEmpty() && r.deletes.isEmpty())
r = prefixRun(sameBooking, tracked51, [:])
check('same booking in two slots, after checkout: the tracked slot goes first', r.deletes == [51])
def delJob = r.jobs.find { it.handler == 'tryDeleteCode' }
def retryJob = r.jobs.find { it.handler == 'reconcileDoorCodes' }
check('same booking in two slots, after checkout: a retry reconcile runs after the delete', retryJob != null && retryJob.secs > delJob.secs)
// The scheduled delete runs and the lock reports the slot gone
app.tryDeleteCode(delJob.data)
check('same booking in two slots, after checkout: the delete reached the lock, tracking kept until confirmed', lock.deleted == [51] && app.atomicState.codeAssignments['7']['51'].deleting == true)
r = prefixRun(['31': [name: 'G:ORB77-Cy', code: '10000031']], app.atomicState.codeAssignments, [:])
check('same booking in two slots, after checkout: the next reconcile removes the other slot', r.deletes == [31])
check('same booking in two slots, after checkout: the lock reports slot 51 empty, its tracking is pruned', !app.atomicState.codeAssignments['7']?.containsKey('51'))

// The tracked slot was reassigned outside the app: trust it only when the lock agrees
Map staleTracked51 = ['7': ['51': [bookingId: 'ORB77', code: '20000070', codeName: 'GT:ORB77-Cy', attempt: 1, confirmed: true]]]
Map named52 = ['52': [name: 'G:ORB77-Cy', code: '20000077']]
r = prefixRun(['51': [name: 'Plumber', code: '30000051']] + named52, staleTracked51, booking77())
check('tracked slot now holds a foreign code: slot 51 is neither written nor deleted', !r.sets.any { it.codePosition == 51 } && !r.deletes.contains(51) && r.sets.isEmpty())
Map booking88 = ['88': [id: '88', guest: 'Di', code: '20000088', lockId: ['7'], checkIn: new Date(now.time - 3600_000), checkOut: new Date(now.time + 3600_000)]]
r = prefixRun(['51': [name: 'G:ORB88-Di', code: '20000088']] + named52, staleTracked51, booking77() + booking88)
check('tracked slot now holds another current booking: no rewrite, no delete', r.sets.isEmpty() && r.deletes.isEmpty())

// Deletes: tracking is kept until the lock confirms (drivers that drop code names)
def tracked = { Map a -> new JsonSlurper().parseText(JsonOutput.toJson(['7': a])) }
def assigned = { String pos -> app.atomicState.codeAssignments['7']?.get(pos) }
Map nameless51 = ['51': [code: '20000077']]
r = prefixRun(nameless51, tracked51, [:])
check('nameless driver: a normal checkout still deletes the tracked slot', r.deletes == [51])
lock.deleted.clear()
app.tryDeleteCode([lockId: '7', codePosition: 51])
check('nameless driver: the delete is sent and the slot is marked deleting', lock.deleted == [51] && assigned('51')?.deleting == true)
// L: the delete was lost, the lock still reports the code
r = prefixRun(nameless51, app.atomicState.codeAssignments, [:])
check('L: lost delete on a nameless driver: the next reconcile deletes it again', r.deletes == [51] && r.jobs.any { it.handler == 'reconcileDoorCodes' })
def codeEvent = { String value, int slot -> [device: [id: '7'], value: value, data: JsonOutput.toJson([codeNumber: slot]), name: 'codeChanged'] }
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true, deleting: true]])
app.codeChangedHandler(codeEvent('deleted', 51))
check('confirmed delete (codeChanged deleted) clears tracking', assigned('51') == null)
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true, deleting: true]])
r = prefixRun([:] + ['40': [name: 'Front desk', code: '10000040']], app.atomicState.codeAssignments, [:])
check('a deleting slot the lock reports empty is pruned', r.deletes.isEmpty() && assigned('51') == null)
Map pendingSet = ['50': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: false]]
r = prefixRun(['40': [name: 'Front desk', code: '10000040']], ['7': pendingSet], [:])
check('a pending setCode on an empty slot is not pruned', assigned('50')?.confirmed == false)
app.atomicState.codeAssignments = tracked(pendingSet)
app.atomicState.bookings = booking77()
app.scheduled.clear()
app.verifySetCode([lockId: '7', codePosition: 50, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('a pending setCode keeps retrying', app.scheduled.any { it.handler == 'verifySetCode' })
app.atomicState.bookings = [:]
app.atomicState.codeAssignments = tracked(['50': pendingSet['50'] + [deleting: true]])
app.scheduled.clear()
app.verifySetCode([lockId: '7', codePosition: 50, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('a setCode retry stops once its slot is being deleted', app.scheduled.isEmpty())

// Upgraded state: stale tracking from earlier versions (deletes never cleared it)
Map stale = ['31': [bookingId: 'ORB12', code: '20000012', codeName: 'GT:ORB12-Ed', attempt: 1, confirmed: true],
             '32': [bookingId: 'ORB13', code: '20000013', codeName: 'GT:ORB13-Flo', attempt: 1, confirmed: true],
             '33': [bookingId: 'ORB14', code: '20000014', codeName: 'GT:ORB14-Gil', attempt: 1, confirmed: true],
             '34': [bookingId: 'ORB15', code: '20000015', codeName: 'GT:ORB15-Hu', attempt: 1, confirmed: true]]
Map upgraded = ['31': [name: 'G:ORB99-Ivy', code: '10000099'],  // U1: another app's booking code in a reused slot
                '32': [name: 'Plumber', code: '30000032']]        // U2: a named code someone added
                                                                   // 34: empty now
app.settings = [:]
app.codeNamePrefix = 'GT:'
r = prefixRun(upgraded, ['7': stale], [:])
check('U1/U2: stale tracking never deletes another app\'s or a named code', r.deletes.isEmpty())
check('U1/U2: and their stale entries are pruned', assigned('31') == null && assigned('32') == null)
check('a confirmed entry of an empty slot is kept (lockCodes can be partial)', assigned('34') != null)
r = prefixRun(['33': [code: '30000033']], ['7': stale], [:])
check('U3: a nameless code in a stale tracked slot is still treated as the app\'s (nameless drivers need it)', r.deletes == [33] && assigned('33') != null)
app.codeNamePrefix = 'G:'

// P1: a partial or empty lockCodes report must not lose a nameless current code
app.codeNamePrefix = 'G:'
r = prefixRun(nameless51, tracked51, booking77())
check('P1: nameless current code in its tracked slot: nothing to do', r.sets.isEmpty() && r.deletes.isEmpty())
app.atomicState.lockCodesReadEmpty = []
r = prefixRun([:], app.atomicState.codeAssignments, booking77())
check('P1: lock reports no codes (not read yet): tracking kept, the booking is written only to its own slot', assigned('51')?.confirmed == true && r.sets.collect { it.codePosition } == [51])
app.atomicState.codeRefresh = [runId: 1L, lockIds: ['7'], index: 0, stepAt: System.currentTimeMillis(), budgetSec: 600, floorSec: 0]
r = prefixRun(['40': [name: 'Front desk', code: '10000040']], app.atomicState.codeAssignments, booking77())
check('P1: lock being read, partial list without the slot: written only to its own slot', assigned('51')?.confirmed == true && r.sets.collect { it.codePosition } == [51])
app.atomicState.codeRefresh = null
app.atomicState.lockCodesReadEmpty = []
app.atomicState.codeWritesDeferred = [:]
r = prefixRun(['40': [name: 'Front desk', code: '10000040']], app.atomicState.codeAssignments, booking77())
check('P1: partial lockCodes without the slot: tracking kept, no second slot', assigned('51')?.confirmed == true && r.sets.collect { it.codePosition } == [51])
r = prefixRun(nameless51, app.atomicState.codeAssignments, [:])
check('P1: the code reappears and is deleted at checkout', r.deletes == [51] && r.sets.isEmpty())
// While a door code refresh reads the lock, nothing is pruned
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true, deleting: true]])
app.atomicState.codeRefresh = [runId: 1L, lockIds: ['7'], index: 0, stepAt: System.currentTimeMillis(), budgetSec: 600, floorSec: 0]
app.helperPruneAssignments('7', ['40': [name: 'Front desk', code: '10000040']])
check('refresh reading the lock: a deleting entry of an empty slot is not pruned yet', assigned('51')?.deleting == true)
app.atomicState.codeRefresh = null
app.helperPruneAssignments('7', ['40': [name: 'Front desk', code: '10000040']])
check('after the refresh: it is pruned', assigned('51') == null)

// --- #55: no first-free writes or rewrites while a lock's codes are unread or being read ---
// Fake booking ids, guests and codes only.
def lock8 = new FakeLock(id: '8', name: 'Back', label: 'Back')
lock8.attrs.lockCodes = JsonOutput.toJson(['40': [name: 'Front desk', code: '10000040']])
lock8.attrs.maxCodes = 60
app.locks = [lock, lock8]
def reset55 = {
    app.atomicState.lockCodesReadEmpty = []
    app.atomicState.codeWritesDeferred = [:]
    app.atomicState.codeAutoReadAt = [:]
    app.atomicState.codeRefresh = null
    app.atomicState.reconcileRetries = 0
    app.atomicState.lockCodesReadIncomplete = []
    app.clockOffset = 0
    lock.lastActivity = null
    lock.getCodesCalls = 0
}
reset55()
Map pastOwn = ['53': [name: 'G:ORB66-Jo', code: '20000066']]
Map frontDesk = ['40': [name: 'Front desk', code: '10000040']]
def booking77on = { List lockIds -> booking77().collectEntries { k, b -> [(k): b + [lockId: lockIds]] } }
def setsOn = { String lockId -> app.scheduled.findAll { it.handler == 'trySetCode' && "${it.data.lockId}" == lockId }.collect { it.data.codePosition } }
int logMark = 0
def mark = { logMark = app.logged.size() }
def newLogs = { app.logged.drop(logMark).collect { it.toString() } }
def deferLog = { String lockId -> newLogs().find { it.startsWith("reconcileDoorCodes: lock ${lockId} ") && it.contains('deferring') } }
def reading = { String lockId -> app.atomicState.codeRefresh = [runId: 5L, lockIds: [lockId], index: 0, stepAt: System.currentTimeMillis(), budgetSec: 600, floorSec: 0] }
def reconciles = { app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.secs } }
// Run the pending job for a handler, as the hub scheduler would
def fireJob = { String handler ->
    def job = app.scheduled.find { it.handler == handler }
    if (!job) return false
    app.scheduled.remove(job)
    app."$handler"(job.data)
    return true
}
// Lock 7 (being read, floor passed) sends lockCodes; its settle timer fires with the other jobs cleared
def settleStep = { ->
    app.codeRefreshOnLockCodes([deviceId: '7'])
    def job = app.scheduled.find { it.handler == 'codeRefreshSettled' }
    app.scheduled.clear()
    if (job) app.codeRefreshSettled(job.data)
    return job != null
}
String heldIncomplete = 'reconcileDoorCodes: lock 7 (Front) has not finished a read of its codes, deferring 1 code writes'

// Being read by a refresh: a partial list must not decide which slot is free
reading('7')
mark()
r = prefixRun(frontDesk + pastOwn, [:], booking77on(['7', '8']))
check('#55 being read: no first-free write on that lock', setsOn('7').isEmpty())
check('#55 being read: deletes on that lock still happen', r.deletes == [53])
check('#55 being read: other locks keep working', setsOn('8') == [50])
check('#55 being read: log names the lock and counts the deferred writes', deferLog('7') == 'reconcileDoorCodes: lock 7 (Front) is being read by a door code refresh, deferring 1 code writes')
check('#55 being read: a retry reconcile is scheduled, no second read started', reconciles() && app.atomicState.codeWritesDeferred == ['7': 1] && app.atomicState.codeRefresh.runId == 5L && lock.getCodesCalls == 0)
r = prefixRun(frontDesk + ourCode, tracked51, booking77(code: '20000078'))
check('#55 being read: a changed code is not rewritten either', setsOn('7').isEmpty())
app.paragraphs.clear()
app.settings = [locks: ['x'] as String[], codeNamePrefix: 'G:']
app.pages()
check('#55 page: the waiting lock is shown', app.paragraphs.any { it.contains('Front: 1 code(s) waiting for a door code read') })
app.settings = [:]

// The refresh reads the lock: it is reconciled again shortly after
lock.attrs.lockCodes = JsonOutput.toJson(frontDesk)
settleStep()
check('#55 read finished: the deferred lock is reconciled again', app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.secs == 10 } && app.atomicState.codeWritesDeferred == [:])
check('#55 read finished with other codes: not marked read-empty', app.atomicState.lockCodesReadEmpty == [])
app.atomicState.codeRefresh = null
r = prefixRun(frontDesk, [:], booking77on(['7']))
check('#55 after the read: the write proceeds', setsOn('7') == [50])
// A read that times out with a readable list of codes: unchanged (not marked, writes proceed)
reset55()
lock.attrs.lockCodes = JsonOutput.toJson(frontDesk)
reading('7')
app.atomicState.codeWritesDeferred = ['7': 1]
app.codeRefreshOnLockCodes([deviceId: '7'])
app.scheduled.clear()
mark()
app.codeRefreshTimeout([runId: 5L, index: 0])
check('timeout with codes: not marked, deferred lock reconciled again, no extra log', app.atomicState.lockCodesReadEmpty == [] && app.atomicState.codeWritesDeferred == [:] && reconciles() && !newLogs().any { it.contains('did not finish reading') })
app.atomicState.codeRefresh = null
r = prefixRun(frontDesk, [:], booking77on(['7']))
check('timeout with codes: the write proceeds as before', setsOn('7') == [50])

// Upgrade, then checkout and the next check-in on a lock holding only this app's codes
reset55()
mark()
r = prefixRun(ourCode, tracked51, booking77())
check('#55 SB-only lock after the upgrade: a list of only this app\'s codes marks it', app.atomicState.lockCodesReadEmpty == ['7'])
r = prefixRun(ourCode, tracked51, [:])
check('#55 SB-only lock: checkout deletes the code', r.deletes == [51])
r = prefixRun([:], tracked51, booking88)
check('#55 SB-only lock: the next check-in is written with no operator and no read', setsOn('7') == [50] && deferLog('7') == null && lock.getCodesCalls == 0)

// A new or factory-reset lock with no codes: the app reads it, then writes
reset55()
mark()
r = prefixRun([:], [:], booking77on(['7', '8']))
check('#55 empty, not read: first-free write deferred', setsOn('7').isEmpty() && deferLog('7') == 'reconcileDoorCodes: lock 7 (Front) has not reported its codes, deferring 1 code writes')
check('#55 empty, not read: other locks keep working', setsOn('8') == [50])
check('#55 empty, not read: retried later', reconciles() && app.atomicState.reconcileRetries > 0)
def run55 = app.atomicState.codeRefresh
check('#55 empty, not read: a read of that lock only is started', run55?.lockIds == ['7'] && app.atomicState.codeAutoReadAt?.containsKey('7') && newLogs().contains('reconcileDoorCodes: reading the codes of 1 lock(s) whose code writes are waiting'))
app.codeRefreshNext([runId: run55.runId, index: 0])
check('#55 auto read: getCodes sent to the lock', lock.getCodesCalls == 1)
check('auto read: budget 180 s and floor 156 s timers for the 60-slot lock', app.scheduled.find { it.handler == 'codeRefreshTimeout' }?.secs == 180 && app.scheduled.find { it.handler == 'codeRefreshFloor' }?.secs == 156)
// The driver reads fast and sends its one {} at 30 s, before the floor; the lock is quiet after
app.clockOffset = 30_000
lock.lastActivity = new Date(app.now())
app.codeRefreshOnLockCodes([deviceId: '7'])
check('fast driver: lockCodes before the floor does not settle yet', !app.scheduled.any { it.handler == 'codeRefreshSettled' })
app.clockOffset = 156_000
fireJob('codeRefreshFloor')
check('fast driver: at the floor, the earlier lockCodes starts the 30 s settle', app.scheduled.find { it.handler == 'codeRefreshSettled' }?.secs == 30)
app.clockOffset = 186_000
def settleJob = app.scheduled.find { it.handler == 'codeRefreshSettled' }
app.scheduled.clear()
app.codeRefreshSettled(settleJob.data)
check('fast driver: settled with {}: marked read-empty, reconciled again', app.atomicState.lockCodesReadEmpty == ['7'] && reconciles() && app.atomicState.lockCodesReadIncomplete == [])
app.codeRefreshTimeout([runId: run55.runId, index: 0])
check('settle then the budget timer in the gap: the mark stays, no second advance', app.atomicState.lockCodesReadEmpty == ['7'] && app.scheduled.count { it.handler == 'codeRefreshNext' } == 1)
app.atomicState.codeRefresh = null
app.clockOffset = 0
mark()
r = prefixRun([:], [:], booking77on(['7']))
check('#55 read and empty: the write lands', setsOn('7') == [50] && deferLog('7') == null)
r = prefixRun(frontDesk, [:], [:])
check('#55 the lock later reports another code: read-empty is dropped', app.atomicState.lockCodesReadEmpty == [])
app.atomicState.codeRefresh = [runId: 9L, lockIds: ['8'], index: 0, stepAt: System.currentTimeMillis(), budgetSec: 600, floorSec: 0]
r = prefixRun([:], [:], booking77on(['7']))
check('#55 empty again (driver change): deferred, no read while another lock is read', setsOn('7').isEmpty() && app.atomicState.codeRefresh.runId == 9L)

// Still reading after its last lockCodes (activity later): not settled, the budget ends the step
reset55()
r = prefixRun([:], [:], booking77on(['7']))
run55 = app.atomicState.codeRefresh
app.codeRefreshNext([runId: run55.runId, index: 0])
app.clockOffset = 160_000
app.codeRefreshOnLockCodes([deviceId: '7'])
lock.lastActivity = new Date(app.now() + 20_000)
app.clockOffset = 190_000
settleJob = app.scheduled.find { it.handler == 'codeRefreshSettled' }
app.codeRefreshSettled(settleJob.data)
check('activity after the last lockCodes: not settled, still reading', app.atomicState.codeRefresh?.index == 0 && app.atomicState.codeRefresh.endedIndex == null && app.atomicState.lockCodesReadEmpty == [])
app.scheduled.clear()
mark()
app.codeRefreshTimeout([runId: run55.runId, index: 0])
check('timeout with {}: not marked read-empty, held as an unfinished read', app.atomicState.lockCodesReadEmpty == [] && app.atomicState.lockCodesReadIncomplete == ['7'] && reconciles())
check('timeout with {}: logged, no codes in the line', newLogs().contains('Door code refresh: Front did not finish reading its codes; its new codes stay on hold until a read finishes'))
check('timeout: the refresh moves on as before', app.scheduled.any { it.handler == 'codeRefreshNext' && it.data.index == 1 })
app.atomicState.codeRefresh = null
app.clockOffset = 0
mark()
r = prefixRun([:], [:], booking77on(['7']))
check('timeout with {}: writes still held, no free-slot write', setsOn('7').isEmpty() && r.sets.findAll { "${it.lockId}" == '7' && it.free }.isEmpty() && deferLog('7') == heldIncomplete)
check('timeout with {}: an auto read is started again later', app.atomicState.codeWritesDeferred == ['7': 1] && app.scheduled.any { it.handler == 'codeAutoReadRetry' })
// The next read settles with {}: read empty as before
lock.lastActivity = null
reading('7')
settleStep()
check('settled read after a timeout: marked read-empty, no longer held', app.atomicState.lockCodesReadEmpty == ['7'] && app.atomicState.lockCodesReadIncomplete == [])
app.atomicState.codeRefresh = null
r = prefixRun([:], [:], booking77on(['7']))
check('settled read after a timeout: the write lands', setsOn('7') == [50])

// A read step that times out without any answer leaves the lock unmarked
reset55()
r = prefixRun([:], [:], booking77on(['7']))
run55 = app.atomicState.codeRefresh
app.codeRefreshNext([runId: run55.runId, index: 0])
app.codeRefreshTimeout([runId: run55.runId, index: 0])
check('#55 read timed out with no answer: not marked read-empty', app.atomicState.lockCodesReadEmpty == [])

// A lock read empty before keeps its mark when a later read times out and it still reports {}
reset55()
lock.attrs.lockCodes = '{}'
app.atomicState.lockCodesReadEmpty = ['7']
reading('7')
app.atomicState.codeRefresh = app.atomicState.codeRefresh + [startEmpty: true]
app.codeRefreshTimeout([runId: 5L, index: 0])
check('read-empty lock, timed-out read, still {}: keeps its mark, not held', app.atomicState.lockCodesReadEmpty == ['7'] && app.atomicState.lockCodesReadIncomplete == [])
app.atomicState.codeRefresh = null
r = prefixRun([:], [:], booking77on(['7']))
check('read-empty lock, timed-out read, still {}: the write lands', setsOn('7') == [50])

// A read from an empty list that times out with a partial list: held until a read settles
reset55()
lock.attrs.lockCodes = '{}'
r = prefixRun([:], [:], booking77on(['7']))
run55 = app.atomicState.codeRefresh
app.codeRefreshNext([runId: run55.runId, index: 0])
check('read from {}: noted as starting empty', app.atomicState.codeRefresh.startEmpty == true)
lock.attrs.lockCodes = JsonOutput.toJson(frontDesk)
app.codeRefreshOnLockCodes([deviceId: '7'])
app.codeRefreshTimeout([runId: run55.runId, index: 0])
check('partial list after a timed-out read from {}: held as unfinished', app.atomicState.lockCodesReadIncomplete == ['7'])
app.atomicState.codeRefresh = null
mark()
r = prefixRun(frontDesk, [:], booking77on(['7']))
check('partial list: no first-free write', setsOn('7').isEmpty() && deferLog('7') == heldIncomplete)
r = prefixRun(ourCode, tracked51, booking77())
check('partial list of only this app\'s codes: not marked read-empty', app.atomicState.lockCodesReadEmpty == [])
// A later read that starts from the partial list and times out: still held
reading('7')
app.codeRefreshTimeout([runId: 5L, index: 0])
check('partial list, next read times out too: still held', app.atomicState.lockCodesReadIncomplete == ['7'])
reading('7')
lock.attrs.lockCodes = JsonOutput.toJson(frontDesk)
settleStep()
check('partial list, then a settled read: no longer held', app.atomicState.lockCodesReadIncomplete == [])
app.atomicState.codeRefresh = null
r = prefixRun(frontDesk, [:], booking77on(['7']))
check('after the settled read: the write proceeds', setsOn('7') == [50])
app.scheduled.clear()
reset55()

// A driver that never fills in lockCodes: auto reads at most once per 6 h
reset55()
lock.attrs.remove('lockCodes')
app.reconcileDoorCodes(booking77on(['7']))
run55 = app.atomicState.codeRefresh
app.codeRefreshNext([runId: run55.runId, index: 0])
app.codeRefreshOnLockCodes([deviceId: '7'])
app.codeRefreshTimeout([runId: run55.runId, index: 0])
app.codeRefreshNext([runId: run55.runId, index: 1])
check('#55 no lockCodes: one auto read, then not marked', lock.getCodesCalls == 1 && app.atomicState.codeRefresh == null && app.atomicState.lockCodesReadEmpty == [])
app.reconcileDoorCodes(booking77on(['7']))
app.reconcileDoorCodes(booking77on(['7']))
check('#55 no lockCodes: no second auto read within 6 h, still deferred', app.atomicState.codeRefresh == null && app.atomicState.codeWritesDeferred == ['7': 1] && setsOn('7').isEmpty())
app.atomicState.codeAutoReadAt = ['7': System.currentTimeMillis() - 6 * 3600_000L - 1000]
app.reconcileDoorCodes(booking77on(['7']))
check('#55 no lockCodes: read again after 6 h', app.atomicState.codeRefresh?.lockIds == ['7'])

// The retry loop gives up; the auto read timer still brings the lock back
reset55()
lock.attrs.lockCodes = '{}'
lock.lastActivity = null
app.scheduled.clear()
app.reconcileDoorCodes(booking77on(['7']))
run55 = app.atomicState.codeRefresh
def autoTimer = app.scheduled.findAll { it.handler == 'codeAutoReadRetry' }
check('#55 auto read timer: one timer, about 6 h after the read', lock.getCodesCalls == 0 && autoTimer.size() == 1 && autoTimer[0].secs > 6 * 3600 - 60 && autoTimer[0].secs <= 6 * 3600)
app.codeRefreshNext([runId: run55.runId, index: 0])
app.codeRefreshTimeout([runId: run55.runId, index: 0])
app.codeRefreshNext([runId: run55.runId, index: 1])
app.atomicState.reconcileRetries = 20
app.scheduled.clear()
app.reconcileDoorCodes(booking77on(['7']))
check('#55 auto read timer: retry loop gave up, timer still set, no second read yet', !reconciles() && app.scheduled.any { it.handler == 'codeAutoReadRetry' } && lock.getCodesCalls == 1 && app.atomicState.codeRefresh == null)
app.atomicState.codeAutoReadAt = ['7': System.currentTimeMillis() - 6 * 3600_000L - 1000]
app.atomicState.bookings = booking77on(['7'])
app.codeAutoReadRetry()
run55 = app.atomicState.codeRefresh
app.codeRefreshNext([runId: run55.runId, index: 0])
check('#55 auto read timer fires: the second auto read starts', run55?.lockIds == ['7'] && lock.getCodesCalls == 2)
app.clockOffset = 30_000
lock.lastActivity = new Date(app.now())
app.codeRefreshOnLockCodes([deviceId: '7'])
app.clockOffset = 156_000
fireJob('codeRefreshFloor')
app.clockOffset = 186_000
fireJob('codeRefreshSettled')
app.clockOffset = 0
app.atomicState.codeRefresh = null
r = prefixRun([:], [:], booking77on(['7']))
check('#55 auto read timer: the lock settled empty and the write lands', app.atomicState.lockCodesReadEmpty == ['7'] && setsOn('7') == [50])
app.atomicState.codeRefresh = [runId: 9L, lockIds: ['8'], index: 0, stepAt: System.currentTimeMillis(), budgetSec: 600, floorSec: 0]
app.atomicState.lockCodesReadEmpty = []
app.atomicState.codeAutoReadAt = [:]
app.scheduled.clear()
app.reconcileDoorCodes(booking77on(['7']))
check('#55 auto read timer: not sooner than 15 minutes while another lock is read', app.scheduled.find { it.handler == 'codeAutoReadRetry' }?.secs == 900)
app.atomicState.bookings = [:]

// Retries stop at the cap; a later read of the lock still brings it back
reset55()
lock.attrs.lockCodes = JsonOutput.toJson(frontDesk)
reading('7')
app.atomicState.reconcileRetries = 20
r = prefixRun(frontDesk, [:], booking77on(['7']))
check('#55 deferred past the retry cap: retry loop stops', !reconciles() && app.atomicState.codeWritesDeferred == ['7': 1])
settleStep()
check('#55 past the cap: the next read of the lock reconciles it', reconciles())

// Unreadable lockCodes at the end of a read
lock.attrs.lockCodes = 'not json'
reading('7')
settleStep()
check('#55 unreadable lockCodes at the end of a read: the refresh still moves on, not marked', app.scheduled.any { it.handler == 'codeRefreshNext' } && app.atomicState.lockCodesReadEmpty == [])

// Optional hardening: a first-free pick never lands on another current booking's tracked empty slot
reset55()
app.atomicState.lockCodesReadEmpty = ['7']
r = prefixRun([:], ['7': ['50': [bookingId: 'ORB88', code: '20000088', codeName: 'G:ORB88-Di', attempt: 1, confirmed: true]]], booking77() + booking88)
check('#55 tracked slot of a current booking is not given to another booking', r.sets.collect { [it.codePosition, it.codeName] } as Set == [[50, 'G:ORB88-Di'], [51, 'G:ORB77-Cy']] as Set)
reset55()
app.atomicState.lockCodesReadEmpty = []
r = prefixRun([:], tracked51, [77: booking77()['77'] + [id: 77]])
check('#55 integer booking ids (from atomicState): still written only to the own tracked slot', r.sets.collect { it.codePosition } == [51])
reset55()

// --- #57: encrypted lockCodes (encrypt(JSON) by some Z-Wave drivers) ---
// Fake values only: the "encrypted" strings are made up, decrypt() is a stub.
String enc = 'enc:Zm9vYmFyMDAx'
def encrypt = { String key, Map plain -> app.ciphers[key] = JsonOutput.toJson(plain); key }
app.decrypted.clear()
check('#57 parse: plain JSON is read without decrypt()', app.helperParseLockCodes(JsonOutput.toJson(frontDesk)) == frontDesk && app.decrypted.isEmpty())
check('#57 parse: empty or missing is [:]', app.helperParseLockCodes(null) == [:] && app.helperParseLockCodes('') == [:] && app.helperParseLockCodes('{}') == [:] && app.decrypted.isEmpty())
check('#57 parse: an encrypted value is read through decrypt()', app.helperParseLockCodes(encrypt(enc, frontDesk)) == frontDesk && app.decrypted == [enc])
['null', 'true', 'false', '42', '-7.5'].each { raw ->
    app.decrypted.clear()
    app.ciphers.clear()
    check("#57 parse: '${raw}' (parses to a non-object) is read through decrypt()".toString(), app.helperParseLockCodes(encrypt(raw, frontDesk)) == frontDesk && app.decrypted == [raw])
    app.ciphers.clear()
    check("#57 parse: '${raw}' that decrypt() can't read is unreadable (null)".toString(), app.helperParseLockCodes(raw) == null)
}
app.ciphers.clear()
check('#57 parse: decrypt() returning null, throwing, or a non-object is unreadable (null)',
    app.helperParseLockCodes('enc:none') == null && app.helperParseLockCodes('undecryptable:X1') == null &&
    app.helperParseLockCodes(encrypt('enc:list', [:]).with { app.ciphers[it] = '[1,2]'; it }) == null && app.helperParseLockCodes(encrypt('enc:num', [:]).with { app.ciphers[it] = '42'; it }) == null)
check('#57 parse: a JSON array is not a code list (null)', app.helperParseLockCodes('[1,2]') == null)

// Reconcile reads an encrypted lock like any other
app.ciphers.clear()
lock8.attrs.lockCodes = JsonOutput.toJson(frontDesk)
app.locks = [lock, lock8]
reset55()
lock.attrs.lockCodes = encrypt(enc, frontDesk + pastOwn)
app.atomicState.codeAssignments = [:]
app.scheduled.clear()
mark()
app.reconcileDoorCodes(booking77on(['7', '8']))
check('#57 encrypted lock: reconciled (stale code deleted, booking written to the first free slot)',
    app.scheduled.findAll { it.handler == 'tryDeleteCode' }.collect { it.data.codePosition } == [53] && setsOn('7') == [50] && setsOn('8') == [50])
check('#57 encrypted lock: not on the unreadable list', app.atomicState.lockCodesUnreadable == [])

// An undecryptable lock is skipped; the other locks still reconcile
reset55()
lock.attrs.lockCodes = 'undecryptable:Q2lwaGVy'
app.atomicState.codeAssignments = tracked(['53': [bookingId: 'ORB66', code: '20000066', codeName: 'G:ORB66-Jo', attempt: 1, confirmed: true]])
app.scheduled.clear()
mark()
app.reconcileDoorCodes(booking77on(['7', '8']))
check('#57 undecryptable lock: no write, delete or prune on it', setsOn('7').isEmpty() && !app.scheduled.any { it.handler == 'tryDeleteCode' } && assigned('53') != null)
check('#57 undecryptable lock: the other lock is still reconciled', setsOn('8') == [50])
check('#57 undecryptable lock: one warn line naming the lock id only', newLogs().findAll { it.contains('lockCodes unreadable') } == ['reconcileDoorCodes: lock 7 lockCodes unreadable, skipped'])
check('#57 undecryptable lock: not marked read-empty, not deferred, no auto read',
    app.atomicState.lockCodesReadEmpty == [] && app.atomicState.codeWritesDeferred == [:] && app.atomicState.codeRefresh == null && lock.getCodesCalls == 0)
check('#57 undecryptable lock: listed as unreadable', app.atomicState.lockCodesUnreadable == ['7'])
app.paragraphs.clear()
app.settings = [locks: ['x'] as String[], codeNamePrefix: 'G:']
app.pages()
check('#57 page: the unreadable lock is shown under Waiting', app.paragraphs.any { it.contains("<b>Waiting:</b> Front: its lockCodes can't be read") })
app.settings = [:]
// Was marked read-empty before: still skipped, the mark is not used to write into a free slot
app.atomicState.lockCodesReadEmpty = ['7']
app.scheduled.clear()
app.reconcileDoorCodes(booking77on(['7']))
check('#57 undecryptable lock marked empty earlier: still no write', setsOn('7').isEmpty())
// Readable again: back to normal and off the list
reset55()
lock.attrs.lockCodes = encrypt(enc, frontDesk)
app.scheduled.clear()
app.reconcileDoorCodes(booking77on(['7']))
check('#57 readable again: reconciled, off the unreadable list', setsOn('7') == [50] && app.atomicState.lockCodesUnreadable == [])

// A read that ends on an undecryptable lock: not empty, so not marked; an encrypted {} is
reset55()
lock.attrs.lockCodes = 'undecryptable:RW1wdHk'
reading('7')
settleStep()
check('#57 read ends with undecryptable lockCodes: refresh moves on, not marked read-empty', app.scheduled.any { it.handler == 'codeRefreshNext' } && app.atomicState.lockCodesReadEmpty == [])
lock.attrs.lockCodes = encrypt('enc:e30', [:])
reading('7')
settleStep()
check('#57 read ends with an encrypted empty list: marked read-empty', app.atomicState.lockCodesReadEmpty == ['7'])
lock.attrs.lockCodes = encrypt(enc, frontDesk)
reading('7')
settleStep()
check('#57 read ends with encrypted codes: not marked read-empty', app.atomicState.lockCodesReadEmpty == [])
reset55()

// Duplicate check reads encrypted lockCodes; an unreadable one is "can't tell"
lock.attrs.lockCodes = encrypt('enc:ZHVw', ['53': [name: 'G:ORB88-Di', code: '20000077']])
check('#57 duplicate check: an encrypted list is checked', app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88))
lock.attrs.lockCodes = 'undecryptable:ZHVw'
mark()
check('#57 duplicate check: undecryptable is no duplicate, warned with the lock id only',
    !app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88) && newLogs().contains('helperHasDuplicateCode: lock 7 lockCodes unreadable, not checked'))

// Bookings page: an encrypted lock's codes are matched, an undecryptable one is reported
lock.attrs.lockCodes = encrypt(enc, named52)
app.atomicState.bookings = booking77()
Map bv = app.bookingsView()
check('#57 bookings page: encrypted lock read', bv.bookings.any { it.contains('Front: slot 52') } && bv.summary.contains('1 codes on locks'))
lock.attrs.lockCodes = 'undecryptable:Qms'
bv = app.bookingsView()
check('#57 bookings page: undecryptable lock reported as unreadable', bv.notes.any { it.contains('Front:</b> its lockCodes could not be read') })
app.atomicState.bookings = [:]

// codeChanged event data encrypted like lockCodes: confirmations still land
def pending51 = { tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: false]]) }
def encEvent = { String value, String cipher -> [device: [id: '7'], value: value, data: cipher, name: 'codeChanged'] }
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('added', encrypt('enc:Y2MxCg', [codeNumber: 51])))
check('#57 encrypted codeChanged (codeNumber): the set is confirmed', assigned('51')?.confirmed == true)
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('changed', encrypt('enc:Y2MyCg', ['51': [name: 'G:ORB77-Cy', code: '20000077']])))
check('#57 encrypted codeChanged (slot map): the set is confirmed', assigned('51')?.confirmed == true)
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(['51': [name: 'G:ORB77-Cy', code: '20000077']])))
check('#57 plain codeChanged slot map: the set is confirmed', assigned('51')?.confirmed == true)
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true, deleting: true]])
app.codeChangedHandler(encEvent('deleted', encrypt('enc:Y2MzCg', [codeNumber: 51])))
check('#57 encrypted codeChanged (deleted): tracking cleared', assigned('51') == null)
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('added', 'undecryptable:Y2M0'))
check('#57 undecryptable codeChanged: nothing confirmed, no error', assigned('51')?.confirmed == false)

// A set is confirmed only by data that agrees with what the app wrote (#59). Another name in
// the slot drops the tracking (their code stays, reconcile places the booking elsewhere);
// our own name with another code leaves the set pending
def slotData = { Map entry -> ['51': entry] }
def outcome = { String value, String data, Map a = null ->
    app.atomicState.codeAssignments = a ? tracked(['51': a]) : pending51()
    app.scheduled.clear()
    app.codeChangedHandler(encEvent(value, data))
    Map t = assigned('51')
    boolean reconcileSoon = app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.secs == 10 }
    return t == null ? (reconcileSoon ? 'dropped' : 'dropped, no reconcile') : (t.confirmed ? 'confirmed' : (reconcileSoon ? 'pending, reconcile' : 'pending'))
}
def pendingName = { String name -> [bookingId: 'ORB77', code: '20000077', codeName: name, attempt: 1, confirmed: false] }
String plumber = JsonOutput.toJson(slotData([name: 'Plumber', code: '30000051']))
mark()
check('#59 foreign write into a pending slot (slot map): tracking dropped, reconcile in 10 s', outcome('changed', plumber) == 'dropped')
List foreignLogs = newLogs()
check('#59 foreign write: logged without the name or code',
    foreignLogs.contains("codeChangedHandler: changed at pos 51 on lock 7 is not ORB77; tracking dropped, reconcile in 10 s") && !foreignLogs.any { it.contains('Plumber') || it.contains('30000051') || it.contains('20000077') })
check('#59 foreign write (encrypted slot map): dropped', outcome('added', encrypt('enc:ZjEK', slotData([name: 'Plumber', code: '30000051']))) == 'dropped')
check('#59 foreign write (codeNumber with a name): dropped', outcome('added', JsonOutput.toJson([codeNumber: 51, name: 'Plumber'])) == 'dropped')
check('#59 foreign write (encrypted codeNumber with a name): dropped', outcome('changed', encrypt('enc:ZjIK', [codeNumber: 51, name: 'Plumber', code: '30000051'])) == 'dropped')
check('#59 another booking\'s code in the slot: dropped', outcome('changed', JsonOutput.toJson(slotData([name: 'G:ORB88-Di', code: '20000088']))) == 'dropped')
check('#59 another code with no name (too little to tell): stays pending, no reconcile', outcome('changed', JsonOutput.toJson(slotData([code: '30000051']))) == 'pending')
check('#59 a name that isn\'t ours with our code (driver renames): confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'Guest 1', code: '20000077']))) == 'confirmed')
check('#59 lower-cased name, no code: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'g:orb77-cy']))) == 'confirmed')
check('#59 another booking\'s name with our code: confirmed (the code decides)', outcome('changed', JsonOutput.toJson(slotData([name: 'X:ORB88-Di', code: '20000077']))) == 'confirmed')
check('#59 guest-first name cut before ORB, with our code: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'G:Cy-OR', code: '20000077'])), pendingName('G:Cy-ORB77')) == 'confirmed')
check('#59 our code reported as a number: confirmed', outcome('changed', JsonOutput.toJson(slotData([code: 20000077]))) == 'confirmed')
check('#59 our code reported as a number without its leading zero: confirmed',
    outcome('changed', JsonOutput.toJson(slotData([code: 1234567])), [bookingId: 'ORB77', code: '01234567', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: false]) == 'confirmed')
check('#59 a longer code with a leading zero is another code: stays pending',
    outcome('changed', JsonOutput.toJson(slotData([code: '01234567'])), [bookingId: 'ORB77', code: '1234567', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: false]) == 'pending')
check('#59 placeholder for another slot: dropped', outcome('changed', JsonOutput.toJson(slotData([name: 'Code 52']))) == 'dropped')
check('#59 a confirmed slot taken by another name: dropped too', outcome('changed', plumber, pendingName('G:ORB77-Cy') + [confirmed: true]) == 'dropped')
check('#59 our name with another code (our own older write): stays pending, no reconcile', outcome('changed', JsonOutput.toJson(slotData([name: 'G:ORB77-Cy', code: '30000051']))) == 'pending')
check('#59 matching name and code (codeNumber): confirmed', outcome('added', JsonOutput.toJson([codeNumber: 51, name: 'G:ORB77-Cy', code: '20000077'])) == 'confirmed')
check('#59 matching name and code (encrypted codeNumber): confirmed', outcome('added', encrypt('enc:bTEK', [codeNumber: 51, name: 'G:ORB77-Cy', code: '20000077'])) == 'confirmed')
check('#59 matching name and code (encrypted slot map): confirmed', outcome('changed', encrypt('enc:bTIK', slotData([name: 'G:ORB77-Cy', code: '20000077']))) == 'confirmed')
check('#59 codeNumber only: confirmed by slot as before', outcome('added', JsonOutput.toJson([codeNumber: 51])) == 'confirmed')
check('#59 slot map with no name or code: confirmed by slot as before', outcome('added', JsonOutput.toJson(slotData([notify: true]))) == 'confirmed')
check('#59 matching name, code masked: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'G:ORB77-Cy', code: '********']))) == 'confirmed')
check('#59 matching code, no name (driver drops names): confirmed', outcome('changed', JsonOutput.toJson(slotData([code: '20000077']))) == 'confirmed')
check('#59 name truncated after the booking id: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'G:ORB77', code: '20000077']))) == 'confirmed')
check('#59 name truncated right after ORB: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'GT:ORB'])), pendingName('GT:ORB77-Cy')) == 'confirmed')
check('#59 guest-first name truncated after ORB: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'G:Cy-ORB'])), pendingName('G:Cy-ORB77')) == 'confirmed')
['GT:ORB7', 'GT', 'G', 'GT:OR'].each { String n ->
    check("#59 ${n} against GT:ORB77-Cy (not a whole ORB<id> token): not confirmed, dropped".toString(), outcome('changed', JsonOutput.toJson(slotData([name: n])), pendingName('GT:ORB77-Cy')) == 'dropped')
}
check('#59 name with padding: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: ' G:ORB77-Cy ']))) == 'confirmed')
check('#59 same booking under another prefix or guest name: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'GT:ORB77-Cyrus']))) == 'confirmed')
check('#59 driver placeholder name for the slot: confirmed', outcome('changed', JsonOutput.toJson(slotData([name: 'Code 51']))) == 'confirmed')
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true, deleting: true]])
app.codeChangedHandler(encEvent('deleted', plumber))
check('#59 deleted with other data: tracking cleared as before', assigned('51') == null)
app.atomicState.codeAssignments = pending51()
app.scheduled.clear()
app.codeChangedHandler(encEvent('failed', JsonOutput.toJson(slotData([name: 'Plumber']))))
check('#59 failed with other data: retried as before', app.scheduled.any { it.handler == 'trySetCode' && it.data.codePosition == 51 })

// The whole path: their code stays, the booking goes to another free slot, no re-send into 51
[['another name', [name: 'Plumber', code: '30000051']], ['another booking', [name: 'X:ORB88-Di', code: '20000088']]].each { String label, Map theirs ->
    Map withTheirs = ['40': [name: 'Front desk', code: '10000040'], '51': theirs]
    lock.attrs.lockCodes = JsonOutput.toJson(withTheirs)
    lock.setCalls.clear()
    lock.deleted.clear()
    app.atomicState.codeAssignments = pending51()
    app.scheduled.clear()
    app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData(theirs))))
    app.verifySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
    check("#59 ${label} in a pending slot: tracking dropped, reconcile in 10 s, the pending verify sends nothing".toString(),
        assigned('51') == null && app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.secs == 10 } && lock.setCalls.isEmpty() && !app.scheduled.any { it.handler in ['trySetCode', 'verifySetCode'] })
    r = prefixRun(withTheirs, app.atomicState.codeAssignments, booking77())
    check("#59 ${label} in a pending slot: reconcile writes the booking to another free slot, slot 51 untouched".toString(),
        r.sets.size() == 1 && r.sets[0].codePosition != 51 && r.sets[0].codePosition >= 50 && r.sets[0].codeName == 'G:ORB77-Cy' && r.deletes.isEmpty())
}

// Our own name keeps coming back with another code: one warn (lock and slot only) at the 3rd in a row
app.atomicState.codeAssignments = pending51()
String staleData = JsonOutput.toJson(slotData([name: 'G:ORB77-Cy', code: '30000051']))
Map resend = [lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1]
mark()
app.codeChangedHandler(encEvent('changed', staleData))
app.trySetCode(resend)
app.codeChangedHandler(encEvent('changed', staleData))
check('#59 two mismatches in a row (across a re-send): no warn yet', !newLogs().any { it.contains('in a row') } && assigned('51')?.mismatches == 2)
app.trySetCode(resend)
app.codeChangedHandler(encEvent('changed', staleData))
List staleLogs = newLogs()
check('#59 third mismatch in a row: one warn with the lock and slot only',
    staleLogs.count { it == "codeChangedHandler: lock 7 pos 51: 3 confirmations in a row didn't match the code written; still re-sending" } == 1 && !staleLogs.findAll { it.startsWith('codeChangedHandler') }.any { it.contains('30000051') || it.contains('20000077') || it.contains('G:ORB77-Cy') })
app.codeChangedHandler(encEvent('changed', staleData))
check('#59 fourth mismatch: no second warn', newLogs().count { it.contains('in a row') } == 1)
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData([name: 'G:ORB77-Cy', code: '20000077']))))
check('#59 a matching confirmation clears the count', assigned('51')?.confirmed == true && assigned('51')?.mismatches == null)
app.codeChangedHandler(encEvent('changed', staleData))
lock.attrs.lockCodes = '{}'
app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'G:ORB77-Cy'])
check('#59 a write of another code starts a new count', assigned('51')?.code == '20000078' && assigned('51')?.mismatches == null)

// Queued writes never land on a code that took the slot after they were scheduled
// (a) the 30 s retry after a failed event, then another code takes the slot
lock.attrs.lockCodes = JsonOutput.toJson(['51': [name: 'Plumber', code: '30000051']])
app.atomicState.codeAssignments = pending51()
app.scheduled.clear()
app.codeChangedHandler(encEvent('failed', JsonOutput.toJson([codeNumber: 51])))
Map queuedRetry = app.scheduled.find { it.handler == 'trySetCode' }?.data
app.codeChangedHandler(encEvent('changed', plumber))
lock.setCalls.clear()
mark()
app.trySetCode(queuedRetry)
check('#59 failed, then another code: the queued retry sends nothing and tracks nothing',
    queuedRetry != null && lock.setCalls.isEmpty() && assigned('51') == null && newLogs().contains('trySetCode: lock 7 pos 51 no longer free for this write, skipped'))
// the same, with lockCodes not updated yet by the driver: the dropped tracking alone stops it
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = pending51()
app.scheduled.clear()
app.codeChangedHandler(encEvent('failed', JsonOutput.toJson([codeNumber: 51])))
queuedRetry = app.scheduled.find { it.handler == 'trySetCode' }?.data
app.codeChangedHandler(encEvent('changed', plumber))
lock.setCalls.clear()
app.trySetCode(queuedRetry)
check('#59 failed, then another code, lockCodes not updated yet: the queued retry sends nothing', lock.setCalls.isEmpty() && assigned('51') == null)
// a retry for a write that has since changed (another code tracked in the slot)
app.atomicState.codeAssignments = pending51()
lock.attrs.lockCodes = '{}'
lock.setCalls.clear()
app.trySetCode([lockId: '7', codePosition: 51, code: '20000076', codeName: 'G:ORB77-Cy', attempt: 1])
check('#59 a retry of an older write sends nothing, tracking kept (lockCodes shows the slot empty)', lock.setCalls.isEmpty() && assigned('51')?.code == '20000077')
// (b) a reconcile rewrite (the booking's code changed), then another code takes the slot
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: '20000077']], ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]], booking77(code: '20000078'))
Map rewrite = r.sets.find { it.codePosition == 51 }
lock.attrs.lockCodes = JsonOutput.toJson(['51': [name: 'Plumber', code: '30000051']])
app.codeChangedHandler(encEvent('changed', plumber))
lock.setCalls.clear()
app.trySetCode(rewrite)
check('#59 scheduled rewrite, then another code: nothing sent, nothing tracked', rewrite != null && !rewrite.free && lock.setCalls.isEmpty() && assigned('51') == null)
// (c) a reconcile write into a free slot, then a code (even nameless) lands there first
r = prefixRun(['40': [name: 'Front desk', code: '10000040']], [:], booking77())
Map newWrite = r.sets[0]
lock.attrs.lockCodes = JsonOutput.toJson(['40': [name: 'Front desk', code: '10000040'], (newWrite.codePosition.toString()): [code: '30000099']])
lock.setCalls.clear()
app.trySetCode(newWrite)
check('#59 scheduled write into a free slot that is no longer free: nothing sent, nothing tracked', newWrite.free == true && lock.setCalls.isEmpty() && assigned(newWrite.codePosition.toString()) == null)
lock.attrs.lockCodes = JsonOutput.toJson(['40': [name: 'Front desk', code: '10000040']])
app.trySetCode(newWrite)
check('#59 scheduled write into a slot still free: sent and tracked', lock.setCalls == [newWrite.codePosition] && assigned(newWrite.codePosition.toString())?.confirmed == false)

// No free slot after another code took the booking's slot: one dedicated warn, lock and booking only
lock.attrs.lockCodes = JsonOutput.toJson(['50': [name: 'Front desk', code: '10000050'], '51': [name: 'Plumber', code: '30000051']])
lock.attrs.maxCodes = 51
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('changed', plumber))
mark()
app.warned.clear()
app.scheduled.clear()
app.reconcileDoorCodes(booking77())
List fullLogs = newLogs()
check('#59 no free slot after the drop: one warn with the lock and booking id only',
    app.warned.count { it == 'reconcileDoorCodes: lock 7: ORB77 lost its slot to another code and no slot is free; no code for it on this lock until one frees' } == 1
    && !app.scheduled.any { it.handler == 'trySetCode' } && !fullLogs.findAll { it.contains('lost its slot') }.any { it.contains('Cy') || it.contains('Plumber') || it.contains('20000077') })
lock.attrs.maxCodes = 60
lock.attrs.lockCodes = JsonOutput.toJson(['50': [name: 'Front desk', code: '10000050'], '51': [name: 'Plumber', code: '30000051']])
r = prefixRun(['50': [name: 'Front desk', code: '10000050'], '51': [name: 'Plumber', code: '30000051']], app.atomicState.codeAssignments, booking77())
app.trySetCode(r.sets[0])
check('#59 once written to a free slot, the booking is no longer marked displaced', r.sets[0].codePosition == 52 && !(app.atomicState.codeDisplaced['7'] ?: []).contains('77'))
// No free slot for a booking whose slot was never taken: only the existing warn
lock.attrs.maxCodes = 51
lock.attrs.lockCodes = JsonOutput.toJson(['50': [name: 'Front desk', code: '10000050'], '51': [name: 'Plumber', code: '30000051']])
app.atomicState.codeAssignments = [:]
mark()
app.reconcileDoorCodes(booking77())
check('#59 no free slot for a booking never displaced: no lost-its-slot warn', !newLogs().any { it.contains('lost its slot') } && newLogs().any { it.startsWith('reconcileDoorCodes: no free code position') })
lock.attrs.maxCodes = 60

// ORB<id> matches ignoring case in tracked slots too, like the event verdict (#61)
check('#61 tracked verdict: a lower-cased same ORB<id> is claimed', app.helperTrackedVerdict([bookingId: 'ORB77'], [name: 'g:orb77-cy'], '51') == 'claim')
check('#61 tracked verdict: a lower-cased other ORB<id> is another booking', app.helperTrackedVerdict([bookingId: 'ORB77'], [name: 'g:orb88-di'], '51') == 'otherBooking')
check('#61 tracked verdict: other names are still other names', ['plumber', 'forb', 'g:or77', 'Front desk'].every { app.helperTrackedVerdict([bookingId: 'ORB77'], [name: it], '51') == 'otherName' })
Map lower51 = ['51': [name: 'g:orb77-cy', code: '20000077']]
Map trackedLower = ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]]
r = prefixRun(lower51, trackedLower, booking77())
check('#61 reconcile: a tracked slot with a lower-cased name stays the booking\'s (no second slot, no rewrite)', r.sets.isEmpty() && r.deletes.isEmpty() && assigned('51')?.bookingId == 'ORB77')
r = prefixRun(lower51, trackedLower, [:])
check('#61 reconcile: a tracked slot with a lower-cased name is removed after checkout', r.deletes == [51])
r = prefixRun(lower51, trackedLower, booking77(code: '20000078'))
check('#61 reconcile: a new code for the booking goes into its lower-cased tracked slot', r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.deletes.isEmpty())
r = prefixRun(['51': [name: 'g:orb88-di', code: '20000088']], trackedLower, booking77())
check('#61 reconcile: a lower-cased other booking in the tracked slot is not claimed (tracking dropped, slot untouched)', assigned('51') == null && !r.deletes.contains(51) && r.sets.size() == 1 && r.sets[0].codePosition != 51)
r = prefixRun(['51': [name: 'plumber', code: '30000051']], trackedLower, booking77())
check('#61 reconcile: a lower-cased other name in the tracked slot is not claimed', assigned('51') == null && !r.deletes.contains(51) && r.sets[0]?.codePosition != 51)
r = prefixRun(lower51, [:], [:])
check('#61 reconcile: an untracked lower-cased name is still not claimed by name (left on the lock)', r.deletes.isEmpty())
// Prune keeps a confirmed tracked slot with a lower-cased name, drops other names
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true],
    '52': [bookingId: 'ORB88', code: '20000088', codeName: 'G:ORB88-Di', attempt: 1, confirmed: true]])
app.helperPruneAssignments('7', ['51': [name: 'G:ORB77-CY'.toLowerCase()], '52': [name: 'g:orb99-zed']])
check('#61 prune: a lower-cased same ORB<id> keeps its tracking, a lower-cased other one is dropped', assigned('51') != null && assigned('52') == null)
// The check before a queued write accepts the lock's lower-cased name for this booking
lock.attrs.maxCodes = 60
lock.attrs.lockCodes = JsonOutput.toJson(lower51)
app.atomicState.codeAssignments = tracked(trackedLower['7'])
lock.setCalls.clear()
app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'G:ORB77-Cy'])
check('#61 guard: a scheduled rewrite into a lower-cased slot of this booking is sent', lock.setCalls == [51] && assigned('51')?.code == '20000078')
lock.setCalls.clear()
app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'G:ORB77-Cy', free: true])
check('#61 guard: a new-slot write into a slot showing this booking lower-cased is sent', lock.setCalls == [51])
['g:orb88-di', 'plumber'].each { String n ->
    lock.attrs.lockCodes = JsonOutput.toJson(['51': [name: n, code: '30000051']])
    lock.setCalls.clear()
    app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'G:ORB77-Cy'])
    app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'G:ORB77-Cy', free: true])
    check("#61 guard: a scheduled write is still skipped over ${n}".toString(), lock.setCalls.isEmpty())
}
// The duplicate check: the booking's own lower-cased code is not a duplicate, another booking's still is
lock.attrs.lockCodes = JsonOutput.toJson(lower51)
check('#61 duplicate check: own lower-cased code is not a duplicate', !app.helperHasDuplicateCode('7', booking77()['77'], booking77()))
lock.attrs.lockCodes = JsonOutput.toJson(['53': [name: 'g:orb88-di', code: '20000077']])
check('#61 duplicate check: the same PIN on another current booking, lower-cased, still is', app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88))

// A confirmation that matched only by the leading-zero tolerance: one warn per write, lock and slot only
String zeroMsg = 'codeChangedHandler: lock 7 pos 51 reported the code written without its leading zeros; the lock may hold the shorter code'
Map zeroWrite = [lockId: '7', codePosition: 51, code: '01234567', codeName: 'G:ORB77-Cy', attempt: 1]
String shortData = JsonOutput.toJson(slotData([name: 'G:ORB77-Cy', code: 1234567]))
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = tracked(['51': [bookingId: 'ORB77', code: '01234567', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: false]])
mark()
app.warned.clear()
app.codeChangedHandler(encEvent('changed', shortData))
check('#61 leading zeros dropped: confirmed, one warn with the lock and slot only', assigned('51')?.confirmed == true && app.warned == [zeroMsg])
app.codeChangedHandler(encEvent('changed', shortData))
app.atomicState.bookings = booking77(code: '01234567')
app.trySetCode(zeroWrite)
app.atomicState.bookings = [:]
check('#61 leading zeros dropped: a re-send of the same write keeps the warned mark', assigned('51')?.shortWarned == true && assigned('51')?.confirmed == false)
app.codeChangedHandler(encEvent('added', shortData))
check('#61 leading zeros dropped again (same event, a re-send): no second warn', app.warned.count { it == zeroMsg } == 1)
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData([name: 'G:ORB77-Cy']))))
check('#61 a confirmation without a code leaves the mark', assigned('51')?.shortWarned == true)
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData([name: 'G:ORB77-Cy', code: '01234567']))))
check('#61 the whole code reported: no warn, mark cleared', app.warned.count { it == zeroMsg } == 1 && assigned('51')?.shortWarned == null)
app.codeChangedHandler(encEvent('changed', shortData))
check('#61 shortened again after the whole code: warns again once', app.warned.count { it == zeroMsg } == 2)
lock.attrs.lockCodes = '{}'
app.trySetCode([lockId: '7', codePosition: 51, code: '01234568', codeName: 'G:ORB77-Cy'])
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData([code: 1234568]))))
check('#61 a write of another code warns on its own', app.warned.count { it == zeroMsg } == 3)
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData([code: '20000077']))))
app.atomicState.codeAssignments = pending51()
app.codeChangedHandler(encEvent('changed', JsonOutput.toJson(slotData([code: 20000077]))))
check('#61 the whole code, as text or a number: no leading-zero warn', app.warned.count { it == zeroMsg } == 3 && assigned('51')?.shortWarned == null)
check('#61 leading-zero confirmation logs: no code', !newLogs().any { it.contains('1234567') || it.contains('1234568') })

// Reconcile keeps finding the shorter code on the lock: it rewrites each run, warns once while it lasts
String zeroRecMsg = 'reconcileDoorCodes: lock 7 pos 51 keeps reporting the code without its leading zeros; rewriting it (the lock may hold the shorter code)'
Map zeroTracked = ['7': ['51': [bookingId: 'ORB77', code: '01234567', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]]
Map shortOnLock = ['51': [name: 'G:ORB77-Cy', code: 1234567]]
app.atomicState.codeShortWarned = [:]
mark()
app.warned.clear()
r = prefixRun(shortOnLock, zeroTracked, booking77(code: '01234567'))
def r2 = prefixRun(shortOnLock, zeroTracked, booking77(code: '01234567'))
check('#61 reconcile, shorter code on the lock: rewritten each run, one warn with the lock and slot only',
    r.sets.size() == 1 && r.sets[0].codePosition == 51 && r2.sets.size() == 1 && app.warned == [zeroRecMsg])
prefixRun(['51': [name: 'G:ORB77-Cy', code: '01234567']], zeroTracked, booking77(code: '01234567'))
check('#61 reconcile, whole code on the lock: no warn, mark cleared', app.warned.size() == 1 && !app.atomicState.codeShortWarned.containsKey('7'))
prefixRun(shortOnLock, zeroTracked, booking77(code: '01234567'))
check('#61 reconcile, shorter code again later: warns again once', app.warned.count { it == zeroRecMsg } == 2)
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: '30000051']], zeroTracked, booking77(code: '01234567'))
check('#61 reconcile, another code in the booking\'s slot: rewritten without a leading-zero warn', r.sets.size() == 1 && app.warned.count { it == zeroRecMsg } == 2)
check('#61 reconcile leading-zero logs: no code', !newLogs().any { it.contains('1234567') || it.contains('30000051') })
lock.attrs.lockCodes = '{}'
app.atomicState.codeShortWarned = [:]

// The tracked booking's own ORB<id> decides, not the first orb<digits> in the name (#61 review N3)
check('#61 N3 verdict: guest Orb5 with the id at the end (any case) is still this booking', ['GT:Orb5-ORB77', 'gt:orb5-orb77'].every { app.helperTrackedVerdict([bookingId: 'ORB77'], [name: it], '51') == 'claim' })
check('#61 N3 verdict: a prefix holding orb9 is still this booking', ['orb9:orb77-cy', 'ORB9:ORB77-Cy'].every { app.helperTrackedVerdict([bookingId: 'ORB77'], [name: it], '51') == 'claim' })
check('#61 N3 verdict: a longer or shorter id, or another id beside orb9, is another booking',
    ['GT:ORB777-Cy', 'GT:ORB7', 'orb9:orb88-di', 'gt:orb5-orb78'].every { app.helperTrackedVerdict([bookingId: 'ORB77'], [name: it], '51') == 'otherBooking' })
app.idAtEnd = true
app.codeNamePrefix = 'GT:'
Map orb5 = ['51': [name: 'gt:orb5-orb77', code: '20000077']]
Map trackedOrb5 = ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'GT:Orb5-ORB77', attempt: 1, confirmed: true]]]
r = prefixRun(orb5, trackedOrb5, booking77(guest: 'Orb5'))
check('#61 N3 reconcile: guest Orb5, id at the end, lower-cased: the tracked slot stays the booking\'s', r.sets.isEmpty() && r.deletes.isEmpty() && assigned('51') != null)
claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(['51': [name: 'GT:Orb5-ORB77'], '52': [name: 'GT:ORB5-ORB78']])))
check('#61 N3 untracked names, id at the end: the id is the one at the end (Orb5); ORB5-ORB78 is ambiguous (#64)', claimed.keySet() == ['77'] as Set && claimed['77'].key == '51')
lock.attrs.lockCodes = JsonOutput.toJson(orb5)
app.atomicState.codeAssignments = tracked(trackedOrb5['7'])
lock.setCalls.clear()
app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'GT:Orb5-ORB77'])
app.trySetCode([lockId: '7', codePosition: 51, code: '20000078', codeName: 'GT:Orb5-ORB77', free: true])
check('#61 N3 guard: writes into a slot showing gt:orb5-orb77 for booking 77 are sent, tracked as ORB77', lock.setCalls == [51, 51] && assigned('51')?.bookingId == 'ORB77')
check('#61 N3 duplicate check: the booking\'s own gt:orb5-orb77 is not a duplicate', !app.helperHasDuplicateCode('7', booking77(guest: 'Orb5')['77'], booking77()))
app.idAtEnd = false
app.codeNamePrefix = 'ORB9:'
Map orb9 = ['51': [name: 'orb9:orb77-cy', code: '20000077']]
Map trackedOrb9 = ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'ORB9:ORB77-Cy', attempt: 1, confirmed: true]]]
r = prefixRun(orb9, trackedOrb9, booking77())
check('#61 N3 reconcile: prefix ORB9:, lower-cased: the tracked slot stays the booking\'s', r.sets.isEmpty() && r.deletes.isEmpty() && assigned('51') != null)
claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(['51': [name: 'ORB9:ORB77-Cy']])))
check('#61 N3 untracked name with prefix ORB9: is booking 77, not 9', claimed.keySet() == ['77'] as Set)
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = [:]
lock.setCalls.clear()
app.trySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'ORB9:ORB77-Cy', free: true])
check('#61 N3 a write named ORB9:ORB77-Cy is tracked as ORB77', assigned('51')?.bookingId == 'ORB77')
lock.attrs.lockCodes = JsonOutput.toJson(orb9)
lock.setCalls.clear()
app.trySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'ORB9:ORB77-Cy', free: true])
check('#61 N3 guard: a new-slot write into a slot showing orb9:orb77-cy is sent', lock.setCalls == [51])
check('#61 N3 duplicate check: own orb9:orb77-cy is not a duplicate', !app.helperHasDuplicateCode('7', booking77()['77'], booking77()))
lock.attrs.lockCodes = JsonOutput.toJson(['53': [name: 'orb9:orb88-di', code: '20000077']])
check('#61 N3 duplicate check: orb9:orb88-di with the same PIN is booking 88\'s (current), a duplicate', app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88))
app.codeNamePrefix = 'G:'

// Untracked lower-cased copies are listed on the Bookings page, never claimed (#61 review N1)
lock.attrs.lockCodes = JsonOutput.toJson(['50': [name: 'G:ORB77-Cy', code: '20000077'], '52': [name: 'g:orb77-cy', code: '20000077'], '53': [name: 'g:orb66-old', code: '20000066'],
    '54': [name: 'x:orb88-di', code: '20000088'], '55': [name: 'plumber', code: '30000051'], '56': [name: 'G:ORB77-Cy', code: '20000077']])
app.atomicState.codeAssignments = tracked(['56': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]])
app.atomicState.bookings = booking77()
mark()
bv = app.bookingsView()
List strayNotes = bv.notes.findAll { it.contains('in another case') }
check('#61 N1 bookings page: untracked lower-cased g:orb77-cy and g:orb66-old listed, with slot and booking id',
    strayNotes.size() == 2 && strayNotes[0].contains('slot 52 (g:orb77-cy)') && strayNotes[0].contains('ORB77') && strayNotes[1].contains('slot 53 (g:orb66-old)') && strayNotes[1].contains('ORB66'))
check('#61 N1 bookings page: other prefixes, other names, tracked slots and exact names not listed', !strayNotes.any { n -> ['slot 50', 'slot 54', 'slot 55', 'slot 56'].any { n.contains(it) } })
check('#61 N1 bookings page: stray lines carry no code, nothing logged', !strayNotes.any { n -> ['20000077', '20000066', '77&', '66&'].any { n.contains(it) } } && newLogs().isEmpty())
r = prefixRun(new JsonSlurper().parseText(lock.attrs.lockCodes), app.atomicState.codeAssignments, [:])
check('#61 N1 reconcile: the listed slots are neither claimed nor deleted', !r.deletes.contains(52) && !r.deletes.contains(53))
app.atomicState.bookings = [:]

// An exact-name copy beside the booking's tracked slot is listed too (#61 re-review N1)
def copyNotes = { Map lockCodes, Map assignments ->
    lock.attrs.lockCodes = JsonOutput.toJson(lockCodes)
    app.atomicState.codeAssignments = tracked(assignments)
    app.atomicState.bookings = booking77()
    mark()
    List notes = app.bookingsView().notes.findAll { it.contains('another copy') }
    app.atomicState.bookings = [:]
    return notes
}
Map lowerTracked51 = ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]
Map copyCodes = ['51': [name: 'g:orb77-cy', code: '20000077'], '60': [name: 'G:ORB77-Cy', code: '20000077']]
List cn = copyNotes(copyCodes, lowerTracked51)
check('#61 copy: tracked gt:orb77-cy in 51, exact G:ORB77-Cy in 60: slot 60 listed as another copy, tracked slot named',
    cn.size() == 1 && cn[0].contains('slot 60 (G:ORB77-Cy) is another copy of ORB77\'s code; the app tracks slot 51'))
check('#61 copy: no code on the page line, nothing logged', !cn.any { it.contains('20000077') || it.contains('77&') } && newLogs().isEmpty())
r = prefixRun(copyCodes, ['7': lowerTracked51], booking77())
check('#61 copy: during the stay reconcile neither writes nor deletes either slot', r.sets.isEmpty() && r.deletes.isEmpty())
r = prefixRun(copyCodes, ['7': lowerTracked51], [:])
check('#61 copy: at checkout only the tracked slot is deleted', r.deletes == [51])
app.tryDeleteCode(r.jobs.find { it.handler == 'tryDeleteCode' }.data)
r = prefixRun(['60': [name: 'G:ORB77-Cy', code: '20000077']], app.atomicState.codeAssignments, [:])
check('#61 copy: once the lock shows slot 51 empty, the next reconcile removes the copy (as the page says)', r.deletes == [60])
app.settings = [previousCodeNamePrefix: 'GT:']
cn = copyNotes(['51': [name: 'GT:ORB77-Cy', code: '20000077'], '31': [name: 'G:ORB77-Cy', code: '20000077']],
    ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'GT:ORB77-Cy', attempt: 1, confirmed: true]])
check('#61 copy: tracked old-prefix GT:ORB77-Cy in 51, exact new-prefix copy in 31: slot 31 listed, tracked slot named',
    cn.size() == 1 && cn[0].contains('slot 31 (G:ORB77-Cy) is another copy of ORB77\'s code; the app tracks slot 51'))
app.settings = [:]
cn = copyNotes(['51': [name: 'G:ORB77-Cy', code: '20000077']], lowerTracked51)
check('#61 copy: the tracked slot alone is never listed', cn.isEmpty())
cn = copyNotes(['31': [name: 'G:ORB77-Cy', code: '20000077']], [:])
check('#61 copy: an untracked exact name reconcile claims is not listed', cn.isEmpty())
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = [:]

// Leading-zero dedup: no key kept for a lock with nothing to warn about, or a lock no longer selected (#61 review N2)
app.atomicState.codeShortWarned = ['7': ['51'], '99': ['5']]
prefixRun(['51': [name: 'G:ORB77-Cy', code: '01234567']], ['7': ['51': [bookingId: 'ORB77', code: '01234567', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]], booking77(code: '01234567'))
check('#61 N2 dedup: an empty lock entry and a deselected lock\'s entry are removed', app.atomicState.codeShortWarned == [:])
lock.attrs.lockCodes = '{}'

// --- #63: reconcile compares codes as text ---
Map num51 = ['51': [name: 'G:ORB77-Cy', code: 20000077]]
mark()
app.warned.clear()
r = prefixRun(num51, ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]], booking77())
r2 = prefixRun(num51, app.atomicState.codeAssignments, booking77())
check('#63 tracked slot, code reported as a number: no rewrite on either run', r.sets.isEmpty() && r2.sets.isEmpty() && r.deletes.isEmpty() && r2.deletes.isEmpty())
r = prefixRun(num51, [:], booking77())
r2 = prefixRun(num51, [:], booking77())
check('#63 untracked slot found by name, code reported as a number: no rewrite, no second slot', r.sets.isEmpty() && r2.sets.isEmpty())
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: '20000077']], [:], booking77(code: 20000077))
check('#63 booking code a number, lock code text: no rewrite', r.sets.isEmpty())
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: ' 20000077 ']], [:], booking77())
check('#63 code with surrounding spaces: no rewrite', r.sets.isEmpty())
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: 20000079]], [:], booking77())
check('#63 a different code (as a number) is still rewritten into its slot', r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.sets[0].code == '20000077')
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: '20000079']], [:], booking77())
check('#63 a different code (as text) is still rewritten into its slot', r.sets.size() == 1 && r.sets[0].codePosition == 51)
check('#63 no warning for equal or different codes', app.warned.isEmpty())
app.atomicState.codeShortWarned = [:]
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: 1234567]], [:], booking77(code: '01234567'))
r2 = prefixRun(['51': [name: 'G:ORB77-Cy', code: 1234567]], [:], booking77(code: '01234567'))
check('#63 a code that only lost its leading zero is not accepted: rewritten each run, warned once',
    r.sets.size() == 1 && r.sets[0].codePosition == 51 && r2.sets.size() == 1 && app.warned == [zeroRecMsg])
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: 101234567]], [:], booking77(code: '01234567'))
check('#63 a longer number is another code: rewritten, no leading-zero warn', r.sets.size() == 1 && app.warned.size() == 1)
check('#63 logs: no code', !newLogs().any { l -> ['20000077', '20000079', '1234567'].any { l.contains(it) } })
app.atomicState.codeShortWarned = [:]
lock.attrs.lockCodes = '{}'

// --- 1.4.16: duplicate check compares codes as text, a dropped leading zero counts ---
def dup = { Map lockCodes, Map bk = booking77() -> lock.attrs.lockCodes = JsonOutput.toJson(lockCodes); app.helperHasDuplicateCode('7', bk['77'], bk + booking88) }
check('1.4.16 duplicate: same PIN reported as a number is a duplicate', dup(['20': [name: 'Front desk', code: 20000077]]))
check('1.4.16 duplicate: booking code a number, lock code text', dup(['20': [name: 'Front desk', code: '20000077']], booking77(code: 20000077)))
check('1.4.16 duplicate: surrounding spaces still a duplicate', dup(['20': [name: 'Front desk', code: ' 20000077 ']]))
check('1.4.16 duplicate: another current booking\'s slot, code as a number', dup(['53': [name: 'G:ORB88-Di', code: 20000077]]))
check('1.4.16 duplicate: a code that only lost its leading zero is a duplicate', dup(['20': [name: 'Front desk', code: 1234567]], booking77(code: '01234567')))
check('1.4.16 duplicate: an all-zero PIN reported as 0 is a duplicate', dup(['20': [name: 'Front desk', code: 0]], booking77(code: '0000')) && app.helperSameCode('0', '0000') && !app.helperSameCode('', '0000'))
check('1.4.16 duplicate: a longer code is not (0 added, not dropped)', !dup(['20': [name: 'Front desk', code: '01234567']], booking77(code: '1234567')))
check('1.4.16 duplicate: a different number is not', !dup(['20': [name: 'Front desk', code: 20000079]]) && !dup(['20': [name: 'Front desk', code: 101234567]], booking77(code: '01234567')))
check('1.4.16 duplicate: a hidden or missing code is not ("can\'t tell")', !dup(['20': [name: 'Front desk', code: '********']]) && !dup(['20': [name: 'Front desk']]) && !dup(['20': [name: 'Front desk', code: '']]))
check('1.4.16 duplicate: a booking without a code matches no hidden or missing code', !dup(['20': [name: 'Front desk', code: '********'], '21': [name: 'Back desk']], booking77(code: null)))
check('1.4.16 duplicate: the booking\'s own slot (code as a number) is not', !dup(['51': [name: 'G:ORB77-Cy', code: 20000077]]))
check('1.4.16 duplicate: the booking\'s own old-name slot with the code short is not', !dup(['51': [name: 'GT:ORB77-Cy', code: 1234567]], booking77(code: '01234567')))
lock.attrs.lockCodes = '{}'

// --- 1.4.16: a hidden or missing code on a slot found by name ---
Map tracked77 = ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]]
mark()
app.warned.clear()
[['hidden', ['51': [name: 'G:ORB77-Cy', code: '********']]], ['missing', ['51': [name: 'G:ORB77-Cy']]], ['empty', ['51': [name: 'G:ORB77-Cy', code: '']]],
 ['partly hidden', ['51': [name: 'G:ORB77-Cy', code: '2000****']]]].each { t ->
    r = prefixRun(t[1], tracked77, booking77())
    r2 = prefixRun(t[1], app.atomicState.codeAssignments, booking77())
    check("1.4.16 tracked slot, code ${t[0]}: no rewrite on either run, no second slot, no delete".toString(),
        r.sets.isEmpty() && r2.sets.isEmpty() && r.deletes.isEmpty() && r2.deletes.isEmpty())
}
Map hidden51 = ['51': [name: 'G:ORB77-Cy', code: '********']]
r = prefixRun(hidden51, tracked77, booking77(guest: 'VIP Cy'))
check('1.4.16 hidden code, guest name changed: no rewrite', r.sets.isEmpty())
r = prefixRun(hidden51, ['7': ['51': tracked77['7']['51'] + [confirmed: false]]], booking77())
check('1.4.16 hidden code, write not confirmed yet: reconcile re-sends it into its slot', r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.sets[0].code == '20000077')
r = prefixRun(hidden51, ['7': ['51': tracked77['7']['51'] + [code: '20000070']]], booking77())
check('1.4.16 hidden code, booking code changed: rewritten once into its slot with the new code',
    r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.sets[0].code == '20000077' && r.sets[0].codeName == 'G:ORB77-Cy')
r = prefixRun(hidden51, ['7': ['51': tracked77['7']['51'] + [bookingId: 'unknown']]], booking77())
check('1.4.16 hidden code, slot tracked for no booking: rewritten', r.sets.size() == 1 && r.sets[0].codePosition == 51)
r = prefixRun(hidden51, ['7': ['51': tracked77['7']['51'] + [deleting: true]]], booking77())
check('1.4.16 hidden code, slot being deleted for a current booking: rewritten', r.sets.size() == 1 && r.sets[0].codePosition == 51)
// While a refresh reads the lock, tracking of another booking isn't pruned yet: it doesn't count
reading('7')
r = prefixRun(hidden51, ['7': ['51': tracked77['7']['51'] + [bookingId: 'ORB88']]], booking77())
check('1.4.16 hidden code, slot tracked for another booking during a read: the write is held, not skipped', r.sets.isEmpty() && app.atomicState.codeWritesDeferred == ['7': 1])
reset55()
// Untracked (Reset State, or written before tracking): written once, then tracked and left alone
r = prefixRun(hidden51, [:], booking77())
check('1.4.16 untracked slot, hidden code: rewritten once into its slot, no second slot', r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.deletes.isEmpty())
lock.setCalls.clear()
app.trySetCode(r.sets[0])
check('1.4.16 untracked slot, hidden code: the write reaches the lock and is tracked', lock.setCalls == [51] && app.atomicState.codeAssignments['7']['51'].code == '20000077')
app.codeChangedHandler([device: [id: '7'], value: 'changed', data: JsonOutput.toJson(['51': [name: 'G:ORB77-Cy', code: '********']])])
check('1.4.16 untracked slot, hidden code: the lock confirms it', app.atomicState.codeAssignments['7']['51'].confirmed == true)
r = prefixRun(hidden51, app.atomicState.codeAssignments, booking77())
r2 = prefixRun(hidden51, app.atomicState.codeAssignments, booking77())
check('1.4.16 untracked slot, hidden code: no rewrite on the next runs', r.sets.isEmpty() && r2.sets.isEmpty())
// A write the lock never confirms (event lost) is re-sent, also after an app-wide unschedule()
app.atomicState.bookings = booking77()
lock.attrs.lockCodes = JsonOutput.toJson(hidden51)
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(['7': ['51': tracked77['7']['51'] + [code: '20000070']]]))
app.scheduled.clear()
app.reconcileDoorCodes(booking77())
def sent = app.scheduled.find { it.handler == 'trySetCode' }
app.scheduled.remove(sent)
lock.setCalls.clear()
app.trySetCode(sent.data)
check('1.4.16 lost confirmation: the write is sent, unconfirmed, with its verify scheduled',
    lock.setCalls == [51] && app.atomicState.codeAssignments['7']['51'].confirmed == false && app.scheduled.any { it.handler == 'verifySetCode' })
app.reconcileDoorCodes(booking77())
check('1.4.16 lost confirmation: the next reconcile (nothing cleared) re-sends it into its slot',
    app.scheduled.count { it.handler == 'trySetCode' && it.data.codePosition == 51 && it.data.code == '20000077' } == 1)
// scheduleEvents' real unschedule() clears every timer; the pending verify comes back
app.scheduleEvents(booking77())
def rearmed = app.scheduled.findAll { it.handler == 'verifySetCode' }
check('scheduleEvents: unschedule() then the unconfirmed write\'s verify is re-armed (one, latest attempt)',
    !app.scheduled.any { it.handler == 'trySetCode' } && rearmed.size() == 1 && rearmed[0].data.codePosition == 51 && rearmed[0].data.code == '20000077' && rearmed[0].data.attempt == 1)
lock.setCalls.clear()
app.verifySetCode(rearmed[0].data)
check('scheduleEvents: the re-armed verify re-sends the write', lock.setCalls == [51] && app.atomicState.codeAssignments['7']['51'].attempt == 2)
lock.setCalls.clear()
app.verifySetCode(rearmed[0].data)
check('verifySetCode: an older attempt\'s verify stops once the slot was sent again', lock.setCalls.isEmpty())
app.codeChangedHandler([device: [id: '7'], value: 'changed', data: JsonOutput.toJson(['51': [name: 'G:ORB77-Cy', code: '********']])])
app.scheduleEvents(booking77())
check('scheduleEvents: a confirmed write gets no verify', app.atomicState.codeAssignments['7']['51'].confirmed == true && !app.scheduled.any { it.handler == 'verifySetCode' })
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(['7': ['51': tracked77['7']['51'] + [confirmed: false, attempt: 3], '52': tracked77['7']['51'] + [confirmed: false, deleting: true]]]))
app.scheduleEvents(booking77())
rearmed = app.scheduled.findAll { it.handler == 'verifySetCode' }
check('scheduleEvents: only unconfirmed writes are re-armed (not deletes), with their backoff', rearmed.size() == 1 && rearmed[0].data.codePosition == 51 && rearmed[0].data.attempt == 3 && rearmed[0].secs == 150)

// --- A retry stops once its booking is gone, confirms from the lock's list, and never writes over another code ---
def retryCycles = { int n ->
    lock.setCalls.clear()
    n.times {
        app.scheduleEvents(app.helperGetBookings(app.atomicState.bookings ?: [:]))
        app.scheduled.findAll { it.handler == 'verifySetCode' }.each { app.verifySetCode(it.data) }
        app.reconcileDoorCodes(app.helperGetBookings(app.atomicState.bookings ?: [:]))
    }
    lock.setCalls.size()
}
Map pend51 = ['7': ['51': tracked77['7']['51'] + [confirmed: false, attempt: 1]]]
[['the lock became unreadable', 'undecryptable:QQ', null], ['the lock reports no codes', '{}', null],
 ['the operator deleted the code by hand', JsonOutput.toJson(['40': [name: 'Front desk', code: '10000040']]), 'deleted']].each { t ->
    lock.attrs.lockCodes = t[1]
    app.atomicState.lockCodesReadEmpty = ['7']
    app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
    if (t[2]) app.codeChangedHandler([device: [id: '7'], value: t[2], data: JsonOutput.toJson([codeNumber: 51])])
    app.atomicState.bookings = [:]
    check("retry after checkout, ${t[0]}: nothing sent over 3 cycles".toString(), retryCycles(3) == 0)
}
app.atomicState.lockCodesReadEmpty = []
app.atomicState.bookings = booking77()
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
app.locks = []
app.scheduled.clear()
app.warned.clear()
app.verifySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('retry: a lock no longer selected gets nothing, the retry stops, no warning', !app.scheduled.any { it.handler in ['trySetCode', 'verifySetCode'] } && app.warned.isEmpty())
app.locks = [lock]
app.atomicState.bookings = booking77() + ['77': booking77()['77'] + [lockId: ['8']]]
app.scheduled.clear()
lock.setCalls.clear()
app.verifySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('retry: a booking moved off this lock gets nothing, no warning', lock.setCalls.isEmpty() && !app.scheduled.any { it.handler == 'verifySetCode' } && app.warned.isEmpty())
app.trySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('retry: a failed-event retry (trySetCode) for a booking off this lock sends nothing', lock.setCalls.isEmpty())
// The lock reports the code written under this app's name, no event came: confirmed, no re-send
app.atomicState.bookings = booking77()
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
lock.attrs.lockCodes = JsonOutput.toJson(['51': [name: 'G:ORB77-Cy', code: '20000077']])
check('retry: the lock already reports the code written: nothing sent over 3 cycles', retryCycles(3) == 0)
check('retry: ... and the write is confirmed', app.atomicState.codeAssignments['7']['51'].confirmed == true)
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
lock.attrs.lockCodes = JsonOutput.toJson(hidden51)
check('retry: a hidden code never confirms from the list: re-sent', retryCycles(1) >= 1 && app.atomicState.codeAssignments['7']['51'].confirmed == false)
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
lock.attrs.lockCodes = JsonOutput.toJson(['51': [name: 'G:ORB77-Cy', code: '20000070']])
app.scheduled.clear()
lock.setCalls.clear()
app.verifySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('retry: our name with an older code is re-sent', lock.setCalls == [51])
// Another name holds the slot and the event that showed it was lost (#59)
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
app.atomicState.codeDisplaced = [:]
lock.attrs.lockCodes = JsonOutput.toJson(['51': [name: 'Front desk', code: '30000051']])
app.scheduled.clear()
lock.setCalls.clear()
app.verifySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1])
check('retry: another code in the slot: not written over, tracking dropped, displaced, reconcile in 10 s',
    lock.setCalls.isEmpty() && !app.atomicState.codeAssignments['7'].containsKey('51') && app.atomicState.codeDisplaced == ['7': ['77']] &&
    app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.secs == 10 })
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(pend51))
check('retry: another code in the slot: nothing written into it over 3 cycles', retryCycles(3) == 0)
check('retry: logs carry no code or name', !newLogs().any { l -> ['20000077', '20000070', '30000051', 'Front desk'].any { l.contains(it) } })
app.atomicState.codeDisplaced = [:]
app.atomicState.bookings = [:]
// The operator asks: Debug -> Reconcile Door Codes rewrites it
lock.attrs.lockCodes = JsonOutput.toJson(hidden51)
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(tracked77))
app.atomicState.bookings = booking77()
app.scheduled.clear()
app.appButtonHandler('btnReconcile')
def opSets = app.scheduled.findAll { it.handler == 'trySetCode' }.collect { it.data }
check('1.4.16 Reconcile Door Codes: a hidden code is rewritten into its slot', opSets.size() == 1 && opSets[0].codePosition == 51 && opSets[0].code == '20000077')
app.atomicState.bookings = [:]
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: '20000079']], tracked77, booking77())
check('1.4.16 a different readable code on a tracked slot is still rewritten', r.sets.size() == 1 && r.sets[0].codePosition == 51)
r = prefixRun(['51': [name: 'G:ORB77-Cy', code: '********']], tracked77, [:])
check('1.4.16 hidden code after checkout: still deleted', r.deletes == [51])
check('1.4.16 no warning for hidden or missing codes (only re-sends of writes not confirmed, and the unreadable lock)', app.warned.every { it.startsWith('verifySetCode: not confirmed for lock 7 pos 51') || it == 'reconcileDoorCodes: lock 7 lockCodes unreadable, skipped' })
check('1.4.16 logs: no code', !newLogs().any { l -> ['20000077', '20000070', '20000079', '1234567'].any { l.contains(it) } })
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = [:]

// --- #64: ambiguous names (different ORB ids after the prefix and at the end) ---
Map booking5 = ['5': [id: '5', guest: 'Eve', code: '20000005', lockId: ['7'], checkIn: new Date(now.time - 3600_000), checkOut: new Date(now.time + 3600_000)]]
[false, true].each { boolean atEnd ->
    app.idAtEnd = atEnd
    String s = atEnd ? 'id at end' : 'id at start'
    check("#64 ${s}: ids read from names".toString(), app.helperCodeNameId('ORB77-ORB5') == null && app.helperCodeNameId('orb77-orb5', true) == null &&
        app.helperCodeNameId('ORB77-Cy') == '77' && app.helperCodeNameId('Cy-ORB77') == '77' && app.helperCodeNameId('ORB77') == '77' && app.helperCodeNameId('ORB77-ORB77') == '77')
    check("#64 ${s}: both ids of an ambiguous name".toString(), app.helperCodeNameIds('ORB77-ORB5') == ['77', '5'] && app.helperCodeNameIds('ORB77-Cy') == null && app.helperCodeNameIds('ORB77-orb5') == null && app.helperCodeNameIds('ORB77-orb5', true) == ['77', '5'])
    claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(['51': [name: 'G:ORB77-ORB5'], '52': [name: 'G:ORB5-ORB77']])))
    check("#64 ${s}: untracked ambiguous names are not claimed".toString(), claimed.isEmpty())
    Map ambCodes = ['51': [name: 'G:ORB77-ORB5', code: '20000077'], '52': [name: 'g:orb5-orb77', code: '20000005']]
    r = prefixRun(ambCodes, [:], booking77() + booking5)
    check("#64 ${s}: current bookings get new slots, the ambiguous slots are untouched".toString(),
        r.deletes.isEmpty() && r.sets.size() == 2 && !r.sets.any { it.codePosition in [51, 52] })
    r = prefixRun(ambCodes, [:], [:])
    check("#64 ${s}: after checkout the ambiguous slots are not deleted".toString(), r.deletes.isEmpty())
    lock.attrs.lockCodes = JsonOutput.toJson(ambCodes)
    app.atomicState.codeAssignments = [:]
    app.atomicState.bookings = booking77()
    mark()
    List an = app.bookingsView().notes.findAll { it.contains('names two bookings') }
    app.atomicState.bookings = [:]
    check("#64 ${s}: Bookings page lists both ambiguous slots with slot, name and ids".toString(), an.size() == 2 &&
        an[0].contains('slot 51 (G:ORB77-ORB5) names two bookings, ORB77 and ORB5') && an[1].contains('slot 52 (g:orb5-orb77) names two bookings, ORB5 and ORB77'))
    check("#64 ${s}: page lines carry no code, nothing logged".toString(), !an.any { it.contains('20000077') || it.contains('20000005') } && newLogs().isEmpty())
}
// Toggling the setting between runs changes nothing for an ambiguous slot
Map ambOnly = ['51': [name: 'G:ORB77-ORB5', code: '20000077']]
app.idAtEnd = false
r = prefixRun(ambOnly, [:], booking77())
app.idAtEnd = true
r2 = prefixRun(ambOnly, app.atomicState.codeAssignments, [:])
app.idAtEnd = false
def r3 = prefixRun(ambOnly, app.atomicState.codeAssignments, booking5)
check('#64 setting toggled between runs: the ambiguous slot is never claimed or deleted',
    ![r, r2, r3].any { x -> x.deletes.contains(51) || x.sets.any { it.codePosition == 51 } } && r2.deletes.isEmpty())
// A tracked slot is decided by its tracking, and is not listed
r = prefixRun(ambOnly, ['7': ['51': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-ORB5', attempt: 1, confirmed: true]]], booking77())
check('#64 tracked slot with an ambiguous name: stays its booking\'s (no second slot)', r.sets.isEmpty() && r.deletes.isEmpty())
app.atomicState.bookings = booking77()
check('#64 tracked slot with an ambiguous name: not listed', !app.bookingsView().notes.any { it.contains('names two bookings') })
app.atomicState.bookings = [:]
// A queued write from an older version carries its own name: the current setting decides
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = [:]
app.trySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB77-ORB5', free: true])
check('#64 queued own write G:ORB77-ORB5, id at start: tracked as ORB77', assigned('51')?.bookingId == 'ORB77')
app.idAtEnd = true
app.atomicState.codeAssignments = [:]
app.trySetCode([lockId: '7', codePosition: 51, code: '20000005', codeName: 'G:ORB77-ORB5', free: true])
check('#64 queued own write G:ORB77-ORB5, id at end: tracked as ORB5', assigned('51')?.bookingId == 'ORB5')
// Code names never carry ORB<digits> from the guest string
app.idAtEnd = false
check('#64 naming: ORB<digits> stripped from the guest string, id at start',
    app.helperGetCodeName([id: '77', guest: 'ORB5']) == 'G:ORB77' && app.helperGetCodeName([id: '77', guest: 'Cy orb5']) == 'G:ORB77-Cy' &&
    app.helperGetCodeName([id: '77', guest: 'Orb5-Cy']) == 'G:ORB77-Cy' && app.helperGetCodeName([id: '77', guest: 'Cy ORB5 Lee']) == 'G:ORB77-Cy Lee')
app.idAtEnd = true
check('#64 naming: ORB<digits> stripped from the guest string, id at end',
    app.helperGetCodeName([id: '77', guest: 'ORB5-Cy']) == 'G:Cy-ORB77' && app.helperGetCodeName([id: '77', guest: 'orb5']) == 'G:ORB77')
check('#64 naming: orb<digits> inside a word is stripped too (Corb12 -> C)', app.helperGetCodeName([id: '77', guest: 'Corb12']) == 'G:C-ORB77')
app.idAtEnd = false
check('#64 naming: orb<digits> inside a word, id at start', app.helperGetCodeName([id: '77', guest: 'Corb12']) == 'G:ORB77-C')
app.idAtEnd = true
check('#64 naming: other guest strings unchanged', app.helperGetCodeName([id: '77', guest: 'Orbison Ann']) == 'G:Orbison Ann-ORB77' && app.helperGetCodeName([id: '77', guest: 'Cy']) == 'G:Cy-ORB77')
app.idAtEnd = false
r = prefixRun([:], [:], booking77(guest: 'Cy ORB5'))
check('#64 reconcile writes the stripped name', r.sets.size() == 1 && r.sets[0].codeName == 'G:ORB77-Cy')
check('#64 duplicate check: an ambiguous name holding the same PIN (not this booking) is a duplicate',
    { lock.attrs.lockCodes = JsonOutput.toJson(['53': [name: 'G:ORB88-ORB5', code: '20000077']]); app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88) }())
check('#64 duplicate check: an ambiguous name holding this booking\'s id is not',
    { lock.attrs.lockCodes = JsonOutput.toJson(['53': [name: 'G:ORB88-ORB77', code: '20000077']]); !app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88) }())
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = [:]

// A skipped lock that a read finds readable again is reconciled shortly after
reset55()
app.atomicState.lockCodesUnreadable = ['7']
lock.attrs.lockCodes = 'undecryptable:UmVh'
reading('7')
settleStep()
check('#57 read ends still unreadable: stays listed, no extra reconcile', app.atomicState.lockCodesUnreadable == ['7'] && !reconciles())
lock.attrs.lockCodes = encrypt(enc, frontDesk)
reading('7')
settleStep()
check('#57 read ends readable again: off the list, reconciled 10 s later', app.atomicState.lockCodesUnreadable == [] && app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.secs == 10 })
reset55()

// Only an unreadable lock has work: the log doesn't claim there is nothing to do
app.locks = [lock]
lock.attrs.lockCodes = 'undecryptable:Tm9uZQ'
mark()
app.reconcileDoorCodes(booking77on(['7']))
check('#57 only an unreadable lock: the log says it was skipped', newLogs().contains('reconcileDoorCodes: No codes to reconcile on the readable locks; 1 lock(s) skipped (lockCodes unreadable)') && !newLogs().contains('reconcileDoorCodes: No codes to reconcile'))

// Lock labels are escaped on the Waiting lines
lock.label = '<i>Front</i>'
app.atomicState.codeWritesDeferred = ['7': 1]
List status = app.codeRefreshStatus()
check('#57 Waiting lines escape the lock label', status.any { it.contains('&lt;i&gt;Front&lt;/i&gt;: its lockCodes') } && status.any { it.contains('&lt;i&gt;Front&lt;/i&gt;: 1 code(s)') } && !status.any { it.contains('<i>') })
lock.label = 'Front'
app.atomicState.codeWritesDeferred = [:]
app.atomicState.lockCodesUnreadable = []
app.locks = [lock, lock8]
check('#57 no encrypted value or decrypt error in any log line', !app.logged.any { l -> ['enc:', 'undecryptable', 'cannot decrypt'].any { l.toString().contains(it) } })
app.ciphers.clear()
app.decrypted.clear()
reset55()
app.locks = [lock]

// Placeholder names: only a driver placeholder naming the slot's own number counts as no name
def verdict = { String name, def pos -> app.helperTrackedVerdict([bookingId: 'ORB77'], name == null ? [code: '1'] : [name: name, code: '1'], pos) }
check('placeholder: "User 51" in slot 51 is claimed', verdict('User 51', '51') == 'claim')
check('placeholder: "Code #51", "code 51", "Slot no. 51", "Lock Code 51" in slot 51 are claimed', ['Code #51', 'code 51', 'Slot no. 51', 'Lock Code 51'].every { verdict(it, 51) == 'claim' })
check('placeholder: no name or a blank name is claimed', verdict(null, '51') == 'claim' && verdict('  ', '51') == 'claim')
check('placeholder: "Code 12" in slot 51 is another name', verdict('Code 12', '51') == 'otherName')
check('placeholder: "Code" and "CODE#" are other names', verdict('Code', '51') == 'otherName' && verdict('CODE#', '51') == 'otherName')
check('placeholder: "Cleaner code" and "SBA: Front door code" are never claimed', verdict('Cleaner code', '51') == 'otherName' && verdict('SBA: Front door code', '51') == 'otherName')
// A hand-set "Code #1" in a slot the app never assigned is never touched
Map userCode1 = ['1': [name: 'Code #1', code: '30000001']]
r = prefixRun(userCode1 + named52, ['7': ['52': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]], [:])
check('untracked "Code #1" in slot 1: never claimed or deleted', r.deletes == [52] && !r.deletes.contains(1))
r = prefixRun(userCode1, [:], booking77())
check('untracked "Code #1" in slot 1: not counted as the booking\'s code', r.sets.size() == 1 && r.sets[0].codePosition == 50)

// A dangerous previous prefix is ignored and the page says why
def prevRefused = { String prefix, String prev ->
    app.codeNamePrefix = prefix
    app.settings = [previousCodeNamePrefix: prev]
    app.paragraphs.clear()
    app.pages()
    [problem: app.previousPrefixProblem(), shown: app.paragraphs.any { it.contains('Previous prefix for code name is not used') }, inUse: app.previousPrefixInUse()]
}
app.codeNamePrefix = 'GT:'
app.settings = [previousCodeNamePrefix: 'G']
claimed = app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(codes)))
check('previous prefix G (no colon): ignored, the other app\'s G:ORB55 is not claimed', claimed.keySet() == ['77'] as Set)
[['', 'GT:', 'empty current prefix'], ['G:', 'G:', 'same as the current prefix'], ['GT:', 'G', 'start of the current prefix'],
 ['G:', 'X', 'shorter than 2 characters'], ['G:', 'GT', 'no colon'], ['G:', 'SBM:', 'starts with SB'], ['G:', 'sba:', 'starts with SB (any case)']].each { t ->
    def v = prevRefused(t[0], t[1])
    check("previous prefix refused (${t[2]}): warning shown and not used", v.problem != null && v.shown && v.inUse == '')
}
def okPrev = prevRefused('G:', ' GT: ')
check('previous prefix GT: with prefix G:: trimmed, used, no warning', okPrev.problem == null && !okPrev.shown && okPrev.inUse == 'GT:')
app.codeNamePrefix = 'G:'
app.settings = [:]

// The booking's own code under an older name is not a duplicate (no 409 after a prefix or guest name change)
lock.attrs.lockCodes = JsonOutput.toJson(ourCode)
check('duplicate check: own old-prefix code is not a duplicate', !app.helperHasDuplicateCode('7', booking77()['77'], booking77()))
check('duplicate check: own code with an old guest name is not a duplicate', !app.helperHasDuplicateCode('7', booking77(guest: 'VIP Cy')['77'], booking77()))
lock.attrs.lockCodes = JsonOutput.toJson(['53': [name: 'G:ORB88-Di', code: '20000077']])
check('duplicate check: the same PIN on another current booking still is', app.helperHasDuplicateCode('7', booking77()['77'], booking77() + booking88))

// --- #95: bookings on one lock with the same code share one slot ---
// In a closure: the script's run() would otherwise exceed the JVM's method size limit
({ ->
// 77 checks in first; 78 is the same guest in the other unit sharing the lock, with the same code
app.locks = [lock]
app.minCodePosition = 50
app.codeNamePrefix = 'G:'
app.settings = [:]
app.atomicState.codeRefresh = null
app.atomicState.codeDisplaced = [:]
app.atomicState.lockCodesReadEmpty = []
long hr = 3600_000L
def stay = { String id, String guest, def code, long inH, long outH -> [(id): [id: id, guest: guest, code: code, lockId: ['7'], checkIn: new Date(now.time + inH * hr), checkOut: new Date(now.time + outH * hr)]] }
Map desk = ['10': [name: 'Front desk', code: '10000040']]
Map slot77 = ['50': [name: 'G:ORB77-Cy', code: '20000077']]
Map track77 = ['7': ['50': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true]]]
Map both = stay('77', 'Cy', '20000077', -2, 1) + stay('78', 'Di', '20000077', -1, 3)
Map only78 = stay('78', 'Di', '20000077', -1, 3)
Map past77 = stay('77', 'Cy', '20000077', -5, -1)

r = prefixRun(desk, [:], both)
check('#95 two same-code current bookings: one write, for the earliest check-in', r.sets.size() == 1 && r.sets[0].codePosition == 50 && r.sets[0].codeName == 'G:ORB77-Cy' && r.deletes.isEmpty())
r = prefixRun(desk + slot77, track77, both)
check('#95 the slot on the lock covers the other booking: no second write, no delete', r.sets.isEmpty() && r.deletes.isEmpty())
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
check('#95 duplicate check: the same code on an overlapping booking\'s slot is shared, not a duplicate', !app.helperHasDuplicateCode('7', both['78'], both))
check('#95 duplicate check: the same for the slot\'s own booking', !app.helperHasDuplicateCode('7', both['77'], both))

// The owner checks out while the other is still current: the slot stays as it is
r = prefixRun(desk + slot77, track77, past77 + only78)
check('#95 owner checks out, other current: slot kept, no delete, no new write', r.sets.isEmpty() && r.deletes.isEmpty())
check('#95 owner checks out: the slot is still tracked for the owner (not renamed)', assigned('50')?.bookingId == 'ORB77' && assigned('50')?.codeName == 'G:ORB77-Cy')
r = prefixRun(desk + slot77, track77, only78)
check('#95 owner removed from the bookings (cancelled): the slot follows the remaining booking', r.sets.isEmpty() && r.deletes.isEmpty())
r = prefixRun(desk + slot77, track77, stay('77', 'Cy', '20000077', 48, 72) + only78)
check('#95 owner moved to later dates: the slot stays for the current booking', r.sets.isEmpty() && r.deletes.isEmpty())
r = prefixRun(desk + slot77, track77, stay('77', 'Cy', '20000077', -2, 1))
check('#95 the other booking removed: the owner keeps its slot as before', r.sets.isEmpty() && r.deletes.isEmpty())
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
check('#95 duplicate check: the remaining booking re-synced after the owner checked out is not a duplicate', !app.helperHasDuplicateCode('7', only78['78'], only78))
// Untracked slot (state reset): the name and the code the lock reports still decide
r = prefixRun(desk + slot77, [:], only78)
check('#95 untracked owner slot with the code reported: kept for the other booking', r.sets.isEmpty() && r.deletes.isEmpty())
r = prefixRun(desk + ['50': [name: 'G:ORB77-Cy', code: '********']], [:], only78)
check('#95 untracked owner slot with the code hidden: can\'t tell, removed and written as before', r.deletes == [50] && r.sets.size() == 1 && r.sets[0].codeName == 'G:ORB78-Di')
// A slot being deleted never covers
Map deleting77 = ['7': ['50': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 1, confirmed: true, deleting: true]]]
r = prefixRun(desk + slot77, deleting77, only78)
check('#95 the owner slot already being deleted: not shared, the other booking gets its own write', r.deletes == [50] && r.sets.size() == 1 && r.sets[0].codePosition == 51)

// Bookings page: the covered booking shows the shared slot, the kept slot is no orphan
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
app.atomicState.codeAssignments = new JsonSlurper().parseText(JsonOutput.toJson(track77))
app.atomicState.bookings = both
def view = app.bookingsView()
check('#95 Bookings page: the other booking is covered by the shared slot, not missing',
    view.bookings.any { it.contains('ORB78') && it.contains('slot 50 (shared with ORB77: same code)') } && view.summary.contains('0 current not on lock') &&
    view.bookings.any { it.contains('ORB77') && it.contains('slot 50 (shared with ORB78: same code)') } && view.orphans.isEmpty())
app.atomicState.bookings = past77 + only78
view = app.bookingsView()
check('#95 Bookings page: after the owner checks out its slot shows as kept for the other booking, no orphan',
    view.bookings.any { it.contains('ORB77') && it.contains('kept: shared with ORB78, same code') } && view.bookings.any { it.contains('ORB78') && it.contains('slot 50 (shared with ORB77') } && view.orphans.isEmpty())
app.atomicState.bookings = only78
view = app.bookingsView()
check('#95 Bookings page: owner gone from the bookings, its slot still no orphan', view.orphans.isEmpty() && view.summary.contains('0 current not on lock'))
app.atomicState.bookings = [:]

// The other later checks out too: the slot goes
r = prefixRun(desk + slot77, track77, past77 + stay('78', 'Di', '20000077', -4, -1))
check('#95 the last booking checks out: the slot is deleted', r.deletes == [50] && r.sets.isEmpty())
r = prefixRun(desk + slot77, track77, [:])
check('#95 no bookings left: the slot is deleted', r.deletes == [50])

// Touching stays (checkout == check-in) share
Map touch = stay('77', 'Cy', '20000077', -5, 0) + stay('78', 'Di', '20000077', 0, 4)
touch['77'].checkOut = new Date(now.time - 60_000); touch['78'].checkIn = touch['77'].checkOut
r = prefixRun(desk + slot77, track77, touch)
check('#95 touching stays: the slot carries over at the changeover, no delete, no write', r.sets.isEmpty() && r.deletes.isEmpty())
Map touchAhead = stay('77', 'Cy', '20000077', -2, 2) + stay('78', 'Di', '20000077', 2, 6)
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
check('#95 duplicate check: an upcoming booking touching the owner\'s stay is not a duplicate', !app.helperHasDuplicateCode('7', touchAhead['78'], touchAhead))

// Same code, stays apart: as before (the slot goes at checkout, the next booking is written at check-in)
r = prefixRun(desk + slot77, track77, past77 + stay('78', 'Di', '20000077', 2, 6))
check('#95 same code, stays apart: the slot is deleted at checkout, nothing written early', r.deletes == [50] && r.sets.isEmpty())
r = prefixRun(desk, [:], stay('78', 'Di', '20000077', -1, 3))
check('#95 same code, stays apart: the later booking gets its own write at check-in', r.sets.size() == 1 && r.sets[0].codeName == 'G:ORB78-Di')

// Different codes: unchanged
Map diff = stay('77', 'Cy', '20000077', -2, 1) + stay('78', 'Di', '20000078', -1, 3)
r = prefixRun(desk, [:], diff)
check('#95 different codes: one slot each', r.sets.collect { it.codePosition } == [50, 51] && r.sets.collect { it.codeName } == ['G:ORB77-Cy', 'G:ORB78-Di'])
r = prefixRun(desk + slot77 + ['51': [name: 'G:ORB78-Di', code: '20000078']], track77, stay('77', 'Cy', '20000077', -5, -1) + stay('78', 'Di', '20000078', -1, 3))
check('#95 different codes: the first checks out, its slot is deleted as before', r.deletes == [50] && r.sets.isEmpty())
r = prefixRun(desk + slot77, track77, stay('77', 'Cy', '20000077', -5, -1) + stay('78', 'Di', '20000078', -1, 3))
check('#95 different codes: no sharing, the other booking gets its own slot', r.deletes == [50] && r.sets.size() == 1 && r.sets[0].codePosition == 51)
lock.attrs.lockCodes = JsonOutput.toJson(desk + ['50': [name: 'G:ORB77-Cy', code: '20000078']])
check('#95 duplicate check: a booking\'s slot holding the PIN while that booking\'s code differs is still a duplicate', app.helperHasDuplicateCode('7', diff['78'], diff))

// Codes from outside this app with the same PIN: still duplicates
lock.attrs.lockCodes = JsonOutput.toJson(desk + ['20': [name: 'Front desk 2', code: '20000077']])
check('#95 duplicate check: an outside code with the same PIN is still a duplicate', app.helperHasDuplicateCode('7', both['78'], both))
lock.attrs.lockCodes = JsonOutput.toJson(desk + ['30': [name: 'GO:ORB77-Cy', code: '20000077']])
check('#95 duplicate check: another app\'s ORB code with the same PIN is still a duplicate', app.helperHasDuplicateCode('7', both['78'], both))

// Leading zero: two booking codes compare as exact text; a lock reporting a code without its zero matches
Map zero = stay('77', 'Cy', '01234567', -2, 1) + stay('78', 'Di', 1234567, -1, 3)
r = prefixRun(desk, [:], zero)
check('#95 booking codes 01234567 and 1234567 are different codes: one write each', r.sets.collect { it.codeName } == ['G:ORB77-Cy', 'G:ORB78-Di'])
Map zero3 = stay('77', 'Cy', '123', -2, 1) + stay('78', 'Di', '0123', -1, 3)
r = prefixRun(desk, [:], zero3)
check('#95 booking codes 123 and 0123 are different codes: one write each', r.sets.size() == 2)
lock.attrs.lockCodes = JsonOutput.toJson(desk + ['50': [name: 'G:ORB77-Cy', code: '123']])
check('#95 duplicate check: 0123 beside booking 77\'s 123 is still a duplicate warning', app.helperHasDuplicateCode('7', zero3['78'], zero3))
r = prefixRun(desk + ['50': [name: 'G:ORB77-Cy', code: 1234567]], [:], stay('78', 'Di', '01234567', -1, 3))
check('#95 leading zero: an owner slot the lock reports without its zero covers the other booking', r.sets.isEmpty() && r.deletes.isEmpty())
Map zeroLock = stay('77', 'Cy', '01234567', -2, 1) + stay('78', 'Di', '01234567', -1, 3)
lock.attrs.lockCodes = JsonOutput.toJson(desk + ['50': [name: 'G:ORB77-Cy', code: 1234567]])
check('#95 duplicate check: the slot reported without its zero, same booking codes: shared, not a duplicate', !app.helperHasDuplicateCode('7', zeroLock['78'], zeroLock))

// A no-longer-current booking's slot counts by what the lock reports, not an unconfirmed tracked code
Map unconfirmed77 = ['7': ['50': [bookingId: 'ORB77', code: '20000077', codeName: 'G:ORB77-Cy', attempt: 3, confirmed: false]]]
r = prefixRun(desk + ['50': [code: '20000066']], unconfirmed77, past77 + only78)
check('#95 nameless driver: the old slot reports another code than tracked: not shared, removed, the other booking written', r.deletes == [50] && r.sets.size() == 1 && r.sets[0].codeName == 'G:ORB78-Di')
r = prefixRun(desk + ['50': [code: '********']], unconfirmed77, past77 + only78)
check('#95 nameless driver: hidden code, tracked write unconfirmed: not shared', r.deletes == [50] && r.sets.size() == 1)
r = prefixRun(desk + ['50': [code: '********']], track77, past77 + only78)
check('#95 nameless driver: hidden code, tracked write confirmed: shared', r.deletes.isEmpty() && r.sets.isEmpty())

// A current booking's confirmed slot that a partial report leaves out still holds the code
r = prefixRun(desk, track77, both)
check('#95 the owner\'s confirmed slot missing from the report: rewritten there, no write for the other booking', r.sets.size() == 1 && r.sets[0].codePosition == 50 && r.sets[0].codeName == 'G:ORB77-Cy')

// The other booking still current but moved to another lock: the 409 check agrees with reconcile
Map moved = stay('77', 'Cy', '20000099', -2, 1) + only78
moved['77'].lockId = ['8']
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
check('#95 duplicate check: slot of a booking now current on another lock (its code since changed) covers this one, not a duplicate', !app.helperHasDuplicateCode('7', moved['78'], moved))
r = prefixRun(desk + slot77, track77, moved)
check('#95 reconcile: that slot is kept for the booking on this lock', r.sets.isEmpty() && r.deletes.isEmpty())

// A covered booking's own unconfirmed write (the lock may have refused it): deleted, never forgotten
app.atomicState.bookings = both
Map refused = ['7': track77['7'] + ['51': [bookingId: 'ORB78', code: '20000077', codeName: 'G:ORB78-Di', attempt: 9, confirmed: false]]]
r = prefixRun(desk + slot77, refused, both)
check('#95 the covered booking\'s unconfirmed write: no new write, a delete of its slot, tracking kept', r.sets.isEmpty() && r.deletes == [51] && assigned('51') != null && assigned('50') != null)
def refusedDelete = app.scheduled.find { it.handler == 'tryDeleteCode' }
lock.deleted.clear()
if (refusedDelete) app.tryDeleteCode(refusedDelete.data)
check('#95 the delete runs and marks the slot deleting', lock.deleted == [51] && assigned('51')?.deleting == true)
lock.setCalls.clear()
app.verifySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB78-Di', attempt: 9])
check('#95 that write is no longer re-sent (the booking is still current)', lock.setCalls.isEmpty())
Map afterDelete = new JsonSlurper().parseText(JsonOutput.toJson(app.atomicState.codeAssignments))
r = prefixRun(desk + ['50': [code: '20000077'], '51': [code: '20000077']], afterDelete, [:])
check('#95 if that write landed anyway (nameless driver), its slot is still removed after checkout', r.deletes.sort() == [50, 51])
// The delete never removes a code written to that slot since it was queued
r = prefixRun(desk + slot77, refused, both)
def guardJob = app.scheduled.find { it.handler == 'tryDeleteCode' }
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77 + ['51': [name: 'Cleaner', code: '30000051']])
app.codeChangedHandler([device: [id: '7'], value: 'added', data: JsonOutput.toJson(['51': [name: 'Cleaner', code: '30000051']])])
lock.deleted.clear()
app.tryDeleteCode(guardJob.data)
check('#95 a foreign code written to that slot before the delete runs is not deleted', assigned('51') == null && lock.deleted.isEmpty())
r = prefixRun(desk + slot77, refused, both)
guardJob = app.scheduled.find { it.handler == 'tryDeleteCode' }
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
app.trySetCode([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB78-Di', free: true])
lock.deleted.clear()
app.tryDeleteCode(guardJob.data)
check('#95 a fresh write of the booking into that slot before the delete runs is not deleted', lock.deleted.isEmpty() && assigned('51')?.attempt == 1 && !assigned('51')?.deleting)
r = prefixRun(desk + slot77 + ['51': [name: 'Plumber', code: '30000051']], refused, both)
check('#95 no delete queued while the lock shows another code there', r.deletes.isEmpty())
r = prefixRun(desk + slot77, refused, both)
guardJob = app.scheduled.find { it.handler == 'tryDeleteCode' }
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77 + ['51': [name: 'G:ORB12-Zed', code: '30000051']])
lock.deleted.clear()
app.tryDeleteCode(guardJob.data)
check('#95 the delete is skipped when the lock shows another booking in that slot', lock.deleted.isEmpty())
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77)
lock.deleted.clear()
app.tryDeleteCode([lockId: '7', codePosition: 50])
check('#95 checkout deletes are unguarded as before', lock.deleted == [50])

// The write lands and the lock confirms it before the delete runs: it is the booking's slot now, never deleted
Map pastBoth = past77 + only78
app.atomicState.bookings = pastBoth
r = prefixRun(desk + slot77, refused, pastBoth)
guardJob = app.scheduled.find { it.handler == 'tryDeleteCode' }
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77 + ['51': [name: 'G:ORB78-Di', code: '20000077']])
app.codeChangedHandler([device: [id: '7'], value: 'added', data: JsonOutput.toJson(['51': [name: 'G:ORB78-Di', code: '20000077']])])
check('#95 a confirmed write is no longer marked as dropped', assigned('51')?.confirmed == true && !assigned('51')?.sharedDrop)
app.scheduled.clear()
app.reconcileDoorCodes(pastBoth)
check('#95 the booking now has its own slot: the old covering slot goes', app.scheduled.findAll { it.handler == 'tryDeleteCode' }.collect { it.data.codePosition } == [50])
lock.deleted.clear()
app.tryDeleteCode(guardJob.data)
check('#95 the queued delete of the confirmed write is skipped', lock.deleted.isEmpty())
// Confirmed by a retry's read of the lock instead of an event
r = prefixRun(desk + slot77, refused, pastBoth)
guardJob = app.scheduled.find { it.handler == 'tryDeleteCode' }
lock.attrs.lockCodes = JsonOutput.toJson(desk + slot77 + ['51': [name: 'G:ORB78-Di', code: '20000077']])
app.helperRetryWanted([lockId: '7', codePosition: 51, code: '20000077', codeName: 'G:ORB78-Di', attempt: 9], app.atomicState.codeAssignments['7']['51'])
lock.deleted.clear()
app.tryDeleteCode(guardJob.data)
check('#95 confirmed from the lock\'s codes: not marked dropped, the delete is skipped', assigned('51')?.confirmed == true && !assigned('51')?.sharedDrop && lock.deleted.isEmpty())
app.atomicState.bookings = both

// The owner's code changes while it covers another booking: its rewrite goes first, whatever the order
[both, only78 + stay('77', 'Cy', '20000077', -2, 1)].each { Map order ->
    Map changed = order.collectEntries { k, b -> [k, k == '77' ? b + [code: '20000099'] : b] }
    r = prefixRun(desk + slot77, track77, changed)
    def ownerJob = r.jobs.find { it.handler == 'trySetCode' && it.data.codePosition == 50 }
    def newJob = r.jobs.find { it.handler == 'trySetCode' && it.data.codeName == 'G:ORB78-Di' }
    check("#95 owner code changes (${order.keySet().join(',')}): the owner's rewrite is queued before the other booking's write".toString(),
        ownerJob && newJob && ownerJob.secs < newJob.secs && newJob.data.codePosition == 51)
}

// Never while the lock is being read (partial list)
app.atomicState.codeRefresh = [index: 0, lockIds: ['7'], stepAt: System.currentTimeMillis(), budgetSec: 600, floorSec: 0, runId: 1]
r = prefixRun(desk + slot77, refused, both)
check('#95 during a door code refresh read: no delete, no write, tracking untouched', r.sets.isEmpty() && r.deletes.isEmpty() && assigned('51')?.confirmed == false && !assigned('51')?.deleting)
app.atomicState.codeRefresh = null
app.atomicState.bookings = [:]
// No slot on the lock yet but a write pending for the later booking: that write is the one
Map pending78 = ['7': ['51': [bookingId: 'ORB78', code: '20000077', codeName: 'G:ORB78-Di', attempt: 1, confirmed: false]]]
r = prefixRun(desk, pending78, both)
check('#95 no slot yet, a write pending for one booking: it is the one written, no second slot', r.sets.size() == 1 && r.sets[0].codePosition == 51 && r.sets[0].codeName == 'G:ORB78-Di')

// Scheduling: every current booking's check-out and each lock's next check-in
app.scheduleEvents(both + stay('79', 'Ed', '20000079', 5, 9) + stay('80', 'Fi', '20000080', 6, 9))
List onceAt = app.scheduled.findAll { it.handler == 'reconcileDoorCodes' && it.at }.collect { it.at }
check('#95 scheduling: reconcile at each current booking\'s check-out and the next check-in only',
    onceAt as Set == [both['77'].checkOut, both['78'].checkOut, new Date(now.time + 5 * hr)] as Set && onceAt.size() == 3)
app.scheduleEvents(stay('79', 'Ed', '20000079', 5, 9))
onceAt = app.scheduled.findAll { it.handler == 'reconcileDoorCodes' && it.at }.collect { it.at }
check('#95 scheduling: one upcoming booking, its check-in as before', onceAt == [new Date(now.time + 5 * hr)])
lock.attrs.lockCodes = '{}'
app.atomicState.codeAssignments = [:]
})()

app.paragraphs.clear()
app.settings = [locks: ['x'] as String[], codeNamePrefix: 'G:']
app.pages()
check('page: explains the previous prefix setting', app.paragraphs.any { it.contains('enter the old one here') })
check('harness: no code in any log line', !app.logged.any { l -> ['20000077', '20000078', '20000070', '20000088', '30000051', '10000031', '10000099', '30000032', '30000033', '20000012', '30000001', '20000066', '10000040', '1234567', '1234568', '20000079', '20000005', '101234567', '20000080', '20000066', '0123', '20000099', '30000051'].any { l.toString().contains(it) } })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
