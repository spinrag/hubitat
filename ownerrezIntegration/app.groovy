// Groovy scripts do not have constants, so we use functions instead
// Functions that start with get can be referenced as variables,
// i.e. "getFunctionName" can be referenced as "functionName"
String getOrezBaseSecureUrl() { 'https://secure.ownerrez.com' }
String getOrezBaseFastUrl() { 'https://fast.ownerrez.com' }
String getOrezAppVersion() { '1.1.1-rc4-cto-sb' } // major.minor.patch[-prerelease]

import groovy.json.JsonOutput

// Define the app
definition(
    name: 'OwnerRez Integration',
    namespace: 'com.ownerreservations.hubitat',
    author: 'OwnerRez, Inc',
    description: 'OwnerRez Hubitat Integration',
    category: 'Convenience', // Unused
    oauth: true, // Unused
    iconUrl: '', // Unused
    iconX2Url: '', // Unused
    singleInstance: true,
    singleThreaded: true,
)

// Define the App's preferences (i.e. the settings UI)
preferences {
    page(name: 'main', install: true, uninstall: true) {
        section() {
            paragraph '<img alt="OwnerRez Logo" src="https://cdn.orez.io/wc/images/logo-new-green.png">'
            paragraph 'OwnerRez Hubitat Integration'
        }

        // We can only access the locks the user has selected
        section(title: '<h2>Configuration</h2>') {
            input(name: 'locks', type: 'capability.lockCodes', title: 'Locks', submitOnChange: true, multiple: true)
            // enhancements by CTO
            input(name: 'minCodePosition', type: 'number', title: 'Minimum Code Position', submitOnChange: true, defaultValue: 0)
            input(name: 'idAtEnd', type: 'bool', title: 'Booking ID at End?', submitOnChange: true)
            input(name: 'codeNamePrefix', type: 'string', title: 'Prefix for code name', submitOnChange: true, defaultValue: '')
        }

        section(title: 'Connection') {
            if (settings) { // Prevents this code from executing during app install, as it will error
                if (!settings.locks || settings.locks.length == 0) {
                    paragraph 'Please configure the locks before connecting to OwnerRez.'
                }
                // The app's installed()/updated() hooks do not get called unless the user actually clicks done
                else if (!state.accessToken) {
                    paragraph 'Please click "Done" to complete setup before connecting to OwnerRez.'
                }
                // Set during the /settings/locks/hubitatConnect process via an API call
                else if (!state.orezId) {
                    paragraph 'You are not connected to OwnerRez.'
                    href(title: 'Connect to OwnerRez', description: 'Click here to connect to OwnerRez', style: 'external', url: orezConnectUrl)
                }
                else {
                    paragraph 'You are connected to OwnerRez.'
                    paragraph 'OwnerRez Id: ' + state.orezId
                    paragraph 'Endpoint: ' + fullApiServerUrl
                    paragraph 'Access Token: ' + state.accessToken
                    paragraph 'App Version: ' + orezAppVersion
                    href(title: 'Reconnect to OwnerRez', description: 'Click here to connect to OwnerRez', style: 'external', url: orezConnectUrl)
                    input(name: 'btnDisconnect', type: 'button', title: 'Disconnect from OwnerRez')
                }
            } else {
                paragraph 'Please configure the locks before connecting to OwnerRez.'
            }
        }
    }

    // Debug page must be navigated to manually
    page(name: 'debug', title: 'Debug')
}

// Debug page for testing
def debug() {
    dynamicPage(name: 'debug', title: 'Debug') {
        section {
            input(name: 'btbAccessToken', type: 'button', title: 'Refresh Access Token')
            input(name: 'btnTest', type: 'button', title: 'Test Webhook')
            input(name: 'btnReconcile', type: 'button', title: 'Reconcile Door Codes')
            input(name: 'btnReset', type: 'button', title: 'Reset State')
        }
        // Direct links to API endpoints
        section(title: 'Links') {
            String url

            // Hub info
            url = fullApiServerUrl + "/info?access_token=${state.accessToken}"
            href(title: url, style: 'external', url: url)

            // Device List
            url = fullApiServerUrl + "/devices?access_token=${state.accessToken}"
            href(title: url, style: 'external', url: url)

            // Device detail links
            locks.each { lock ->
                url = fullApiServerUrl + "/devices/${lock.id}?access_token=${state.accessToken}"
                href(title: url, style: 'external', url: url)
            }
        }
    }
}

