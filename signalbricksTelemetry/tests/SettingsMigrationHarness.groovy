// Settings migration to 1.9.0 (#82) with a stubbed Hubitat sandbox, from 1.7.x
// (codeLocks) and 1.8.0 (managedLocks, fullControlLocks): every selected lock
// becomes managed, full control becomes one switch, keypads keep their list,
// control settings carry over, and it runs once, on Done, on opening the app
// or at the daily check-in. Fake values only.
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
    Boolean fullControlEnabled = null
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
    // Hubitat may not show a setting written in a run until the run ends: when
    // set, writes of these kinds are held until flush()
    Set deferred = [] as Set
    List held = []
    // The run stops (e.g. the hub restarts) at the nth setting write from now
    int failAt = 0
    void write(String kind, Closure c) {
        if (failAt > 0 && --failAt == 0) throw new IllegalStateException('run interrupted')
        if (kind in deferred) held << c else c()
    }
    void removeSetting(String n) {
        def hub = owner
        write('remove') { hub.removedSettings << n; hub.setProperty(n, null) }
    }
    void updateSetting(String n, Map v) {
        Map devs = owner.devices
        def value = v.type?.startsWith('capability.') ? v.value.collect { devs[it] } : v.value
        def hub = owner
        write('update') { hub.updatedSettings << [name: n, type: v.type, value: v.value]; hub.setProperty(n, value) }
    }
    void flush() { held.each { it() }; held.clear() }
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
List codes = ['86420001', '86420002', '86420003']
List apps = []
def ids = { List l -> (l ?: []).collect { it.id.toString() } }
def registers = { app -> app.posts.findAll { it.cb == 'registerCallback' }.collect { new JsonSlurper().parseText(it.params.body) } }
def settingsWritten = { app -> app.updatedSettings.size() + app.removedSettings.size() }

def newHub = {
    def app = shell.parse(source)
    app.run()
    def a = new FakeLock(id: '101', displayName: 'Lock A', label: 'Lock A', attrs: [lock: 'locked', lockCodes: JsonOutput.toJson(['1': [name: 'SBA: Owner', code: codes[0]]])])
    def b = new FakeLock(id: '102', displayName: 'Lock B', label: 'Lock B', attrs: [lock: 'locked', lockCodes: JsonOutput.toJson(['2': [name: 'SBA: Owner', code: codes[2]]])])
    def c = new FakeLock(id: '103', displayName: 'Lock C', label: 'Lock C', attrs: [lock: 'unlocked', lockCodes: JsonOutput.toJson(['12': [name: 'SBM: Cleaner', code: codes[1]]])])
    app.devices = ['101': a, '102': b, '103': c]
    app.state.sbToken = 'ingest-fake'
    app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
    app.state.lockMgmtLastEnabled = true
    app.state.lockMgmtRegistered = true
    app.state.registeredVersion = '1.8.0'
    app.state.lockConfig = [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['101': [adopted: []], '103': [adopted: [40]]]]
    app.state.fingerprintKey = 'fake-fingerprint-key-not-real'
    app.approvedAdoptedSlots = ['103:40']
    app.state.pendingCodeChanges = ['103:12': [lockId: '103', slot: 12, action: 'delete', name: 'SBM: Cleaner', prevName: 'SBM: Cleaner', requestId: 'r1',
                                              attempts: 3, createdAt: app.now(), status: 'failed', reason: 'not_applied']]
    app.createAccessToken()
    apps << app
    app
}
// 1.7.x: Locks A and B for telemetry; A and C (C only there) for lock code management; full control on A
def hub17 = {
    def app = newHub()
    app.locks = [app.devices['101'], app.devices['102']]
    app.codeLocks = [app.devices['101'], app.devices['103']]
    app.fullControlLocks = ['101']
    app
}
// 1.8.0: Locks A, B and C; A and C ticked as managed; full control on A; lock control on
def hub18 = {
    def app = newHub()
    app.locks = [app.devices['101'], app.devices['102'], app.devices['103']]
    app.managedLocks = ['101', '103']
    app.fullControlLocks = ['101']
    app.lockControlEnabled = true
    app.state.lockControlOn = true
    app
}
def migrated = { app ->
    ids(app.locks) == ['101', '102', '103'] && app.managedLocks == null && app.codeLocks == null && app.fullControlLocks == null &&
        app.fullControlEnabled == true && app.state.settingsMigration == [done: true]
}
def keptState = { app ->
    app.state.pendingCodeChanges?.containsKey('103:12') && app.state.lockConfig?.locks?.keySet() == ['101', '103'] as Set &&
        app.state.fingerprintKey == 'fake-fingerprint-key-not-real' && app.approvedAdoptedSlots == ['103:40']
}
def page = { app -> app.paragraphs.clear(); app.inputs.clear(); app.mainPage(); app.paragraphs.join('\n') }

