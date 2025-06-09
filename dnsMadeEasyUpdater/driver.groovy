metadata {
	definition(name: "DNS Made Easy Updater", namespace: "spinrag", author: "Chris Ogden") {
		capability "Actuator"
		capability "Refresh"
		capability "Notification"
		
		command "updateDNS"
		
		attribute "lastIP", "string"
		attribute "lastUpdated", "string"
	}

	preferences {
		input("username", "text", title: "DNS Made Easy Username", required: true)
		input("password", "password", title: "DNS Made Easy Record Password", required: true)
		input("recordId", "text", title: "DNS Record ID", required: true)
		input("updateFrequency", "number", title: "Update Frequency (minutes)", defaultValue: 15)
		input("logEnable", "bool", title: "Enable Debug Logging", defaultValue: true)
	}
}

def installed() {
	log.info "Installed - initializing"
	initialize()
}

def updated() {
	log.info "Updated - reinitializing"
	log.debug "Username: ${username}, Record ID: ${recordId}, Frequency: ${updateFrequency}"
	unschedule()
	initialize()
}

def initialize() {
	if (updateFrequency && updateFrequency.toInteger() > 0) {
		def freq = updateFrequency.toInteger()
		if (logEnable) log.debug "Scheduling updateDNS() to run every ${freq} minutes"
		schedule("0 0/${freq} * * * ?", updateDNS)
	} else {
		log.warn "Invalid update frequency. Must be greater than 0."
	}
}

def refresh() {
	updateDNS()
}

def updateDNS() {
	if (logEnable) log.debug "Fetching public IP from ipify..."
	try {
		httpGet("https://api.ipify.org/") { resp ->
			if (resp.status == 200) {
				def publicIp = resp.getData().text.trim()
				if (logEnable) log.debug "Detected public IP: ${publicIp}"

				def lastIpStored = state.lastIP
				def ipChanged = (lastIpStored != publicIp)

				if (ipChanged && logEnable) log.info "IP has changed from ${lastIpStored} to ${publicIp}"

				def updateUrl = "https://cp.dnsmadeeasy.com/servlet/updateip" +
					"?username=${URLEncoder.encode(username)}" +
					"&password=${URLEncoder.encode(password)}" +
					"&id=${recordId}&ip=${publicIp}"

				httpGet(updateUrl) { updateResp ->
					if (updateResp.status == 200) {
						log.info "DNS update successful: ${updateResp.data}"
						sendEvent(name: "lastIP", value: publicIp)
						sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))

						if (ipChanged) {
							state.lastIP = publicIp
							sendNotificationEvent("DNS IP updated to ${publicIp}")
						}
					} else {
						log.warn "DNS update failed with HTTP ${updateResp.status}"
					}
				}
			} else {
				log.warn "Failed to retrieve public IP. HTTP ${resp.status}"
			}
		}
	} catch (Exception e) {
		log.error "Error during DNS update: ${e.message}"
	}
}

