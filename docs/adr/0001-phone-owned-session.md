# ADR-0001: Phone owns the session; desktop controls over USB

**Date:** 2026-10-02
**Status:** proposed
**Deciders:** Architecture assistant; pending project-owner adoption

## Context

The user wants a desktop-controlled Android tool and a standalone sideloaded APK. Ordinary routes should remain active when the desktop disconnects. Android requires its own runtime integration for location injection and background execution.

## Decision

One Kotlin Android runtime owns plan validation, offline routing, timing, and provider injection. Native Android UI and a Tauri desktop UI control that runtime. Desktop communication uses an authorized USB ADB tunnel with ephemeral application pairing. Windows is the proposed first desktop target.

## Alternatives considered

- **Desktop emits every sample:** Centralizes computation, but cable removal or laptop sleep interrupts every mode and cannot support independent APK use.
- **Separate phone and desktop engines:** Supports desktop-only planning, but duplicates timing and route behavior and makes reconnect conflicts harder.
- **Cloud relay or wireless-first control:** Eases remote connectivity, but introduces infrastructure and privacy costs; airplane mode can sever wireless control.

## Consequences

The APK is always required on physical stock phones. Ordinary sessions survive desktop disconnection while the phone process remains alive. Desktop route planning requires a connected phone. Conditional radio operations still require a live capable USB host. ADB trust material and diagnostics require a separate privacy audit. Native map and webview adapters remain subject to feasibility gates.
