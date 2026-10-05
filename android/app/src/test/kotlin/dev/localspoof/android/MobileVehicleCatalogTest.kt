package dev.localspoof.android

import dev.localspoof.domain.FuelGrade
import org.junit.Assert.*
import org.junit.Test

class MobileVehicleCatalogTest {
    @Test fun singletonAndArrayMenusDecodeWithoutInventedEntries() {
        assertEquals(listOf(VehicleMenuItem("2025","2025")),MobileVehicleCatalog.decodeMenu("""{"menuItem":{"text":"2025","value":"2025"}}""".toByteArray()))
        assertEquals(2,MobileVehicleCatalog.decodeMenu("""{"menuItem":[{"text":"A","value":"1"},{"text":"B","value":"2"}]}""".toByteArray()).size)
        assertThrows(IllegalArgumentException::class.java) { MobileVehicleCatalog.decodeMenu("""{"menuItem":[{"text":"A","value":"1"},{"text":"B","value":"1"}]}""".toByteArray()) }
    }
    @Test fun electricMissingAndUnknownFuelNeverBecomeGasolineMpg() {
        val base="""{"id":1,"year":2025,"make":"Example","model":"Vehicle","comb08":30,"city08":0,"highway08":35,"fuelType1":"Regular Gasoline"}"""
        val regular=MobileVehicleCatalog.decodeVehicle(base.toByteArray(),1)
        assertEquals(FuelGrade.REGULAR,regular.fuelGrade);assertEquals(30.0,regular.combinedMpg);assertNull(regular.cityMpg)
        val electric=MobileVehicleCatalog.decodeVehicle(base.replace("Regular Gasoline","Electricity").toByteArray(),1)
        assertNull(electric.fuelGrade);assertNull(electric.combinedMpg)
        assertThrows(IllegalArgumentException::class.java) { MobileVehicleCatalog.decodeVehicle(base.toByteArray(),2) }
    }
    @Test fun pricesHaveExplicitReferenceUnitsAndUnknownsRemainNull() {
        val prices=MobileVehicleCatalog.decodePrices("""{"regular":"3.45","premium":0,"diesel":"unknown"}""".toByteArray(),123)
        assertEquals(3.45,prices.regular);assertNull(prices.midgrade);assertNull(prices.premium);assertNull(prices.diesel)
        assertEquals("USD",prices.currency);assertEquals(123,prices.retrievedAtEpochMs)
    }
    @Test fun structuredVehicleUrlsCannotBecomeArbitraryNetworkTargets() {
        val address=MobilePlanningNetwork.vehicleAddress(VehicleSelection(VehicleResource.MODEL,2025,"A&B / C"))
        assertTrue(address.startsWith("https://www.fueleconomy.gov/ws/rest/vehicle/menu/model?year=2025&make=A%26B+%2F+C"))
        assertThrows(IllegalArgumentException::class.java) { MobilePlanningNetwork.vehicleAddress(VehicleSelection(VehicleResource.VEHICLE,id=-1)) }
        assertThrows(IllegalArgumentException::class.java) { MobilePlanningNetwork.vehicleAddress(VehicleSelection(VehicleResource.PRICES,year=2025)) }
    }
}