// --- 1.7 -> 1.9 ---
def app = hub17()
check('1.7, before migration: the earlier lists still decide', app.findCodeLock('101') != null && app.findCodeLock('103') != null && app.findCodeLock('102') == null &&
    app.fullControlAllowedIds() == ['101'])
app.params = [:]
check('1.7, before migration: /locks lists the code-managed locks', body(app.apiListLocks()).locks*.id == ['101', '103'])
check('1.7, before migration: nothing written', settingsWritten(app) == 0)
String p17 = page(app)
check('1.7 open the app: migrated', migrated(app))
check('1.7: Locks written as a lock capability setting', app.updatedSettings.find { it.name == 'locks' } == [name: 'locks', type: 'capability.lock', value: ['101', '102', '103']])
check('1.7: full control written as one switch', app.updatedSettings.find { it.name == 'fullControlEnabled' } == [name: 'fullControlEnabled', type: 'bool', value: true])
check('1.7: every lock managed, full control on all', ['101', '102', '103'].every { app.findCodeLock(it) != null } && app.fullControlAllowedIds() == ['101', '102', '103'])
check('1.7: slot rules, fingerprint key, approvals and the held change kept', keptState(app))
check('1.7: note names the newly managed lock and full control', p17.contains('Newly managed: Lock B.') && !p17.contains('Lock A,') && p17.contains('Full control above the ceiling is now one switch'))
check('1.7: note can be dismissed', app.inputs.btnDismissSettingsNote?.type == 'button')
check('1.7: logged once, with a count only', app.logged.count { it.contains('settings moved to') } == 1 &&
    app.logged.any { it == 'SignalBricks Telemetry: settings moved to SignalBricks Telemetry 1.9.0: 1 lock(s) newly managed, full control is one switch' })
check('1.7: page has one Locks list and one full control switch', app.inputs.locks?.type == 'capability.lock' && app.inputs.fullControlEnabled?.type == 'bool' &&
    !['managedLocks', 'fullControlLocks', 'codeLocks'].any { app.inputs.containsKey(it) })
int writes = settingsWritten(app)
page(app)
app.updated()
app.dailyCheckIn()
check('1.7: runs once', settingsWritten(app) == writes && app.logged.count { it.contains('settings moved to') } == 1)
check('1.7: next Done clears the note', app.state.settingsNote == null && !page(app).contains('Updated settings'))
Map reg = registers(app)[-1]
check('1.7: Done after migration: still registered, code management on, same features', reg.lockManagement == true && reg.codeManagement == true &&
    reg.lockControl == false && reg.deviceControl == false && keptState(app))

// Dismiss
app = hub17()
page(app)
app.appButtonHandler('btnDismissSettingsNote')
check('dismiss: note gone', app.state.settingsNote == null && !page(app).contains('Updated settings'))

// The daily check-in migrates (an HPM update without anyone opening the app)
app = hub17()
app.dailyCheckIn()
check('1.7 daily check-in: migrated, note waits for the page', migrated(app) && page(app).contains('Newly managed: Lock B.'))
app.updated()
check('1.7 daily check-in: the next Done clears the note', app.state.settingsNote == null)

// 1.7 with lock code management off: migrated, still off, no note
app = hub17()
app.lockManagementEnabled = false
app.state.remove('lockMgmtLastEnabled')
app.fullControlLocks = null
app.state.remove('lockConfig'); app.state.remove('fingerprintKey'); app.approvedAdoptedSlots = null
app.updated()
check('1.7 off: migrated and still off, no note', ids(app.locks) == ['101', '102', '103'] && app.codeLocks == null && app.lockManagementActive() == false &&
    app.state.settingsNote == null && app.endpointOn() == false)

