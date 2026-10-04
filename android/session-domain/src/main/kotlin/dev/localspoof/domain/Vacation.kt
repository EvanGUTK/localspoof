package dev.localspoof.domain

import kotlin.math.max

enum class StopKind { PLACE, FUEL, HOTEL }
enum class FuelGrade { REGULAR, MIDGRADE, PREMIUM, DIESEL }
data class FuelStop(val refillTargetLiters: Double, val pricePerLiter: Double? = null, val currency: String? = null) {
    init { require(refillTargetLiters.isFinite() && refillTargetLiters > 0 && refillTargetLiters <= 2000) { "INVALID_REFILL_TARGET" }; require(pricePerLiter == null || pricePerLiter.isFinite() && pricePerLiter >= 0 && pricePerLiter <= 1000) { "INVALID_FUEL_PRICE" }; require(currency == null || currency.matches(Regex("[A-Z]{3}"))) { "INVALID_CURRENCY" } }
}
data class HotelStop(val checkInMs: Long, val stayMs: Long, val checkOutMs: Long, val nights: Int,
    val nightlyPrice: Double? = null, val fees: Double? = null, val currency: String = "USD") {
    init { require(checkInMs in 0..86_400_000 && stayMs in 0..2_592_000_000L && checkOutMs in 0..86_400_000 && nights in 0..365) { "INVALID_HOTEL_DURATION" }; require(listOf(nightlyPrice,fees).all { it == null || it.isFinite() && it >= 0 && it <= 1_000_000 }) { "INVALID_HOTEL_PRICE" }; require(currency.matches(Regex("[A-Z]{3}"))) { "INVALID_CURRENCY" } }
    val durationMs: Long get() = checkInMs + stayMs + checkOutMs
    val knownCost: Double? get() = nightlyPrice?.let { price -> fees?.let { price * nights + it } }
}
data class VacationConfig(val tankLiters: Double, val startingFuelLiters: Double, val reserveLiters: Double,
    val litersPer100Km: Double, val fuelGrade: FuelGrade, val currency: String = "USD", val fuelPricePerLiter: Double? = null) {
    init { require(tankLiters.isFinite() && tankLiters in 0.1..2000.0 && startingFuelLiters.isFinite() && startingFuelLiters in 0.0..tankLiters && reserveLiters.isFinite() && reserveLiters in 0.0..tankLiters) { "INVALID_STARTING_FUEL" }; require(litersPer100Km.isFinite() && litersPer100Km in 0.1..200.0) { "INVALID_CONSUMPTION" }; require(currency.matches(Regex("[A-Z]{3}"))) { "INVALID_CURRENCY" }; require(fuelPricePerLiter == null || fuelPricePerLiter.isFinite() && fuelPricePerLiter in 0.0..1000.0) { "INVALID_FUEL_PRICE" } }
}
data class VacationSnapshot(val remainingFuelLiters: Double, val consumedFuelLiters: Double, val refillCash: Double?, val hotelCost: Double?, val consumedFuelValue: Double?, val currency: String, val effectiveMpg: Double, val estimatedRemainingRangeMeters: Double, val stopStage: String?)
data class JourneyStage(val label: String, val kind: String, val status: String, val remainingMs: Long?)
data class RemainingEditContext(val id: String, val revision: Long, val phase: FlightPhase?, val confirmedOrigin: GeoPoint, val choices: PlanRequest, val canUndo: Boolean)

/** Pure phone-owned estimate. Only route-distance progress consumes fuel, never hold or renderer time. */
internal class VacationLedger(var config: VacationConfig) {
    var fuel = config.startingFuelLiters
    var consumed = 0.0
    var refillCash: Double? = 0.0
    var hotelCost: Double? = 0.0
    fun consume(meters: Double) { val amount=max(0.0,meters)*config.litersPer100Km/100_000.0; require(fuel-amount >= config.reserveLiters-1e-7) { "FUEL_RESERVE_EXHAUSTED" };fuel=max(0.0,fuel-amount);consumed+=amount }
    fun complete(stop: RouteStop) {
        stop.fuel?.let { f -> val added=max(0.0,f.refillTargetLiters-fuel);fuel+=added;val price=f.pricePerLiter ?: config.fuelPricePerLiter;refillCash=if (price == null || f.currency != null && f.currency != config.currency || refillCash == null) null else refillCash!!+added*price }
        stop.hotel?.let { h -> hotelCost=if(h.currency != config.currency || h.knownCost == null || hotelCost == null) null else hotelCost!!+h.knownCost!! }
    }
    fun snapshot(stage: String?) = VacationSnapshot(fuel,consumed,refillCash,hotelCost,config.fuelPricePerLiter?.let{consumed*it},config.currency,235.214583/config.litersPer100Km,max(0.0,fuel-config.reserveLiters)*100_000/config.litersPer100Km,stage)
}
