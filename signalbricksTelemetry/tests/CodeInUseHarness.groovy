// Code in use (#77) with a stubbed Hubitat sandbox: POST
// /locks/:id/codeInUse answers only whether a lock holds a code with the given
// fingerprint, on any occupied slot. Fake values only.
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

abstract class HubitatStub extends Script {
    Map state = [:]
    // Same store as state here; the hub writes atomicState through immediately.
    Map getAtomicState() { state }
    Map params = [:]
    Map request = [:]
    def codeLocks = []
    def locks = null, contactSensors = null, temperatureSensors = null, humiditySensors = null
    Boolean lockManagementEnabled = true
    Boolean includeDoorCodes = false
    List subscriptions = []
    void subscribe(def devices, String attribute, String handler) { subscriptions << attribute }
    Boolean debugLogging = false
    def maxManagedSlot = null
    def approvedAdoptedSlots = null
    def fullControlLocks = null
    List removedSettings = []
    List logged = []
    List posts = []
    def log = [info: { logged << it }, warn: { logged << it }, debug: { logged << it }, error: { logged << it }]
    def app = new FakeApp(this)
    def location = [name: 'Test', hub: [firmwareVersionString: '2.4.1.177']]
    void definition(Map m) {}
    void preferences(Closure c) {}
    void mappings(Closure c) {}
    Map render(Map m) { m }
    Long fakeNow = null
    long now() { fakeNow ?: System.currentTimeMillis() }
    List scheduled = []
    void runIn(Number secs, String handler, Map opts = [:]) {
        if (opts?.overwrite != false) scheduled.removeAll { it.handler == handler }
        scheduled << [secs: secs, handler: handler, data: opts?.data]
    }
    void schedule(String cron, String handler) {
        scheduled.removeAll { it.handler == handler }
        scheduled << [cron: cron, handler: handler]
    }
    void unschedule(String handler = null) { if (handler) scheduled.removeAll { it.handler == handler } else scheduled.clear() }
    void unsubscribe() {}
    String getHubUID() { '00000000-0000-4000-8000-00000000abcd' }
    String getFullApiServerUrl() { "https://cloud.hubitat.com/api/${getHubUID()}/apps/42" }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    String createAccessToken() { state.accessToken = 'tok-' + UUID.randomUUID(); state.accessToken }
    Boolean throwOnPost = false
    void asynchttpPost(String cb, Map p, Map d) {
        if (throwOnPost) throw new IllegalArgumentException("post failed: ${p.body}")
        posts << [cb: cb, params: p, data: d]
    }
    String decrypt(String s) { throw new RuntimeException('no decrypt') }
}

// Hubitat AsyncResponse: getData() on success, getErrorData() on error.
class FakeResp {
    int status; String body
    Boolean hasError() { status >= 400 }
    int getStatus() { status }
    String getData() { if (hasError()) throw new IllegalStateException('no data on error'); body }
    String getErrorData() { body }
}

class FakeApp {
    def owner
    def id = 42
    FakeApp(o) { owner = o }
    void removeSetting(String n) { owner.removedSettings << n; owner.setProperty(n, null) }
    void updateSetting(String n, Map v) { owner.setProperty(n, v.value) }
}

class FakeLock {
    String id; String displayName; String name; String label
    List caps = ['Lock', 'LockCodes']
    Boolean hasCapability(String c) { caps.contains(c) }
    Map attrs = [:]
    List calls = []
    // Off: commands are only recorded. On: they update lockCodes, except the
    // next `dropCommands` ones (a lock that silently drops a change).
    Boolean applyCommands = false
    int dropCommands = 0
    Boolean throwOnSet = false
    // A driver that throws on delete with the slot's code in its message
    Boolean throwOnDelete = false
    def currentValue(String a, Boolean skipCache = false) { attrs[a] }
    void setCode(Integer slot, String code, String name) {
        calls << ['setCode', slot, name]
        if (throwOnSet) throw new IllegalStateException("setCode(${slot}, ${code}, ${name}) failed")
        apply { it[slot.toString()] = [name: name, code: code] }
    }
    void deleteCode(Integer slot) {
        calls << ['deleteCode', slot]
        if (throwOnDelete) throw new IllegalStateException("deleteCode(${slot}) failed for code ${(new JsonSlurper().parseText(attrs.lockCodes) as Map)[slot.toString()]?.code}")
        apply { it.remove(slot.toString()) }
    }
    void apply(Closure c) {
        if (!applyCommands) return
        if (dropCommands > 0) { dropCommands--; return }
        Map codes = new JsonSlurper().parseText(attrs.lockCodes) as Map
        c(codes)
        attrs.lockCodes = groovy.json.JsonOutput.toJson(codes)
    }
}