// We only get one button handler function, so we have to switch on the button name
void appButtonHandler(String btnName) {
    log.debug "appButtonHandler: $btnName"

    switch (btnName) {
        // Disconnect from OwnerRez
        case 'btnDisconnect':
            state.accessToken = null
            state.orezId = null
            unschedule()
            unsubscribe()
            break
        // Create/refresh access token (this will break the connection until the user reconnects)
        case 'btbAccessToken':
            state.accessToken = createAccessToken()
            break
        // Manually run the reconcileDoorCodes function
        case 'btnReconcile':
            reconcileDoorCodes()
            break
        case 'btnReset':
            unsubscribe()
            unschedule()
            state.lastVersion = orezAppVersion
            state.bookings = [:]
            break
        // Send a test webhook
        case 'btnTest':
            Map testEvent = [
                name: 'test',
                value: 'test',
                displayName: 'Test Webhook',
                deviceId: null,
                descriptionText: null,
                unit: null,
                type: null,
                data: null
            ]

            orezHttpPostJson('/hubitat', testEvent, { r ->
                log.debug "Test Webhook: ${r.data}"
            })
            break
    }
}

// Mappings define the API endpoints
mappings {
    // Set (or unset) the OwnerRez Id (ORLACT) state variable
    path('/register') {
        action: [
            POST: 'apiRegister',
            DELETE: 'apiUnregister',
        ]
    }

    // Hub and App info, including bookings
    path('/info') {
        action: [
            GET: 'apiGetInfo',
        ]
    }

    // List of devices
    path('/devices') {
        action: [
            GET: 'apiGetDevices',
        ]
    }

    // Detailed device info
    path('/devices/:deviceId') {
        action: [
            GET: 'apiGetDevice',
        ]
    }

    // Execute a command on a device
    path('/devices/:deviceId/:command') {
        action: [
            POST: 'apiExecuteCommand',
        ]
    }

    // Sync all bookings (replaces state.bookings)
    path('/sync') {
        action: [
            PUT: 'apiSync',
            PATCH: 'apiSyncPatch',
        ]
    }

    // Sync a single booking (Legacy)
    path('/sync/:bookingId') {
        action: [
            POST: 'apiSyncBooking',
            PUT: 'apiSyncBooking',
            DELETE: 'apiDeleteBooking',
        ]
    }

    // Sync a single booking by lock
    path('/sync/:bookingId/:lockId') {
        action: [
            POST: 'apiSyncBookingByLock',
            PUT: 'apiSyncBookingByLock',
            DELETE: 'apiDeleteBookingByLock',
        ]
    }
}

// Only gets called when the user clicks "Done" on the main page
void installed() {
    log.debug 'installed'

    // Initialize default state

    // Future use for version checking / update process
    state.lastVersion = orezAppVersion

    // Stores current and future bookings
    state.bookings = [:]

    // The API Key
    state.accessToken = createAccessToken()

    // The OwnerRez Id (ORLACT)
    state.orezId = null

    // Unlikely any reason to call, but just in case, clear out any old hooks
    unsubscribe()
    unschedule()
}

// Called when the settings page is updated (and use clicks done)
void updated() {
    log.debug 'updated'

    // Make sure these defautls are set

    if (!state.bookings) {
        state.bookings = [:]
    }

    if (!state.accessToken) {
        state.accessToken = createAccessToken()
    }

    if (state.lastVersion != orezAppVersion) {
        state.lastVersion = orezAppVersion
    }

    SyncState()
}

