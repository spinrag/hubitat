// Lock and device control (#80) with a stubbed Hubitat sandbox: lock, unlock,
// refresh and state on managed locks; listing and commanding devices selected
// for control, with a fixed command list per capability and checked args; the
// toggles, the selection gating, the limits, the audit events and that no code
// reaches a response, log or event. Fake values only.
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
    Boolean lockControlEnabled = false, deviceControlEnabled = false
    Boolean lockManagementEnabled = true
    Boolean includeDoorCodes = false
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    def fullControlLocks = null
    List removedSettings = []
    List logged = []
    List posts = []
    List subscriptions = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, error: { logged << it.toString() }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177'], timeZone: TimeZone.getTimeZone('UTC'), temperatureScale: 'F']
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
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite != false) scheduled.removeAll { it.handler == handler }
        scheduled << [secs: secs, handler: handler, data: opts?.data]
    }
    void schedule(String cron, String handler) { scheduled << [cron: cron, handler: handler] }
    void unschedule(String handler = null) { if (handler) scheduled.removeAll { it.handler == handler } else scheduled.clear() }
    void subscribe(def target, String attribute, String handler) {
        (target instanceof Collection ? target : [target]).each { subscriptions << [id: it.hasProperty('id') ? it.id : null, attribute: attribute] }
    }
    void unsubscribe() { subscriptions.clear() }
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
    void updateSetting(String n, Map v) { owner.setProperty(n, v.value) }
}

