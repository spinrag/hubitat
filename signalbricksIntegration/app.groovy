// Groovy scripts do not have constants, so we use functions instead
// Functions that start with get can be referenced as variables,
// i.e. "getFunctionName" can be referenced as "functionName"
String getSbBaseSecureUrl() { 'https://app.signalbricks.com' } // TODO: update to SignalBricks backend URL
String getSbBaseFastUrl() { 'https://api.signalbricks.com' } // TODO: update to SignalBricks backend URL
String getSbAppVersion() { '1.3.0' } // major.minor.patch[-prerelease] 

import groovy.json.JsonOutput

// Define the app
definition(
    name: 'SignalBricks Integration',
    namespace: 'com.signalbricks.hubitat',
    author: 'SignalBricks',
    description: 'SignalBricks Hubitat Integration',
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
            paragraph '<b>SignalBricks</b> Property Automation'
            paragraph 'SignalBricks Hubitat Integration'
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
                    paragraph 'Please configure the locks before connecting to SignalBricks.'
                }
                else if (!state.accessToken) {
                    paragraph 'Please click "Done" to complete setup before connecting to SignalBricks.'
                }
                else {
                    // Always show endpoint and access token for CLI configuration
                    paragraph '<b>Cloud URL:</b> ' + fullApiServerUrl
                    try {
                        paragraph '<b>Local URL:</b> ' + fullLocalApiServerUrl
                    } catch (e) {
                        paragraph '<b>Local URL:</b> (not available)'
                    }
                    paragraph '<b>Access Token:</b> ' + state.accessToken
                    paragraph '<b>App Version:</b> ' + sbAppVersion

                    if (state.sbId) {
                        paragraph '<b>Status:</b> Connected (ID: ' + state.sbId + ')'
                        input(name: 'btnDisconnect', type: 'button', title: 'Disconnect from SignalBricks')
                    } else {
                        paragraph '<b>Status:</b> Not registered (use CLI add-hub to configure)'
                    }
                }
            } else {
                paragraph 'Please configure the locks before connecting to SignalBricks.'
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
        // Disconnect from SignalBricks
        case 'btnDisconnect':
            state.accessToken = null
            state.sbId = null
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
            state.lastVersion = sbAppVersion
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

            sbHttpPostJson('/hubitat', testEvent, { r ->
                log.debug "Test Webhook: ${r.data}"
            })
            break
    }
}

