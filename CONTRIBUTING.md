# Contributing

Bug reports, feature requests and pull requests are welcome. For a security problem, don't open an issue: follow [SECURITY.md](SECURITY.md).

## What goes in public issues, PRs and commits

This repository is public. Write issues, pull request descriptions, review comments, commit messages, release notes and READMEs at the level of the README: what changes for a hub owner, why it matters, and how to update and test it.

Never include:

- door codes or PINs (even made-up ones that look real), tokens, credentials or keys, or their formats;
- real property, guest, staff or organisation names; booking, lock, device, hub or app ids; hostnames, IP addresses, ports, internal URLs or file paths of other systems;
- the exact rules, patterns or limits that protect booking codes, or step-by-step descriptions of how a code could stay on a lock, be replayed, revealed or misattributed;
- unfixed weaknesses, residual risks or review findings: report them privately (see [SECURITY.md](SECURITY.md));
- code or details from any private fork.

Security-relevant detail goes through private vulnerability reporting, or the maintainers keep it private (tracked privately). Detailed design notes, edge-case analysis and reviews are never posted as public comments.

## Tests and fixtures

- Test fixtures use obviously fake names, ids and codes.
- Each app's harnesses run in CI on every pull request; see the app's README for how to run them locally.
- When you change an app's version, update its `packageManifest.json` in the same pull request.
