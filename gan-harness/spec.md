# LocalSpoof build specification

Build the complete local Android/desktop feature set in [the architecture](../docs/ARCHITECTURE.md) and [implementation plan](../docs/IMPLEMENTATION_PLAN.md). User authorized implementation on 2026-10-02. Keep all runtime activity in memory; no logging, analytics, accounts, ads, or cloud dependencies.

Vertical slices: instant location service; offline pack routes; airport journey; USB control and pairing; desktop map/controller; privacy/security tests; build and distribution instructions.

Do not claim APK/native packaging, real-device background behavior, map SDK audits, or Life360 compatibility when not verified. Android and registry toolchains are unavailable in the current sandbox. Dependency-free Node tests and cached Rust dependencies can be used locally. This harness uses code-only review where native runtime cannot execute, supplemented by browser QA when available.

No git initialization/commits in reserved `.git`; no outside-workspace installs, destructive commands, publication, or live phone mutations during development. The safety-guard skill's host hooks are not installed by this build; existing sandbox restrictions and scoped operations enforce the work boundary.