// A lock or any other device: records every method called on it.
class FakeDevice {
    String id; String displayName; String name; String label
    List caps = []
    List commands = []
    Map attrs = [:]
    List calls = []
    List reads = []
    Date lastActivity = null
    Boolean throwOnCommand = false
    Boolean hasCapability(String c) { caps.contains(c) }
    Boolean hasCommand(String c) { commands.contains(c) }
    Date getLastActivity() { lastActivity }
    def currentValue(String a, Boolean skipCache = false) { reads << [a, skipCache]; attrs[a] }
    def invokeMethod(String m, def args) {
        calls << ([m] + (args as List))
        if (throwOnCommand) throw new IllegalStateException("driver failed for ${m} with 24681357")
        null
    }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
def body = { Map r -> r.data ? new JsonSlurper().parseText(r.data) : null }
List responses = []
def call = { String handler, Map p = [:], def json = null ->
    app.params = p
    app.request = [JSON: json]
    def r = app."${handler}"()
    responses << r
    r
}
def cmd = { String id, def json -> call('apiDeviceCommand', [deviceId: id], json) }
def resetLimits = { ['lockCommandTimes', 'lockCommandLockTimes', 'deviceCommandTimes', 'deviceCommandDeviceTimes'].each { app.state.remove(it) } }
def events = { app.posts.findAll { it.cb == 'postCallback' }.collect { new JsonSlurper().parseText(it.params.body) }.findAll { it.name == 'device_command' } }
def lastEvent = { events()[-1] }
def evData = { Map e -> new JsonSlurper().parseText(e.data) }
def registers = { app.posts.findAll { it.cb == 'registerCallback' }.collect { new JsonSlurper().parseText(it.params.body) } }
String UUID1 = '0f8fad5b-d9cb-469f-a165-70867728950e'

// Fake codes on the locks: none may leave the app through these endpoints.
List codes = ['86420001', '86420002', '97530003']
def lockCodesJson = JsonOutput.toJson(['1': [name: 'SBA: Owner', code: codes[0]], '12': [name: 'SBM: Cleaner', code: codes[1]], '30': [name: 'Guest Smith', code: codes[2]]])
Date seen = new Date(1_799_999_000_000L)
def lockA = new FakeDevice(id: '10', displayName: 'Front', label: 'Front', caps: ['Lock', 'LockCodes'], commands: ['lock', 'unlock', 'refresh'],
    attrs: [lock: 'locked', battery: '87', lockCodes: lockCodesJson, lastCodeName: 'Guest Smith'], lastActivity: seen)
def lockB = new FakeDevice(id: '11', displayName: 'Shed', label: 'Shed', caps: ['Lock'], commands: ['lock', 'unlock'], attrs: [lock: 'unlocked'])
def lockC = new FakeDevice(id: '12', displayName: 'Side', label: 'Side', caps: ['Lock'], commands: ['lock', 'unlock'], attrs: [lock: 'jammed'])
def lockD = new FakeDevice(id: '13', displayName: 'Back', label: 'Back', caps: ['Lock'], commands: ['lock', 'unlock'], attrs: [lock: 'locked'])
def lockE = new FakeDevice(id: '14', displayName: 'Gym', label: 'Gym', caps: ['Lock'], commands: ['lock', 'unlock'], attrs: [lock: 'locked'])
def sw = new FakeDevice(id: '20', displayName: 'Porch light', label: 'Porch light', caps: ['Switch'], attrs: [switch: 'off'])
def dim = new FakeDevice(id: '21', displayName: 'Hall dimmer', label: 'Hall dimmer', caps: ['Switch', 'SwitchLevel'], attrs: [switch: 'on', level: 40])
def garage = new FakeDevice(id: '22', displayName: 'Garage', label: 'Garage', caps: ['GarageDoorControl', 'ContactSensor'], attrs: [door: 'closed'])
def gate = new FakeDevice(id: '23', displayName: 'Gate', label: 'Gate', caps: ['DoorControl'], attrs: [door: 'open'])
def thermo = new FakeDevice(id: '24', displayName: 'Thermostat', label: 'Thermostat', caps: ['Thermostat', 'Switch'],
    attrs: [thermostatMode: 'heat', heatingSetpoint: 68, coolingSetpoint: 76, temperature: 70.5, thermostatOperatingState: 'idle'])
def valve = new FakeDevice(id: '25', displayName: 'Main valve', label: 'Main valve', caps: ['Valve'], attrs: [valve: 'open'])
def sensor = new FakeDevice(id: '26', displayName: 'Door sensor', label: 'Door sensor', caps: ['ContactSensor'], attrs: [contact: 'closed'])
app.locks = [lockA, lockB, lockC, lockD, lockE]
app.managedLocks = ['10', '12', '13', '14']
app.contactSensors = [sensor]
app.controlSwitches = [sw, dim]
app.controlGarageDoors = [garage]
app.controlDoors = [gate]
app.controlThermostats = [thermo]
app.controlValves = [valve]
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.state.lockMgmtLastEnabled = true
// SignalBricks accepted an earlier registration
app.state.lockMgmtRegistered = true
app.createAccessToken()

// --- Off by default ---
check('advertises lock_control and device_control', app.CAPABILITIES.containsAll(['lock_control', 'device_control']))
List gated = [['apiLock', [lockId: '10']], ['apiUnlock', [lockId: '10']], ['apiLockRefresh', [lockId: '10']], ['apiLockState', [lockId: '10']],
              ['apiListDevices', [:]], ['apiDeviceCommand', [deviceId: '20']]]
check('toggles off: every control endpoint 404', gated.every { h, p -> call(h, p, [command: 'on']).status == 404 })
check('toggles off: nothing sent', lockA.calls.isEmpty() && sw.calls.isEmpty())

// Toggle on: not until Done, which replaces the token first
app.lockControlEnabled = true
app.paragraphs.clear(); app.mainPage()
check('lock control on, before Done: 404', call('apiUnlock', [lockId: '10']).status == 404 && lockA.calls.isEmpty())
check('page: says to click Done', app.paragraphs.any { it.contains('Click Done') })
check('page: one Devices section with every selection', ['locks', 'managedLocks', 'contactSensors', 'temperatureSensors', 'humiditySensors', 'controlSwitches',
    'controlGarageDoors', 'controlDoors', 'controlThermostats', 'controlValves'].every { app.inputs.containsKey(it) } && !app.inputs.containsKey('codeLocks'))
check('page: managed locks are picked from the Locks list', app.inputs.managedLocks.type == 'enum' && app.inputs.managedLocks.options.keySet() == ['10', '11', '12', '13', '14'] as Set)
String tokenBefore = app.state.accessToken
app.updated()
check('Done: access token replaced', app.state.accessToken != tokenBefore && app.state.lockControlOn == true && app.state.deviceControlOn == null)
Map reg = registers()[-1]
check('Done: registers lockControl on, deviceControl off, with the new token', reg.lockControl == true && reg.deviceControl == false && reg.appAccessToken == app.state.accessToken &&
    reg.capabilities.containsAll(['lock_control', 'device_control']) && reg.version == '1.8.0')
check('token replaced is logged without the token', app.logged.any { it == 'SignalBricks Telemetry: lock control turned on; access token rotated' } && !app.logged.any { it.contains('fake-app-token') })
String tokenOn = app.state.accessToken
app.updated()
check('Done again: token kept', app.state.accessToken == tokenOn)
check('device control still off: /devices 404', call('apiListDevices').status == 404)

// --- Locks ---
def r = call('apiUnlock', [lockId: '10'])
check('unlock: 202 {lockId, requested, requestId}', r.status == 202 && body(r).lockId == '10' && body(r).requested == true && body(r).requestId ==~ /[0-9a-f-]{36}/ && body(r).keySet() == ['lockId', 'requested', 'requestId'] as Set)
check('unlock: sent to the lock', lockA.calls == [['unlock']])
check('unlock: logged with the lock name and command only', app.logged[-1] == 'SignalBricks Telemetry: unlock requested on Front')
Map ev = lastEvent()
check('unlock: device_command event for the audit', ev.name == 'device_command' && ev.value == 'unlock' && ev.deviceId == '10' && ev.displayName == 'Front' &&
    evData(ev) == [deviceId: '10', target: 'lock', command: 'unlock', result: 'requested', requestId: body(r).requestId])
r = call('apiLock', [lockId: '10'], [requestId: UUID1.toUpperCase()])
check('lock: 202, requestId from the body echoed', r.status == 202 && body(r).requestId == UUID1 && lockA.calls[-1] == ['lock'])
r = call('apiLock', [lockId: '10', requestId: UUID1], null)
check('lock: requestId from the query, empty body', r.status == 202 && body(r).requestId == UUID1)
r = call('apiLock', [lockId: '10'], [requestId: 'not-a-uuid'])
check('lock: invalid requestId -> 400', r.status == 400 && body(r).error == 'invalid_request_id')
check('two commands with one requestId: distinct event ids', events().findAll { evData(it).requestId == UUID1 }*.id.unique().size() == 2)
r = call('apiLockRefresh', [lockId: '10'])
check('refresh: 202 and sent', r.status == 202 && lockA.calls[-1] == ['refresh'])
r = call('apiLockRefresh', [lockId: '12'])
check('refresh without driver support -> 409 not_supported', r.status == 409 && body(r).error == 'not_supported' && lockC.calls.isEmpty())
lockA.reads.clear()
r = call('apiLockState', [lockId: '10'])
check('state: {lockId, lock, battery, lastActivity}', r.status == 200 && body(r) == [lockId: '10', lock: 'locked', battery: 87, lastActivity: '2027-01-15T07:43:20.000Z'])
check('state: read from the device, not the cache', lockA.reads.any { it == ['lock', true] } && lockA.reads.any { it == ['battery', true] })
check('state: jammed lock without battery or activity', body(call('apiLockState', [lockId: '12'])) == [lockId: '12', lock: 'jammed'])
check('state: no code, code name or lockCodes', !lockA.reads.any { it[0] in ['lockCodes', 'lastCodeName'] })
['apiLock', 'apiUnlock', 'apiLockRefresh', 'apiLockState'].each { h ->
    def nm = call(h, [lockId: '11'])
    check("${h}: lock selected but not managed -> 404", nm.status == 404 && body(nm) == [error: 'lock_not_found'])
    check("${h}: unknown lock -> 404", call(h, [lockId: '999']).status == 404)
    check("${h}: a device selected for control is not a lock -> 404", call(h, [lockId: '20']).status == 404)
}
check('unmanaged lock: nothing sent', lockB.calls.isEmpty())
lockD.throwOnCommand = true
r = call('apiUnlock', [lockId: '13'])
check('driver throws -> 502 send_error', r.status == 502 && body(r).error == 'send_error')
check('driver throws: logged by class name only', app.logged[-1] == 'SignalBricks Telemetry: unlock on Back could not be sent (IllegalStateException)')
check('driver throws: audit event with result send_error', evData(lastEvent()).result == 'send_error' && lastEvent().deviceId == '13')
lockD.throwOnCommand = false

// Lock limits: per lock, then across the app
resetLimits()
List perLock = (1..6).collect { call('apiUnlock', [lockId: '10']).status }
r = call('apiUnlock', [lockId: '10'])
check('lock limit per lock -> 429', perLock.every { it == 202 } && r.status == 429 && body(r) == [error: 'rate_limited'])
check('lock limit: refresh counts too', call('apiLockRefresh', [lockId: '10']).status == 429)
int sentBefore = lockA.calls.size()
call('apiLock', [lockId: '10'])
check('lock limit: nothing sent once limited', lockA.calls.size() == sentBefore)
check('lock limit: another lock still answers', call('apiLock', [lockId: '12']).status == 202)
check('lock limit: state reads are not limited', call('apiLockState', [lockId: '10']).status == 200)
app.clock += 61000
check('lock limit: a minute later the lock answers again', call('apiLock', [lockId: '10']).status == 202)
app.clock += 61000
resetLimits()
List across = (1..20).collect { i -> call('apiLock', [lockId: ['10', '12', '13', '14'][i % 4]]).status }
r = call('apiLock', [lockId: '14'])
check('lock limit across the app -> 429', across.every { it == 202 } && r.status == 429)
app.clock += 61000
resetLimits()

// --- Devices ---
app.deviceControlEnabled = true
check('device control on, before Done: 404', call('apiListDevices').status == 404)
tokenBefore = app.state.accessToken
app.updated()
check('device control Done: token replaced, registered on', app.state.accessToken != tokenBefore && registers()[-1].deviceControl == true && registers()[-1].lockControl == true)
r = call('apiListDevices')
Map devs = body(r).devices.collectEntries { [(it.id): it] }
check('list: only devices selected for control', r.status == 200 && devs.keySet() == ['20', '21', '22', '23', '24', '25'] as Set)
check('list: switch', devs['20'] == [id: '20', label: 'Porch light', capabilities: ['switch'], commands: ['on', 'off'], state: [switch: 'off']])
check('list: dimmer', devs['21'].capabilities == ['switch', 'level'] && devs['21'].commands == ['on', 'off', 'setLevel'] && devs['21'].state == [switch: 'on', level: 40])
check('list: garage door', devs['22'].capabilities == ['garageDoor'] && devs['22'].commands == ['open', 'close'] && devs['22'].state == [door: 'closed'])
check('list: gate', devs['23'].capabilities == ['door'] && devs['23'].state == [door: 'open'])
check('list: thermostat, opted in as a thermostat only', devs['24'].capabilities == ['thermostat'] &&
    devs['24'].commands == ['setHeatingSetpoint', 'setCoolingSetpoint', 'setThermostatMode'] &&
    devs['24'].state == [thermostatMode: 'heat', heatingSetpoint: 68, coolingSetpoint: 76, thermostatOperatingState: 'idle', temperature: 70.5])
check('list: valve', devs['25'].capabilities == ['valve'] && devs['25'].commands == ['open', 'close'] && devs['25'].state == [valve: 'open'])

def okCmd = { String id, Map json, def device, List expectCall ->
    def res = cmd(id, json)
    res.status == 202 && body(res).deviceId == id && body(res).command == json.command && body(res).requested == true && device.calls[-1] == expectCall
}
check('switch on', okCmd('20', [command: 'on'], sw, ['on']))
check('switch off with empty args', okCmd('20', [command: 'off', args: []], sw, ['off']))
check('dimmer setLevel 50', okCmd('21', [command: 'setLevel', args: [50]], dim, ['setLevel', 50]))
check('dimmer setLevel "75" -> 75', okCmd('21', [command: 'setLevel', args: ['75']], dim, ['setLevel', 75]))
check('dimmer setLevel 0 and 100', okCmd('21', [command: 'setLevel', args: [0]], dim, ['setLevel', 0]) && okCmd('21', [command: 'setLevel', args: [100]], dim, ['setLevel', 100]))
check('garage open, close', okCmd('22', [command: 'open'], garage, ['open']) && okCmd('22', [command: 'close'], garage, ['close']))
check('gate open', okCmd('23', [command: 'open'], gate, ['open']))
check('valve close', okCmd('25', [command: 'close'], valve, ['close']))
check('heating setpoint 68', okCmd('24', [command: 'setHeatingSetpoint', args: [68]], thermo, ['setHeatingSetpoint', 68]))
check('cooling setpoint 75.5', okCmd('24', [command: 'setCoolingSetpoint', args: [75.5]], thermo, ['setCoolingSetpoint', 75.5]))
check('mode heat', okCmd('24', [command: 'setThermostatMode', args: ['heat']], thermo, ['setThermostatMode', 'heat']))
check('device command logged with label and command only', app.logged[-1] == 'SignalBricks Telemetry: setThermostatMode requested on Thermostat')
check('device command logs never carry args', !app.logged.any { it.contains('requested on') && (it.contains('75.5') || it.contains(' 68')) })
ev = lastEvent()
check('device command audit event', ev.value == 'setThermostatMode' && ev.deviceId == '24' && evData(ev).target == 'device' && evData(ev).args == ['heat'] && evData(ev).result == 'requested')
resetLimits()

// Allowlist
[['20', 'setLevel', 'switch without level'], ['24', 'on', 'thermostat not opted in as a switch'], ['20', 'open', 'switch'], ['22', 'on', 'garage'],
 ['25', 'setLevel', 'valve'], ['21', 'refresh', 'refresh'], ['20', 'deleteCode', 'deleteCode'], ['20', 'setCode', 'setCode'], ['20', 'unlock', 'unlock'],
 ['20', 'getClass', 'getClass'], ['20', 'toString', 'toString'], ['20', 'ON', 'case'], ['20', ' on', 'space'], ['20', '', 'empty']].each { id, c, why ->
    def bad = cmd(id, [command: c])
    check("not allowlisted (${why}: ${c}) -> 400", bad.status == 400 && body(bad).error == 'command_not_allowed')
}
check('command not a string -> 400', body(cmd('20', [command: 1])).error == 'command_not_allowed' && body(cmd('20', [command: ['on']])).error == 'command_not_allowed' &&
    body(cmd('20', [:])).error == 'command_not_allowed')
check('body not an object -> 400', body(cmd('20', 'on')).error == 'invalid_body')
check('invalid requestId -> 400', body(cmd('20', [command: 'on', requestId: 'x'])).error == 'invalid_request_id')
check('nothing sent for refused commands', sw.calls.every { it[0] in ['on', 'off'] } && thermo.calls.every { it[0].startsWith('set') } && dim.calls.every { it[0] in ['setLevel'] })

// Args
[['21', 'setLevel', [101]], ['21', 'setLevel', [-1]], ['21', 'setLevel', [50.5]], ['21', 'setLevel', ['abc']], ['21', 'setLevel', [true]],
 ['21', 'setLevel', [null]], ['21', 'setLevel', []], ['21', 'setLevel', null], ['21', 'setLevel', [50, 10]], ['21', 'setLevel', 50], ['21', 'setLevel', [[50]]],
 ['20', 'on', [1]], ['22', 'open', ['now']],
 ['24', 'setHeatingSetpoint', [100]], ['24', 'setHeatingSetpoint', [30]], ['24', 'setHeatingSetpoint', [68.25]], ['24', 'setHeatingSetpoint', ['warm']],
 ['24', 'setHeatingSetpoint', [true]], ['24', 'setCoolingSetpoint', []], ['24', 'setCoolingSetpoint', ['NaN']],
 ['24', 'setThermostatMode', ['turbo']], ['24', 'setThermostatMode', ['HEAT']], ['24', 'setThermostatMode', [1]], ['24', 'setThermostatMode', ['heat', 'cool']]].each { id, c, a ->
    def bad = cmd(id, [command: c, args: a])
    check("bad args ${c} ${a} -> 400", bad.status == 400 && body(bad).error == 'invalid_args')
}
check('bad args: nothing sent', !dim.calls.any { it[1] in [101, -1] } && !thermo.calls.any { it[1] in [100, 30, 'turbo'] })
app.location.temperatureScale = 'C'
check('Celsius: 21.5 accepted', okCmd('24', [command: 'setHeatingSetpoint', args: [21.5]], thermo, ['setHeatingSetpoint', 21.5]))
check('Celsius: 68 refused', cmd('24', [command: 'setHeatingSetpoint', args: [68]]).status == 400)
app.location.temperatureScale = 'F'
thermo.attrs.supportedThermostatModes = '["heat","off"]'
check('mode the thermostat does not support -> 400', body(cmd('24', [command: 'setThermostatMode', args: ['cool']])).error == 'invalid_args')
check('mode it supports -> 202', cmd('24', [command: 'setThermostatMode', args: ['off']]).status == 202)
thermo.attrs.supportedThermostatModes = '[heat, auto]'
check('supported modes in the unquoted form', cmd('24', [command: 'setThermostatMode', args: ['cool']]).status == 400 && cmd('24', [command: 'setThermostatMode', args: ['auto']]).status == 202)
thermo.attrs.remove('supportedThermostatModes')
resetLimits()

// Selection gating
check('device not selected for control -> 404', body(cmd('26', [command: 'on'])) == [error: 'device_not_found'])
check('lock via /devices -> 404', cmd('10', [command: 'on']).status == 404 && cmd('10', [command: 'unlock']).status == 404)
check('unknown device -> 404', cmd('999', [command: 'on']).status == 404)
app.controlSwitches = [dim]
check('switch removed from the selection -> 404', cmd('20', [command: 'on']).status == 404)
app.controlSwitches = [sw, dim]
check('not selected: nothing sent', sensor.calls.isEmpty())

dim.throwOnCommand = true
r = cmd('21', [command: 'setLevel', args: [10]])
check('device driver throws -> 502, class name only in the log', r.status == 502 && app.logged[-1] == 'SignalBricks Telemetry: setLevel on Hall dimmer could not be sent (IllegalStateException)')
dim.throwOnCommand = false
resetLimits()

// Device limits
List perDevice = (1..10).collect { cmd('20', [command: 'on']).status }
r = cmd('20', [command: 'on'])
check('device limit per device -> 429', perDevice.every { it == 202 } && r.status == 429 && body(r) == [error: 'rate_limited'])
check('device limit: another device still answers', cmd('21', [command: 'off']).status == 202)
check('device limit: locks have their own limit', call('apiLock', [lockId: '10']).status == 202)
app.clock += 61000
resetLimits()
List devAcross = (1..30).collect { i -> i % 3 == 0 ? cmd('20', [command: 'on']).status : (i % 3 == 1 ? cmd('23', [command: 'close']).status : cmd('25', [command: 'open']).status) }
r = cmd('22', [command: 'close'])
check('device limit across the app -> 429', devAcross.every { it == 202 } && r.status == 429)
app.clock += 61000
resetLimits()

// Telemetry for controlled devices
app.subscriptions.clear()
app.subscribeToEvents()
Map subs = app.subscriptions.groupBy { it.id }.collectEntries { k, v -> [(k): v*.attribute as Set] }
check('telemetry: controlled devices report what their commands change', subs['20'] == ['switch', 'battery'] as Set &&
    subs['21'].containsAll(['switch', 'level']) && subs['22'].contains('door') && subs['24'].containsAll(['thermostatMode', 'heatingSetpoint', 'coolingSetpoint']) && subs['25'].contains('valve'))
check('telemetry: managed and unmanaged locks report lock state, never lockCodes', subs['10'].contains('lock') && subs['11'].contains('lock') && !app.subscriptions.any { it.attribute == 'lockCodes' })
check('telemetry: battery once per device', app.subscriptions.findAll { it.attribute == 'battery' }*.id.size() == app.subscriptions.findAll { it.attribute == 'battery' }*.id.unique().size())

// No code anywhere
String everything = (app.posts.collect { it.params.body } + app.logged + responses.collect { it.data?.toString() }).join('\n')
check('no code in any response, log or event', !codes.any { everything.contains(it) } && !everything.contains('24681357'))
check('no code name in lock control responses or events', !(responses.collect { it.data?.toString() } + events().collect { JsonOutput.toJson(it) }).any { it?.contains('Guest Smith') })
check('no token in any log', !app.logged.any { it.contains('fake-app-token') || it.contains('ingest-fake') })

// Off again: at once from the page, before Done
app.deviceControlEnabled = false
app.paragraphs.clear(); app.mainPage()
check('device control off: 404 at once', call('apiListDevices').status == 404 && cmd('20', [command: 'on']).status == 404 && app.state.deviceControlOn == null)
check('lock control still on', call('apiLock', [lockId: '10']).status == 202)
app.deviceControlEnabled = true
app.updated()
app.lockManagementEnabled = false
app.paragraphs.clear(); app.mainPage()
check('lock code management off: lock and device control 404', call('apiUnlock', [lockId: '10']).status == 404 && call('apiListDevices').status == 404)
check('lock code management off: control state and limits cleared', app.state.lockControlOn == null && app.state.deviceControlOn == null && app.state.lockCommandLockTimes == null)
check('lock code management off: registered off', registers()[-1].lockManagement == false && registers()[-1].lockControl == false && registers()[-1].appAccessToken == null)
check('page: control needs lock code management', app.paragraphs.any { it.contains('Turn on lock code management') })
app.updated()
check('Done with lock code management off: control stays off', app.state.lockControlOn == null && call('apiUnlock', [lockId: '10']).status == 404)
app.lockManagementEnabled = true
app.paragraphs.clear(); app.mainPage()
check('lock code management back on: control waits for Done', call('apiUnlock', [lockId: '10']).status == 404)
app.updated()
check('after Done: control back on', call('apiUnlock', [lockId: '10']).status == 202 && call('apiListDevices').status == 200)
app.state.remove('sbToken')
check('disconnected: 404', call('apiUnlock', [lockId: '10']).status == 404 && call('apiListDevices').status == 404)

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
