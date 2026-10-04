# 104WORK — next-session usability, gas Vacation and map fixes

Updated: 2026-10-03. Status: documentation handoff for tomorrow. The user selected all seven usability additions and requested the gas-only Vacation planner, cost estimates, phone map/follow fixes and dot/route alignment investigation below. Selection establishes the next planned scope; implementation resumes in the next work session. The user subsequently requested this handoff be named **104WORK** and made available through the private Git repository for laptop continuation. Documentation commit/push is in scope; no app code, device action, rebuild or installation is performed today.

The user's two requested journey changes are **change destination during a route** and **change an upcoming airport during a flight journey**. **Follow the dot** and reliable map return are priority usability fixes. All proposed behavior applies to Windows and Android, with desktop-to-Android control remaining the primary workflow. Vacation planning is **gasoline/diesel only for this iteration**; EV/Tesla charging remains deferred.

## Start here tomorrow

1. Reproduce and fix the phone map disappearing after Settings or another screen, then returning to Map. Verify on the actual installed S24 app.
2. Expose and qualify a visible phone Follow/recenter control, including returning to Map during playback; investigate dot/route-line alignment and the desktop map click that clears the active route while flight playback continues. Compare both apps.
3. Implement phase-aware remaining-route edits for destination and airports, retaining progress and required radio gates.
4. Deliver all seven selected usability additions U1–U7 below.
5. Add gas-only Vacation planning, vehicle/starting-fuel inputs, station stops, speed-aware estimated MPG, hotel budgets and total cost/ETA; revise both interfaces as needed.
6. Run focused regressions and independent review, then matching Android/Windows builds and real-device qualification. Update the handoff with actual evidence and limitations.

This sequence prioritizes reliability before more map controls. Sensor spoofing, EV charging, live hotel booking/pricing and a hosted backend are outside this iteration.

## User-reported phone defects — unresolved

**Map disappears on return:** the user reports that opening Settings or leaving Map and returning causes the phone map not to show. This is a new runtime report after the previous fixes; earlier successful map qualification does not resolve it. The defect has not been reproduced in this documentation-only turn, and its cause is not yet established.

Investigate Compose/WebView creation and disposal, network cancellation/recreation, measured viewport height after navigation, map readiness and resize, and replay of the latest geometry/places/confirmed motion state into a new renderer. Treat these as investigation points, not an asserted cause. Returning to Map must restore visible tiles, route and dot without repair, restarting the app, repairing the controller or stopping the journey. Preserve camera/follow state where valid. Show an explicit loading/retry state if the provider actually fails, rather than an unexplained empty map.

**No usable Follow on phone:** the user cannot find/use Follow in the installed phone app. Source has a follow action in the map-key dialog, but this is not proof of a working, discoverable control on the actual device. Expose the clearly visible map control described below and verify that it follows the real submitted dot. Keep Stop and the map usable at small sizes and large font settings.

## Dot is offset from the route line — user screenshot

The user supplied a screenshot showing a blue location dot visibly above a purple dashed route line and reported a difference between Android and desktop alignment. Record this as an unresolved visual defect and compare both apps on the same active journey; do not assume both exhibit identical behavior. No device reproduction or confirmed root cause is claimed in this documentation turn.

Check that the dot and line use the same active journey/phase, geometry, coordinate order, camera projection and confirmed placement generation. Check road-corner interpolation, DOM marker anchor/offset, camera pitch/bearing, route updates and WebView recreation. For flight specifically, compare the engine's spherical/great-circle path with a display line that may contain only the airport endpoints; a straight projected chord need not represent the same geographic path. This is a hypothesis to investigate, not a diagnosed cause.

Draw the route from geometry consistent with actual provider progression, with bounded sampling/antimeridian handling where needed. Do not cosmetically snap a truly off-route submitted point onto the line or move the mock provider to satisfy the renderer. The displayed confirmed dot must represent the acknowledged point; any existing bounded animation stays between confirmed samples and on the applicable path. Preserve truthful stale/jump behavior. Qualify meaningful alignment for both Walk/Drive and timed/jumped flights at different zooms and perspectives, including returning from Settings. Initial physical location before a route starts may correctly be outside the proposed line; that is separate from an active simulation defect.

