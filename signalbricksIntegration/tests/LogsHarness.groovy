// No door code, PIN or access token in any log line (#36) for SignalBricks
// Integration with a stubbed Hubitat sandbox: drives the sync endpoints
// (PUT/PATCH /sync, /sync/<id>, /sync/<id>/<lock>, their deletes), the device
// command and detail endpoints, /register, reconcile and its set/verify/delete
// jobs, the duplicate-code check and the lock event handlers with fake codes,
// and checks every captured log level. Guest names and booking ids may be logged.
// Fake values only; nothing is sent to a hub.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import org.codehaus.groovy.control.CompilerConfiguration

// atomicState is stored as JSON on the hub: every read returns a fresh copy
class JsonStateMap extends LinkedHashMap {
    Object put(Object k, Object v) { super.put(k, v == null ? null : JsonOutput.toJson(v)) }
    Object get(Object k) { def s = super.get(k); s == null ? null : new JsonSlurper().parseText(s) }
}

abstract class HubitatStub extends Script {
    Map state = [:]
    Map atomicState = new JsonStateMap()
    Map settings = [:]
    def locks = []
    def minCodePosition = 50
    def idAtEnd = false
    def codeNamePrefix = 'GT:'
    def request = [:]
    def params = [:]
    long clock = 1_800_000_000_000L
    // Every level, with the level name, as the hub would show it
    List logged = []
    def log = [info: { logged << "info ${it}".toString() }, warn: { logged << "warn ${it}".toString() }, debug: { logged << "debug ${it}".toString() },
               trace: { logged << "trace ${it}".toString() }, error: { logged << "error ${it}".toString() }]
    List scheduled = []
    List posts = []
    def app = [id: 1]
    def location = [name: 'Test', hub: [name: 'Hub']]
    void definition(Map m) {}
    void mappings(Closure c) {}
    void preferences(Closure c) {}
    long now() { clock }
    String getHubUID() { 'hub-fake' }
    String getFullApiServerUrl() { 'https://cloud.test/api/hub/apps/1' }
    String getFullLocalApiServerUrl() { 'http://hub.local/apps/api/1' }
    void runIn(Number secs, String handler, Map opts = [:]) { scheduled << [secs: secs, handler: handler, data: opts?.data] }
    void runOnce(Date d, String handler, Map opts = [:]) { scheduled << [at: d, handler: handler] }
    void schedule(String cron, String handler) {}
    void unschedule() { scheduled.clear() }
    void unschedule(String handler) { scheduled.removeAll { it.handler == handler } }
    void subscribe(Object... args) {}
    void unsubscribe() {}
    void httpPostJson(Map p, Closure c) { posts << p; c([data: [ok: true]]) }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { 'fake-access-token-0001' }
    Date toDateTime(String s) { new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(s) }
    def propertyMissing(String name) {
        if (name.endsWith('Handler')) return name
        throw new MissingPropertyException(name, getClass())
    }
}

class FakeLock {
    String id; String name; String label; String typeName = 'Fake Lock'; String displayName
    Map attrs = [:]
    List calls = []
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    def getSupportedAttributes() { [[name: 'lockCodes', dataType: 'JSON_OBJECT', values: null], [name: 'maxCodes', dataType: 'NUMBER', values: null]] }
    def getSupportedCommands() { ['setCode', 'deleteCode', 'lock'].collect { [name: it, arguments: [], parameters: []] } }
    def getCapabilities() { [[name: 'LockCodes']] }
    void setCode(def pos, def code, def name) { calls << [setCode: pos] }
    void deleteCode(def pos) { calls << [deleteCode: pos] }
    void lock() { calls << 'lock' }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }

// Fake codes and token, distinct enough not to appear by accident
String TOKEN = 'fake-access-token-0001'
String LEGACY_TOKEN = 'fake-legacy-token-0002'
List CODES = ['80001188', '80002277', '80003366', '80004455', '80005544', '80006633', '80007722', '80008811']
List secrets = CODES + [TOKEN, LEGACY_TOKEN]

app.state.accessToken = TOKEN
app.settings = [locks: ['1'] as String[]]
// Slot 10 is below the minimum (another app's code), 51 an orphan of this app, 52 booking 88 with an old code,
// 54 booking 66's code (so booking 55 with the same code is a duplicate)
def front = new FakeLock(id: '1', name: 'A', label: 'Front', displayName: 'Front', attrs: [maxCodes: 60, lockCodes: JsonOutput.toJson([
    '10': [name: 'SBA: Owner', code: CODES[5]],
    '51': [name: 'GT:ORB99-Old', code: CODES[6]],
    '52': [name: 'GT:ORB88-Ann', code: CODES[7]],
    '54': [name: 'GT:ORB66-Dee', code: CODES[2]],
])])
app.locks = [front]
long h = 3600_000L
long t = new Date().time

// Logs from one step; each step is checked on its own so a failure names it
def step = { String label, Closure c ->
    app.logged.clear()
    String err = null
    try { c() } catch (e) { err = e.toString() }
    String logs = app.logged.join('\n')
    List leaked = secrets.findAll { logs.contains(it) }
    check("${label}: no code or token in any log level${err ? ' (threw ' + err + ')' : ''}${leaked ? ' (found ' + leaked.size() + ')' : ''}".toString(), !err && leaked.isEmpty())
    return logs
}