// Mappings define the API endpoints
mappings {
    // Set (or unset) the SignalBricks Id (ORLACT) state variable
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
    state.lastVersion = sbAppVersion

    // Stores current and future bookings
    state.bookings = [:]

    // The API Key
    state.accessToken = createAccessToken()

    // The SignalBricks Id (ORLACT)
    state.sbId = null

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

    if (state.lastVersion != sbAppVersion) {
        state.lastVersion = sbAppVersion
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
    // Only call if we've successfully connected to SignalBricks
    if (atomicState.sbId) {
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

                // Ensure dates are Date objects (atomicState serialization loses types)
                Date checkIn = helperToDate(booking.checkIn)
                Date checkOut = helperToDate(booking.checkOut)

                if (!checkIn || !checkOut) return

                // Only schedule check-in if its in the future
                if (checkIn.after(now)) {
                    if (!schedules.contains(checkIn)) {
                        log.debug "scheduleEvents: schedule reconcileDoorCodes for ${booking}"
                        runOnce(checkIn, 'reconcileDoorCodes', [overwrite: false])
                        schedules.add(checkIn)
                    }
                } else if (!schedules.contains(checkOut)) {
                    log.debug "scheduleEvents: schedule reconcileDoorCodes for ${booking}"
                    runOnce(checkOut, 'reconcileDoorCodes', [overwrite: false])
                    schedules.add(checkOut)
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
        // Normalize both sides to strings for comparison since types may vary
        // (CLI sends strings, atomicState may deserialize as integers)
        def lockIdMatch = lock.id
        Map currentLockBookings = currentBookings
            .findAll { key, booking ->
                booking.lockId.any { it == lockIdMatch || "${it}" == "${lockIdMatch}" }
            }
            .collectEntries { key, booking -> [(booking.id): booking] }

        log.trace "reconcileDoorCodes: current lock bookings ${currentLockBookings}"

        // Get the lock's current codes, as we don't remove non-SignalBricks codes
        def lockCodesRaw = lock.currentValue('lockCodes', true)

        if (!lockCodesRaw) {
            log.debug "reconcileDoorCodes: No lock codes"
            return
        }

        Map lockCodes = parseJson(lockCodesRaw)
        Map sbCodes = helperOnlySbCodes(lockCodes, lock.id)
        log.trace "reconcileDoorCodes: lock codes ${lockCodes}"
        log.trace "reconcileDoorCodes: sb codes ${sbCodes}"

        // Remove all codes that are not in the current bookings
        sbCodes.each { bookingId, lockCode ->
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
                if (!sbCodes[bookingId]) {
                    log.debug "reconcileDoorCodes: trySetCode ${booking}"

                    // Get the next available code position
                    int codePosition = availableCodePositions[index++]

                    // Create the code
                    runIn(++i * 60, 'trySetCode', [ overwrite: false, data: [ lockId: lock.id, codePosition: codePosition, code: booking.code, codeName: codeName ]])
                }
                // Is the booking's code different from the current code 
                else if (sbCodes[bookingId].code != booking.code) {
                    int codePosition

                    // setCode has to be called with an integer
                    if (sbCodes[bookingId].key instanceof String) {
                        codePosition = sbCodes[bookingId].key.toInteger()
                    } else {
                        codePosition = sbCodes[bookingId].key
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
        atomicState.reconcileRetries = 0
        scheduleEvents(bookings)
    } else {
        int retries = (atomicState.reconcileRetries ?: 0) + 1
        int maxRetries = 4
        if (retries > maxRetries) {
            log.warn "reconcileDoorCodes: max retries (${maxRetries}) reached, stopping retry loop"
            atomicState.reconcileRetries = 0
            scheduleEvents(bookings)
        } else {
            log.debug "reconcileDoorCodes: ${i} codes to reconcile (retry ${retries}/${maxRetries})"
            atomicState.reconcileRetries = retries
            runIn(++i * 60, 'reconcileDoorCodes', [ overwrite: true ])
        }
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

    // Track the assignment in state (lock drivers may not preserve code names)
    Map assignments = atomicState.codeAssignments ?: [:]
    String lockKey = "${data.lockId}"
    if (!assignments[lockKey]) assignments[lockKey] = [:]
    assignments[lockKey]["${data.codePosition}"] = [
        bookingId: data.codeName?.find(/ORB\d+/) ?: 'unknown',
        code: data.code,
        codeName: data.codeName
    ]
    atomicState.codeAssignments = assignments
    log.debug "trySetCode: tracked assignment lock ${data.lockId} pos ${data.codePosition}"
}

void tryDeleteCode(Map data) {
    log.debug "tryDeleteCode (${data})"

    def lock = locks.find { lock -> lock.id == data.lockId }

    if (!lock) {
        log.debug "tryDeleteCode: Lock not found"
        return
    }

    lock.deleteCode(data.codePosition)

    // Remove from tracked assignments
    Map assignments = atomicState.codeAssignments ?: [:]
    String lockKey = "${data.lockId}"
    if (assignments[lockKey]) {
        assignments[lockKey].remove("${data.codePosition}")
        atomicState.codeAssignments = assignments
        log.debug "tryDeleteCode: removed assignment lock ${data.lockId} pos ${data.codePosition}"
    }
}

// Send outbound webhook
void webhook(e) {
    log.debug "webhook: ${e.name}"

    // Don't if we don't have an SignalBricks Id
    // Authentication would fail anyway
    if (!state.sbId) {
        log.debug "webhook: No SignalBricks ID"
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

    sbHttpPostJson('/hubitat', payload, { r ->
        log.debug "Webhook: ${r.data}"
    })
}

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
    // Verify our tracked assignments against what the lock confirmed
    try {
        Map data = e.data ? parseJson(e.data) : null
        if (data?.codeNumber) {
            String lockId = "${e.device.id}"
            String pos = "${data.codeNumber}"
            Map assignments = atomicState.codeAssignments ?: [:]
            Map lockAssignments = assignments[lockId] ?: [:]
            Map assignment = lockAssignments[pos]

            if (assignment) {
                if (e.value == 'added' || e.value == 'changed') {
                    log.info "codeChangedHandler: confirmed code at pos ${pos} on lock ${lockId} for ${assignment.bookingId}"
                } else if (e.value == 'failed') {
                    log.warn "codeChangedHandler: FAILED to set code at pos ${pos} on lock ${lockId} for ${assignment.bookingId}"
                    // Remove failed assignment so reconcile retries
                    lockAssignments.remove(pos)
                    assignments[lockId] = lockAssignments
                    atomicState.codeAssignments = assignments
                } else if (e.value == 'deleted') {
                    log.info "codeChangedHandler: confirmed delete at pos ${pos} on lock ${lockId}"
                }
            }
        }
    } catch (Exception ex) {
        log.debug "codeChangedHandler: ${ex.message}"
    }

    webhook(e)
}

void maxCodesHandler(e) {
    webhook(e)
}

void codeLengthHandler(e) {
    webhook(e)
}

// Get the Connect to SignalBricks URL
// This endpoint will create/update the LinkedAccount for this hub,
// And will reach back out to the hub's /register endpoint to set the SignalBricks Id (ORLACT) state variable
String getSbConnectUrl() {
    String connectUrl = sbBaseSecureUrl + '/settings/locks/HubitatConnect'

    if (state.accessToken) {
        connectUrl += '?'
        connectUrl += '&hubId=' + URLEncoder.encode(hubUID)
        connectUrl += '&appId=' + URLEncoder.encode(app.id.toString())
        connectUrl += '&accessToken=' + URLEncoder.encode(state.accessToken)
        connectUrl += '&version=' + URLEncoder.encode(sbAppVersion)
        connectUrl += '&hub=' + URLEncoder.encode(location.hub.name)
    }

    return connectUrl
}

// Send API request to SignalBricks FastApi application
void sbHttpPostJson(String uri, Map body, Closure closure) {
    log.debug "sbHttpPostJson: $uri, $body"
    log.debug "sbBaseFastUrl: $sbBaseFastUrl"

    Map params = [
        uri: sbBaseFastUrl, // Base URL
        path: uri, // Uri appended to base URL
        contentType: 'application/json',
        body: body,
        headers: [
            // Headers used for matching to the correct LinkedAccount,
            // And for authentication
            'X-Hubitat-SB-Id': state.sbId,
            'X-Hubitat-Hub-Id': hubUID,
            'X-Hubitat-App-Id': app.id,
            'X-Hubitat-Access-Token': state.accessToken,
            'X-Hubitat-SB-Version': sbAppVersion,
        ]
    ]

    // Do the request. The closure will be called when the request completes
    // Hubitat appears to do this synchronously, so we don't have to worry about concurrency
    httpPostJson(params, closure)
}

// Create a response for the API request
// Done to include custom headers/response codes
Map sbHttpResponseJson(def data, int status = 200) {
    return [
        renderMethod: true,
        status: status,
        contentType: 'application/json',
        headers: [
            'X-Hubitat-SB-Version': sbAppVersion,
        ],
        data: data ? JsonOutput.toJson(data) : null,
    ]
}

// Register the SignalBricks account with this hub
// SignalBricks needs to already have the hub's Id and Access Token
// So just accept whatever is passed in at face value
Map apiRegister() {
    log.debug "apiRegister: ${request.JSON}"

    try {
        atomicState.sbId = request.JSON.sbId

        // Now that we have the SignalBricks Id, we can setup the webhook subscriptions
        // And schedule the reconcileDoorCodes tasks
        Map bookings = helperGetBookings(atomicState.bookings)
        SyncState(bookings)

        return apiGetInfo()
    } catch (Exception ex) {
        log.error "apiRegister: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Disassociate the SignalBricks account from this hub
Map apiUnregister() {
    log.debug 'apiUnregister'

    try {
        atomicState.sbId = null

        // This will break the connection until the user reconnects
        atomicState.accessToken = null

        // Unsubscribe from events and unschedule tasks
        unschedule()
        unsubscribe()

        return sbHttpResponseJson(null)
    } catch (Exception ex) {
        log.error "apiUnregister: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

 // Get basic hub info, including bookings
Map apiGetInfo() {
    log.debug 'apiGetInfo'

    try {
        return sbHttpResponseJson([
            sbId: atomicState.sbId,
            hubId: hubUID,
            appId: app.id,
            endpoint: fullApiServerUrl,
            version: sbAppVersion,
            location: location.name,
            name: location.hub.name,
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(null),
        ])
    } catch (Exception ex) {
        log.error "apiGetInfo: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Get simplified list of devices
Map apiGetDevices() {
    log.debug 'apiGetDevices'

    try {
        List resp = []

        locks.each { lock ->
            resp << [id: lock.id, name: lock.name, type: lock.typeName, label: lock.label ?: lock.displayName]
        }

        return sbHttpResponseJson(resp)
    } catch (Exception ex) {
        log.error "apiGetDevices: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Get detailed per-device info
Map apiGetDevice() {
    log.debug "apiGetDevice: $params"

    try {
    def deviceId = params.deviceId
    def lock = locks.find { lock -> lock.id == deviceId }

    if (!lock) {
        return sbHttpResponseJson([ error: 'Device not found' ], 404)
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
                        attr.currentValue = parseJson(attr.currentValue)
                        if (attr.name == 'lockCodes' && minCodePosition > 0) {
                            log.debug('Redact Lock Codes')
                            attr.currentValue.each { key, code ->
                                if (key.toInteger() < minCodePosition) {
                                    code.code = '<redacted>'
                                }
                                log.debug("Code: ${key} ${code}")
                            }
                        }
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

    return sbHttpResponseJson(resp)
    } catch (Exception ex) {
        log.error "apiGetDevice: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Execute a command on a device
// Only allows a subset of commands
def apiExecuteCommand() {
    log.debug "apiExecuteCommand: $params ${request.JSON}"

    try {
        def deviceId = params.deviceId
        def lock = locks.find { lock -> lock.id == deviceId }

        if (!lock) {
            return sbHttpResponseJson([ error: 'Device not found' ], 404)
        }

        def command = params.command
        def cmd = lock.supportedCommands.find { cmd -> cmd.name == command }

        if (!cmd) {
            return sbHttpResponseJson([ error: 'Command not found' ], 404)
        }

        // Even if just an empty object, gotta pass in something
        if (request.JSON == null) {
            return sbHttpResponseJson([ error: 'No JSON body' ], 400)
        }

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
                return sbHttpResponseJson([ error: 'Command not supported' ], 400)
        }
    } catch (Exception ex) {
        log.error "apiExecuteCommand: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Sync all bookings (replaces state.bookings), schedule tasks, and reconcile door codes
Map apiSync() {
    try {
        log.debug "apiSync ${request.JSON}"

        atomicState.bookings = helperGetBookings(request.JSON)
        atomicState.reconcileRetries = 0
        scheduleEvents(atomicState.bookings)

        runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
        ])
    } catch (Exception ex) {
        log.error "apiSync: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
            error: ex.message
        ], 500)
    }
}

Map apiSyncPatch() {
    try {
        log.debug "apiSyncPatch ${request.JSON}"

        def combined = atomicState.bookings + request.JSON

        atomicState.bookings = helperGetBookings(combined)
        atomicState.reconcileRetries = 0
        scheduleEvents(atomicState.bookings)

        runIn(10, 'reconcileDoorCodes', [ overwrite: true ])

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
        ])
    } catch (Exception ex) {
        log.error "apiSyncPatch: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([
            bookings: atomicState.bookings,
            nextBooking: helperFindNextBooking(atomicState.bookings),
            error: ex.message
        ], 500)
    }
}

// Sync a single booking, schedule tasks, and reconcile door codes
Map apiSyncBooking() {
    log.debug "apiSyncBooking ${params.bookingId}"

    try {
        params.lockId = request.JSON.lockId

        return apiSyncBookingByLock()
    } catch (Exception ex) {
        log.error "apiSyncBooking: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Sync a single booking, by lockID, schedule tasks, and reconcile door codes
Map apiSyncBookingByLock() {
    log.debug "apiSyncBookingByLock ${params.bookingId} ${params.lockId}"

    try {
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
            booking.code = null
            return sbHttpResponseJson(booking, 202)
        }

        if (helperHasDuplicateCode(params.lockId, atomicState.bookings[key], atomicState.bookings)) {
            return sbHttpResponseJson([ error: "Duplicate code: '${booking.code}'."], 409)
        }

        return sbHttpResponseJson(atomicState.bookings[key])
    } catch (Exception ex) {
        log.error "apiSyncBookingByLock: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Delete a single booking, schedule tasks, and reconcile door codes
Map apiDeleteBooking() {
    log.debug "apiDeleteBooking ${params.bookingId}"

    try {
        params.lockId = request.JSON.lockId

        return apiDeleteBookingByLock()
    } catch (Exception ex) {
        log.error "apiDeleteBooking: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Delete a single booking, schedule tasks, and reconcile door codes
Map apiDeleteBookingByLock() {
    log.debug "apiDeleteBookingByLock ${params.bookingId} ${params.lockId}"

    try {
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

        return sbHttpResponseJson(null)
    } catch (Exception ex) {
        log.error "apiDeleteBookingByLock: ${ex.message}"
        log.trace ex.stackTrace

        return sbHttpResponseJson([ error: ex.message ], 500)
    }
}

// Help functions
// Convert various date representations to Date objects
// Handles: Date, Number (epoch millis), String (via toDateTime)
Date helperToDate(def value) {
    if (value == null) return null
    if (value instanceof Date) return value
    if (value instanceof Number) return new Date(value)
    if (value instanceof String) {
        try {
            return toDateTime(value)
        } catch (e) {
            log.error "helperToDate: could not parse '${value}': ${e.message}"
            return null
        }
    }
    return null
}

String helperGetCodeName(Map booking) {
    String prefix = codeNamePrefix ?: ''
    String orbId = 'ORB' + booking.id
    String guest = booking.guest ?: ''

    if (!guest) return prefix + orbId

    if (idAtEnd) {
        return prefix + guest + '-' + orbId
    }
    return prefix + orbId + '-' + guest
}

// Format booking (ensure keyed by id, dates are objects), and only return future bookings
Map helperGetBookings(Map bookings) {
    log.debug 'helperGetBookings'

    Date now = new Date()

    return bookings.collect { key, booking ->
        log.trace "helperGetBookings state.bookings.collect ${key} ${booking}"
        booking.checkIn = helperToDate(booking.checkIn)
        booking.checkOut = helperToDate(booking.checkOut)
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
boolean helperHasDuplicateCode(String lockId, Map booking, Map bookings = null) {
    log.debug "helperHasDuplicateCode ${lockId} ${booking.id} ${booking.code}"

    def lock = locks.find { lock -> lock.id == lockId }

    if (!lock) {
        return false
    }

    String lockCodesJson = lock.currentValue('lockCodes', true);

    if (!lockCodesJson) {
        return false
    }

    Map lockCodes = parseJson(lockCodesJson)

    String codeName = helperGetCodeName(booking)

    return lockCodes.any { k, existing ->
        if (existing.name == codeName || existing.code != booking.code) {
            return false
        }

        if (existing.name.contains('ORB')) {
            // Match to existing SignalBricks code
            String[] parts = existing.name.split('-')
            String bId = parts[idAtEnd ? 1 : 0]
            if (codeNamePrefix != '' && codeNamePrefix != null) {
                log.debug('Strip prefix')
                String strippedId = bId.substring(bId.indexOf('ORB'))
                bId = strippedId
            } else {
                log.debug('Skip removing prefix')
            }
            String bookingId = bId
            if (bookings && bookings.containsKey(bookingId)) {
                // Only warn if the bookings overlap check-in to check-out
                Map otherBooking = bookings[bookingId]
                log.debug "helperHasDuplicateCode: checking active booking ${otherBooking}"
                // Other booking is already active, so we only need to check that the check-out date is after the check-in date
                return otherBooking.checkOut > booking.checkIn
            }
        }

        return true
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

// Get only the SignalBricks codes based on code name beginning with the booking Id
Map helperOnlySbCodes(Map codes, def lockId = null) {
    log.debug 'helperOnlySbCodes'

    String prefix = codeNamePrefix ?: ''
    Map result = [:]

    // First: check lock's reported code names (works when lock driver preserves names)
    codes.each { key, code ->
        if (code.name?.contains('ORB')) {
            if (prefix && !code.name.startsWith(prefix)) return
            def matcher = (code.name =~ /ORB(\d+)/)
            if (matcher.find()) {
                code.key = key
                String bId = matcher.group(1) // Just the digits, no ORB prefix
                code.bookingId = bId
                result[bId] = code
                log.debug("helperOnlySbCodes: found by name: ${bId} at pos ${key}")
            }
        }
    }

    // Second: check tracked assignments (for lock drivers that don't preserve names)
    if (lockId) {
        Map assignments = atomicState.codeAssignments ?: [:]
        String lockKey = "${lockId}"
        Map lockAssignments = assignments[lockKey] ?: [:]

        lockAssignments.each { pos, assignment ->
            // Extract numeric ID from tracked bookingId (may be "ORB12345" or "12345")
            String bId = assignment.bookingId
            if (!bId) return
            def bMatcher = (bId =~ /(\d+)/)
            if (bMatcher.find()) bId = bMatcher.group(1)
            if (result[bId]) return // Already found by name

            // Verify there is a code at this position on the lock
            // Don't compare code values — some drivers redact codes as ********
            if (codes[pos] || codes["${pos}"]) {
                def lockCode = codes[pos] ?: codes["${pos}"]
                lockCode.key = pos
                lockCode.bookingId = bId
                lockCode.code = assignment.code // Use our tracked code, not the lock's (may be redacted)
                result[bId] = lockCode
                log.debug("helperOnlySbCodes: found by tracking: ${bId} at pos ${pos}")
            }
        }
    }

    return result
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