## Existing behavior and the actual gaps

- Follow location and Fit route exist in source. Android's map-key action does not satisfy the user's reported missing Follow control; actual phone availability, navigation lifecycle and behavior need qualification. Windows has follow controls and disables follow on manual map movement. Reuse the existing mechanism while fixing access and state visibility.
- Live walk/car/plane speed changes, ETA, fixed/variable cruise, next-stop jumps and draft stop editing already exist. They are not new feature proposals.
- Editing a draft can invalidate and prepare another plan, but this is not a dedicated continuous reroute workflow. The current domain commit replaces the whole plan and resets playback; it also rejects replacement when a changed radio state has not been recovered. It must not be reused unchanged for an airborne diversion.
- The phone remains authoritative for submitted location, active phase, radio observations, playback and approvals. The map renderer never moves the provider merely because a destination or airport was selected.

Source references: [desktop follow and planner](../desktop/ui/src/app.ts), [mobile controls](../android/app/src/main/kotlin/dev/localspoof/android/MobileUi.kt), [domain prepare/commit and flight phases](../android/session-domain/src/main/kotlin/dev/localspoof/domain/Session.kt), [current motion handoff](MOTION_SPEED_FLIGHT_HANDOFF_SOL_6_1.md).

## Desktop map click removes the active route — unresolved user report

The user reports clicking the desktop map during an active flight: the **whole route disappeared**, but the phone continued flying to its destination. This is a display/editor ownership defect, not evidence that the phone stopped or accepted a new destination. Record it as a priority regression alongside map return and dot alignment. No device reproduction is claimed in this documentation turn.

A narrow source inspection is consistent with the report: `setEndpoint()` writes the selected draft endpoint and calls `edit()`, which clears shared `route` and `segments` arrays, invalidates the prepared draft and redraws. Those arrays are also used to draw the active journey. This is a concrete investigation lead, not a complete runtime diagnosis. Source changes are deferred to the next implementation session.

Required behavior:

- A normal map click during playback must not erase the active route or silently change the phone's journey. Offer a selected-place pin and explicit **Change destination / Add stop** actions, or stage a change only when an explicit endpoint-picking mode is active.
- Keep **active phone-approved journey geometry** separate from **draft/candidate geometry** and endpoint fields. Invalidating a prepared draft may clear its candidate preview; it must not clear the active route, active ETA, phase, confirmed dot or follow state. Display the active endpoint/airports from the active plan rather than replacing their labels with uncommitted selections.
- Preserve the active route during search, endpoint picking, speed-field drafts, airport selection, preview loading and validation failure. If a candidate is displayed, label and distinguish it visibly from the active journey; never imply that the candidate is already playing.
- Provide **Cancel change / Back to active route**. This discards the candidate and restores the active presentation immediately, without restarting playback, replaying completed legs, changing radios or moving the dot. It must not depend on obtaining another online route.
- Only **Apply change**, followed by the phone's accepted remaining-journey update, can replace the active route and labels. Keep the old presentation if submission fails. Fence late candidate results so they cannot overwrite a newer plan or reappear after Cancel/Stop.
- Reuse the same separation for Drive, Walk and all flight phases, as well as automatic fuel stops and hotel edits. During an airborne diversion, the current flight geometry remains visible until the accepted diversion replaces its future portion; selecting another airport alone does not erase or restart the flight.
- If an already applied edit is undone, use the one-edit undo/replan-from-current-point behavior; do not confuse it with cancelling an unapplied draft or rewinding the airplane/car.
- Active geometry remains bounded and in RAM, keyed to the acknowledged journey/generation. Respect existing Stop, approval-expiry and disconnect privacy rules; genuine loss of authority may clear private state with an explanation. An ordinary map click must not be treated as one of those lifecycle events.

