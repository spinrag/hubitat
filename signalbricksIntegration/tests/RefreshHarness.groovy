// Door code refresh (#24) for SignalBricks Integration with a stubbed Hubitat
// sandbox: Off by default (also on upgrade), the old midnight job removed,
// Done not refreshing, the Weekly/Daily cadence, the one-lock-at-a-time queue,
// the manual button and progress, and re-arming after a booking sync.
// Fake values only; nothing is sent to a hub.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
import org.codehaus.groovy.control.CompilerConfiguration

// atomicState and runIn data are stored as JSON on the hub: every read returns
// a fresh copy, so an edit is lost unless it is written back (as on the hub)
class JsonStateMap extends LinkedHashMap {
    Object put(Object k, Object v) { super.put(k, v == null ? null : JsonOutput.toJson(v)) }
    Object get(Object k) { def s = super.get(k); s == null ? null : new JsonSlurper().parseText(s) }
}

abstract class HubitatStub extends Script {
    Map state = [:]
    Map atomicState = new JsonStateMap()
    Map settings = [:]
    def locks = []
    def minCodePosition = 0
    def idAtEnd = false
    def codeNamePrefix = 'GT:'
    def request = [:]
    def params = [:]
    long clock = 1_800_000_000_000L
    List logged = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, trace: { logged << it.toString() }, error: { logged << it.toString() }]
    List scheduled = []
    List paragraphs = []
    Map inputs = [:]
    def app = [id: 1]
    def location = [name: 'Test', hub: [name: 'Hub']]
    void definition(Map m) {}
    void mappings(Closure c) {}
    void preferences(Closure c) { pages = c }
    Closure pages
    void page(Map m, Closure c = null) { if (c) c() }
    def dynamicPage(Map m, Closure c) { c() }
    void section(Object... args) { args.find { it instanceof Closure }?.call() }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) {}
    long now() { clock }
    String getHubUID() { 'hub-fake' }
    String getFullApiServerUrl() { 'https://cloud.test/api/hub/apps/1' }
    String getFullLocalApiServerUrl() { 'http://hub.local/apps/api/1' }
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite) scheduled.removeAll { it.handler == handler }
        def data = opts?.data == null ? null : new JsonSlurper().parseText(JsonOutput.toJson(opts.data))
        scheduled << [secs: secs, handler: handler, data: data]
    }
    void runOnce(Date d, String handler, Map opts = [:]) { scheduled << [at: d, handler: handler] }
    void schedule(String cron, String handler) { scheduled.removeAll { it.handler == handler }; scheduled << [cron: cron, handler: handler] }
    void unschedule() { scheduled.clear() }
    void unschedule(String handler) { scheduled.removeAll { it.handler == handler } }
    void subscribe(Object... args) {}
    void unsubscribe() {}
    void httpPostJson(Map p, Closure c) { throw new IllegalStateException('no network in the harness') }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { 'tok-fake' }
    // JsonOutput writes dates as yyyy-MM-ddTHH:mm:ss+0000
    Date toDateTime(String s) { new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").parse(s) }
    def propertyMissing(String name) {
        if (name.endsWith('Handler')) return name
        throw new MissingPropertyException(name, getClass())
    }
}