def cfg = new CompilerConfiguration()
cfg.scriptBaseClass = HubitatStub.name
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
def app = shell.parse(new File(args[0]).text)

def body = { Map r -> r.data ? new JsonSlurper().parseText(r.data) : null }
int fails = 0
def check = { String label, boolean ok -> println((ok ? 'PASS ' : 'FAIL ') + label); if (!ok) fails++ }

// Independent RFC 2104 HMAC-SHA256 (MessageDigest only), first 16 hex chars
def hmac16 = { byte[] k, String msg ->
    def sha = { byte[] b -> java.security.MessageDigest.getInstance('SHA-256').digest(b) }
    byte[] kk = new byte[64]
    System.arraycopy(k, 0, kk, 0, k.length)
    byte[] ipad = kk.collect { (byte) (it ^ 0x36) } as byte[]
    byte[] opad = kk.collect { (byte) (it ^ 0x5c) } as byte[]
    sha((opad as List) + (sha(((ipad as List) + (msg.getBytes('UTF-8') as List)) as byte[]) as List) as byte[]).encodeHex().toString().substring(0, 16)
}
byte[] keyBytes = (1..32).collect { (byte) (it * 7) } as byte[]
String KEY = keyBytes.encodeBase64().toString()
byte[] otherKeyBytes = (1..32).collect { (byte) (255 - it) } as byte[]
def fpOf = { String code -> hmac16(keyBytes, code) }

def lockA = new FakeLock(id: '10', displayName: 'IuA', name: 'IuA', label: 'IuA')
lockA.attrs.maxCodes = 250
lockA.attrs.lockCodes = groovy.json.JsonOutput.toJson([
    '1': [name: 'SBA: Owner', code: '12340001'],
    '3': [name: 'SBA: Code 3', code: '55550003'],
    '4': [name: 'SBA: Empty', code: ''],            // empty
    '12': [name: 'SBM: Cleaner', code: '12340012'],
    '26': [name: 'Above', code: '55550026'],        // above the ceiling
    '30': [name: 'Code 30', code: '55550030'],
    '31': [name: 'Code 31', code: '55550031'],
    '40': [name: 'Legacy', code: '55550040'],       // adopted, not approved
    '41': [name: null, code: '55550041'],           // unnamed
])
def lockB = new FakeLock(id: '11', displayName: 'IuB', name: 'IuB', label: 'IuB')
lockB.attrs.maxCodes = 250
lockB.attrs.lockCodes = groovy.json.JsonOutput.toJson([
    '12': [name: 'SBM: Cleaner', code: '99990012'],
    '13': [name: 'SBM: Numeric', code: 12340012],
])
def lockC = new FakeLock(id: '12', displayName: 'IuC', name: 'IuC', label: 'IuC')
lockC.attrs.lockCodes = groovy.json.JsonOutput.toJson(['1': [name: 'SBA: Owner', code: '12340001']])
def lockOff = new FakeLock(id: '20', displayName: 'IuOff', name: 'IuOff', label: 'IuOff')
lockOff.attrs.lockCodes = groovy.json.JsonOutput.toJson(['1': [name: 'SBA: Owner', code: '12340001']])
List codes = ['12340001', '55550003', '12340012', '55550026', '55550030', '55550031', '55550040', '55550041', '99990012']
app.codeLocks = [lockA, lockB, lockC]
app.locks = [lockA, lockB, lockC, lockOff]
app.state.sbToken = 'ingest-fake'
app.state.ingestUrl = 'https://ingest.test/v1/webhooks/hubitat'
app.state.lockMgmtLastEnabled = true
app.fakeNow = 5000000000L

List responses = []
List sentFps = []
def call = { String handler -> def r = app."${handler}"(); responses << r; r }
def inUse = { String lockId, def fp, Boolean resetLimits = true ->
    if (resetLimits) { app.state.codeInUseTimes = []; app.state.codeInUseLockTimes = [:]; app.state.codeInUseLockDaily = [:] }
    if (fp instanceof String) sentFps << fp
    app.params = [lockId: lockId]
    app.request = [JSON: fp instanceof Map ? fp : [fingerprint: fp]]
    call('apiCodeInUse')
}

app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['10': [adopted: [40]], '11': [:]]]]
check('setup: config without key accepted', call('apiSetLockConfig').status == 200)
def noKey = inUse('10', fpOf('12340001'))
check('no key -> 409 fingerprint_key_missing', noKey.status == 409 && body(noKey).error == 'fingerprint_key_missing')
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['10': [adopted: [40]], '11': [:]], fingerprintKey: KEY]]
check('setup: key accepted', call('apiSetLockConfig').status == 200 && app.state.fingerprintKey == KEY)

check('register: advertises code_in_use', app.CAPABILITIES.contains('code_in_use'))

