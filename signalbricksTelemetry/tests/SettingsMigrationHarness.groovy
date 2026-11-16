// Settings migration to 1.8.0 (#80) with a stubbed Hubitat sandbox: the locks
// picked for lock code management before (codeLocks) join Locks and become the
// managed locks, on Done, on opening the app or at the daily check-in, and
// work before that too (an HPM update doesn't run updated()). Fake values only.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    Map getAtomicState() { state }
    Map params = [:]
    def request = [:]
    def locks = null, managedLocks = null, codeLocks = null
    def contactSensors = null, temperatureSensors = null, humiditySensors = null
    def controlSwitches = null, controlGarageDoors = null, controlDoors = null, controlThermostats = null, controlValves = null
    Boolean lockControlEnabled = null, deviceControlEnabled = null
    Boolean lockManagementEnabled = true
    Boolean includeDoorCodes = false
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    def fullControlLocks = null
    List removedSettings = []
    List updatedSettings = []
    // The hub's devices, by id: a capability setting written with ids reads back as devices
    Map devices = [:]
    List logged = []
    List posts = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, error: { logged << it.toString() }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177'], timeZone: TimeZone.getTimeZone('UTC')]
    void definition(Map m) {}
    void preferences(Closure c) {}
    void mappings(Closure c) {}
    List paragraphs = []
    Map inputs = [:]
    def dynamicPage(Map m, Closure c) { c(); m }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) {}
    Map render(Map m) { m }
    long clock = 1_800_000_000_000L
    long now() { clock }
    List scheduled = []
    void runIn(Number secs, String handler, Map opts = [:]) { scheduled << [secs: secs, handler: handler, data: opts?.data] }
    void schedule(String cron, String handler) { scheduled << [cron: cron, handler: handler] }
    void unschedule(String handler = null) { if (handler) scheduled.removeAll { it.handler == handler } else scheduled.clear() }
    void subscribe(Object... args) {}
    void unsubscribe() {}
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    int tokenSeq = 0
    String createAccessToken() { tokenSeq++; state.accessToken = "fake-app-token-000${tokenSeq}"; state.accessToken }
    void asynchttpPost(String cb, Map p, Map d) { posts << [cb: cb, params: p, data: d] }
    String decrypt(String s) { throw new RuntimeException('no decrypt') }
}

class FakeApp {
    def owner
    def id = 42
    FakeApp(o) { owner = o }
    void removeSetting(String n) { owner.removedSettings << n; owner.setProperty(n, null) }
    void updateSetting(String n, Map v) {
        owner.updatedSettings << [name: n, type: v.type, value: v.value]
        Map devs = owner.devices
        owner.setProperty(n, v.type?.startsWith('capability.') ? v.value.collect { devs[it] } : v.value)
    }
}

