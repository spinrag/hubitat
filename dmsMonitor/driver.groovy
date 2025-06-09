
metadata {
	definition (name: "DMS Monitor (Dead Man's Snitch)", namespace: "spinrag", author: "Chris Ogden") {
		capability "Refresh"
		capability "Sensor"
		capability "Presence Sensor"
	}

	preferences {
		section {
			input (
				type: "string",
				name: "endpointUrl",
				title: "Endpoint URL",
				required: true				
			)
			input (
				type: "number",
				name: "snitchInterval",
				title: "Interval",
				required: true,
				defaultValue: 5
			)
			input (
				type: "bool",
				name: "enableDebugLogging",
				title: "Enable Debug Logging?",
				required: true,
				defaultValue: false
			)
		}
	}
}


def log(msg) {
	if (enableDebugLogging) {
		log.debug msg
	}
}


def installed () {
	log.info "${device.displayName}.installed()"
	updated()
}


def updated () {
	log.info "${device.displayName}.updated()"
	
	state.tryCount = 0
	
	unschedule(refresh)
	runIn(1, refresh)
	schedule("0 */${snitchInterval} * ? * *", refresh)
}


def refresh() {
	log "${device.displayName}.refresh()"

	state.tryCount = state.tryCount + 1
	
	if (state.tryCount > 3 && device.currentValue('presence') != "not present") {
		def descriptionText = "${device.displayName} is OFFLINE";
		log descriptionText
		sendEvent(name: "presence", value: "not present", linkText: deviceName, descriptionText: descriptionText)
	}
	
	asynchttpGet("httpGetCallback", [
		uri: endpointUrl,
		timeout: 10
	]);
}


def httpGetCallback(response, data) {
	log.debug "${device.displayName}: httpGetCallback(response, data)"
	log.debug response.getStatus()
	log.debug data
	
	if (response == null || response.class != hubitat.scheduling.AsyncResponse) {
		return
	}
		
	if (response.getStatus() == 200 || response.getStatus() == 202) {
		state.tryCount = 0
		
		if (device.currentValue('presence') != "present") {
			def descriptionText = "${device.displayName} is ONLINE";
			log descriptionText
			sendEvent(name: "presence", value: "present", linkText: deviceName, descriptionText: descriptionText)
		}
	}
}

