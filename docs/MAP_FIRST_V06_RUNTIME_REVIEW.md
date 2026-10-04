# 0.6 runtime and protocol review

Independent mobile/UI owner source review of the runtime/domain owner's changes, 2026-10-03. Build evidence must be reported separately by root; this review is not a hardware qualification.

Reviewed `Session.kt`, `RuntimeModel.kt`, `PhoneControlServer.kt`, `WireJson.kt`, protocol v1.4 controls, and `LiveJourneyTest` cases.

No blocking issue found in the final reviewed runtime control paths:

- SetSpeeds validates separate walking 0.1–3, ground 0.1–55 and flight 30–350 m/s. It retains distance, elapsed movement, dwell consumption and radio ownership, changes revision and invalidates staged previews. Pause freezes movement; Resume reanchors the tick clock. Estimates include remaining edge limits and dwell time, with arrival time unavailable while paused or externally gated.
- JumpToNextStop is available only during active travel, without a pending placement or stop dwell. Ground jump targets a declared route vertex and waits for provider acknowledgement before moving internal route distance and consuming dwell. Flight jump is confined to origin ground, flight interval or final ground. Boarding, radio confirmation, settling and restoration remain barriers. The final placement also waits for acknowledgement.
- PhoneLocation receives simulation metadata only after framework provider injection succeeds. Submission failure clears the live location, fails the coordinator, cleans providers and disarms the phone. Planned coordinator points may describe a requested jump, but the displayed live dot is taken from submitted phone metadata.
- Dispatcher ownership, approved token/session/channel and current revision precede mutation. Exact SetSpeeds/Jump retries keep only a public acknowledgement, regenerate current authenticated status and return before lease authorization, so retry does not mutate or renew the lease. Stop revokes authority and clears acknowledgement/resource state.
- Online stop indices are bounded, ordered, internal route vertices and within 250 m of the requested stop. Custom paths require 0.01 m consistency. Existing endpoint and airport access bounds remain; no hidden connector is fabricated. Offline routes join actual route legs and reject discontinuity.
- Legacy flight duration derives a speed constrained to 30–350 m/s; explicit bounded flight speed takes precedence. Major-airport policy requires scheduled, open large airports on the phone and desktop.

Finite tests cover position-preserving speed edits, paused changes, declared-stop jump acknowledgement/dwell, protected radio phases, invalid speed/Stop, forged stop indices, airborne speed edits and legacy duration bounds. Actual consumer location acceptance, screen-off/OEM behavior, complete flight/radio transitions and sustained transport remain pending physical qualification.

Integration findings addressed during implementation: independent invalid plane/car draft errors; strict portable saved numeric/string types and bounded UTF-8/depth parsing; boarding hold retention; imported USB policy preserved on export while phone-local preparation uses disclosed manual steps; stop lists cleared when switching to Teleport/Fly. These UI/storage changes were reviewed by root separately.