Do not add an automatic movement restart or replan merely to bring the line back. Restore valid active geometry from the retained authoritative plan/snapshot and fix editor/render ownership. Qualify marker animation against the **active** geometry while a candidate is being edited; a cleared draft must not force a moving road dot into straight-line fallback.

## Requested camera improvement: follow the dot

Reuse the existing follow state and expose a single, easy-to-find recenter/follow button on each map. Android should use a 48 dp target in existing map chrome, without another permanent panel or a reduction of the normal 60% clear-map target. Windows should expose an equally clear selected state and tooltip.

One tap recenters and enables following. Manual dragging turns following off; the button visibly changes state. Fit route temporarily disables following, so the camera does not immediately undo the user's overview. Following keeps the dot within the usable map area above the phone sheet or beside the desktop dock. It preserves user zoom and uses bounded camera movement rather than snapping or constantly refitting the whole journey.

Use a fresh, authorized phone location before playback and the successfully submitted simulated dot during playback. A paused or holding dot remains a valid recenter target when fresh. Stale or missing data must not make the camera invent movement; show the existing freshness state and retain manual map navigation. Reopening the map must not add duplicate listeners or camera animations. Follow preference remains session-only unless the user separately approves saved settings.

## Requested feature 1: change destination during a route

In the compact journey actions, provide **Change destination**. Search, choose on the map, or select a local bookmark. Label the candidate **New destination**, show its route and updated ETA, and provide **Apply change** and **Cancel**. Selecting a search result alone does not alter playback.

Default to routing from the current submitted simulated location. Never return to the original origin, use a newly acquired physical GPS fix, or replay completed stops. Preserve mode, speed controls and unvisited stops unless the user explicitly edits them. For a flight journey, keep the current arrival airport unless the user also chooses **Change arrival airport**; explain that changing the final address alone changes its remaining ground leg.

An intentional teleport is a separate explicit **Jump to this place** action. It must identify its target and use the existing acknowledgement and flight/radio restrictions. An ordinary destination change is continuous movement, not a teleport.

Editing and previewing can happen while the existing journey runs. When Apply is requested, obtain phone authorization and a current snapshot, briefly hold the actual current point, and validate/rebuild the remaining route from that held point. Show **Updating route** while this happens. This avoids committing geometry whose start became stale during online planning. The provider holds the confirmed point and reports the appropriate paused/update status; the camera must not disguise this as movement.

Apply the validated suffix atomically, update ETA and route display from the phone's acknowledgement, and resume only if the journey was running and no subsequent user action superseded the update. A previously paused journey stays paused. Preserve current velocity where geometrically valid and use bounded acceleration/braking when the new route requires a different speed. Do not reset to the original target speed or fake a new submitted sample.

If lookup, road access or validation fails, leave the existing route intact. Cancel or failure can resume the original running journey if the operation still owns the temporary hold. Stop, an explicit Pause or a newer edit always wins; an old network reply may never restart playback. Route replacement must not revoke a healthy desktop connection or require enabling USB again.

## Requested feature 2: change an upcoming airport

Provide **Change departure airport** or **Change arrival airport** according to the current flight phase. Show airport name, city where available, code and a clear explanation of which remaining legs will change. Use the same approved catalog policy as the current app: open, scheduled-service major airports (`large_airport`). Do not reintroduce Jackson TN-style small-field defaults or claim an unsupported metropolitan/international classification.

| Current stage | Permitted edit | Result |
|---|---|---|
| Driving to departure / boarding, before the airplane-ON gate | Departure or arrival airport | A departure change rebuilds travel from the current point, then boarding and future legs. An arrival change preserves the completed departure progress and rebuilds affected future legs. |
| Waiting for airplane mode / a provider acknowledgement | Arrival airport may be prepared; apply waits for a stable gate | Do not race an observation, placement acknowledgement or USB radio command. Departure is locked once its gate has begun. |
| In flight | Arrival airport | Divert from the current submitted airborne point to the new airport, then rebuild its final ground leg. Keep the original radio state and ownership of any change. |
| Arrival placement / settling / restoring connectivity | Arrival airport locked | Offer editing the final destination; applying its ground route waits for the required placement/restoration acknowledgements. |
| Driving after landing | Final destination | Do not restart the completed flight. A new flight is a separate explicitly started journey. |

