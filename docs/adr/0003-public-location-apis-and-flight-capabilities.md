# ADR-0003: Use public mock APIs and capability-based flight control

**Date:** 2026-10-02
**Status:** proposed
**Deciders:** Architecture assistant; pending project-owner adoption

## Context

The user requests root-free location simulation and a journey that reaches an airport, enables airplane mode, arrives at another airport, and restores connectivity. Android's mock-location mechanism and global-settings permissions impose different capabilities on ordinary apps and authorized shell tooling.

## Decision

Use public mock-location APIs and keep their mock markers intact. Implement a guided airplane-mode flow in the APK and optional capability-tested automation over USB ADB. Treat 250 miles as a product suggestion threshold. Precompute the complete journey before any connectivity changes.

## Alternatives considered

- **Root or system modification:** Conflicts with the user's requirement and adds device-specific maintenance.
- **Promise silent airplane control from every APK:** Not supported by ordinary app privileges.
- **Assume a plausible route defeats detection:** Receiving apps can reject mock data or use other signals regardless of route shape.
- **Always disable airplane mode at completion:** Can override the user's original state or later action.

## Consequences

The same journey UI exposes capabilities honestly. Standalone users perform system toggles themselves. USB automation restores only a setting it owns and downgrades to manual control on uncertainty. Flight timing is a simulation, not verified flight availability. Mock acceptance, radio behavior, and cleanup require real-device testing. No generic Life360 compatibility or invisibility claim is permitted.