// Setup event subscriptions, and any scheduled tasks
void SyncState()
{
    Map bookings = helperGetBookings(atomicState.bookings)
    atomicState.bookings = bookings
    SyncState(bookings)
}

void SyncState(Map bookings)
{
    // Only call if we've successfully connected to OwnerRez
    if (atomicState.orezId) {
        subscribeToEvents()
        scheduleEvents(bookings)
        refreshDoorCodes()
    }
    else {
        unsubscribe()
        unschedule()
    }
}

// Subscribe to events for all locks
void subscribeToEvents() {
    log.debug 'subscribeToEvents'

    // Remove old subscriptions from potentially removed locks
    unsubscribe()

    // Separate handler for each event for future flexibility
    subscribe(locks, 'lock', lockHandler)
    subscribe(locks, 'unlock', lockHandler)
    subscribe(locks, 'lastCodeName', lastCodeNameHandler)
    subscribe(locks, 'lockCodes', lockCodesHandler)
    subscribe(locks, 'codeChanged', codeChangedHandler)
    subscribe(locks, 'maxCodes', maxCodesHandler)
    subscribe(locks, 'codeLength', codeLengthHandler)
}

// Setup scheduled tasks for all bookings
void scheduleEvents(Map bookings) {
    log.debug 'scheduleEvents'

    // Remove old scheduled tasks
    unschedule()

    // Keep track of scheduled times as we add them to avoid simultaneous executions of reconcileDoorCodes
    List schedules = []

    // Schedule tasks
    if (bookings) {
        schedule('0 0 0 * * ?', 'refreshDoorCodes') // Daily at midnight

        // Iterate through all bookings for each lock
        // There's an assumption there can only be one active booking per lock
        Map nextBooking = helperFindNextBooking(bookings)

        if (nextBooking) {
            Date now = new Date()

            // Schedule reconcileDoorCodes for next booking
            // This will add new codes and remove old codes
            nextBooking.each { lockId, booking ->

                // Only schedule check-in if its in the future
                if (booking.checkIn > now) {
                    if (!schedules.contains(booking.checkIn)) {
                        log.debug "scheduleEvents: schedule reconcileDoorCodes for ${booking}"
                        runOnce(booking.checkIn, 'reconcileDoorCodes', [overwrite: false])
                        schedules.add(booking.checkIn)
                    }
                } else if (!schedules.contains(booking.checkOut)) {
                    log.debug "scheduleEvents: schedule reconcileDoorCodes for ${booking}"
                    runOnce(booking.checkOut, 'reconcileDoorCodes', [overwrite: false])
                    schedules.add(booking.checkOut)
                }
            }
        }
    }
}

// Call getCodes() on each lock to ensure Hubitat has the latest codes
void refreshDoorCodes() {
    log.debug 'refreshDoorCodes'

    int i = 0

    locks.each { lock ->
        log.trace "refreshDoorCodes: lock ${lock.name}"

        runIn(60 + (i++ * 90), 'tryRefreshDoorCodes', [overwrite: false, data: [ lockId: lock.id ]])
    }
}

void tryRefreshDoorCodes(Map data) {
    log.debug "tryRefreshDoorCodes ${data}"

    lock = locks.find { lock -> lock.id == data.lockId }

    if (!lock) {
        log.debug "tryRefreshDoorCodes: Lock not found"
        return
    }

    // Varies by lock driver, but this can iterate through all code positions over z-wave/zigbee and can take a while
    lock.getCodes()
}

// Reconcile door codes for all locks
void reconcileDoorCodes() {
    log.debug 'reconcileDoorCodes (no args)'

    Map bookings = helperGetBookings(atomicState.bookings)
    reconcileDoorCodes(bookings)
}