def r = inUse('10', fpOf('12340001'))
check('200 {lockId, inUse} and nothing else', r.status == 200 && body(r) == [lockId: '10', inUse: true])
check('managed slot code found', body(inUse('10', fpOf('12340012'))).inUse == true)
check('slot 30 code found', body(inUse('10', fpOf('55550030'))).inUse == true)
check('slot 31 code found', body(inUse('10', fpOf('55550031'))).inUse == true)
check('every occupied slot is checked: slot 3 code found', body(inUse('10', fpOf('55550003'))).inUse == true)
check('code above the ceiling found', body(inUse('10', fpOf('55550026'))).inUse == true)
check('unapproved adopted slot code found', body(inUse('10', fpOf('55550040'))).inUse == true)
check('unnamed slot code found', body(inUse('10', fpOf('55550041'))).inUse == true)
check('code on another lock only -> false', body(inUse('10', fpOf('99990012'))).inUse == false && body(inUse('11', fpOf('99990012'))).inUse == true)
check('numeric lockCodes value matches its digits', body(inUse('11', fpOf('12340012'))).inUse == true)
check('lock without slot rules is checked too', body(inUse('12', fpOf('12340001'))).inUse == true)
check('unknown code -> false', body(inUse('10', fpOf('00000000'))).inUse == false)
check('empty slot never matches', body(inUse('10', fpOf(''))).inUse == false)
check('upper-case fingerprint accepted', body(inUse('10', fpOf('12340001').toUpperCase())).inUse == true)
check('fingerprint under another key -> false', body(inUse('10', hmac16(otherKeyBytes, '12340001'))).inUse == false)
lockC.attrs.lockCodes = null
check('lock without codes -> false', body(inUse('12', fpOf('12340001'))).inUse == false)

[[null, 'missing'], ['abc', 'too short'], [fpOf('1') + '0', '17 chars'], ['zzzzzzzzzzzzzzzz', 'not hex'], [' ' + fpOf('1').substring(1), 'leading space'],
 [1234567890123456, 'number'], [[fingerprint: [fpOf('1')]], 'list']].each { fp, why ->
    def bad = inUse('10', fp)
    check("invalid fingerprint (${why}) -> 400", bad.status == 400 && body(bad).error == 'invalid_fingerprint')
}
app.request = [JSON: 'not an object']; app.params = [lockId: '10']; app.state.codeInUseTimes = []; app.state.codeInUseLockTimes = [:]; app.state.codeInUseLockDaily = [:]
check('body not an object -> 400', body(call('apiCodeInUse')).error == 'invalid_fingerprint')
check('unknown lock -> 404', body(inUse('999', fpOf('12340001'))) == [error: 'lock_not_found'])
check('lock not selected for code management -> 404', inUse('20', fpOf('12340001')).status == 404)
String saved = lockA.attrs.lockCodes
lockA.attrs.lockCodes = 'ENCRYPTEDBLOB'
def unread = inUse('10', fpOf('12340001'))
check('unreadable lockCodes -> 409 lock_codes_unreadable', unread.status == 409 && body(unread).error == 'lock_codes_unreadable')
lockA.attrs.lockCodes = saved

// Limits: per lock, and across the app
app.state.codeInUseTimes = []; app.state.codeInUseLockTimes = [:]; app.state.codeInUseLockDaily = [:]; app.state.fingerprintTimes = []
List perLock = (1..10).collect { inUse('10', fpOf('00000000'), false).status }
def limited = inUse('10', fpOf('00000000'), false)
check('per-lock limit -> 429', perLock.every { it == 200 } && limited.status == 429 && body(limited) == [error: 'rate_limited'])
check('another lock still answers', inUse('11', fpOf('00000000'), false).status == 200)
app.params = [lockId: '10']
// Not via call(): the listing returns fingerprints by design.
check('fingerprint listing has its own bucket', app.apiListFingerprints().status == 200)
app.fakeNow += 61000
check('a minute later the lock answers again', inUse('10', fpOf('00000000'), false).status == 200)
check('old entries dropped from the per-lock record', app.state.codeInUseLockTimes.keySet() == ['10'] as Set && app.state.codeInUseLockTimes['10'].size() == 1)
app.fakeNow += 61000
app.state.codeInUseTimes = []; app.state.codeInUseLockTimes = [:]; app.state.codeInUseLockDaily = [:]
List acrossLocks = (1..20).collect { i -> inUse(['10', '11', '12'][i % 3], fpOf('00000000'), false).status }
def appLimited = inUse('12', fpOf('00000000'), false)
check('app-wide limit -> 429', acrossLocks.every { it == 200 } && appLimited.status == 429)
app.fakeNow += 61000

