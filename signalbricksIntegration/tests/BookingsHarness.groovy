// Bookings page (#29) for SignalBricks Integration with a stubbed Hubitat
// sandbox: renders with no state (as when the hub saves the code), each
// booking's lock status (on lock by name or by tracked slot, not on lock),
// orphans, the summary and last sync, the layout (bold booking heading, its
// locks indented under it, a current booking's "not on lock" highlighted,
// header-style section titles), and that codes are masked to their last
// 2 digits and never rendered or logged in full.
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
    List logged = []
    def log = [info: { logged << it.toString() }, warn: { logged << it.toString() }, debug: { logged << it.toString() }, trace: { logged << it.toString() }, error: { logged << it.toString() }]
    List scheduled = []
    List paragraphs = []
    List sectionTitles = []
    List hrefs = []
    Map inputs = [:]
    def app = [id: 1]
    def location = [name: 'Test', hub: [name: 'Hub']]
    void definition(Map m) {}
    void mappings(Closure c) {}
    void preferences(Closure c) { pages = c }
    Closure pages
    void page(Map m, Closure c = null) { if (c) c() }
    def dynamicPage(Map m, Closure c) { c() }
    void section(Object... args) {
        def m = args.find { it instanceof Map }
        if (m?.title) sectionTitles << m.title
        args.find { it instanceof Closure }?.call()
    }
    void paragraph(String s) { paragraphs << s }
    void input(Map m) { inputs[m.name] = m }
    void href(Map m) { hrefs << m }
    long now() { clock }
    String getHubUID() { 'hub-fake' }
    String getFullApiServerUrl() { 'https://cloud.test/api/hub/apps/1' }
    String getFullLocalApiServerUrl() { 'http://hub.local/apps/api/1' }
    void runIn(Number secs, String handler, Map opts = [:]) { scheduled << [secs: secs, handler: handler] }
    void runOnce(Date d, String handler, Map opts = [:]) { scheduled << [at: d, handler: handler] }
    void schedule(String cron, String handler) { scheduled << [cron: cron, handler: handler] }
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
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)
app.run()

int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }
def mainPage = { app.paragraphs.clear(); app.hrefs.clear(); app.pages(); app.paragraphs.join('\n') }
def bookingsPage = { app.paragraphs.clear(); app.bookingsPage(); app.paragraphs.join('\n') }
String bullets = '&bull;&bull;&bull;&bull;'

check('version is 1.4.2', app.sbAppVersion == '1.4.2')

// --- Saving the app code renders pages with no state, settings or locks ---
app.state = null; app.atomicState = null; app.settings = null; app.locks = null
String saveError = null
try { mainPage() } catch (e) { saveError = e.toString() }
check('save: main page renders without state', saveError == null)
check('main page: links to the Bookings page', app.hrefs.any { it.page == 'bookingsPage' })
saveError = null
String empty = null
try { empty = bookingsPage() } catch (e) { saveError = e.toString() }
check('save: Bookings page renders without state', saveError == null && empty.contains('No app state yet'))
app.state = [:]; app.atomicState = new JsonStateMap(); app.settings = [locks: ['1', '2', '3'] as String[]]

// --- Fake locks and bookings ---
// Front (1): 88 by name, 66 by a slot the app assigned earlier (driver dropped
// the name and redacted the code), 33 past and 22 upcoming still on the lock,
// 99 an orphan, 77 on a lock it isn't assigned to, plus codes that aren't the app's
Map frontCodes = [
    '20': [name: 'Front desk', code: '97531864'],
    '30': [name: 'G:ORB77-Bob', code: '11223344'], // the OwnerRez app's prefix
    '50': [name: 'GT:ORB88-Ann', code: '24681357'],
    '51': [name: 'GT:ORB99-Old', code: '13572468'],
    '52': [name: 'GT:ORB77-Bob', code: '86420975'], // 77 is not assigned to Front
    '54': [name: 'Code 54', code: '********'],
    '55': [name: 'GT:ORB33-Gus', code: '30000033'],
    '56': [name: 'GT:ORB22-Hal', code: '30000022'],
]
// Back (2): one orphan with an odd slot key; Side (3): unreadable; Garage (4): never reported codes
Map backCodes = ['<9>': [name: 'GT:ORB98-Ivy', code: '31415926']]
def front = new FakeLock(id: '1', name: 'A', label: 'Front', attrs: [maxCodes: 60, lockCodes: JsonOutput.toJson(frontCodes)])
def back = new FakeLock(id: '2', name: 'B', label: 'Back <Door>', attrs: [maxCodes: 60, lockCodes: JsonOutput.toJson(backCodes)])
def side = new FakeLock(id: '3', name: 'Side', attrs: [maxCodes: 60, lockCodes: 'encrypted-not-json'])
def garage = new FakeLock(id: '4', name: 'Garage', attrs: [maxCodes: 60])
app.locks = [front, back, side, garage]
app.atomicState.codeAssignments = ['1': ['54': [bookingId: 'ORB66', code: '55667788', codeName: 'GT:ORB66-Dee', attempt: 1, confirmed: true]]]

