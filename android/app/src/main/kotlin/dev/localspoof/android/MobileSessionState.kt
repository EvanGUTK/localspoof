package dev.localspoof.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.localspoof.domain.*
import dev.localspoof.domain.Mode
import dev.localspoof.domain.GroundLegMode
import dev.localspoof.domain.FlightTransitMode

/** Retained only in RAM across configuration changes; no saved-state or persistence adapter. */
internal class MobileSessionState : ViewModel() {
    val mapOverlay = MobileMapOverlayState()
    var section by mutableStateOf(MobileSection.MAP)
    var draft by mutableStateOf(MobileDraft())
    var preferences by mutableStateOf(MobilePreferences())
    var walkDefaultText by mutableStateOf(mobileFormatSpeed(preferences.walkSpeedMps, preferences.speedUnit))
    var driveDefaultText by mutableStateOf(mobileFormatSpeed(preferences.driveSpeedMps, preferences.speedUnit))
    var settingsError by mutableStateOf<String?>(null)
    var remainingEdit by mutableStateOf<MobileRemainingDraft?>(null)
        private set
    private var planningCancel: () -> Unit = {}
    fun attachPlanningCancellation(callback: () -> Unit) { planningCancel = callback }
    var candidateBudget by mutableStateOf<MobileVacationEstimate?>(null)
    var vehicleCatalog by mutableStateOf(MobileVehicleUi())
    private var beforeEdit: MobileDraft? = null
    private var generation = 0L
    private var preparationSequence = 0L
    private var preparationOwner: Long? = null
    private var invalidWalkDefault = false
    private var invalidDriveDefault = false
    fun invalidate() { candidateBudget = null; planningCancel(); generation++; draft = draft.copy(preparedId = null) }
    data class PreparationToken(val generation: Long, val owner: Long)
    fun beginPreparation(): PreparationToken {
        check(!draft.busy)
        val owner = ++preparationSequence
        preparationOwner = owner
        draft = draft.copy(busy = true, preparedId = null)
        return PreparationToken(generation, owner)
    }
    fun acceptsPreparation(token: PreparationToken): Boolean = token.generation == generation && token.owner == preparationOwner
    fun finishPreparation(token: PreparationToken) {
        if (token.owner == preparationOwner) { preparationOwner = null; draft = draft.copy(busy = false) }
    }
    fun cancelPendingPreparation() { invalidate(); preparationOwner = null; draft = draft.copy(busy = false) }
    private fun preset(mode: Mode): Double = if (mode == Mode.WALK) preferences.walkSpeedMps else preferences.driveSpeedMps
    fun clearActivity() {
        remainingEdit = null; beforeEdit = null
        mapOverlay.viewport = null; mapOverlay.nearby = emptyList(); mapOverlay.follow = false
        cancelPendingPreparation()
        val speed = preset(draft.mode)
        draft = MobileDraft(mode = draft.mode, speedMps = speed, speedText = mobileFormatSpeed(speed, preferences.speedUnit), driveMinText = mobileFormatSpeed(40.2336, preferences.speedUnit), driveMaxText = mobileFormatSpeed(55.88, preferences.speedUnit))
    }
    fun startRemainingEdit(context: MobileRemainingDraft) {
        cancelPendingPreparation()
        if (remainingEdit == null) beforeEdit = draft
        remainingEdit = context
        val request = context.choices
        val controls = request.speedControls
        draft = draft.copy(mode = request.mode, originLat = context.confirmedOrigin.lat.toString(), originLon = context.confirmedOrigin.lon.toString(), originLabel = "Current confirmed position",
            destinationLat = request.destination.lat.toString(), destinationLon = request.destination.lon.toString(), destinationLabel = "Remaining destination",
            speedMps = request.speedMps, speedText = mobileFormatSpeed(request.speedMps, preferences.speedUnit),
            flightSpeedMps = request.flightSpeedMps ?: draft.flightSpeedMps, flightSpeedText = mobileFormatSpeed(request.flightSpeedMps ?: draft.flightSpeedMps, preferences.speedUnit),
            departureId = request.flight?.originAirportId ?: "", arrivalId = request.flight?.destinationAirportId ?: "",
            departureGroundMode = request.flight?.departureGroundMode ?: GroundLegMode.DRIVE, arrivalGroundMode = request.flight?.arrivalGroundMode ?: GroundLegMode.DRIVE,
            flightTransitMode = request.flight?.flightTransitMode ?: FlightTransitMode.TIMED, simulationOnly = request.flight?.simulationOnly ?: false,
            boardingHoldSeconds = ((request.flight?.boardingMs ?: 30000) / 1000).toString(),
            stops = request.stops, automaticFuelStops = draft.automaticFuelStops || request.stops.any { it.id.startsWith("auto_fuel_") }, customPath = false, preparedId = null, busy = false, speedError = null, flightSpeedError = null, drivingError = null,
            drivingSpeedMode = controls.mode, driveMinMps = controls.driveMinMps, driveMaxMps = controls.driveMaxMps,
            driveMinText = mobileFormatSpeed(controls.driveMinMps, preferences.speedUnit), driveMaxText = mobileFormatSpeed(controls.driveMaxMps, preferences.speedUnit), followRoadLimits = controls.followRoadLimits,
            vacation = request.vacation?.let { MobileVacationDraft(enabled = true, metric = true, tank = it.tankLiters.toString(), consumption = it.litersPer100Km.toString(),
                startingMethod = MobileFuelInput.AMOUNT, starting = it.startingFuelLiters.toString(), reserve = it.reserveLiters.toString(), grade = it.fuelGrade, price = it.fuelPricePerLiter?.toString() ?: "", currency = it.currency) } ?: MobileVacationDraft())
        section = MobileSection.JOURNEY
    }
    fun refreshRemainingPhase(choices: PlanRequest, phase: FlightPhase?) {
        val current = remainingEdit ?: return
        if (current.held != null || current.phase == phase) return
        remainingEdit = current.copy(choices = choices, phase = phase)
        draft = draft.copy(departureId = choices.flight?.originAirportId ?: draft.departureId,
            arrivalId = if (phase in setOf(FlightPhase.ARRIVAL_PLACEMENT, FlightPhase.SETTLING, FlightPhase.RESTORE_CONNECTIVITY, FlightPhase.DESTINATION_GROUND, FlightPhase.DESTINATION_PLACEMENT, FlightPhase.COMPLETE)) choices.flight?.destinationAirportId ?: draft.arrivalId else draft.arrivalId,
            preparedId = null)
    }
    fun attachEditHold(context: RemainingEditContext) { remainingEdit = remainingEdit?.copy(held = context) }
    fun releaseEditHold() { remainingEdit = remainingEdit?.copy(held = null) }
    fun finishRemainingEdit(applied: Boolean) {
        cancelPendingPreparation()
        if (!applied) beforeEdit?.let { draft = it.copy(preparedId = null, busy = false) }
        beforeEdit = null; remainingEdit = null
        section = MobileSection.MAP
    }
    fun selectMode(mode: Mode) {
        remainingEdit?.let { edit ->
            if (edit.choices.mode == Mode.FLIGHT && mode != Mode.FLIGHT || edit.choices.mode != Mode.FLIGHT && mode !in setOf(Mode.WALK, Mode.DRIVE) || edit.choices.vacation != null && mode != Mode.DRIVE) return
        }
        invalidate()
        val speed = preset(mode)
        mapOverlay.placeRole = "Destination"; mapOverlay.nearby = emptyList()
        draft = draft.copy(mode = mode, stops = if (mode in setOf(Mode.WALK, Mode.DRIVE)) draft.stops else emptyList(), speedMps = speed, speedText = mobileFormatSpeed(speed, preferences.speedUnit), speedError = null)
    }
    fun selectDrivingMode(mode: dev.localspoof.domain.DrivingSpeedMode) { invalidate(); draft = draft.copy(drivingSpeedMode = mode) }
    fun selectFollowRoadLimits(enabled: Boolean) { invalidate(); draft = draft.copy(followRoadLimits = enabled) }
    fun selectDepartureGroundMode(mode: GroundLegMode) { invalidate(); draft = draft.copy(departureGroundMode = mode) }
    fun selectArrivalGroundMode(mode: GroundLegMode) { invalidate(); draft = draft.copy(arrivalGroundMode = mode) }
    fun selectFlightTransitMode(mode: FlightTransitMode) { invalidate(); draft = draft.copy(flightTransitMode = mode) }
    fun edit(field: MobileField, value: String) {
        if (field !in setOf(MobileField.AIRPORT_QUERY, MobileField.PLACE_QUERY)) invalidate()
        draft = when (field) {
            MobileField.ORIGIN_LAT -> draft.copy(originLat = value, originLabel = "")
            MobileField.ORIGIN_LON -> draft.copy(originLon = value, originLabel = "")
            MobileField.DESTINATION_LAT -> draft.copy(destinationLat = value, destinationLabel = "")
            MobileField.DESTINATION_LON -> draft.copy(destinationLon = value, destinationLabel = "")
            MobileField.FLIGHT_SECONDS -> draft.copy(flightSeconds = value)
            MobileField.BOARDING_HOLD -> draft.copy(boardingHoldSeconds = value)
            MobileField.DEPARTURE -> draft.copy(departureId = value)
            MobileField.ARRIVAL -> draft.copy(arrivalId = value)
            MobileField.AIRPORT_QUERY -> draft.copy(airportQuery = value)
            MobileField.PLACE_QUERY -> draft.copy(placeQuery = value)
            MobileField.FLIGHT_SPEED -> {
                val result = runCatching { mobileParseSpeed(value, preferences.speedUnit, Mode.FLIGHT).also { require(it >= 30.0) } }
                draft.copy(flightSpeedText = value, flightSpeedMps = result.getOrDefault(draft.flightSpeedMps), flightSpeedError = if (result.isFailure) "Use ${mobileFormatSpeed(30.0, preferences.speedUnit)}–${mobileFormatSpeed(350.0, preferences.speedUnit)} ${preferences.speedUnit.label} for flight." else null)
            }
            MobileField.DRIVE_MIN, MobileField.DRIVE_MAX -> {
                val minText = if (field == MobileField.DRIVE_MIN) value else draft.driveMinText
                val maxText = if (field == MobileField.DRIVE_MAX) value else draft.driveMaxText
                val parsed = runCatching {
                    val min = mobileParseSpeed(minText, preferences.speedUnit, Mode.DRIVE)
                    val max = mobileParseSpeed(maxText, preferences.speedUnit, Mode.DRIVE)
                    require(min <= max)
                    min to max
                }.getOrNull()
                draft.copy(driveMinText = minText, driveMaxText = maxText, driveMinMps = parsed?.first ?: draft.driveMinMps,
                    driveMaxMps = parsed?.second ?: draft.driveMaxMps, drivingError = if (parsed == null) "Use a valid minimum no greater than the maximum (up to ${mobileFormatSpeed(56.0, preferences.speedUnit)} ${preferences.speedUnit.label})." else null)
            }
            MobileField.SPEED -> {
                val result = runCatching { mobileParseSpeed(value, preferences.speedUnit, if (draft.mode == Mode.FLIGHT) Mode.DRIVE else draft.mode) }
                draft.copy(speedText = value, speedMps = result.getOrDefault(draft.speedMps), speedError = if (result.isFailure) "Use ${mobileFormatSpeed(0.1, preferences.speedUnit)}–${mobileFormatSpeed(mobileMaximumSpeed(if (draft.mode == Mode.FLIGHT) Mode.DRIVE else draft.mode), preferences.speedUnit)} ${preferences.speedUnit.label} for this mode." else null)
            }
        }
    }
    fun changeSpeedUnit(unit: MobileSpeedUnit) {
        if (draft.speedError != null || draft.flightSpeedError != null || draft.drivingError != null || invalidWalkDefault || invalidDriveDefault) {
            settingsError = "Correct invalid speeds before changing the speed unit."
            return
        }
        preferences = preferences.copy(speedUnit = unit)
        draft = draft.copy(speedText = mobileFormatSpeed(draft.speedMps, unit), flightSpeedText = mobileFormatSpeed(draft.flightSpeedMps, unit), driveMinText = mobileFormatSpeed(draft.driveMinMps, unit), driveMaxText = mobileFormatSpeed(draft.driveMaxMps, unit))
        walkDefaultText = mobileFormatSpeed(preferences.walkSpeedMps, unit)
        driveDefaultText = mobileFormatSpeed(preferences.driveSpeedMps, unit)
        settingsError = null
    }
    fun editDefault(mode: Mode, value: String) {
        if (mode == Mode.WALK) walkDefaultText = value else driveDefaultText = value
        val parsed = runCatching { mobileParseSpeed(value, preferences.speedUnit, mode) }
        if (mode == Mode.WALK) invalidWalkDefault = parsed.isFailure else invalidDriveDefault = parsed.isFailure
        settingsError = if (invalidWalkDefault || invalidDriveDefault) "Enter a valid walking and driving default within the mode's speed limit." else null
        // Even an invalid edit must discard a preview based on the matching preset.
        if (mode == draft.mode || mode == Mode.DRIVE && draft.mode == Mode.FLIGHT) invalidate()
        parsed.getOrNull()?.let { speed ->
            preferences = if (mode == Mode.WALK) preferences.copy(walkSpeedMps = speed) else preferences.copy(driveSpeedMps = speed)
            if (mode == draft.mode || mode == Mode.DRIVE && draft.mode == Mode.FLIGHT) draft = draft.copy(speedMps = speed, speedText = mobileFormatSpeed(speed, preferences.speedUnit), speedError = null)
        }
    }
    fun resetPreferences() {
        invalidate()
        preferences = MobilePreferences()
        invalidWalkDefault = false; invalidDriveDefault = false; settingsError = null
        walkDefaultText = mobileFormatSpeed(preferences.walkSpeedMps, preferences.speedUnit)
        driveDefaultText = mobileFormatSpeed(preferences.driveSpeedMps, preferences.speedUnit)
        val speed = preset(draft.mode)
        draft = draft.copy(speedMps = speed, speedText = mobileFormatSpeed(speed, preferences.speedUnit), speedError = null, driveMinText = mobileFormatSpeed(draft.driveMinMps, preferences.speedUnit), driveMaxText = mobileFormatSpeed(draft.driveMaxMps, preferences.speedUnit), drivingError = null)
    }
}
