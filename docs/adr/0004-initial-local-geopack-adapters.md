# ADR-0004: Start with local GeoPack adapters and native canvas rendering

**Date:** 2026-10-02
**Status:** accepted for initial implementation
**Deciders:** Implementation assistant within user-authorized build scope

## Context

The architecture made MapLibre and BRouter conditional on privacy feasibility evidence. SDK caches are not yet audited, the sandbox cannot fetch packages, and there is no Android toolchain. The requested features still need concrete implementation with explicit offline coverage and no activity retention.

## Decision

Implement a bounded read-only GeoPack contract, profile-aware graph routing in the phone domain, and Android/desktop canvas rendering without remote assets or SDK caches. Provide a developer OSM XML conversion tool and a clearly labeled synthetic fixture. Keep MapLibre/BRouter as future optional audited adapters, not unimplemented mandatory runtime dependencies.

## Alternatives considered

- **Ship hosted maps/routing:** Would disclose geographic requests and contradict the local/no-cloud requirement.
- **Assert candidate SDKs retain nothing without testing:** Would hide an unresolved privacy gate.
- **Label straight paths as driving routes:** Would misrepresent actual coverage.

## Consequences

The app supports graph routes in imported regional packs and explicit custom paths. It does not ship global street coverage or satellite maps. Pack size/count caps are intentional. The first canvas is a geographic grid with imported roads/polygons; it is not a full cartographic map. Android compilation, real-device behavior, and desktop webview filesystem/network audits remain release gates. Airport routing uses reachable surface access points rather than jumping between graph endpoints and catalog centroids.
