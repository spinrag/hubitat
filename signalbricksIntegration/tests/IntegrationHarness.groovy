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
    void unsubscribe() {}
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { 'tok-fake' }
}

class FakeLock {
    String id; String name; String label
    Map attrs = [:]
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
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
check('page: version line shows name and version', app.paragraphs.contains("<b>App Version:</b> SignalBricks Integration ${version}".toString()))
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

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