Date now = new Date()
long h = 3600_000L
Map bookings = [
    '88': [id: '88', guest: 'Ann', code: '20000088', lockId: ['1', '2', '4'], checkIn: new Date(now.time - h), checkOut: new Date(now.time + h)],
    '66': [id: '66', guest: 'Dee', code: '55667788', lockId: ['1'], checkIn: new Date(now.time - h), checkOut: new Date(now.time + h)],
    '11': [id: '11', guest: 'Ida', code: '30000011', lockId: ['9'], checkIn: new Date(now.time - h), checkOut: new Date(now.time + h)],
    '77': [id: '77', guest: '<b>Bob</b>', code: '30000077', lockId: ['2'], checkIn: new Date(now.time + 24 * h), checkOut: new Date(now.time + 48 * h)],
    '55': [id: '55', guest: 'Eve', code: '30000055', lockId: ['9'], checkIn: new Date(now.time + 72 * h), checkOut: new Date(now.time + 96 * h)],
    '44': [id: '44', guest: 'Fay', code: '30000044', lockId: ['1'], checkIn: new Date(now.time - 48 * h), checkOut: new Date(now.time - 24 * h)],
    '33': [id: '33', guest: 'Gus', code: '30000033', lockId: ['1'], checkIn: new Date(now.time - 96 * h), checkOut: new Date(now.time - 72 * h)],
    '22': [id: '22', guest: 'Hal', code: '30000022', lockId: ['1'], checkIn: new Date(now.time + 5 * h), checkOut: new Date(now.time + 30 * h)],
]
app.atomicState.bookings = bookings
List allCodes = (frontCodes.values()*.code + backCodes.values()*.code + bookings.values()*.code + ['55667788']).findAll { it != '********' }

app.logged.clear()
String page = bookingsPage()
check("render: no log lines while rendering (${app.logged.size()})", app.logged.isEmpty())
Map v = app.bookingsView()
def row = { String id -> v.bookings.find { it.startsWith("<b>ORB${id} ") } ?: '' }
String missing = app.bookingsMissing
String indent = "<div style='margin-left:1.5em'>"

// Layout (#36)
app.sectionTitles.clear(); bookingsPage()
check('layout: section titles are headers', app.sectionTitles == ['<h2>Summary</h2>', '<h2>Known bookings</h2>', '<h2>Orphans</h2>'])
check('layout: booking heading is bold with name, status, dates and masked code, its locks indented under it',
    row('88') ==~ /<b>ORB88 Ann &middot; current &middot; \d{4}-\d\d-\d\d \d\d:\d\d to \d{4}-\d\d-\d\d \d\d:\d\d &middot; code ${bullets}88<\/b>${indent}.*<\/div>/)
check('layout: one paragraph per booking', v.bookings.size() == 8 && v.bookings.every { it.count('<b>ORB') == 1 && it.count(indent) == 1 })
check('layout: "not on lock" is highlighted', missing.contains('not on lock') && missing.contains('color:'))
check('layout: only a current booking\'s "not on lock" is highlighted', ['77', '55', '44'].every { !row(it).contains(missing) })

// On lock
check('on lock: by name, with its slot', row('88').contains(indent + 'Front: slot 50<br>'))
check('on lock: by a slot the app assigned earlier', row('66').contains('Front: slot 54') && !row('66').contains('reconcile'))
check('on lock, past: flagged as removed by reconcile', row('33').contains('past') && row('33').contains('Front: slot 55 &mdash; reconcile will remove it (past checkout)'))
check('on lock, upcoming: flagged as early', row('22').contains('upcoming') && row('22').contains('Front: slot 56 (early: reconcile removes it until check-in)'))
// Not on lock
check('not on lock: current booking, assigned lock without the code', row('88').contains('Back &lt;Door&gt;: ' + missing))
check('not on lock: current booking, lock not selected', row('11').contains('lock 9 (not selected in this app): ' + missing))
check('not on lock: upcoming says added at check-in', row('77').contains('Back &lt;Door&gt;: not on lock (added at check-in)') && row('55').contains('lock 9 (not selected in this app): not on lock (added at check-in)'))
check('not on lock: past says removed after checkout', row('44').contains('Front: not on lock (removed after checkout)'))
check('lock that never reported codes: "no codes reported"', row('88').contains('Garage: no codes reported') && page.contains('<b>Garage:</b> has reported no codes yet'))
check('unreadable lockCodes: noted, not counted', page.contains('<b>Side:</b> its lockCodes could not be read'))
List order = v.bookings.collect { (it =~ /ORB(\d+)/)[0][1] }
check("rows: sorted by check-in (${order})", order.first() == '33' && order[1] == '44' && order[-3..-1] == ['22', '77', '55'])
check('rows: guest name is HTML-escaped', row('77').contains('&lt;b&gt;Bob&lt;/b&gt;') && !page.contains('<b>Bob</b>'))
// Orphans
check('orphan: unknown booking with the app prefix listed with lock, slot, name, masked code',
    v.orphans.any { it.contains('Front') && it.contains('slot 51') && it.contains('GT:ORB99-Old') && it.contains(bullets + '68') })