class FakeLock {
    String id; String displayName; String name; String label
    Map attrs = [:]
    List calls = []
    List caps = ['Lock', 'LockCodes']
    Boolean hasCapability(String c) { caps.contains(c) }
    Boolean hasCommand(String c) { c in ['lock', 'unlock'] }
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void lock() { calls << 'lock' }
    void unlock() { calls << 'unlock' }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
String source = new File(args[0]).text

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
def body = { Map r -> r.data ? new JsonSlurper().parseText(r.data) : null }
List codes = ['86420001', '86420002']
List apps = []

// A hub on 1.7.0: Locks A and B for telemetry; A and C (C only there) for lock
// code management; full control on A; a failed code change held for C.
def oldHub = {
    def app = shell.parse(source)
    app.run()
    def a = new FakeLock(id: '101', displayName: 'Lock A', label: 'Lock A', attrs: [lock: 'locked', lockCodes: JsonOutput.toJson(['1': [name: 'SBA: Owner', code: codes[0]]])])
    def b = new FakeLock(id: '102', displayName: 'Lock B', label: 'Lock B', attrs: [lock: 'locked'])
    def c = new FakeLock(id: '103', displayName: 'Lock C', label: 'Lock C', attrs: [lock: 'unlocked', lockCodes: JsonOutput.toJson(['12': [name: 'SBM: Cleaner', code: codes[1]]])])
    app.devices = ['101': a, '102': b, '103': c]
    app.locks = [a, b]
    app.codeLocks = [a, c]
    app.fullControlLocks = ['101']
    app.state.sbToken = 'ingest-fake'
    app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
    app.state.lockMgmtLastEnabled = true
    app.state.lockMgmtRegistered = true
    app.state.lockConfig = [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['101': [adopted: []], '103': [adopted: []]]]
    app.state.pendingCodeChanges = ['103:12': [lockId: '103', slot: 12, action: 'delete', name: 'SBM: Cleaner', prevName: 'SBM: Cleaner', requestId: 'r1',
                                              attempts: 3, createdAt: app.now(), status: 'failed', reason: 'not_applied']]
    app.createAccessToken()
    apps << app
    app
}
def ids = { List l -> l.collect { it.id.toString() } }
def migrated = { app ->
    ids(app.locks) == ['101', '102', '103'] && app.managedLocks == ['101', '103'] && app.codeLocks == null && app.removedSettings.contains('codeLocks')
}

// Before the migration runs (HPM update, no Done yet): everything works as before
def app = oldHub()
check('before migration: code-managed locks are managed', app.findCodeLock('101') != null && app.findCodeLock('103') != null && app.findCodeLock('102') == null)
app.params = [:]
check('before migration: /locks lists the code-managed locks', body(app.apiListLocks()).locks*.id == ['101', '103'])
check('before migration: every lock is reported once', app.reportLocks()*.deviceId == ['101', '102', '103'])
check('before migration: full control kept', app.fullControlAllowedIds() == ['101'])
check('before migration: nothing written yet', app.updatedSettings.isEmpty() && app.removedSettings.isEmpty())

// Opening the app migrates
app.mainPage()
check('open the app: codeLocks join Locks and become the managed locks', migrated(app))
check('open the app: Locks written as a lock capability setting', app.updatedSettings.find { it.name == 'locks' } == [name: 'locks', type: 'capability.lock', value: ['101', '102', '103']])
check('open the app: managed locks written as an enum', app.updatedSettings.find { it.name == 'managedLocks' }?.type == 'enum')
check('open the app: logged once, with a count only', app.logged.count { it.contains('moved to Devices') } == 1 &&
    app.logged.any { it == 'SignalBricks Telemetry: 2 lock(s) selected for lock code management moved to Devices as managed locks' })
check('after: same locks managed, B still not managed', app.findCodeLock('101') != null && app.findCodeLock('103') != null && app.findCodeLock('102') == null)
check('after: full control, slot rules and the held change kept', app.fullControlAllowedIds() == ['101'] && app.state.lockConfig.locks.keySet() == ['101', '103'] as Set &&
    app.state.pendingCodeChanges.containsKey('103:12'))
check('after: page shows the managed locks, no separate code-lock list', app.inputs.managedLocks?.options?.keySet() == ['101', '102', '103'] as Set && !app.inputs.containsKey('codeLocks'))
int writes = app.updatedSettings.size()
app.mainPage()
app.updated()
app.dailyCheckIn()
check('migration runs once', app.updatedSettings.size() == writes && app.removedSettings.count { it == 'codeLocks' } == 1 && app.logged.count { it.contains('moved to Devices') } == 1)
check('Done after migration: held change kept, lock code management still registered on', app.state.pendingCodeChanges.containsKey('103:12') &&
    new JsonSlurper().parseText(app.posts.findAll { it.cb == 'registerCallback' }[-1].params.body).lockManagement == true)

// Done migrates
app = oldHub()
app.updated()
check('Done: migrated', migrated(app))
check('Done: the held change for the lock only under lock code management is kept', app.state.pendingCodeChanges.containsKey('103:12'))
Map reg = new JsonSlurper().parseText(app.posts.findAll { it.cb == 'registerCallback' }[-1].params.body)
check('Done: lock and device control stay off after the update', reg.lockControl == false && reg.deviceControl == false && app.state.lockControlOn == null)
app.params = [lockId: '101']
check('Done: unlock not available', app.apiUnlock().status == 404 && app.devices['101'].calls.isEmpty())

// The daily check-in migrates (an HPM update without anyone opening the app)
app = oldHub()
app.dailyCheckIn()
check('daily check-in: migrated', migrated(app))

// Code-managed locks already under Locks: Locks is left as it is
app = oldHub()
app.locks = [app.devices['101'], app.devices['102'], app.devices['103']]
app.updated()
check('all already under Locks: Locks not rewritten', !app.updatedSettings.any { it.name == 'locks' } && app.managedLocks == ['101', '103'] && app.codeLocks == null)

// No Locks selected at all, only code-managed ones
app = oldHub()
app.locks = null
app.updated()
check('no telemetry locks: the code-managed ones become Locks', ids(app.locks) == ['101', '103'] && app.managedLocks == ['101', '103'])

// An empty codeLocks setting is only removed
app = oldHub()
app.codeLocks = []
app.updated()
check('empty codeLocks: removed, nothing managed', app.codeLocks == null && app.removedSettings.contains('codeLocks') && app.managedLocks == null && ids(app.locks) == ['101', '102'])

// Never had one: nothing to do
app = oldHub()
app.codeLocks = null
app.updated()
check('no codeLocks: no lock list written, nothing logged', !app.updatedSettings.any { it.name in ['locks', 'managedLocks', 'codeLocks'] } &&
    !app.removedSettings.contains('codeLocks') && !app.logged.any { it.contains('moved to Devices') })

// Lock code management was off (its lock list stays on the hub): migrated, still off
app = oldHub()
app.lockManagementEnabled = false
app.state.lockMgmtLastEnabled = false
app.updated()
check('lock code management off: migrated and still off', migrated(app) && app.lockManagementEnabled == false && app.lockManagementActive() == false)

// Managed locks already set (a beta), plus a leftover codeLocks: both kept
app = oldHub()
app.managedLocks = ['102']
app.updated()
check('existing managed locks are kept and the old ones added', app.managedLocks == ['102', '101', '103'] && app.codeLocks == null)

// Code-only devices (keypads) can't be Locks: they stay in codeLocks, still managed
app = oldHub()
def keypad = new FakeLock(id: '104', displayName: 'Keypad', label: 'Keypad', caps: ['LockCodes'], attrs: [lockCodes: JsonOutput.toJson(['2': [name: 'SBA: Owner', code: codes[0]]])])
app.devices['104'] = keypad
app.codeLocks = [app.devices['101'], keypad, app.devices['103']]
app.mainPage()
check('keypad: locks migrated, keypad kept in codeLocks', ids(app.locks) == ['101', '102', '103'] && app.managedLocks == ['101', '103'] && ids(app.codeLocks) == ['104'] &&
    !app.removedSettings.contains('codeLocks') && app.updatedSettings.find { it.name == 'codeLocks' }?.type == 'capability.lockCodes')
check('keypad: not added to Locks', !app.updatedSettings.find { it.name == 'locks' }.value.contains('104'))
check('keypad: still managed for codes', app.findCodeLock('104') != null && body(app.apiListLocks()).locks*.id.contains('104'))
app.paragraphs.clear(); app.inputs.clear(); app.mainPage()
check('keypad: shown under Devices to see or clear', app.inputs.codeLocks?.type == 'capability.lockCodes' && !app.inputs.managedLocks.options.containsKey('104'))
int kw = app.updatedSettings.size()
app.updated()
app.dailyCheckIn()
check('keypad: nothing rewritten again', app.updatedSettings.findAll { it.name in ['locks', 'managedLocks', 'codeLocks'] }.size() == app.updatedSettings.take(kw).findAll { it.name in ['locks', 'managedLocks', 'codeLocks'] }.size() &&
    ids(app.codeLocks) == ['104'])
app.lockControlEnabled = true
app.updated()
app.params = [lockId: '104']
check('keypad: lock control never reaches it', app.state.lockControlOn == true && app.apiUnlock().status == 404 && keypad.calls.isEmpty())
app.params = [lockId: '101']
check('keypad: a migrated lock is controllable once turned on', app.apiUnlock().status == 202)
app.codeLocks = null
app.updated()
check('keypad cleared: no longer managed', app.findCodeLock('104') == null)

app = oldHub()
def pad2 = new FakeLock(id: '105', displayName: 'Pad', label: 'Pad', caps: ['LockCodes'])
app.devices['105'] = pad2
app.codeLocks = [pad2]
app.updated()
check('only keypads: nothing moved, codeLocks kept, nothing logged', ids(app.codeLocks) == ['105'] && app.managedLocks == null && ids(app.locks) == ['101', '102'] &&
    !app.updatedSettings.any { it.name in ['locks', 'managedLocks', 'codeLocks'] } && !app.logged.any { it.contains('moved to Devices') })

String everything = apps.collect { (it.logged + it.posts.collect { p -> p.params.body }).join('\n') }.join('\n')
check('no code in any log or post', !codes.any { everything.contains(it) })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