For an airborne diversion, recalculate remaining air distance and ETA using the current plane speed; retain the selected timed/jump behavior and disclose any timing change in the preview. Do not teleport to the old departure airport, replay boarding, turn airplane mode off mid-flight, or forget the original radio restoration target. An explicit flight jump still respects required placement, settling and restoration gates.

Validate every changed airport at the phone boundary and rebuild only affected, uncompleted geometry. A failed diversion leaves the old arrival airport and existing route intact. Airplane-mode guidance remains tied to real engine gates; changing a preview must not emit a second ON notification. These are simulated journeys, not live flight schedules or airline routing.

## Seven selected usability additions

The user selected **all seven**. They remain planned, not implemented by this document update.

| ID | Idea | Concrete user benefit | Priority suggestion |
|---|---|---|---|
| U1 | Add a stop while moving | Search a hotel, restaurant or other place, then choose **Add next stop** without rebuilding completed travel. | High |
| U2 | Edit remaining stops | Remove, reorder or change the wait time of upcoming stops; completed stops cannot be replayed accidentally. Existing next-stop jump stays available. | High |
| U3 | Undo the last destination/airport edit | Keep one prior set of choices in RAM; rebuild back toward those choices from the current dot, rather than rewinding location or restoring an expired controller. | High |
| U4 | Compact journey-stage strip | Show **Drive to airport → Fly → Drive**, the current stage, its ETA and total ETA; tap a future stage to edit it. | Medium |
| U5 | One-shot timed stop | **Pause here for 1 / 5 / 15 minutes**, show a countdown and Cancel/Resume. User interruption, radio waits and Stop prevent automatic restart. | Medium |
| U6 | Change Walk ↔ Drive for the remaining ground route | Recalculate eligible remaining roads from the current dot and show the ETA difference. Preserve separate mode speeds; do not change an airborne stage. | Medium |
| U7 | Clear connection recovery action | Show **Phone playback continues; desktop control disconnected** where confirmed, then a reconnect action. Refresh state after approval without restarting the route; if disconnected, do not claim an unobserved current phone position. | High |

Implement U1–U3 together with the remaining-route machinery, then U4–U7 without adding a permanent row for every action. U7 is connection polish using existing reconnection capabilities, rather than a new transport feature. One-edit undo must also preserve estimated fuel consumed, incurred stop costs and hotel choices unless the user explicitly changes them; it never rewinds a journey or restores fuel.

## Vacation / Road trip mode — gas only

Keep **Walk · Drive · Fly** under Route. Add an optional **Vacation** pill/toggle for Drive that opens the vehicle and stop-planning details. Initial support is a road trip with gasoline or diesel; applying separate car profiles to flight ground legs can follow after the main workflow is qualified. Do not expose a working EV switch in this iteration.

### Vehicle, fuel and starting range

Offer year → make → model → variant/engine where the data supports it. Provide a manual/custom vehicle path for missing models, worldwide gaps or user corrections. Vehicle inputs include tank capacity, baseline average MPG, optional city/highway figures, fuel type/grade, and an editable consumption assumption. Label US MPG, Imperial MPG and L/100 km correctly; internal calculations use consistent distance/volume units.

Assume the user's earlier “three mixes” means **regular, midgrade and premium gasoline**, plus **diesel**. Use regional labels and only grades compatible with the selected vehicle; do not invent compatibility. Fuel type and octane-grade preferences are distinct from efficiency. Manufacturer-required fuel remains a vehicle specification, not a suggestion generated from estimated cost.

**Never assume a full tank.** Require the user to choose one starting-input method:

- Fuel level percentage of entered tank capacity.
- Fuel amount in gallons/litres.
- **Remaining-range mileage**, such as “I have 120 miles left,” with miles/km selection. Convert that estimate to a consistent starting fuel amount using the selected effective MPG/consumption assumption. Disclose the conversion and reject a range implying more fuel than the entered capacity, offering correction rather than silently increasing the tank.

Do not ask for conflicting starting values at once. Capacity and MPG alone do not establish current fuel level. If a full-tank shortcut exists, it must be explicitly selected. Range entry is an estimate, not a readout from an actual car. Once converted, a speed/MPG change updates projected remaining range but must not create more fuel. Permit an explicit user correction during playback and recalculate future stops from the current point.

Include a configurable fuel reserve and refill target/amount, plus an editable refueling stop duration. Reserve is retained at every station arrival and final arrival where coverage permits. If the starting fuel cannot reach a suitable station, show an actionable unreachable-stop result; do not pretend the car can travel there or add an automatic teleport.

### Planner behavior selected by the user

- Calculate range from the **starting level or entered remaining-range mileage**, consumption and a configurable reserve.
- Find reachable stations **along the road route**, including the distance needed to enter and leave them.
- Add compatible gasoline/diesel stops before the reserve is exhausted.
- Include detours and stop durations in ETA and estimated trip cost.
- Hold the simulated dot at the station during the stop, replenish its estimated fuel according to the refill target, then continue.
- Let users replace a station, adjust the refill amount/target and stop duration, and recalculate remaining stops after a destination change.

Search bounded route corridors and candidate sets rather than only near the current map center. A station that looks close across a river or highway is not necessarily reachable: use actual drivable access and detour geometry for consumption, eligibility and ETA. Prefer known compatible fuel and usable access; missing grade, hours or availability stays **Unknown** and is disclosed. Never fabricate a station when no reachable candidate is found.

Keep automatic fuel stops distinct from user-added hotels/places so recalculation replaces only future automatic stops and preserves user choices. Respect route point/stop/request limits; explain plans that exceed supported bounds. Fuel stops are real phone-owned route legs and timed holds with placement acknowledgement, not map-only pins or desktop timers. Refueling begins after arrival acknowledgement and completes once; Pause, Stop, retries or reconnect must not duplicate a refill or cost event. The phone can continue an approved plan independently of the desktop.

### Vehicle catalog and price sources