class FakeLock {
    String id; String name; String label
    Map attrs = [:]
    List calls
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void getCodes() { calls << id }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
// Run the pending job for a handler, as the hub scheduler would
def fire = { String handler ->
    def job = app.scheduled.find { it.handler == handler }
    if (!job) return false
    app.scheduled.remove(job)
    if (job.data == null) app."$handler"() else app."$handler"(job.data)
    return true
}
def pending = { String handler -> app.scheduled.find { it.handler == handler } }
def refreshJobs = { app.scheduled.findAll { it.handler in ['refreshDoorCodes', 'tryRefreshDoorCodes', 'codeRefreshScheduled', 'codeRefreshNext', 'codeRefreshTimeout', 'codeRefreshSettled'] } }
def mainPage = { app.paragraphs.clear(); app.pages(); app.paragraphs.join('\n') }
def reset = { List cl -> app.scheduled.clear(); app.atomicState.codeRefresh = null; cl.clear() }

// Fake door codes; none of them may ever appear in a log line
Map fakeCodes = ['50': [name: 'GT:ORB88-Ann', code: '24681357'], '51': [name: 'Front desk', code: '97531864']]
List calls = []
def a = new FakeLock(id: '1', name: 'A', label: 'Front', calls: calls, attrs: [maxCodes: 250, lockCodes: JsonOutput.toJson(fakeCodes)])
def b = new FakeLock(id: '2', name: 'B', label: 'Back', calls: calls, attrs: [maxCodes: 30, lockCodes: JsonOutput.toJson(fakeCodes)])
def c = new FakeLock(id: '3', name: 'C', calls: calls, attrs: [:])
app.locks = [a, b, c]
app.settings = [locks: ['1', '2', '3'] as String[]]

Date now = new Date()
Map bookings = ['88': [id: '88', guest: 'Ann', code: '24681357', lockId: ['1'], checkIn: new Date(now.time + 86400_000), checkOut: new Date(now.time + 2 * 86400_000)]]

check('version is 1.4.0', app.sbAppVersion == '1.4.0')

// Saving the app code runs the main page with no state and no settings
def savedState = app.state, savedAtomic = app.atomicState, savedSettings = app.settings
app.state = null; app.atomicState = null; app.settings = null
String saveError = null
try { mainPage() } catch (e) { saveError = e.toString() }
check('save: main page renders without state', saveError == null)
app.state = savedState; app.atomicState = savedAtomic; app.settings = savedSettings

// --- Off by default: a new install ---
app.installed()
check('install: no cadence saved -> no refresh scheduled', refreshJobs().isEmpty() && app.state.codeRefreshCron == null)
check('install: nothing read from the locks', calls.isEmpty())
check('page: cadence defaults to Off', mainPage().contains('<b>Scheduled:</b> Off') && app.inputs.codeRefreshCadence.defaultValue == 'off')

// --- Upgrade from 1.3.4: midnight cron and per-lock jobs left behind, no cadence saved ---
app.scheduled.clear()
app.scheduled << [cron: '0 0 0 * * ?', handler: 'refreshDoorCodes']
app.scheduled << [secs: 60, handler: 'tryRefreshDoorCodes', data: [lockId: '1']]
app.state.bookings = bookings
app.atomicState.bookings = bookings
app.updated() // the operator clicks Done
check('upgrade + Done: midnight refreshDoorCodes cron removed', !app.scheduled.any { it.handler == 'refreshDoorCodes' || it.cron == '0 0 0 * * ?' })
check('upgrade + Done: Off, no refresh of any kind scheduled', refreshJobs().isEmpty())
check('upgrade + Done: no lock read', calls.isEmpty() && app.atomicState.codeRefresh == null)
check('upgrade + Done: booking reconcile still scheduled', app.scheduled.any { it.handler == 'reconcileDoorCodes' && it.at })

// Upgrade without Done: the old midnight cron fires once with the new code
app.scheduled.clear()
app.scheduled << [cron: '0 0 0 * * ?', handler: 'refreshDoorCodes']
fire('refreshDoorCodes')
check('upgrade without Done: old midnight job reads no lock', calls.isEmpty() && app.atomicState.codeRefresh == null)
app.scheduled << [cron: '0 0 0 * * ?', handler: 'refreshDoorCodes']
app.refreshDoorCodes()
check('upgrade without Done: old midnight job removes itself', !app.scheduled.any { it.handler == 'refreshDoorCodes' } && refreshJobs().isEmpty())
app.tryRefreshDoorCodes([lockId: '1'])
check('upgrade: old per-lock tryRefreshDoorCodes job is a no-op', calls.isEmpty() && app.scheduled.isEmpty())

// --- Done never starts a refresh, whatever the cadence ---
app.settings.codeRefreshCadence = 'daily'
app.scheduled.clear()
app.updated()
check('Done (daily): no lock read, no run started', calls.isEmpty() && app.atomicState.codeRefresh == null && !pending('codeRefreshNext'))
app.request = [JSON: [sbId: 'sb-fake']]
app.apiRegister()
check('/register: no lock read, no run started', calls.isEmpty() && app.atomicState.codeRefresh == null && !pending('codeRefreshNext'))
app.state.sbId = null
app.atomicState.sbId = null

// --- Cadence ---
def cronOf = { pending('codeRefreshScheduled')?.cron }
app.settings.codeRefreshCadence = 'weekly'
app.state.remove('codeRefreshHour'); app.state.remove('codeRefreshMinute')
app.scheduleEvents(bookings)
String cron = cronOf()
check('weekly: cron on Tuesday in 01:00-03:59', cron ==~ /0 ([0-9]|[1-5][0-9]) [123] \? \* TUE/)
app.scheduleEvents(bookings)
check('weekly: time stays the same', cronOf() == cron)
check('page: shows weekly time', mainPage().contains(String.format('weekly on Tuesday at %02d:%02d hub time', app.state.codeRefreshHour as int, app.state.codeRefreshMinute as int)))
app.settings.codeRefreshCadence = 'daily'
app.scheduleEvents(bookings)
check('daily: same time, every day', cronOf() == cron.replace('TUE', '*'))
Set hours = [] as Set
Set minutes = [] as Set
200.times {
    app.state.remove('codeRefreshHour'); app.state.remove('codeRefreshMinute')
    def m = (app.codeRefreshCron() =~ /^0 (\d+) (\d+) \? \* \*$/)
    if (m.matches()) { minutes << (m.group(1) as int); hours << (m.group(2) as int) }
}
check("daily/weekly: random time always within 01:00-03:59 (hours ${hours.sort()})", hours && hours.every { it in 1..3 } && minutes.every { it in 0..59 } && minutes.size() > 10)
app.scheduleEvents([:])
check('cadence scheduled even with no bookings', cronOf() != null)
app.settings.codeRefreshCadence = 'off'
app.scheduleEvents(bookings)
check('off: cadence removed, nothing scheduled', refreshJobs().isEmpty() && app.state.codeRefreshCron == null)
app.settings.codeRefreshCadence = 'weekly'
app.scheduleEvents(bookings)
fire('codeRefreshScheduled')
check('cadence job starts a run (first lock after 60 s)', pending('codeRefreshNext')?.secs == 60 && calls.isEmpty())
reset(calls)
app.settings.codeRefreshCadence = null

// --- Sequential queue ---
// From here to the booking sync the log must hold no code (the existing sync
// and reconcile debug lines print bookings, which is out of scope here)
app.logged.clear()
app.startCodeRefresh()
check('queue: nothing read immediately, one job only', calls.isEmpty() && app.scheduled.size() == 1 && pending('codeRefreshNext')?.data?.index == 0)
fire('codeRefreshNext')
check('queue: lock A read alone', calls == ['1'])
check('queue: A budget 3 s/slot (250 -> 750 s)', pending('codeRefreshTimeout')?.secs == 750)
check('queue: B not scheduled while A reads', !pending('codeRefreshNext'))

app.startCodeRefresh()
check('queue: second start while running does not restart', calls == ['1'] && app.atomicState.codeRefresh.index == 0 && !pending('codeRefreshNext'))

// A's floor is 650 s (2.6 s/slot): large locks send lockCodes mid-read, so earlier events don't finish it
check('floor: 2.6 s/slot (250 -> 650 s), 60 s minimum', app.codeRefreshFloorSec(250) == 650 && app.codeRefreshFloorSec(30) == 78 && app.codeRefreshFloorSec(10) == 60)
app.clock += 100_000
app.lockCodesHandler([deviceId: 1L, name: 'lockCodes', value: JsonOutput.toJson(fakeCodes)])
check('floor: lockCodes at 100 s on a 250-slot lock ignored', !pending('codeRefreshSettled'))
app.clock += 545_000
app.lockCodesHandler([deviceId: 1L, name: 'lockCodes', value: JsonOutput.toJson(fakeCodes)])
check('floor: lockCodes at 645 s (< 650 s floor) ignored', !pending('codeRefreshSettled'))
app.clock += 15_000
app.lockCodesHandler([deviceId: 2L, name: 'lockCodes', value: JsonOutput.toJson(fakeCodes)])
check('lockCodes from another lock ignored', !pending('codeRefreshSettled'))
app.lockCodesHandler([deviceId: 1L, name: 'lockCodes', value: JsonOutput.toJson(fakeCodes)])
check('lockCodes from A at 660 s (after its floor) -> settle 30 s', pending('codeRefreshSettled')?.secs == 30)
app.clock += 10_000
app.lockCodesHandler([deviceId: 1L, name: 'lockCodes', value: JsonOutput.toJson(fakeCodes)])
check('settle restarts on a further lockCodes (one settle job)', app.scheduled.count { it.handler == 'codeRefreshSettled' } == 1)

fire('codeRefreshSettled')
check('queue: B queued only after A is done', pending('codeRefreshNext')?.data?.index == 1 && calls == ['1'])
fire('codeRefreshTimeout') // A's budget timer firing in the 5 s gap
check('stale: A timeout in the gap does not double-queue', app.scheduled.count { it.handler == 'codeRefreshNext' } == 1 && calls == ['1'])

fire('codeRefreshNext')
check('queue: lock B read after A', calls == ['1', '2'])
check('budget: 30-slot lock gets the 120 s minimum', pending('codeRefreshTimeout')?.secs == 120)
app.clock += 30_000
app.lockCodesHandler([deviceId: 2L, name: 'lockCodes'])
check('floor: 30-slot lock 78 s (lockCodes at 30 s ignored)', !pending('codeRefreshSettled'))

// Timers from an earlier step or run do nothing
long runId = app.atomicState.codeRefresh.runId
app.codeRefreshSettled([runId: runId, index: 0])
app.codeRefreshTimeout([runId: runId, index: 0])
app.codeRefreshNext([runId: runId, index: 1])
app.codeRefreshTimeout([runId: runId - 1, index: 1])
app.codeRefreshNext([runId: runId - 1, index: 2])
check('stale: old step and old run timers are no-ops', !pending('codeRefreshNext') && !pending('codeRefreshSettled') && calls == ['1', '2'] && app.atomicState.codeRefresh.index == 1)

// Budget timeout moves on without lockCodes
fire('codeRefreshTimeout')
check('timeout: B moves on after its budget', pending('codeRefreshNext')?.data?.index == 2 && app.logged.any { it.contains('did not finish within its time budget') })
fire('codeRefreshNext')
check('queue: lock C (no maxCodes) read last', calls == ['1', '2', '3'] && pending('codeRefreshTimeout')?.secs == 120)
check('page: progress shows lock 3 of 3', mainPage().contains('running, lock 3 of 3 (C)'))
fire('codeRefreshTimeout')
fire('codeRefreshNext')
check('queue: run finishes, state cleared, no jobs left', app.atomicState.codeRefresh == null && app.scheduled.isEmpty() && calls == ['1', '2', '3'])
check('page: last refresh shown', mainPage().contains('<b>Last refresh finished:</b>') && mainPage().contains('(3 lock(s))') && mainPage().contains('<b>Refresh:</b> not running'))

// Budget cap
calls.clear()
a.attrs.maxCodes = 1000
app.locks = [a]
app.startCodeRefresh()
fire('codeRefreshNext')
check('budget: capped at 20 min', pending('codeRefreshTimeout')?.secs == 1200)
a.attrs.maxCodes = 250
reset(calls)

// A lock removed from the app mid-run is skipped
app.locks = [a, b, c]
app.startCodeRefresh()
fire('codeRefreshNext')
app.locks = [a, c]
fire('codeRefreshTimeout')
fire('codeRefreshNext')
check('removed lock skipped without getCodes', calls == ['1'] && pending('codeRefreshNext')?.data?.index == 2)
fire('codeRefreshNext')
check('run continues with the next lock', calls == ['1', '3'])
reset(calls)
app.locks = [a, b, c]

// Lost timers (hub reboot): a fresh run is kept, a stalled one restarts
app.startCodeRefresh()
fire('codeRefreshNext')
long oldRun = app.atomicState.codeRefresh.runId
app.scheduled.clear()
app.clock += 600_000
app.startCodeRefresh()
check('reboot: run within budget + 300 s not restarted', app.atomicState.codeRefresh.runId == oldRun)
app.clock += 500_000
check('page: stalled run shown', mainPage().contains('stopped at lock 1 of 3'))
app.startCodeRefresh()
check('reboot: stalled run restarts from the first lock', app.atomicState.codeRefresh.runId != oldRun && app.atomicState.codeRefresh.index == -1 && pending('codeRefreshNext')?.data?.index == 0)
reset(calls)

// --- Manual button ---
app.settings.codeRefreshLock = null
app.appButtonHandler('btnRefreshCodes')
check('button: starts all locks, one at a time', app.atomicState.codeRefresh.lockIds == ['1', '2', '3'] && calls.isEmpty() && pending('codeRefreshNext')?.secs == 60)
check('page: progress shows starting', mainPage().contains('running, starting, 3 lock(s)'))
fire('codeRefreshNext')
check('button: first lock read', calls == ['1'])
check('page: progress shows lock 1 of 3', mainPage().contains('running, lock 1 of 3 (Front)'))
app.paragraphs.clear(); app.debug()
check('debug page: progress shown too', app.paragraphs.any { it.contains('lock 1 of 3 (Front)') })
reset(calls)
app.settings.codeRefreshLock = '2'
app.appButtonHandler('btnRefreshCodes')
fire('codeRefreshNext')
check('button: one chosen lock only', app.atomicState.codeRefresh.lockIds == ['2'] && calls == ['2'])
reset(calls)
app.settings.codeRefreshLock = 'all'
app.appButtonHandler('btnRefreshCodes')
check('button: "all" reads every lock', app.atomicState.codeRefresh.lockIds == ['1', '2', '3'])
mainPage()
check('page: lock choice lists all locks', app.inputs.codeRefreshLock.options.keySet() == ['all', '1', '2', '3'] as Set)
reset(calls)

// --- No door code values in any refresh log line (lockCodes events carried codes) ---
List leaked = app.logged.findAll { line -> fakeCodes.values().any { line.contains(it.code) } }
check("logs: no door code values logged (${leaked.size()} lines)", leaked.isEmpty())
List refreshLines = app.logged.findAll { it.startsWith('Door code refresh') }
check('logs: refresh progress was logged', refreshLines.size() > 5)

// --- Re-arm after a booking sync clears schedules ---
Map syncBody = ['88': [id: '88', guest: 'Ann', code: '24681357', lockId: ['1'], checkIn: now.time + 86400_000, checkOut: now.time + 2 * 86400_000]]
app.settings.codeRefreshCadence = null
app.request = [JSON: syncBody]
app.apiSync()
check('sync (Off, idle): only reconcile scheduled, no refresh', refreshJobs().isEmpty() && app.scheduled.any { it.handler == 'reconcileDoorCodes' })
app.settings.codeRefreshCadence = 'off'
app.request = [JSON: syncBody]
app.apiSyncPatch()
check('sync patch (Off): no refresh', refreshJobs().isEmpty())
app.settings.codeRefreshCadence = 'weekly'
app.request = [JSON: syncBody]
app.apiSync()
check('sync (weekly): cadence re-armed, no run started', pending('codeRefreshScheduled')?.cron ==~ /0 \d+ [123] \? \* TUE/ && !pending('codeRefreshNext') && calls.isEmpty())
app.settings.codeRefreshCadence = 'off'
app.appButtonHandler('btnRefreshCodes')
fire('codeRefreshNext')
app.clock += 200_000
app.request = [JSON: syncBody]
app.apiSync()
check('sync (Off) mid manual run: its timer re-armed with the remaining budget', pending('codeRefreshTimeout')?.secs == 550 && pending('codeRefreshTimeout')?.data?.index == 0)
check('sync (Off) mid manual run: no cadence, no restart', !pending('codeRefreshScheduled') && calls == ['1'])
app.params = [bookingId: '88', lockId: '2']
app.request = [JSON: [id: '88', guest: 'Ann', code: '24681357', checkIn: now.time + 86400_000, checkOut: now.time + 2 * 86400_000]]
app.apiSyncBookingByLock()
check('sync one booking mid run: timer kept, no cadence', pending('codeRefreshTimeout')?.data?.index == 0 && !pending('codeRefreshScheduled'))
app.params = [bookingId: '88', lockId: '2']
app.apiDeleteBookingByLock()
check('delete booking mid run: timer kept, no cadence', pending('codeRefreshTimeout')?.data?.index == 0 && !pending('codeRefreshScheduled'))
app.locks = [c] // no codes on the lock: reconcile finishes and calls scheduleEvents
app.reconcileDoorCodes([:])
app.locks = [a, b, c]
check('reconcile done: timer kept, no cadence', pending('codeRefreshTimeout')?.data?.index == 0 && !pending('codeRefreshScheduled'))
app.appButtonHandler('btnReset')
check('Reset State: run cleared, Off stays off', app.atomicState.codeRefresh == null && refreshJobs().isEmpty())
reset(calls)

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