String logs = step('PUT /sync') {
    app.request = [JSON: ['88': [id: '88', guest: 'Ann', code: CODES[0], lockId: ['1'], checkIn: t - h, checkOut: t + h]]]
    app.apiSync()
}
check('PUT /sync: logs the booking count, id and guest name', logs.contains('apiSync: 1 bookings (ORB88 Ann)'))
logs = step('PATCH /sync') {
    app.request = [JSON: ['77': [id: '77', guest: 'Bob', code: CODES[1], lockId: ['1'], checkIn: t + 24 * h, checkOut: t + 48 * h]]]
    app.apiSyncPatch()
}
check('PATCH /sync: logs the booking count, id and guest name', logs.contains('apiSyncPatch: 1 bookings (ORB77 Bob)'))
step('PUT /sync/<id>/<lock>') {
    app.params = [bookingId: '66', lockId: '1', access_token: TOKEN]
    app.request = [JSON: [id: '66', guest: 'Dee', code: CODES[2], checkIn: t - h, checkOut: t + h]]
    app.apiSyncBookingByLock()
}
Map dup = null
step('PUT /sync/<id>/<lock> with a duplicate code') {
    app.params = [bookingId: '55', lockId: '1', access_token: TOKEN]
    app.request = [JSON: [id: '55', guest: 'Eve', code: CODES[2], checkIn: t - h, checkOut: t + h]]
    dup = app.apiSyncBookingByLock()
}
check('PUT /sync/<id>/<lock> duplicate: 409 with the code masked to its last 2 digits',
    dup?.status == 409 && new JsonSlurper().parseText(dup.data).error == "Duplicate code: '\u2022\u2022\u2022\u202266'." && !dup.data.contains(CODES[2]))
check('helperMaskCode: short or missing code shows no digits', app.helperMaskCode('12') == '\u2022' * 4 && app.helperMaskCode(null) == '\u2022' * 4)
step('POST /sync/<id>') {
    app.params = [bookingId: '44', access_token: TOKEN]
    app.request = [JSON: [id: '44', guest: 'Fay', code: CODES[3], lockId: '1', checkIn: t - h, checkOut: t + h]]
    app.apiSyncBooking()
}
step('POST /devices/<id>/setCode') {
    app.params = [deviceId: '1', command: 'setCode', access_token: TOKEN]
    app.request = [JSON: [codePosition: 53, pinCode: CODES[4], name: 'GT:ORB33-Gus']]
    app.apiExecuteCommand()
}
check('POST /devices/<id>/setCode: the command reached the lock', front.calls.contains([setCode: 53]))
step('GET /devices/<id>') {
    app.params = [deviceId: '1', access_token: TOKEN]
    app.apiGetDevice()
}
step('POST /register') {
    app.request = [JSON: [sbId: 'sb-fake', accessToken: LEGACY_TOKEN]]
    app.apiRegister()
}
step('reconcile') {
    app.scheduled.clear()
    app.reconcileDoorCodes()
}
List jobs = app.scheduled.findAll { it.handler in ['trySetCode', 'tryDeleteCode'] }
check('reconcile: scheduled set and delete jobs to run', jobs.any { it.handler == 'trySetCode' } && jobs.any { it.handler == 'tryDeleteCode' })
step('trySetCode, verifySetCode and tryDeleteCode jobs') {
    jobs.each { app."${it.handler}"(it.data) }
    app.scheduled.findAll { it.handler == 'verifySetCode' }.each { app.verifySetCode(it.data) }
}
step('duplicate code check') {
    Map bookings = app.helperGetBookings(app.atomicState.bookings)
    app.helperHasDuplicateCode('1', bookings['88'], bookings)
}
step('lock events (lockCodes webhook, failed code)') {
    app.state.sbId = 'sb-fake'
    app.webhook([name: 'lockCodes', value: front.attrs.lockCodes, deviceId: '1', displayName: 'Front'])
    app.state.sbId = null
    def pos = app.atomicState.codeAssignments['1']?.keySet()?.first()
    app.codeChangedHandler([name: 'codeChanged', value: 'failed', data: JsonOutput.toJson([codeNumber: pos as int]), device: [id: '1']])
}
// A malformed body (a List, not a Map of bookings) makes the handler throw; the
// exception message can list the body, code included (#38). Neither the logs nor
// the 500 response may carry it: the class name and a fixed message only.
String BAD_CODE = '80009900'
secrets << BAD_CODE
[
    ['PUT /sync', 'apiSync', [:]],
    ['PATCH /sync', 'apiSyncPatch', [:]],
    ['PUT /sync/<id>/<lock>', 'apiSyncBookingByLock', [bookingId: '22', lockId: '1', access_token: TOKEN]],
].each { String label, String handler, Map p ->
    Map resp = null
    String logsBad = step("${label} with a List body") {
        app.params = p
        app.request = [JSON: [[id: '22', guest: 'Hal', code: BAD_CODE, lockId: ['1'], checkIn: t - h, checkOut: t + h]]]
        resp = app."${handler}"()
    }
    String body = resp?.data ?: ''
    def error = body ? new JsonSlurper().parseText(body).error : null
    check("${label} with a List body: 500 without the code in the response".toString(),
        resp?.status == 500 && !body.contains(BAD_CODE) && error ==~ /${handler} failed \(\w+\)/)
    check("${label} with a List body: logs the handler and exception class".toString(),
        logsBad.contains("error ${error}"))
}

step('DELETE /sync/<id>/<lock> and /sync/<id>') {
    app.params = [bookingId: '66', lockId: '1', access_token: TOKEN]
    app.apiDeleteBookingByLock()
    app.params = [bookingId: '88', access_token: TOKEN]
    app.request = [JSON: [lockId: '1']]
    app.apiDeleteBooking()
}

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
