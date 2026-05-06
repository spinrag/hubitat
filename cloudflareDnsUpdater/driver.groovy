import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
	definition(name: "Cloudflare DNS Updater", namespace: "spinrag", author: "Chris Ogden") {
		capability "Actuator"
		capability "Refresh"
		capability "Notification"

		command "updateDNS"
		command "lookupRecord"
		command "clearRecordCache"

		attribute "lastIP", "string"
		attribute "lastUpdated", "string"
		attribute "recordId", "string"
		attribute "status", "string"
	}

	preferences {
		input("apiToken", "password", title: "Cloudflare API Token",
			description: "Scoped token with Zone:DNS:Edit on the target zone only. Never use a Global API Key.",
			required: true)
		input("zoneId", "text", title: "Zone ID",
			description: "Cloudflare dashboard → select zone → Overview → API section (right sidebar).",
			required: true)
		input("recordName", "text", title: "Record Name (FQDN)",
			description: "Fully-qualified hostname of the existing A/AAAA record, e.g. home.example.com.",
			required: true)
		input("recordType", "enum", title: "Record Type",
			options: ["A", "AAAA"], defaultValue: "A", required: true)
		input("proxied", "bool", title: "Cloudflare Proxy (orange cloud)", defaultValue: false)
		input("ttl", "number", title: "TTL (seconds; 1 = Auto)", defaultValue: 1)
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
	if (logEnable) log.debug "Zone: ${zoneId}, Record: ${recordName} (${recordType}), Frequency: ${updateFrequency}m"
	unschedule()
	// Settings may have changed (different record/zone) — drop the cached id
	state.recordId = null
	sendEvent(name: "recordId", value: "")
	initialize()
}

def initialize() {
	if (!apiToken || !zoneId || !recordName) {
		log.warn "Cloudflare DNS Updater: configuration incomplete. Set API token, Zone ID, and Record Name."
		setStatus("not configured")
		return
	}

	def freq = (updateFrequency ?: 15) as Integer
	if (freq <= 0) {
		log.warn "Invalid update frequency '${updateFrequency}'. Must be > 0."
		return
	}
	if (logEnable) log.debug "Scheduling updateDNS() every ${freq} minute(s)"
	schedule("0 0/${freq} * * * ?", updateDNS)

	// Resolve the record ID up front so we can warn loudly if it doesn't exist
	if (!state.recordId) {
		lookupRecord()
	}
}

def refresh() {
	updateDNS()
}

def clearRecordCache() {
	log.info "Clearing cached record ID"
	state.recordId = null
	sendEvent(name: "recordId", value: "")
}

// Look up the DNS record ID by zone + name + type. Cache it in state so future
// updates skip the lookup. Refuses to create the record if it doesn't exist —
// this driver only ever updates an existing record.
def lookupRecord() {
	if (!apiToken || !zoneId || !recordName) {
		log.warn "lookupRecord: missing required settings"
		setStatus("not configured")
		return
	}

	def params = [
		uri: "https://api.cloudflare.com/client/v4/zones/${zoneId}/dns_records",
		query: [name: recordName, type: (recordType ?: "A")],
		headers: [
			"Authorization": "Bearer ${apiToken}",
			"Accept": "application/json"
		],
		contentType: "application/json"
	]

	if (logEnable) log.debug "lookupRecord: GET zones/${zoneId}/dns_records?name=${recordName}&type=${recordType ?: 'A'}"

	try {
		httpGet(params) { resp ->
			if (resp.status != 200) {
				log.error "lookupRecord: Cloudflare returned HTTP ${resp.status}"
				setStatus("lookup failed (HTTP ${resp.status})")
				return
			}

			def data = resp.data
			if (!data?.success) {
				def msg = data?.errors?.collect { it.message }?.join("; ") ?: "unknown error"
				log.error "lookupRecord: Cloudflare API error: ${msg}"
				setStatus("lookup error: ${msg}")
				return
			}

			def matches = data.result ?: []
			if (matches.size() == 0) {
				// EXPLICIT: refuse to create. The user must create the record in Cloudflare first.
				log.warn "lookupRecord: NO RECORD FOUND for '${recordName}' (${recordType ?: 'A'}) in zone ${zoneId}. " +
					"This driver only updates EXISTING records — create the A/AAAA record in the Cloudflare dashboard first, " +
					"then run 'Lookup Record' again."
				setStatus("record not found — create it in Cloudflare first")
				state.recordId = null
				sendEvent(name: "recordId", value: "")
				return
			}
			if (matches.size() > 1) {
				log.warn "lookupRecord: ${matches.size()} records match '${recordName}' (${recordType ?: 'A'}). " +
					"Using the first one (id=${matches[0].id}). Consider tightening the record name."
			}

			def id = matches[0].id
			def existingIp = matches[0].content
			state.recordId = id
			sendEvent(name: "recordId", value: id)
			log.info "lookupRecord: cached record id ${id} (current value: ${existingIp})"
			setStatus("ready (record cached)")
		}
	} catch (Exception e) {
		log.error "lookupRecord: ${e.message}"
		setStatus("lookup exception: ${e.message}")
	}
}

