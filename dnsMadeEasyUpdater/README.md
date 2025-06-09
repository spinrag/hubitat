# DNS Made Easy Updater (Hubitat Driver)

This custom Hubitat driver updates a DNS Made Easy A record with your current public IP address. It is ideal for networks where your router cannot reliably update dynamic DNS, such as when it reports a non-public IP due to double NAT, CGNAT, or PPPoE interfaces.

---

## ❓ Why Use This?

Many routers — including UniFi Dream Machine, UDM Pro, and other firewall appliances — incorrectly report their WAN IP to DDNS providers. This often happens when:

- The router's WAN interface receives a **private** IP (due to NAT or CGNAT)
- The DDNS update includes `%i`, which passes along the **incorrect interface IP**
- The provider (like DNS Made Easy) requires the correct **public IP**

This driver solves that by:
- Querying your **real public IP** from [ipify.org](https://api.ipify.org)
- Sending it directly to DNS Made Easy via their update API
- Scheduling automatic updates, or allowing on-demand refreshes
- Supporting notifications when your IP changes

---

## 🌐 Features

- Detects public IP using [https://api.ipify.org](https://api.ipify.org)
- Sends secure update request to DNS Made Easy
- Displays:
  - Last public IP
  - Last update time
- Sends Hubitat notification if IP changes
- Manual "Refresh" command for immediate update
- User-defined schedule (e.g., every 15 mins)
- Fully local execution (no cloud control beyond DNS & IP detection)

---

## 🔧 Setup Instructions

1. Go to **Hubitat > Drivers Code**, click **"New Driver"**
2. Paste the contents of `driver.groovy` and save
3. Go to **Devices > Add Virtual Device**
4. Name it (e.g., “DNS Updater”) and assign the `DNS Made Easy Updater` driver
5. Click **"Save Device"**
6. Configure the following preferences:
   - **Username**: DNS Made Easy account or DDNS user
   - **Password**: DNS Made Easy password or DDNS key
   - **Record ID**: See below for how to find this
   - **Update frequency**: In minutes (e.g., `15`)
7. Click **"Save Preferences"**

---

## 🔎 How to Find Your Record ID in DNS Made Easy

To use this driver, you need the **Record ID** (also called the **Dynamic DNS ID**) for the A record you want to update. Here's how to locate it:

1. Log in to your [DNS Made Easy Control Panel](https://cp.dnsmadeeasy.com/).
2. Go to **DNS > Managed DNS** and select the domain you're updating.
3. Locate the **A record** you want to dynamically update.
4. Click the **edit icon** (✏️) next to the record to open its configuration form.
5. Check the box labeled **"Dynamic DNS"** — this must be enabled for dynamic updates.
6. Once checked, a new field labeled **"Dynamic DNS ID"** will appear. This is the numeric **Record ID** you’ll use in the Hubitat driver.

> ⚠️ **If you don’t see the "Dynamic DNS ID" field**:
> - Ensure the record type is **A**
> - Ensure the **Dynamic DNS checkbox is checked**
> - You may need to save the record after checking the box for the ID to appear

Copy that **Record ID** into the driver preferences under `Record ID` in Hubitat.

---

## 🌐 Updating Multiple Records

To update multiple A records:
- Create a **separate Virtual Device** in Hubitat for **each record**
- Assign the same driver to each
- Use different `Record ID` values
- Each device will manage its own scheduled updates independently

You cannot (currently) update multiple records in one driver instance — doing so ensures each record can have its own frequency, name, and notification.

---

## 🔔 Notifications

- This driver supports Hubitat’s `Notification` capability
- When the public IP changes, the driver triggers a `deviceNotification()` event
- Use **Rule Machine**, **Notification app**, or **Dashboard tile** to act on changes

---

## 🧪 Manual Refresh

- Use the **“Refresh”** button on the device page to run an update immediately
- You can also trigger it from dashboards, Rule Machine, or other apps

---

## 📜 License

MIT License. See the `LICENSE` file for details.

---

## 🤝 Contributions

Forks and pull requests are welcome! If you improve support for additional DNS providers or features (like multi-record batching), feel free to contribute.