check('orphan: known booking on a lock it is not assigned to', v.orphans.any { it.contains('slot 52') && it.contains('not assigned to this lock') })
check('orphan: slot key is HTML-escaped', v.orphans.any { it.contains('slot &lt;9&gt;') } && !page.contains('<9>'))
check('orphan: other app\'s and non-booking codes are not orphans', v.orphans.size() == 3 && !page.contains('G:ORB77-Bob') && !page.contains('Front desk'))
// Summary: 8 known; on locks Front 88 99 77 66 33 22 + Back 98; current not on lock 88@Back, 11@9
check('summary: counts only current bookings as not on lock', page.contains('<b>8 bookings known &middot; 7 codes on locks &middot; 2 current not on lock &middot; 3 orphans</b>'))
check('summary: last sync not recorded yet', page.contains('<b>Last sync:</b> not recorded yet'))
// Masking
check('mask: last 2 digits only', app.bookingsMask('24681357') == bullets + '57' && row('88').contains('code ' + bullets + '88'))
check('mask: short or missing code shows no digits', app.bookingsMask('12') == bullets && app.bookingsMask(null) == bullets && app.bookingsMask('123') == bullets)
check('mask: redacted lock code stays redacted', app.bookingsMask('********') == bullets + '**')
List shown = allCodes.findAll { page.contains(it) }
check("render: no full code on the page (${shown.size()} found)", shown.isEmpty())
check('render: main page still renders with state', mainPage().contains('SignalBricks Integration 1.4.2'))
// Reconcile still logs its matches (quiet only for the page)
app.logged.clear()
app.helperOnlySbCodes(new JsonSlurper().parseText(JsonOutput.toJson(frontCodes)), '1')
check('helperOnlySbCodes: still logs when not quiet', app.logged.any { it.contains('found by name: 88 at pos 50') })

// --- Last sync time and source ---
Map syncBody = ['88': [id: '88', guest: 'Ann', code: '20000088', lockId: ['1'], checkIn: now.time - h, checkOut: now.time + h]]
app.request = [JSON: syncBody]
app.apiSync()
check('last sync: PUT recorded', bookingsPage().contains('(PUT /sync)') && app.atomicState.lastSync.at == app.clock)
app.clock += 60_000
app.request = [JSON: syncBody]
app.apiSyncPatch()
check('last sync: PATCH recorded', bookingsPage().contains('(PATCH /sync)') && app.atomicState.lastSync.at == app.clock)
check('after sync: 1 booking known, older codes become orphans', bookingsPage().contains('1 bookings known') && app.bookingsView().orphans.size() == 6)
app.clock += 60_000
app.params = [bookingId: '88', lockId: '2']
app.request = [JSON: [id: '88', guest: 'Ann', code: '20000088', checkIn: now.time - h, checkOut: now.time + h]]
app.apiSyncBookingByLock()
check('last sync: single booking recorded', bookingsPage().contains('(POST/PUT /sync/88)') && app.atomicState.lastSync.at == app.clock)
app.clock += 60_000
app.request = [JSON: [lockId: '2']]
app.params = [bookingId: '88']
app.apiDeleteBooking()
check('last sync: delete recorded', bookingsPage().contains('(DELETE /sync/88)') && app.atomicState.lastSync.at == app.clock)
app.appButtonHandler('btnReset')
check('Reset State: last sync cleared', app.atomicState.lastSync == null && bookingsPage().contains('not recorded yet'))

// No bookings, no locks
app.atomicState.bookings = [:]
app.locks = []
page = bookingsPage()
check('empty: no bookings, no orphans', page.contains('No bookings known.') && page.contains('None.') && page.contains('0 bookings known'))

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
