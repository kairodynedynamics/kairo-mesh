# Security policy

KairoMesh is meant to carry emergency calls. A flaw can cost lives, so please report it privately.

## Reporting

- Use **GitHub private vulnerability reporting** on this repository (Security → Report a vulnerability).
- Do not open a public issue, pull request or post about it until a fix is released.
- Include what an attacker can do, the steps to reproduce, and the affected component (JS node, Android, iOS, authority server, triage model, website).

We acknowledge reports within 3 working days and aim to release a fix for critical issues within 30 days. We credit reporters who wish to be named.

## Scope

In scope: the protocol and envelope (`src/crypto.js`, Kotlin and Swift cores), emergency relay and receipts, authority codes and root approval, announcements, the authority server (`src/kurum.js`), the Android and iOS apps.

Of particular interest: reading or forging emergency calls, receipts or announcements; redirecting calls to a fake authority; tracking a device over time; flooding the mesh so real calls are lost; making a real call less urgent.

## Status

Research prototype. The envelope follows the Noise X pattern and matches a published test vector, but the implementation has **not** had an independent security audit; one is planned. Do not rely on it for safety yet.

The test root key in `test/kok-test/` is public on purpose and is trusted only by debug builds. Release builds trust only the production root, whose private key is not in this repository.