// Key check and a key awaiting approval
String check8 = app.fingerprintKeyCheck(KEY)
def kc = { def v -> [fingerprint: fpOf('12340001'), keyCheck: v] }
check('matching keyCheck -> 200 {lockId, inUse}', body(inUse('10', kc(check8))) == [lockId: '10', inUse: true])
check('matching keyCheck in upper case -> 200', inUse('10', kc(check8.toUpperCase())).status == 200)
String otherCheck = app.fingerprintKeyCheck(otherKeyBytes.encodeBase64().toString())
def mismatch = inUse('10', kc(otherCheck))
check('different keyCheck -> 409 fingerprint_key_mismatch', mismatch.status == 409 && body(mismatch).error == 'fingerprint_key_mismatch')
[['abc', 'too short'], ['zzzzzzzz', 'not hex'], [check8 + '0', '9 chars'], [12345678, 'number'], ['', 'empty']].each { v, why ->
    def bad = inUse('10', kc(v))
    check("invalid keyCheck (${why}) -> 400", bad.status == 400 && body(bad).error == 'invalid_key_check')
}
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['10': [adopted: [40]], '11': [:]], fingerprintKey: otherKeyBytes.encodeBase64().toString()]]
call('apiSetLockConfig')
check('setup: a different key awaits approval', app.state.fingerprintKey == KEY && app.state.fingerprintKeyPending != null)
def pending = inUse('10', fpOf('12340001'))
check('key awaiting approval -> 409 fingerprint_key_pending', pending.status == 409 && body(pending).error == 'fingerprint_key_pending')
check('key awaiting approval -> 409 even with a matching keyCheck', body(inUse('10', kc(check8))).error == 'fingerprint_key_pending')
app.state.remove('fingerprintKeyPending')
check('no key awaiting approval -> answers again', inUse('10', fpOf('12340001')).status == 200)

// Daily limit per lock
app.state.codeInUseTimes = []; app.state.codeInUseLockTimes = [:]; app.state.codeInUseLockDaily = [:]
List daily = (1..100).collect { app.fakeNow += 7000; app.state.codeInUseTimes = []; inUse('10', fpOf('00000000'), false).status }
app.fakeNow += 7000; app.state.codeInUseTimes = []
def dayLimited = inUse('10', fpOf('00000000'), false)
check('daily limit -> 429', daily.every { it == 200 } && dayLimited.status == 429 && body(dayLimited) == [error: 'rate_limited'])
check('daily limit is per lock', inUse('11', fpOf('00000000'), false).status == 200)
check('daily record is a count and window start per lock', app.state.codeInUseLockDaily['10'] == [start: app.state.codeInUseLockDaily['10'].start, count: 100])
app.fakeNow += 86400000L
app.state.codeInUseTimes = []
check('a day after the window started the lock answers again', inUse('10', fpOf('00000000'), false).status == 200 && app.state.codeInUseLockDaily['10'].count == 1)
check('expired windows dropped', app.state.codeInUseLockDaily.keySet() == ['10'] as Set)

// Nothing secret leaves the app
String everything = (app.posts.collect { it.params.body } + app.logged.collect { it.toString() } + responses.collect { it.data }).join('\n')
check('no code, key or candidate fingerprint in any response, log or post',
    !codes.any { everything.contains(it) } && !everything.contains(KEY) && !sentFps.any { everything.contains(it) || everything.contains(it.toUpperCase()) })
check('logged with the lock name only', app.logged.any { it.toString() == 'SignalBricks Telemetry: code-in-use check on IuA' })

// Off -> 404, and turning it off clears the limits and the key
app.state.codeInUseTimes = [app.now()]; app.state.codeInUseLockTimes = ['10': [app.now()]]; app.state.codeInUseLockDaily = ['10': [start: app.now(), count: 1]]
app.lockManagementEnabled = false
app.params = [lockId: '10']; app.request = [JSON: [fingerprint: fpOf('12340001')]]
check('lock management off -> 404', call('apiCodeInUse').status == 404)
app.syncLockManagementEnabled()
check('limits and key cleared on disable', app.state.codeInUseTimes == null && app.state.codeInUseLockTimes == null && app.state.codeInUseLockDaily == null && app.state.fingerprintKey == null)
app.lockManagementEnabled = true
app.syncLockManagementEnabled()
app.request = [JSON: [admin: [from: 1, to: 10], managed: [from: 11, to: 25], locks: ['10': [:]]]]
call('apiSetLockConfig')
check('after re-enable -> 409 fingerprint_key_missing', body(inUse('10', fpOf('12340001'))).error == 'fingerprint_key_missing')

println(fails == 0 ? 'ALL PASS' : "${fails} FAILED")
System.exit(fails == 0 ? 0 : 1)
