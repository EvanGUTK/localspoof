package dev.localspoof.android

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.localspoof.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MobileSavedStoreTest {
    private fun draft() = MobileDraft(mode = Mode.DRIVE, originLat = "1", originLon = "2", destinationLat = "1.2", destinationLon = "2.3", customPath = true,
        stops = listOf(RouteStop(GeoPoint(1.1, 2.1), 12000, "Cafe"), RouteStop(GeoPoint(1.15, 2.2), 34000, "Park")), preparedId = "private-runtime-id", busy = true)
    @Test fun explicitSavePersistsIndependentCopyAndClearRemovesFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MobileSavedStore(context); store.clear()
        val source = draft(); val item = MobileSavedStore.newItem("A trip", "Trips", source)
        assertNull(item.draft.preparedId); assertFalse(item.draft.busy)
        store.save(listOf(item))
        val loaded = MobileSavedStore(context).load().single()
        assertEquals(item.id, loaded.id); assertEquals(source.stops, loaded.draft.stops); assertTrue(loaded.draft.customPath)
        assertNull(loaded.draft.preparedId); assertFalse(loaded.draft.busy)
        assertTrue(MobileSavedStore(context).load().single().draft.destinationLat != source.copy(destinationLat = "5").destinationLat)
        store.clear(); assertTrue(MobileSavedStore(context).load().isEmpty())
    }
    @Test fun exportOmitsRuntimeCredentialsAndUnknownOrOversizedImportRejectsAtomically() {
        val item = MobileSavedStore.newItem("Trip", "", draft())
        val bytes = MobileSavedStore.encode(listOf(item)); val text = bytes.toString(Charsets.UTF_8)
        listOf("private-runtime-id", "preparedId", "busy", "session", "token", "pairing", "controller").forEach { assertFalse(text.contains(it)) }
        val root = Json.parseToJsonElement(text).jsonObject
        val changed = JsonObject(root + ("sessionToken" to JsonPrimitive("private")))
        assertTrue(runCatching { MobileSavedStore.decode(changed.toString().toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.decode(ByteArray(MobileSavedStore.MAX_BYTES + 1)) }.isFailure)
        val store = MobileSavedStore(ApplicationProvider.getApplicationContext()); store.clear(); store.save(listOf(item))
        assertTrue(runCatching { val rejected = MobileSavedStore.decode(changed.toString().toByteArray()); store.save(rejected) }.isFailure)
        assertEquals(item.id, store.load().single().id); store.clear()
    }
    @Test fun duplicateIdsAndInvalidNamesRejectAndPortableFlightAliasesMustAgree() {
        val item = MobileSavedStore.newItem("Trip", "", draft())
        assertTrue(runCatching { MobileSavedStore.encode(listOf(item, item)) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.encode(listOf(item.copy(name = "bad\nname"))) }.isFailure)
        val root = Json.parseToJsonElement(MobileSavedStore.encode(listOf(item)).toString(Charsets.UTF_8)).jsonObject
        val raw = root.getValue("items").jsonArray.first().jsonObject
        val changed = JsonObject(raw + ("flightSpeedMps" to JsonPrimitive(200)))
        val bad = JsonObject(root + ("items" to JsonArray(listOf(changed))))
        assertTrue(runCatching { MobileSavedStore.decode(bad.toString().toByteArray()) }.isFailure)
    }
    @Test fun portableDesktopImportPreservesBoardingUsbPolicyAndDefaultsOnExport() {
        val input = """{"version":1,"items":[{"id":"00000000-0000-4000-8000-000000000001","name":"Flight","group":"Trips","kind":"ROUTE","origin":{"lat":1,"lon":2},"destination":{"lat":20,"lon":30},"mode":"FLIGHT","speedMps":20,"walkSpeedMps":1.8,"driveSpeedMps":20,"planeSpeedMps":250,"dwellSeconds":90,"flightDurationSeconds":7200,"radioPolicy":"USB","departureGroundMode":"INSTANT","arrivalGroundMode":"DRIVE","flightTransitMode":"TIMED"}]}"""
        val items = MobileSavedStore.decode(input.toByteArray())
        assertEquals("90.0", items.single().draft.boardingHoldSeconds)
        assertFalse(items.single().draft.simulationOnly)
        val exported = Json.parseToJsonElement(MobileSavedStore.encode(items).toString(Charsets.UTF_8)).jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals("USB", exported.getValue("radioPolicy").jsonPrimitive.content)
        assertEquals(90.0, exported.getValue("dwellSeconds").jsonPrimitive.double, 0.0)
        assertEquals(1.8, exported.getValue("walkSpeedMps").jsonPrimitive.double, 0.0)
        assertEquals(7200.0, exported.getValue("flightDurationSeconds").jsonPrimitive.double, 0.0)
        assertTrue(runCatching { MobileSavedStore.decode(input.replace("\"speedMps\":20", "\"speedMps\":\"20\"").toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.decode(("[".repeat(33) + "0" + "]".repeat(33)).toByteArray()) }.isFailure)
    }

    @Test fun cruisePortableFieldsRetain125MphAndRejectReversedOrStringInputs() {
        val source = draft().copy(speedMps = 55.88, drivingSpeedMode = DrivingSpeedMode.VARIABLE, driveMinMps = 40.2336, driveMaxMps = 55.88, followRoadLimits = false)
        val bytes = MobileSavedStore.encode(listOf(MobileSavedStore.newItem("Cruise", "", source)))
        val result = MobileSavedStore.decode(bytes).single().draft
        assertEquals(DrivingSpeedMode.VARIABLE, result.drivingSpeedMode)
        assertEquals(55.88, result.driveMaxMps, 0.0); assertEquals(40.2336, result.driveMinMps, 0.0); assertFalse(result.followRoadLimits)
        assertEquals(bytes.toString(Charsets.UTF_8), MobileSavedStore.encode(MobileSavedStore.decode(bytes)).toString(Charsets.UTF_8))
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(runCatching { MobileSavedStore.decode(text.replace("\"driveMinMps\":40.2336", "\"driveMinMps\":56.0").toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.decode(text.replace("\"followRoadLimits\":false", "\"followRoadLimits\":\"false\"").toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.encode(listOf(MobileSavedStore.newItem("Bad", "", source.copy(drivingError = "Invalid range")))) }.isFailure)
    }


    @Test fun explicitVacationAndTypedStopsRoundTripWithoutRuntimeStateOrInventingHotelFees() {
        val hotel = HotelStop(60000, 3600000, 120000, 1, nightlyPrice = 100.0)
        val stops = listOf(RouteStop(GeoPoint(1.1, 2.1), 300000, "Fuel", StopKind.FUEL, fuel = FuelStop(40.0)),
            RouteStop(GeoPoint(1.15, 2.2), hotel.durationMs, "Hotel", StopKind.HOTEL, hotel = hotel))
        val config = MobileVacationDraft(enabled = true, metric = true, tank = "50", consumption = "8", startingMethod = MobileFuelInput.AMOUNT,
            starting = "20", reserve = "5", price = "1.5")
        val bytes = MobileSavedStore.encode(listOf(MobileSavedStore.newItem("Vacation", "Trips", draft().copy(stops = stops, vacation = config))))
        val loaded = MobileSavedStore.decode(bytes).single().draft
        assertEquals(stops, loaded.stops); assertEquals(mobileVacationConfig(config), mobileVacationConfig(loaded.vacation))
        assertNull(loaded.stops.last().hotel!!.fees); assertNull(loaded.stops.last().hotel!!.knownCost)
        val text = bytes.toString(Charsets.UTF_8)
        listOf("completedStops", "editId", "remainingFuelLiters", "refillCash", "provider", "sessionToken").forEach { assertFalse(text.contains(it)) }
        assertTrue(runCatching { MobileSavedStore.decode(text.replace("\"startingFuelLiters\":20.0,", "").toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.decode(text.replace("\"refillTargetLiters\":40.0", "\"refillTargetLiters\":40.0,\"completed\":true").toByteArray()) }.isFailure)
    }
    @Test fun planningPreferencesAreOptionalBoundedUserConfigurationOnly() {
        val bytes = MobileSavedStore.encode(listOf(MobileSavedStore.newItem("Plan", "", draft().copy(planningSource = MobilePlanningSource.OFFLINE, automaticFuelStops = true))))
        val text = bytes.toString(Charsets.UTF_8)
        val loaded = MobileSavedStore.decode(bytes).single().draft
        assertEquals(MobilePlanningSource.OFFLINE, loaded.planningSource); assertTrue(loaded.automaticFuelStops)
        val legacy = text.replace("\"planningSource\":\"OFFLINE\",", "").replace("\"automaticFuelStops\":true,", "")
        assertEquals(MobilePlanningSource.ONLINE, MobileSavedStore.decode(legacy.toByteArray()).single().draft.planningSource)
        assertFalse(MobileSavedStore.decode(legacy.toByteArray()).single().draft.automaticFuelStops)
        assertTrue(runCatching { MobileSavedStore.decode(text.replace("\"OFFLINE\"", "\"REMOTE\"").toByteArray()) }.isFailure)
        assertTrue(runCatching { MobileSavedStore.decode(text.replace("\"automaticFuelStops\":true", "\"automaticFuelStops\":\"true\"").toByteArray()) }.isFailure)
    }

}
