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
    def log = [info: { logged << it }, warn: { logged << it }, debug: { logged << it }, trace: { logged << it }, error: { logged << it }]
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
    String createAccessToken() { 'tok-fake' }
}

class FakeLock {
    String id; String name; String label
    Map attrs = [:]
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    List deleted = []
    void deleteCode(def pos) { deleted << pos }
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
check('same booking in two slots, after checkout: the delete reached the lock and cleared tracking', lock.deleted == [51] && !app.atomicState.codeAssignments['7']?.containsKey('51'))
r = prefixRun(['31': [name: 'G:ORB77-Cy', code: '10000031']], app.atomicState.codeAssignments, [:])
check('same booking in two slots, after checkout: the next reconcile removes the other slot', r.deletes == [31])

// The tracked slot was reassigned outside the app: trust it only when the lock agrees
Map staleTracked51 = ['7': ['51': [bookingId: 'ORB77', code: '20000070', codeName: 'GT:ORB77-Cy', attempt: 1, confirmed: true]]]
Map named52 = ['52': [name: 'G:ORB77-Cy', code: '20000077']]
r = prefixRun(['51': [name: 'Plumber', code: '30000051']] + named52, staleTracked51, booking77())
check('tracked slot now holds a foreign code: slot 51 is neither written nor deleted', !r.sets.any { it.codePosition == 51 } && !r.deletes.contains(51) && r.sets.isEmpty())
Map booking88 = ['88': [id: '88', guest: 'Di', code: '20000088', lockId: ['7'], checkIn: new Date(now.time - 3600_000), checkOut: new Date(now.time + 3600_000)]]
r = prefixRun(['51': [name: 'G:ORB88-Di', code: '20000088']] + named52, staleTracked51, booking77() + booking88)
check('tracked slot now holds another current booking: no rewrite, no delete', r.sets.isEmpty() && r.deletes.isEmpty())

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

app.paragraphs.clear()
app.settings = [locks: ['x'] as String[], codeNamePrefix: 'G:']
app.pages()
check('page: explains the previous prefix setting', app.paragraphs.any { it.contains('enter the old one here') })
check('harness: no code in any log line', !app.logged.any { l -> ['20000077', '20000078', '20000070', '20000088', '30000051', '10000031'].any { l.toString().contains(it) } })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