void reconcileDoorCodes(Map bookings) {
    log.debug 'reconcileDoorCodes'

    // We only care about bookings that should be active right now
    Map currentBookings = helperFindCurrentBookings(bookings)

    int i = 0

    // Iterate through all locks
    locks.each { lock ->
        log.trace "reconcileDoorCodes: lock ${lock.id} ${lock.label}"

        // Get current bookings for the current lock
        Map currentLockBookings = currentBookings
            .findAll { key, booking -> booking.lockId.contains(lock.id) }
            .collectEntries { key, booking -> [(booking.id): booking] }

        log.trace "reconcileDoorCodes: current lock bookings ${currentLockBookings}"

        // Get the lock's current codes, as we don't remove non-OwnerRez codes
        Map lockCodes = parseJson(lock.currentValue('lockCodes', true))
        Map orezCodes = helperOnlyOrezCodes(lockCodes)
        log.trace "reconcileDoorCodes: lock codes ${lockCodes}"
        log.trace "reconcileDoorCodes: orez codes ${orezCodes}"

        // Remove all codes that are not in the current bookings
        orezCodes.each { bookingId, lockCode ->
            if (!currentLockBookings[bookingId]) {
                int codePosition

                // deleteCode has to be called with an integer
                if (lockCode.key instanceof String) {
                    codePosition = lockCode.key.toInteger()
                } else {
                    codePosition = lockCode.key
                }

                log.trace "reconcileDoorCodes: tryDeleteCode ${lock.id} ${codePosition}"

                // Remove the code
                runIn(++i * 60, 'tryDeleteCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition ]])
            }
        }

        // Are there any active bookings
        if (currentLockBookings) {
            // Get all available code positions as each one has a static index
            List availableCodePositions = helperFindCodePositions(lock, lockCodes)
            int index = 0

            currentLockBookings.each { bookingId, booking ->
                String codeName = helperGetCodeName(booking)

                // Is the booking missing from the list of codes
                if (!orezCodes[bookingId]) {
                    log.debug "reconcileDoorCodes: trySetCode ${booking}"

                    // Get the next available code position
                    int codePosition = availableCodePositions[index++]

                    // Create the code
                    runIn(++i * 60, 'trySetCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition, code: booking.code, codeName: codeName ]])
                }
                // Is the booking's code different from the current code
                else if (orezCodes[bookingId].code != booking.code) {
                    int codePosition

                    // setCode has to be called with an integer
                    if (orezCodes[bookingId].key instanceof String) {
                        codePosition = orezCodes[bookingId].key.toInteger()
                    } else {
                        codePosition = orezCodes[bookingId].key
                    }

                    // Re-set the code
                    log.debug "reconcileDoorCodes: re-running trySetCode ${codePosition} ${booking}"

                    runIn(++i * 60, 'trySetCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition, code: booking.code, codeName: codeName ]])
                }
            }
        }
    }

    if (i == 0) {
        log.debug 'reconcileDoorCodes: No codes to reconcile'
        scheduleEvents(bookings)
    } else {
        log.debug "reconcileDoorCodes: ${i} codes to reconcile"
        runIn(++i * 60, 'reconcileDoorCodes', [ overwrite: true ])
    }
}

void trySetCode(Map data) {
    log.debug "trySetCode (${data})"

    def lock = locks.find { lock -> lock.id == data.lockId }

    if (!lock) {
        log.debug "trySetCode: Lock not found"
        return
    }

    lock.setCode(data.codePosition, data.code, data.codeName)
}

void tryDeleteCode(Map data) {
    log.debug "tryDeleteCode (${data})"

    def lock = locks.find { lock -> lock.id == data.lockId }

    if (!lock) {
        log.debug "tryDeleteCode: Lock not found"
        return
    }

    lock.deleteCode(data.codePosition)
}

// Send outbound webhook
void webhook(e) {
    log.debug "webhook: ${e.name}"

    // Don't if we don't have an OwnerRez Id
    // Authentication would fail anyway
    if (!state.orezId) {
        log.debug "webhook: No OwnerRez ID"
        unsubscribe()
        unschedule()
        return
    }

    // Normalize the event payload
    Map payload = [
        id: e.id,
        deviceId: e.deviceId,
        name: e.name,
        displayName: e.displayName,
        source: e.source,
        value: e.value,
        data: e.jsonData,
        descriptionText: e.descriptionText,
        isStateChange: e.isStateChange,
        type: e.type,
        date: e.date,
        unit: e.unit,
    ]

    orezHttpPostJson('/hubitat', payload, { r ->
        log.debug "Webhook: ${r.data}"
    })
}

// Separate handler for each event for future flexibility
void lockHandler(e) {
    webhook(e)
}

void lastCodeNameHandler(e) {
    webhook(e)
}

void lockCodesHandler(e) {
    webhook(e)
}

void codeChangedHandler(e) {
    webhook(e)
}

void maxCodesHandler(e) {
    webhook(e)
}

void codeLengthHandler(e) {
    webhook(e)
}

// Get the Connect to OwnerRez URL
// This endpoint will create/update the LinkedAccount for this hub,
// And will reach back out to the hub's /register endpoint to set the OwnerRez Id (ORLACT) state variable
String getOrezConnectUrl() {
    String connectUrl = orezBaseSecureUrl + '/settings/locks/HubitatConnect'

    if (state.accessToken) {
        connectUrl += '?'
        connectUrl += '&hubId=' + URLEncoder.encode(hubUID)
        connectUrl += '&appId=' + URLEncoder.encode(app.id.toString())
        connectUrl += '&accessToken=' + URLEncoder.encode(state.accessToken)
        connectUrl += '&version=' + URLEncoder.encode(orezAppVersion)
        connectUrl += '&hub=' + URLEncoder.encode(location.hub.name)
    }

    return connectUrl
}

// Send API request to OwnerRez's FastApi application
void orezHttpPostJson(String uri, Map body, Closure closure) {
    log.debug "orezHttpPostJson: $uri, $body"
    log.debug "orezBaseFastUrl: $orezBaseFastUrl"

    Map params = [
        uri: orezBaseFastUrl, // Base URL
        path: uri, // Uri appended to base URL
        contentType: 'application/json',
        body: body,
        headers: [
            // Headers used for matching to the correct LinkedAccount,
            // And for authentication
            'X-Hubitat-Orez-Id': state.orezId,
            'X-Hubitat-Hub-Id': hubUID,
            'X-Hubitat-App-Id': app.id,
            'X-Hubitat-Access-Token': state.accessToken,
            'X-Hubitat-Orez-Version': orezAppVersion,
        ]
    ]

    // Do the request. The closure will be called when the request completes
    // Hubitat appears to do this synchronously, so we don't have to worry about concurrency
    httpPostJson(params, closure)
}

// Create a response for the API request
// Done to include custom headers/response codes
Map orezHttpResponseJson(def data, int status = 200) {
    return [
        renderMethod: true,
        status: status,
        contentType: 'application/json',
        headers: [
            'X-Hubitat-Orez-Version': orezAppVersion,
        ],
        data: JsonOutput.toJson(data),
    ]
}

// Register the OwnerRez account with this hub
// OwnerRez needs to already have the hub's Id and Access Token
// So just accept whatever is passed in at face value
Map apiRegister() {
    log.debug "apiRegister: ${request.JSON}"

    atomicState.orezId = request.JSON.orezId

    // Now that we have the OwnerRez Id, we can setup the webhook subscriptions
    // And schedule the reconcileDoorCodes tasks
    Map bookings = helperGetBookings(atomicState.bookings)
    SyncState(bookings)

    return apiGetInfo()
}

// Disassociate the OwnerRez account from this hub
void apiUnregister() {
    log.debug 'apiUnregister'

    atomicState.orezId = null

    // This will break the connection until the user reconnects
    atomicState.accessToken = null

    // Unsubscribe from events and unschedule tasks
    unschedule()
    unsubscribe()
}

 // Get basic hub info, including bookings
Map apiGetInfo() {
    log.debug 'apiGetInfo'

    return orezHttpResponseJson([
        orezId: atomicState.orezId,
        hubId: hubUID,
        appId: app.id,
        endpoint: fullApiServerUrl,
        version: orezAppVersion,
        location: location.name,
        name: location.hub.name,
        bookings: atomicState.bookings,
        nextBooking: helperFindNextBooking(null),
    ])
}

// Get simplified list of devices
Map apiGetDevices() {
    log.debug 'apiGetDevice'

    List resp = []

    locks.each { lock ->
        resp << [id: lock.id, name: lock.name, type: lock.typeName, label: lock.label ?: lock.displayName]
    }

    return orezHttpResponseJson(resp)
}

// Get detailed per-device info
Map apiGetDevice() {
    log.debug "apiGetDevice: $params"

    def deviceId = params.deviceId
    def lock = locks.find { lock -> lock.id == deviceId }

    if (!lock) {
        return orezHttpResponseJson([ error: 'Device not found' ], 404)
    }

    Map resp = [
        id: lock.id,
        name: lock.name,
        type: lock.typeName,
        label: lock.label,
        displayName: lock.displayName,
        // Simplify the attibutes as the data structure is too complex/verbose
        attributes: lock.supportedAttributes.collect { attr ->
        [
            name: attr.name,
            dataType: attr.dataType,
            values: attr.values,
            currentValue: lock.currentValue(attr.name),
        ]}.collect { attr ->
            switch (attr.dataType) {
                case 'JSON_OBJECT':
                    if (attr.currentValue) {
                        //if (attr.name == 'lockCodes' && minCodePosition > 0) {
                            //log.debug('Redact Lock Codes', attr)
                            attr.currentValue = parseJson(attr.currentValue)
                            //attr.currentValue.each { key, code ->
                            //    if (key.toInteger() < minCodePosition) {
                            //        code.code = '<redacted>'
                            //    }
                            //    log.debug("Code: ${key} ${code}")
                            //}
                        //} else {
                          //  attr.currentValue = parseJson(attr.currentValue)
                        //}
                    } else {
                        attr.currentValue = null
                    }
                    break
            }

            return attr
        },
        // Simplify the commands as the data structure is too complex/verbose
        commands: lock.supportedCommands.collect { cmd -> [
            name: cmd.name,
            arguments: cmd.arguments,
            parameters: cmd.parameters.collect { param -> [
                name: param.name,
                type: param.type,
                description: param.description,
            ]},
        ]},
        // Just need the capabilities names
        capabilities: lock.capabilities.collect { cap -> cap.name },
    ]

    return orezHttpResponseJson(resp)
}

// Execute a command on a device
// Only allows a subset of commands
def apiExecuteCommand() {
    log.debug "apiExecuteCommand: $params ${request.JSON}"

    def deviceId = params.deviceId
    def lock = locks.find { lock -> lock.id == deviceId }

    if (!lock) {
        return orezHttpResponseJson([ error: 'Device not found' ], 404)
    }

    def command = params.command
    def cmd = lock.supportedCommands.find { cmd -> cmd.name == command }

    if (!cmd) {
        return orezHttpResponseJson([ error: 'Command not found' ], 404)
    }

    // Even if just an empty object, gotta pass in something
    if (request.JSON == null) {
        return orezHttpResponseJson([ error: 'No JSON body' ], 400)
    }

    try {
        switch (command) {
            case 'lock':
            case 'unlock':
            case 'refresh':
            case 'configure':
                return lock."$command"()
            case 'deleteCode':
                return lock.deleteCode(request.JSON.codePosition)
            case 'setCode':
                return lock.setCode(request.JSON.codePosition, request.JSON.pinCode, request.JSON.name)
            case 'setCodeLength':
                return lock.setCodeLength(request.JSON.pinCodeLength)
            default:
                return orezHttpResponseJson([ error: 'Command not supported' ], 400)
        }
    } catch (e) {
        return orezHttpResponseJson([ error: e.message ], 500)
    }
}

// Sync all bookings (replaces state.bookings), schedule tasks, and reconcile door codes
Map apiSync() {
    log.debug "apiSync ${request.JSON}"

    atomicState.bookings = helperGetBookings(request.JSON)
    scheduleEvents(atomicState.bookings)

    runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

    return orezHttpResponseJson([
        bookings: atomicState.bookings,
        nextBooking: helperFindNextBooking(atomicState.bookings),
    ])
}

Map apiSyncPatch() {
    log.debug "apiSyncPatch ${request.JSON}"

    atomicState.bookings = helperGetBookings(atomicState.bookings + request.JSON)
    scheduleEvents(atomicState.bookings)

    runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

    return orezHttpResponseJson([
        bookings: atomicState.bookings,
        nextBooking: helperFindNextBooking(atomicState.bookings),
    ])
}

// Sync a single booking, schedule tasks, and reconcile door codes
Map apiSyncBooking() {
    log.debug "apiSyncBooking ${params.bookingId}"

    params.lockId = request.JSON.lockId

    return apiSyncBookingByLock()
}

// Sync a single booking, by lockID, schedule tasks, and reconcile door codes
Map apiSyncBookingByLock() {
    log.debug "apiSyncBookingByLock ${params.bookingId} ${params.lockId}"

    Map bookings = atomicState.bookings
    String key = params.bookingId
    Map existing = bookings[key]
    Map booking = request.JSON

    // If the booking is already in the state, merge the lockId
    if (existing) {
        booking = existing + booking
        if (existing.lockId instanceof List) {
            booking.lockId = existing.lockId
        } else {
            booking.lockId = [existing.lockId]
        }
        booking.lockId << params.lockId
    } else {
        booking.lockId = [params.lockId]
    }

    booking.lockId = booking.lockId.unique()
    bookings[key] = booking
    bookings = helperGetBookings(bookings)
    scheduleEvents(bookings)

    atomicState.bookings = bookings

    runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

    // If you try to save a booking that's already passed, it will be removed by helperGetBookings
    if (atomicState.bookings[key] == null) {
        return orezHttpResponseJson([ error: 'Could not save booking.'], 400)
    }

    if (helperHasDuplicateCode(params.lockId, atomicState.bookings[key])) {
        return orezHttpResponseJson([ error: "Duplicate code: '${booking.code}'."], 409)
    }

    return orezHttpResponseJson(atomicState.bookings[key])
}

// Delete a single booking, schedule tasks, and reconcile door codes
void apiDeleteBooking() {
    log.debug "apiDeleteBooking ${params.bookingId}"

    params.lockId = request.JSON.lockId

    apiDeleteBookingByLock()
}

// Delete a single booking, schedule tasks, and reconcile door codes
void apiDeleteBookingByLock() {
    log.debug "apiDeleteBookingByLock ${params.bookingId} ${params.lockId}"

    String key = params.bookingId

    Map bookings = atomicState.bookings.collectEntries { k, booking ->
        if (k == key) {
            booking.lockId = booking.lockId - params.lockId
        }
        return [ k, booking ]
    }
    .findAll { k, booking ->
        return booking.lockId.size() > 0
    }

    atomicState.bookings = helperGetBookings(bookings)
    scheduleEvents(bookings)

    runIn(10, 'reconcileDoorCodes', [ overwrite: true ])
}

// Help functions
String helperGetCodeName(Map booking) {
    String codeName = booking.id

    if (booking.guest)
        if (idAtEnd) {
            codeName = codeNamePrefix + booking.guest + ' -' + codeName
        } else {
            codeName = codeNamePrefix + codeName + '-' + booking.guest
        }
    return codeName
}

// Format booking (ensure keyed by id, dates are objects), and only return future bookings
Map helperGetBookings(Map bookings) {
    log.debug 'helperGetBookings'

    Date now = new Date()

    return bookings.collect { key, booking ->
        log.trace "helperGetBookings state.bookings.collect ${key} ${booking}"
        if (booking.checkIn instanceof String)
            booking.checkIn = toDateTime(booking.checkIn)
        if (booking.checkOut instanceof String)
            booking.checkOut = toDateTime(booking.checkOut)
        return booking
    }
    .findAll { booking ->
        return booking.checkIn >= now || booking.checkOut >= now
    }
    .groupBy { booking ->
        log.trace "helperGetBookings state.bookings.groupBy ${booking}"
        return booking.id
    }
    .collectEntries { k, group ->
        log.trace "helperGetBookings state.bookings.collectEntries ${k} ${group}"
        return [ k, group.inject(group[0] + [lockId: []]) { acc, v -> acc.lockId += v.lockId; acc } ]
    }
}

// Check for duplicate code
boolean helperHasDuplicateCode(String lockId, Map booking) {
    log.debug "helperHasDuplicateCode ${lockId} ${booking.id} ${booking.code}"

    def lock = locks.find { lock -> lock.id == lockId }

    if (!lock) {
        return false
    }

    Map lockCodes = parseJson(lock.currentValue('lockCodes', true))

    String codeName = helperGetCodeName(booking)

    return lockCodes.any { k, existing ->
        return existing.name != codeName && existing.code == booking.code
    }
}

// For each lock, find the next/current booking
Map helperFindNextBooking(Map bookings) {
    log.debug 'helperFindNextBooking'

    if (!bookings) {
        bookings = helperGetBookings(state.bookings)
    }

    // Aggregate next bookings by lockId
    return bookings.inject([:]) { byLock, key, booking ->
        booking.lockId.each { lockId ->
            if (byLock[lockId] == null) {
                byLock[lockId] = booking
            } else if (booking.checkIn < byLock[lockId].checkIn) {
                byLock[lockId] = booking
            }
        }

        return byLock
    }
}

// Find all bookings that are currently active
Map helperFindCurrentBookings(Map bookings) {
    log.debug 'helperFindCurrentBooking'

    Date now = new Date()

    return bookings.findAll { id, booking ->
        if (booking.checkIn <= now && booking.checkOut >= now) {
            log.trace "helperFindCurrentBooking ${booking}"
            return true
        }

        return false
    }
}

// Get only the OwnerRez codes based on code name beginning with the booking Id
Map helperOnlyOrezCodes(Map codes) {
    log.debug 'helperOnlyOrezCodes'

    // Only manage codes carrying THIS app's prefix. Without this guard the app
    // claims EVERY code whose name contains "ORB" — including the SignalBricks
    // integration's codes (different prefix) — and deletes them during reconcile
    // because their booking IDs aren't in this app's state. Mirrors the guard in
    // SignalBricks' helperOnlySbCodes so the two integrations can coexist on the
    // same locks (e.g. OwnerRez prefix "G:" vs SignalBricks "GT:").
    String prefix = codeNamePrefix ?: ''

    return codes
        .findAll { key, code ->
            code.name?.contains('ORB') && (!prefix || code.name.startsWith(prefix))
        }
        .collectEntries { key, code ->
            // Split name on dash to get bookingId
            String[] parts = code.name.split('-')
            code.key = key
            String bId = '<blank>'
            if (parts.length > 1) {
                   bId = parts[idAtEnd ? 1 : 0]
            } else {
                bId = parts[0]
            }
            code.bookingId = bId
            if (codeNamePrefix != '' && codeNamePrefix != null) {
                log.debug('Strip prefix')
                code.bookingId = bId.substring(bId.indexOf('ORB'))
                bId = code.bookingId
            } else {
                log.debug('Skip removing prefix')
            }
            log.debug("Result ${bId}")
            return [ bId, code ]
        }
}

// Find all unused code positions
List helperFindCodePositions(def lock, Map codes) {
    log.debug 'helperFindCodePositions'

    List positions = []

    for (int i = minCodePosition; i <= lock.currentValue('maxCodes'); i++) {
        // The map key is a string, so we have to check for both just to be safe
        if (!codes[i] && !codes["${i}"]) {
            positions << i
        }
    }

    log.trace "helperFindCodePositions ${positions}"

    return positions
}

// EOF