The vehicle catalog is feasible, but needs honest coverage. FuelEconomy.gov provides year/make/model/options selections, MPG, electric-consumption fields and reference energy prices. Its vehicle data primarily covers the US market. Tank capacities and charging curves require separately verified data or manual input; never invent missing specifications. Electric fields do not enable EV support in this iteration. [Official vehicle-data documentation](https://www.fueleconomy.gov/feg/ws/index.shtml).

Use a versioned reference-price dataset shipped with an app update and/or prices manually entered by the user. Store source, as-of date, region, currency, fuel grade and volume unit with each published reference. Publisher-supplied app updates can refresh the dataset without a hosted backend. Retain the existing release signing identity for updates. New shipped prices must not silently overwrite an explicit user override or change an active trip's budget; offer an explicit recalculation.

Allow separate regular/midgrade/premium/diesel reference prices and per-station overrides where available. Clearly distinguish regional/reference averages, user-entered assumptions and verified station-specific quotes. FuelEconomy.gov's fuel-price endpoint supplies reference values, not live quotes at each station. No worldwide current-pump-price guarantee, paid price feed or hidden account/server dependency is approved. Static geography/vehicle/price resources are separate from private activity data.

### MPG at speed and ongoing estimates

Show estimated effective average MPG alongside current simulated MPH and ETA. Baseline MPG comes from the vehicle record or user input; it is not a measured fuel rate. For variable-speed travel, accumulate fuel by actual simulated road distance using the applicable consumption assumption rather than calculating from the latest speed alone. Holds add time but no driving-distance consumption; any idling model must be separately defined and disclosed.

Support speed-dependent consumption only from a reviewed model with identified inputs, provenance and limits. A standard combined EPA MPG figure is not a verified consumption curve at 90–125 MPH. If a credible curve is unavailable, use an editable constant MPG and label the estimate accordingly instead of inventing a speed penalty. User speed changes trigger a recalculation of future range, stops and cost, preserving already consumed fuel and completed stops. Estimates can change; the remaining-route replacement rules still prevent hidden jumps or stale edits.

### Hotel prices and trip budget

Add optional hotel stops and a hotel budget to Vacation mode. Let users enter an average nightly price, number of nights and currency; allow each chosen hotel to override the nightly estimate and nights. Enter known taxes/fees separately; missing fees are Unknown, not silently treated as a verified zero. Show lodging total, fuel total and combined estimated trip cost alongside ETA. A hotel stop's planned stay duration affects journey ETA independently of the number of paid nights.

Map/place results supply locations, not verified room rates. Any bundled regional hotel average must have a justified source, region and as-of date; otherwise ask for the user's estimate. No live booking API, reservation, room availability or hotel-price scraping is approved. Never present an invented rate as a hotel quote. Automatic overnight hotel placement is not required for the initial version: users add a hotel or budget nights explicitly.

Separate **estimated fuel value consumed** from **estimated cash spent on trip refills**, because the starting tank may have been paid for earlier and the final tank may retain purchased fuel. Label which number contributes to the selected trip-budget view and avoid double counting. Preview shows full-trip estimates; playback separates consumed/incurred estimates from remaining estimates. Route edits retain prior estimated spending and recompute only future travel/stops. Unknown prices must produce a partial/unknown cost, not a falsely complete total. Do not sum different currencies without an explicit reviewed conversion or user-supplied rate.

Example output labels, not fabricated live data: **ETA · Estimated average MPG · Fuel estimate · Hotel estimate · Estimated total**. Tapping the estimate opens the assumptions, price source/date, stop costs and remaining fuel/range. Costs are informational simulated-trip estimates; the app neither purchases fuel nor books hotels.

### Android and Windows UI/UX revision

Rework both interfaces where needed instead of stacking every new field on the map. Keep the phone map prominent and reliable after navigation. Use an expandable Vacation details sheet on Android and a collapsible details area on Windows for vehicle, starting fuel/range, reserve, prices and hotel budget. The always-visible journey summary stays compact with ETA, selected cost/MPG information and the existing playback controls. Full estimates and editable assumptions live in details; small screens and large font use a compact summary rather than clipping a long metric row.

Keep a visible Follow/recenter control, selected state and reachable Stop. The stage strip can include fuel/hotel stops and their durations. A station action should clearly say **Replace fuel stop**, and a destination edit should show its new ETA and budget before Apply. Name unlabeled estimates explicitly; MPG and cost must not look like real vehicle telemetry. Preserve dark styling, accessibility targets, keyboard/inset behavior and the normal minimum clear-map area in both active and idle states.

### Local architecture and deferred EV research

Extend the shared planning model with a vehicle configuration, starting-fuel method, remaining fuel, reserve, refill target, typed automatic fuel stops and price/budget assumptions. Windows supplies bounded candidate routes/station information; Android validates and owns the active energy/hold progression. Keep renderer calculations display-only. Cross-platform input and saved-item schemas must agree before users can explicitly save a vehicle/trip profile.

Profiles and assumptions remain in RAM unless explicitly saved locally; no fuel/route/hotel activity history, application logs, accounts or cloud storage is added. Online station/route requests require disclosure of additional providers and their logging. Offline support uses available installed station/routing resources and reports missing coverage.

**EV/Tesla charging is deferred.** The prior charging reference is retained only for future research: Open Charge Map does not guarantee live charger occupancy, so a later EV feature would treat availability and charging duration as estimates unless verified current data supports them. It is not a gasoline-price source or a dependency of this gas-only implementation. [Open Charge Map FAQ](https://community.openchargemap.org/t/frequently-asked-questions-faq/221).

## Implementation boundary for the next work session

Introduce a phase-aware operation to prepare and replace the **remaining** journey. Do not ship a UI-only workaround around the current whole-plan commit/reset. Extend the domain/runtime contract and authenticated desktop command path together; use a matching protocol capability/version if required. Exact command names and wire fields are to be defined during implementation review.

The update context should bind the operation to the active journey, controller generation, revision, phase and confirmed origin. Ordinary provider ticks do not authorize a candidate to overwrite a later Stop, phase change or replacement. Preserve completed-leg progress, pause intent, radio ownership, speed controls and local consent. Serialize updates with placement/radio acknowledgements. Explicit Stop remains reachable throughout planning.

Retain only the active plan, bounded candidate and optional one-edit undo choices in RAM. No automatic route history, activity logging, cloud storage or account is added. Local bookmarks remain explicitly saved configuration. Online reroutes use existing disclosed providers and bounded requests; offline reroutes require the installed routing coverage. Neither mode promises every address or worldwide offline routing.

Windows and Android should use the same actions and outcomes. When the desktop supplies an online route, the phone validates it. Phone-side search or preview is not proof that standalone Android online rerouting exists; audit its actual route-planning support and either supply an approved connected-desktop planning path or clearly explain unavailable coverage. Do not silently substitute straight-line travel for a requested road route.

## Acceptance cases for later implementation

1. Follow while moving, paused, holding, dragging, fitting and receiving stale data; camera movement never changes provider location.
2. Change destination at high speed without returning to origin, skipping preserved stops, replaying completed legs or creating a hidden teleport.
3. Preview while moving, then Apply from a newer confirmed point; route start and phone state agree.
4. Cancel or fail a reroute; retain the old plan and respect the user's latest Pause/Stop intent.
5. Change departure during ground travel, and divert arrival while airborne; retain required radio state and original restoration ownership.
6. Race changes with a flight gate, delayed placement, disconnect, Stop or another edit; stale completion cannot advance/restart a journey.
7. Reject an ineligible airport or unreachable ground access with an actionable explanation.
8. Retain unit preferences, cruise controls, meaningful current speed and revised ETA through an accepted edit.
9. Match Android and Windows behavior, preserve mobile map space/accessibility, and distinguish local/device observations from mock or layout tests.
10. Undo rebuilds from the current point; no rewind, restored approval, radio bypass or persisted activity history.
11. Navigate Map → Settings → Map and Map → another screen → Map repeatedly during IDLE, READY, ACTIVE, PAUSED and HOLDING; verify visible map, route/dot replay, camera/follow state and healthy playback/USB control. Include keyboard, orientation, background/reopen and online failure/retry cases. Source existence or tile-request success alone does not establish a visible map.
12. Find and tap Follow on the actual S24; verify submitted-dot tracking, drag disabling follow, Fit overview, recentering, paused/holding/stale behavior and navigation return without duplicate listeners.
13. Choose each starting-fuel method; reject missing, negative, over-capacity or conflicting values. Selecting a vehicle never fills the tank automatically. Range input converts once into estimated fuel rather than regenerating fuel on a speed change.
14. Confirm reserve-aware fuel stops use road distance including station detours, compatible fuel and refill duration. An unreachable station, missing coverage or unknown grade/price must remain explicit.
15. Refill exactly once through acknowledgement, timed hold, Pause/Resume, reconnect and Stop; phone progression survives desktop disconnection and never resets consumed fuel.
16. Change speed, destination, stop order or station midway; preserve progress and spent fuel/cost, update future stops/ETA/budget and reject stale completions.
17. Distinguish reference, manual and verified station prices; include date/region/currency/units, preserve manual overrides through app-data updates and avoid silently repricing an active trip.
18. Enter hotel averages and per-hotel overrides, nights, duration and fees; check totals/ETA and unknown fees. Separate fuel-consumption value from refill cash spending; no double counting or invented complete totals.
19. Verify any speed/MPG model against reviewed reference cases and bounds; without suitable data, disclose the constant editable MPG fallback. Changing speed does not make assumed consumption measured telemetry.
20. Test compact Android/Windows cost/MPG/ETA summaries, accessible large font, narrow screens and input insets without obscuring the map or Stop. EV charging remains unavailable.
21. Compare the actual submitted point, rendered dot and route geometry on both apps during road corners and flight legs; confirm alignment through navigation return, zoom, bearing, pitch and the antimeridian. An initial physical DEVICE dot outside a proposed route is not itself a simulation alignment failure.
22. On a paired active flight, click elsewhere on the desktop map; verify the route remains visible and phone destination/phase stay unchanged. Repeat for Drive/Walk, paused/holding playback, airport picking, fuel/hotel edits and the phone map. Candidate previews stay separate; Cancel restores the active view, failure leaves it intact, and Apply switches only after the phone accepts. Late previews, Cancel, Stop and disconnect cannot restore an obsolete or unauthorized route.

## Laptop continuation and what Git preserves

Repository: [EvanGUTK/localspoof](https://github.com/EvanGUTK/localspoof), **private**, branch `main`. This canonical handoff is `docs/104WORK.md`; the repository name remains `localspoof`. Source, tests, build scripts, lockfiles, packaged source assets/catalogs, architecture and handoffs are in Git. The latest documentation must be committed and pushed before declaring laptop continuation ready.

Excluded local material includes `.build/` QA receipts/captures, `.tooling/`, `node_modules/`, Gradle/Rust caches, generated APKs/Windows installers under `release/`, SDK paths/local properties, signing keys and encrypted passwords. Links to excluded receipts remain local evidence references and will not resolve in a fresh clone. The source repository is not a full machine backup and does not carry phone/controller pairing state.

On the laptop, install Git/GitHub CLI as needed and authenticate as an account with access to the private repo. Clone with:

```powershell
gh auth login
gh repo clone EvanGUTK/localspoof
Set-Location localspoof
```

Open that folder in Codex and start with: **“Read docs/104WORK.md and docs/BUILD_HANDOFF_SOL_6_1.md. Continue the planned work, starting with map return, phone Follow and dot/route alignment. Inspect current source before changing it.”** Git provides the source and this written context; do not assume signing in alone copies this machine's workspace or build tools.

For builds, use [Windows toolchains](WINDOWS_TOOLCHAINS.md), [Android build notes](ANDROID_BUILD.md) and [desktop build notes](DESKTOP_BUILD.md). This machine has Java 17 for compilation and a separate Java 21 runtime for Android unit tests, Android SDK/platform tools, Node, Rust/MSVC and Tauri tooling. Verify availability on the laptop, regenerate dependencies/builds there and adapt local paths rather than copying old `.build/` scripts with machine-specific paths. Phone QA requires connecting/authorizing the laptop and approving its desktop controller.

Future release APK updates must use the existing signing identity. Transfer the signing key and usable password separately through a secure method if release signing is needed on the laptop; neither belongs in Git. The encrypted password file was protected with Windows user-specific DPAPI and is not a portable password backup for another machine/user. Debug builds cannot replace the installed original-signer release as though they were signed updates.

An alternative is configured remote access to this original computer, where its tools and files stay available; it must remain online. Supported Codex/ChatGPT remote connections and explicit chat handoff can retain a conversation/host context, but they are a configured workflow, not automatic Git/workspace replication. Availability depends on the client and rollout. [Official remote connection and chat-handoff documentation](https://developers.openai.com/codex/remote-connections). No remote access or chat transfer is configured by this documentation task.

Sensor spoofing remains deferred at the user's request. This handoff makes no additional Life360 compatibility claims. All seven usability additions and gas-only Vacation scope are selected for future work; map-return, missing Follow, dot/route alignment and desktop map-click route disappearance reports are unresolved. Today's changes are documentation and its requested GitHub synchronization, not application implementation.