def updateDNS() {
	if (!apiToken || !zoneId || !recordName) {
		log.warn "updateDNS: configuration incomplete"
		setStatus("not configured")
		return
	}

	// IP discovery endpoint depends on record type. For AAAA the hub itself
	// must have IPv6 transit, otherwise api6.ipify.org won't resolve the
	// hub's public v6 address.
	String ipSource = (recordType == "AAAA")
		? "https://api6.ipify.org/"
		: "https://api.ipify.org/"

	if (logEnable) log.debug "updateDNS: fetching public IP from ${ipSource}"

	try {
		httpGet(ipSource) { ipResp ->
			if (ipResp.status != 200) {
				log.warn "updateDNS: failed to retrieve public IP from ${ipSource} (HTTP ${ipResp.status})"
				setStatus("ip lookup failed (HTTP ${ipResp.status})")
				return
			}

			def publicIp = ipResp.getData().text.trim()

			// Sanity-check: did we get the right family back?
			if (recordType == "AAAA" && !publicIp.contains(":")) {
				log.warn "updateDNS: AAAA configured but ipify returned non-IPv6 value '${publicIp}'. " +
					"Hub may not have IPv6 transit; AAAA records require an IPv6-capable hub."
				setStatus("no ipv6 transit")
				return
			}
			if ((recordType ?: "A") == "A" && publicIp.contains(":")) {
				log.warn "updateDNS: A configured but ipify returned IPv6-looking value '${publicIp}'. Aborting."
				setStatus("ip family mismatch")
				return
			}
			if (logEnable) log.debug "updateDNS: detected public IP: ${publicIp}"

			def lastIpStored = state.lastIP
			def ipChanged = (lastIpStored != publicIp)

			// Skip the API call if nothing has changed AND we already have a cached recordId.
			// First-run still goes through the update path so we surface any auth or record-id problems early.
			if (!ipChanged && state.recordId) {
				if (logEnable) log.debug "updateDNS: IP unchanged (${publicIp}), skipping update"
				sendEvent(name: "lastUpdated", value: nowFormatted())
				setStatus("up-to-date")
				return
			}

			if (ipChanged) log.info "updateDNS: IP changed from ${lastIpStored} to ${publicIp}"

			// Make sure we have a record id; lookupRecord will warn if it doesn't exist
			if (!state.recordId) {
				lookupRecord()
				if (!state.recordId) {
					log.warn "updateDNS: cannot update — no record id resolved"
					return
				}
			}

			pushUpdate(publicIp, ipChanged)
		}
	} catch (Exception e) {
		log.error "updateDNS: ${e.message}"
		setStatus("update exception: ${e.message}")
	}
}

private void pushUpdate(String publicIp, boolean ipChanged) {
	def body = [
		type: (recordType ?: "A"),
		name: recordName,
		content: publicIp,
		ttl: ((ttl ?: 1) as Integer),
		proxied: (proxied as Boolean)
	]

	def params = [
		uri: "https://api.cloudflare.com/client/v4/zones/${zoneId}/dns_records/${state.recordId}",
		headers: [
			"Authorization": "Bearer ${apiToken}",
			"Accept": "application/json"
		],
		contentType: "application/json",
		body: JsonOutput.toJson(body)
	]

	if (logEnable) log.debug "pushUpdate: PUT zones/${zoneId}/dns_records/${state.recordId} content=${publicIp}"

	try {
		httpPut(params) { resp ->
			if (resp.status != 200) {
				log.error "pushUpdate: Cloudflare returned HTTP ${resp.status}"
				setStatus("update failed (HTTP ${resp.status})")
				return
			}

			def data = resp.data
			if (!data?.success) {
				def msg = data?.errors?.collect { it.message }?.join("; ") ?: "unknown error"
				log.error "pushUpdate: Cloudflare API error: ${msg}"
				setStatus("update error: ${msg}")
				// Common cause: cached record id no longer exists. Drop it so next run re-resolves.
				if (data?.errors?.any { it.code == 81044 || it.code == 81057 || it.code == 7003 }) {
					log.warn "pushUpdate: clearing cached record id (probably stale)"
					state.recordId = null
					sendEvent(name: "recordId", value: "")
				}
				return
			}

			log.info "pushUpdate: DNS update successful (${recordName} → ${publicIp})"
			sendEvent(name: "lastIP", value: publicIp)
			sendEvent(name: "lastUpdated", value: nowFormatted())
			setStatus("ok")

			if (ipChanged) {
				state.lastIP = publicIp
				sendNotificationEvent("Cloudflare DNS updated: ${recordName} → ${publicIp}")
			}
		}
	} catch (Exception e) {
		log.error "pushUpdate: ${e.message}"
		setStatus("update exception: ${e.message}")
	}
}

private void setStatus(String s) {
	sendEvent(name: "status", value: s)
}

private String nowFormatted() {
	return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}