// --- 1.8 -> 1.9: a lock that was unticked becomes managed, so lock control waits for a Done ---
app = hub18()
String token = app.state.accessToken
app.params = [lockId: '102']
check('1.8, before migration: an unticked lock is not controlled', app.apiUnlock().status == 404 && app.findCodeLock('102') == null)
app.params = [lockId: '101']
check('1.8, before migration: a ticked lock is', app.apiUnlock().status == 202)
app.dailyCheckIn()
check('1.8 daily check-in: migrated', migrated(app) && keptState(app))
check('1.8: lock control paused, switch kept, token unchanged', app.state.lockControlOn == null && app.lockControlEnabled == true && app.state.accessToken == token)
app.params = [lockId: '102']
def paused102 = app.apiUnlock()
app.params = [lockId: '101']
check('1.8: no lock can be unlocked until Done, the newly managed one included', paused102.status == 404 && app.apiUnlock().status == 404 &&
    app.devices['102'].calls.isEmpty())
reg = registers(app)[-1]
check('1.8: re-registered on the version change, lock control off until Done', reg.version == '1.9.0' && reg.appAccessToken == token &&
    reg.lockManagement == true && reg.codeManagement == true && reg.lockControl == false && reg.deviceControl == false)
String p18 = page(app)
check('1.8: note says why lock control is paused and names the lock', p18.contains('Lock control is paused because these locks are now managed: Lock B. Click Done to turn it back on.') &&
    p18.contains('Full control above the ceiling') && p18.contains('Click Done to turn it on'))
app.appButtonHandler('btnDismissSettingsNote')
app.params = [lockId: '101']
check('1.8: dismissing the note does not resume lock control', app.state.lockControlOn == null && app.apiUnlock().status == 404)
app.updated()
app.params = [lockId: '102']
check('1.8: Done resumes lock control with a new token, the newly managed lock included', app.state.lockControlOn == true && app.state.accessToken != token &&
    app.apiUnlock().status == 202 && registers(app)[-1].lockControl == true && registers(app)[-1].appAccessToken == app.state.accessToken)

// Lock control on, but the note is still there before Done (state held from an older run): still refused
app = hub18()
app.dailyCheckIn()
app.state.lockControlOn = true
app.params = [lockId: '101']
check('1.8: lock control refused while the pause note is up', app.apiUnlock().status == 404)

// 1.8 with no managed locks ticked: all of them become managed and are listed; lock control paused
app = hub18()
app.managedLocks = null
app.fullControlLocks = null
page(app)
check('1.8 nothing ticked: every lock managed, all listed, lock control paused, no full control', ['101', '102', '103'].every { app.findCodeLock(it) != null } &&
    app.paragraphs.any { it.contains('Lock control is paused because these locks are now managed: Lock A, Lock B, Lock C.') } && app.state.lockControlOn == null &&
    app.fullControlEnabled == null && app.fullControlAllowedIds() == [])
check('1.8 nothing ticked: done', app.state.settingsMigration == [done: true] && !app.updatedSettings.any { it.name == 'locks' })

// 1.8 nothing ticked, lock control off: newly managed listed, nothing paused
app = hub18()
app.managedLocks = null
app.fullControlLocks = null
app.lockControlEnabled = false
app.state.remove('lockControlOn')
page(app)
check('1.8 nothing ticked, lock control off: listed as newly managed', app.paragraphs.any { it.contains('Newly managed: Lock A, Lock B, Lock C.') } &&
    !app.state.settingsNote.lockControlPaused)

// 1.8 with every lock ticked: lock control keeps working with no Done and the same token
app = hub18()
app.managedLocks = ['101', '102', '103']
token = app.state.accessToken
app.dailyCheckIn()
app.params = [lockId: '102']
check('1.8 all ticked: lock control still on, same token, no Done', app.state.lockControlOn == true && app.state.accessToken == token && app.apiUnlock().status == 202)
reg = registers(app)[-1]
check('1.8 all ticked: registered with lock control on and the same token', reg.lockControl == true && reg.appAccessToken == token)
check('1.8 all ticked: note is only about full control', !page(app).contains('Newly managed') && !app.paragraphs.any { it.contains('paused') } &&
    app.paragraphs.any { it.contains('Full control above the ceiling') })

