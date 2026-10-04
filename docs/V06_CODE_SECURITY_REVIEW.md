# LocalSpoof 0.6 code and security review

Reviewed 2026-10-03 using ECC security-review and frontend-design-direction. The desktop implementation owner independently reviewed the root-owned native storage, IPC, network airport validation, and minor-4 bridge changes. This is a source and regression review; it does not claim full physical-phone playback or radio qualification.

## Outcome

No unresolved blocking finding in the reviewed native changes. Three cross-component issues found during review were corrected before packaging:

1. Desktop saved-flight loading originally ignored Android's `planeSpeedMps` alias. The portable decoder now normalizes it to `flightSpeedMps`, preserves optional defaults, rejects conflicting aliases, and converts legacy waypoints plus stop dwell into explicit stops. Regression coverage includes a 200 m/s mobile-exported flight and a 90-second boarding hold.
2. Android saved-flight import originally validated and then discarded `dwellSeconds`. The mobile draft, encoder, decoder, and preparation now preserve the boarding hold. Mobile validation is reported separately in the build handoff.
3. A process-local storage mutex alone permitted two desktop processes to lose a saved-item update. The native store now holds an OS file lock for the complete read-modify-write operation. The lock is acquired only for an explicit mutation, reports contention, and is released when the file handle closes. Loading never creates the profile or lock. A regression exercises independent store owners, contention, and successful mutation after releasing the lock.

## Native storage and authority

- Storage resolves a fixed application-local data directory. Renderer commands accept items or IDs, never arbitrary filesystem paths.
- The persistent JSON is limited to 100 explicitly saved items and 512 KiB. Place coordinates, route drafts, names, and collections have bounded validation. Tokens, revisions, observed location records, arbitrary geometry, and unknown fields are rejected.
- Imported batches are validated before writing. Existing corruption is reported and cannot be silently overwritten by Save or Import. Clear remains an explicit user action.
- Writes use a uniquely created temporary file, flush, and replacement. Failure cleanup removes only the temporary file created by that operation. Existing directory, saved file, and lock file checks reject symlinks and Windows reparse points.
- The OS lock surrounds load, merge/delete, and replacement. It does not serialize activity authority with saved items: saved storage has its own gate, and cannot restore a controller lease or start a route.
- No page-load hook saves activity or loads a journey into playback. A saved item must be selected and previewed before phone playback can begin.

## Bridge and airport policy

- Protocol minor 4 requires approval pairing, live phone location, flight-leg options, live journey controls, and route-stop capabilities. Older companions fail the compatibility check instead of exposing controls that they cannot execute.
- `SetSpeeds` accepts only optional `walkMps`, `driveMps`, and `flightMps`, with finite bounds 0.1–3, 0.1–55, and 30–350 m/s respectively. Empty updates, unknown authority keys, and missing expected revisions are rejected.
- `JumpToNextStop` accepts an empty payload and requires an expected revision. The desktop uses the authenticated phone next-stop target; it cannot supply an arbitrary jump point through this command.
- Credentials remain native. Public Hello and pairing replies copy only the known public fields. Approval stays explicit on the phone and remains tied to its controller channel.
- Both automatic and manual airport choices require a scheduled, open `large_airport`. Native routing validates the exact catalog airport endpoint before granting the airport access radius. The Jackson regional airport (`MKL`) regression prevents the prior nearby regional choice.
- No generic network or filesystem IPC was introduced. Existing provider restrictions, bounded route validation, and offline-mode request boundaries remain in place.

## Desktop checks

- 97 desktop Node regressions passed; TypeScript 6.0.3 passed.
- Source privacy scan passed under the explicit saved-items exception.
- Isolated browser rendering used public Chicago/New York coordinates. 33 checks passed at 1440×900, 1280×840, 1024×640, 900×640, 700×700, and 390×844. Checks cover actual dark worldwide map rendering, honest flight/instant leg geometry, major-airport choices, overflow, settings, perspective control, and desktop primary-action visibility. Four additional checks exercise a synthetic active-control layout at 1280×840 and 900×640, including expanded live-speed controls; these are layout checks, not phone authentication or playback evidence.
- Screenshots and the rendered check receipt are under `.build/v06-desktop-qa/`. No phone GPS coordinates, comparison codes, or credentials were captured in those artifacts.

## Remaining qualification

Native packaging, complete Android unit/lint results, signed-artifact verification, and physical-phone results are recorded in `BUILD_HANDOFF_SOL_6_1.md` and `VALIDATION.md`. Browser layout checks and synthetic runtime regressions do not establish long-duration USB stability, screen-off playback, OEM behavior, or real airplane-mode transitions. Public online map/search providers may retain requests; explicit local saving does not change that provider behavior.
