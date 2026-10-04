# ADR-0002: Keep activity only in memory and operate offline

**Date:** 2026-10-02
**Status:** proposed
**Deciders:** Architecture assistant; pending project-owner adoption

## Context

The user requires no logging or retained data, no account, ads, or fees. Useful maps and realistic road routes require geographic data. A literal promise that no bytes are ever stored anywhere conflicts with application installation and platform behavior.

## Decision

Retain no user activity or session recovery state. Runtime maps, search, routing, and airport selection operate locally. Static read-only resources may persist under an explicit planning assumption; if declined, use bundled resources and memory-only imports. No online fallback or in-app download service.

## Alternatives considered

- **Encrypted local history:** Still retains user activity and requires deletion/key-management promises.
- **Online maps and routing without our backend:** Providers can receive requests revealing destinations and viewed regions.
- **Persistent recovery journal:** Improves crash continuity but violates the requested retention constraint.

## Consequences

There are no favorites, recent searches, saved routes, remembered devices, or restart recovery. Users may import large static packs. Stopping drops app-owned references but does not securely erase platform memory. SDK caches, backup, webview state, ADB, and OS artifacts must be tested and clearly scoped. The app cannot control third-party consumers' retention. Loss of a flight session can require manual airplane-mode recovery.