// 1.8 with every lock ticked: no note
app = hub18()
app.managedLocks = ['101', '102', '103']
app.fullControlLocks = null
page(app)
check('1.8 all ticked: no note', app.state.settingsNote == null && !app.paragraphs.any { it.contains('Updated settings') })

// 1.8: control ticked while code management was off never took effect: off after the migration
app = hub18()
app.lockManagementEnabled = false
app.state.remove('lockMgmtLastEnabled')
app.state.remove('lockControlOn')
app.deviceControlEnabled = true
app.fullControlLocks = null
app.state.remove('lockConfig'); app.state.remove('fingerprintKey'); app.approvedAdoptedSlots = null
page(app)
check('1.8 control without code management: switches turned off, no note', app.lockControlEnabled == false && app.deviceControlEnabled == false &&
    app.state.settingsNote == null && app.state.settingsMigration == [done: true])
app.updated()
check('1.8 control without code management: Done turns nothing on', app.state.lockControlOn == null && app.state.deviceControlOn == null && app.endpointOn() == false)

// 1.8: control ticked with code management on but no Done yet: still ticked, still waits for Done
app = hub18()
app.state.remove('lockControlOn')
app.deviceControlEnabled = true
page(app)
check('1.8 ticked, no Done yet: kept ticked, not on', app.lockControlEnabled == true && app.deviceControlEnabled == true && app.state.lockControlOn == null)
token = app.state.accessToken
app.updated()
check('1.8 ticked: next Done turns them on with a new token', app.state.lockControlOn == true && app.state.deviceControlOn == true && app.state.accessToken != token)

// 1.8 device control on carries over too
app = hub18()
app.deviceControlEnabled = true
app.state.deviceControlOn = true
token = app.state.accessToken
app.dailyCheckIn()
check('1.8 device control: still on, same token', app.state.deviceControlOn == true && app.deviceControlActive() && app.state.accessToken == token)

// After the migration, code management off: control carries on, code endpoints 404
app = hub18()
page(app)
app.updated()
app.lockManagementEnabled = false
page(app)
app.params = [lockId: '102']
check('code management off after migration: lock control still works', app.apiUnlock().status == 202)
app.params = [:]
check('code management off after migration: code endpoints 404', app.apiListLocks().status == 404 && app.apiGetLockConfig().status == 404)
check('code management off after migration: its state wiped, full control off', app.state.lockConfig == null && app.state.fingerprintKey == null &&
    app.fullControlEnabled == null && app.state.pendingCodeChanges == null)
reg = registers(app)[-1]
check('code management off after migration: registered with lock control only', reg.lockManagement == true && reg.codeManagement == false && reg.lockControl == true &&
    reg.appAccessToken == app.state.accessToken)

// 1.8 with everything off: nothing on, nothing registered
app = hub18()
app.lockManagementEnabled = false
app.lockControlEnabled = false
app.state.remove('lockMgmtLastEnabled'); app.state.remove('lockControlOn'); app.state.lockMgmtRegistered = false
app.managedLocks = null; app.fullControlLocks = null
app.state.remove('lockConfig'); app.state.remove('fingerprintKey'); app.approvedAdoptedSlots = null; app.state.remove('pendingCodeChanges')
app.posts.clear()
page(app)
app.updated()
check('everything off: migrated, nothing on, no note, nothing registered', app.state.settingsMigration == [done: true] && app.endpointOn() == false &&
    app.state.settingsNote == null && registers(app).isEmpty())

// --- A Done that migrates, with settings written in the run not readable until it ends ---
[['update'], ['update', 'remove']].each { kinds ->
    def hub = hub17()
    hub.lockControlEnabled = true
    hub.app.deferred = kinds as Set
    hub.updated()
    check("Done that migrates (${kinds} held): held change, approvals and full control kept in the run", keptState(hub) && hub.fullControlAllowedIds() == ['101', '102', '103'])
    check("Done that migrates (${kinds} held): nothing turned on in it, note kept", hub.state.lockControlOn == null && hub.state.settingsNote?.locks == ['102'])
    hub.app.flush()
    hub.app.deferred = [] as Set
    hub.updated()
    check("next Done (${kinds}): migrated, state kept", migrated(hub) && keptState(hub) && hub.findCodeLock('103') != null)
    check("next Done (${kinds}): note cleared now", hub.state.settingsNote == null)
    check("next Done (${kinds}): lock control on now", hub.state.lockControlOn == true)
}

