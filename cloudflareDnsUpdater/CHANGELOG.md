# Changelog – Cloudflare DNS Updater

## [1.0.0] – 2026-05-06
### Added
- Initial release of Cloudflare DNS Updater for Hubitat
- Public IP detection: `api.ipify.org` for A records, `api6.ipify.org` for AAAA records (with sanity-checks on returned IP family)
- Cloudflare API integration via scoped Bearer token (PUT `/zones/{zone_id}/dns_records/{record_id}`)
- Automatic record ID lookup by zone + name + type, cached in `state.recordId`
- Update-only behavior — refuses to create a new record; warns explicitly when no matching record exists
- Manual `Refresh`, `Update DNS`, `Lookup Record`, and `Clear Record Cache` commands
- Scheduled updates (configurable frequency in minutes)
- Per-update skip when public IP is unchanged (cuts API calls)
- Support for `proxied` (orange cloud) and TTL settings
- Status attribute reflecting current state (`ok`, `not configured`, `record not found`, etc.)
- Hubitat `Notification` event on public-IP change
