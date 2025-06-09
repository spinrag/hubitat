# DMS Monitor (Dead Man’s Snitch)

This custom Hubitat driver sends scheduled heartbeat pings to a [Dead Man’s Snitch](https://deadmanssnitch.com) endpoint to confirm the Hubitat hub and internet connection are still functioning. If the snitch does not receive a ping within the expected time window, it alerts you — making this driver a powerful self-monitoring tool for your smart home hub.

---

## ❓ Why Use This?

Hubitat does not have a native way to alert you if it goes offline, crashes, or loses internet. By using this driver with Dead Man’s Snitch, you can:

- Be notified if your **Hubitat hub crashes**, loses power, or internet
- Monitor **internet connectivity** indirectly through heartbeat pings
- Detect if **scheduled tasks stop running** due to internal errors
- Use external alerts (email, SMS, Slack, etc.) via Dead Man’s Snitch

---

## 🌐 Features

- Sends HTTP GET requests to your Dead Man’s Snitch URL on a fixed schedule
- Uses non-blocking `asynchttpGet()` for performance
- Marks itself "offline" if multiple consecutive requests fail
- Optional debug logging
- Refresh button for manual ping

---

## 🔧 Setup Instructions

1. Go to **Hubitat > Drivers Code**, and click **"New Driver"**
2. Paste the contents of `driver.groovy` and save
3. Go to **Devices > Add Virtual Device**
4. Name the device (e.g., `Hub Heartbeat - DMS`)
5. Assign the driver: **DMS Monitor (Dead Man's Snitch)**
6. Configure:
   - **Endpoint URL**: Copy your Snitch URL from [deadmanssnitch.com](https://deadmanssnitch.com)
   - **Heartbeat Interval**: How often to ping, in minutes (e.g., every 5)
   - **Enable Debug Logging** (optional)
7. Click **Save Preferences**

> 💡 Be sure to configure the check-in frequency in Dead Man’s Snitch to match or exceed your polling interval.

---

## 🔄 Manual Trigger

- Click the **Refresh** button on the device page to manually trigger a heartbeat
- Useful for testing configuration

---

## ⚠️ Notes

- This driver does not store state in Dead Man’s Snitch; all notifications come from their service
- If the hub crashes or loses power/internet, Snitch will detect the absence of scheduled pings and alert you

---

## 🧪 Example Use Cases

- Reboot your ISP modem? Confirm the hub comes back online
- Power outage? Know if the UPS died or failed to restart
- Hub lock-up? Detect if automation stops functioning silently

---

## 📜 License

MIT License

---

## 🤝 Contributions

Pull requests are welcome to expand support