// --- A Done while the migration hasn't finished (its writes not read back yet) turns nothing on ---
[['update'], ['update', 'remove']].each { kinds ->
    def hub = hub17()
    hub.lockControlEnabled = true
    hub.deviceControlEnabled = true
    hub.app.deferred = kinds as Set
    page(hub)
    hub.updated()
    check("Done before the migration finished (${kinds} held): nothing turned on", hub.state.lockControlOn == null && hub.state.deviceControlOn == null &&
        hub.state.settingsMigration?.done != true && keptState(hub))
    hub.app.flush()
    hub.app.deferred = [] as Set
    hub.updated()
    check("Done after it finished (${kinds}): turned on", migrated(hub) && hub.state.lockControlOn == true && hub.state.deviceControlOn == true)
}

// --- Interrupted part-way: the next run finishes it, note and log once ---
(1..4).each { n ->
    def hub = hub17()
    hub.app.failAt = n
    try { page(hub) } catch (IllegalStateException e) { /* the run stopped */ }
    page(hub)
    page(hub)
    check("interrupted at write ${n}: finished on the next runs", migrated(hub) && keptState(hub) && hub.state.settingsNote?.locks == ['102'] &&
        hub.logged.count { it.contains('settings moved to') } == 1)
}

// --- Keypads (code-only devices) keep their list, shown while it has any ---
app = hub17()
def keypad = new FakeLock(id: '104', displayName: 'Keypad', label: 'Keypad', caps: ['LockCodes'], attrs: [lockCodes: JsonOutput.toJson(['2': [name: 'SBA: Owner', code: codes[0]]])])
app.devices['104'] = keypad
app.codeLocks = [app.devices['101'], keypad, app.devices['103']]
page(app)
check('keypad: locks moved, keypad kept in its list', ids(app.locks) == ['101', '102', '103'] && ids(app.codeLocks) == ['104'] &&
    app.updatedSettings.find { it.name == 'codeLocks' } == [name: 'codeLocks', type: 'capability.lockCodes', value: ['104']] && app.state.settingsMigration == [done: true])
check('keypad: shown under Devices', app.inputs.codeLocks?.type == 'capability.lockCodes')
check('keypad: managed for codes, never for lock control', app.findCodeLock('104') != null && app.findControlLock('104') == null)
app.lockControlEnabled = true
app.updated()
app.params = [lockId: '104']
check('keypad: unlock 404', app.state.lockControlOn == true && app.apiUnlock().status == 404 && keypad.calls.isEmpty())
app.codeLocks = null
page(app)
check('keypad cleared: list hidden, no longer managed', !app.inputs.containsKey('codeLocks') && app.findCodeLock('104') == null)

app = hub17()
def pad2 = new FakeLock(id: '105', displayName: 'Pad', label: 'Pad', caps: ['LockCodes'])
app.devices['105'] = pad2
app.codeLocks = [pad2]
app.fullControlLocks = null
page(app)
check('only keypads: kept, nothing moved, done', ids(app.codeLocks) == ['105'] && ids(app.locks) == ['101', '102'] && app.state.settingsMigration == [done: true] &&
    !app.updatedSettings.any { it.name in ['locks', 'codeLocks'] })

// An empty codeLocks setting is only removed
app = hub17()
app.codeLocks = []
app.fullControlLocks = null
page(app)
check('empty codeLocks: removed', app.codeLocks == null && app.removedSettings.contains('codeLocks') && app.state.settingsMigration == [done: true])

// --- A new install: nothing to move ---
app = shell.parse(source)
app.run()
apps << app
app.lockManagementEnabled = false
page(app)
check('new install: done, nothing written, no note, nothing logged', app.state.settingsMigration == [done: true] && settingsWritten(app) == 0 &&
    app.state.settingsNote == null && !app.logged.any { it.contains('settings moved to') })

String everything = apps.collect { (it.logged + it.posts.collect { p -> p.params.body } + it.paragraphs).join('\n') }.join('\n')
check('no code in any log, post or page', !codes.any { everything.contains(it) })

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
