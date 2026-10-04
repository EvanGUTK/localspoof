package dev.localspoof.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class VacationJourneyTest {
    private val a=GeoPoint(0.0,0.0);private val station=GeoPoint(0.0,0.001);private val hotel=GeoPoint(0.0,0.002);private val end=GeoPoint(0.0,0.003)
    private fun request()=PlanRequest(Mode.DRIVE,a,end,customPath=listOf(a,station,hotel,end),stops=listOf(
        RouteStop(station,1000,"Fuel",StopKind.FUEL,FuelStop(10.0,2.0,"USD"),id="fuel"),
        RouteStop(hotel,9000,"Hotel",StopKind.HOTEL,hotel=HotelStop(2000,5000,2000,1,100.0,10.0,"USD"),id="hotel")),
        vacation=VacationConfig(10.0,2.0,1.0,10.0,FuelGrade.REGULAR,"USD",2.0))
    private fun running()=Coordinator().also { r->val p=r.prepare(request());r.commit(p.id);r.start(0);r.tick(0);r.acknowledgePlacement(true) }
    @Test fun explicitFuelInputAndReserveAreValidated() {
        assertFails { VacationConfig(10.0,11.0,1.0,10.0,FuelGrade.REGULAR) }
        assertFails { VacationConfig(10.0,2.0,1.0,Double.NaN,FuelGrade.REGULAR) }
        assertFails { Coordinator().prepare(request().copy(vacation=request().vacation!!.copy(startingFuelLiters=1.001))) }
    }
    @Test fun refillRequiresAcknowledgementAndCompletesExactlyOnceAfterHold() {
        val r=running();r.jumpToNextStop();r.tick(1000)
        assertEquals(2.0,r.snapshot().vacation!!.remainingFuelLiters)
        r.tick(2000);assertEquals(2.0,r.snapshot().vacation!!.remainingFuelLiters)
        r.acknowledgePlacement(true);r.pause();r.tick(3000);assertEquals(2.0,r.snapshot().vacation!!.remainingFuelLiters)
        r.resume(3000);r.tick(4000);r.acknowledgePlacement(true)
        assertEquals(10.0,r.snapshot().vacation!!.remainingFuelLiters,0.001)
        assertEquals(16.0,r.snapshot().vacation!!.refillCash!!,0.001)
        r.tick(5000);r.acknowledgePlacement(true);assertEquals(16.0,r.snapshot().vacation!!.refillCash!!,0.001)
    }
    @Test fun hotelLeaveStartsCheckoutAndCostsOnce() {
        val r=running();r.jumpToNextStop();r.tick(1000);r.acknowledgePlacement(true);r.tick(2000);r.acknowledgePlacement(true)
        r.jumpToNextStop();r.tick(3000);r.acknowledgePlacement(true);r.tick(4000);r.acknowledgePlacement(true)
        assertEquals("CHECK_IN",r.snapshot().waitingFor)
        r.leaveStop();assertEquals("CHECK_OUT",r.snapshot().waitingFor);assertFails {r.leaveStop()}
        r.tick(5000);r.acknowledgePlacement(true);assertEquals(hotel,r.snapshot().point)
        r.tick(6000);r.acknowledgePlacement(true);assertEquals(110.0,r.snapshot().vacation!!.hotelCost!!)
        r.tick(7000);r.acknowledgePlacement(true);assertEquals(110.0,r.snapshot().vacation!!.hotelCost!!)
    }
    @Test fun unknownPricesAndMixedCurrencyNeverProduceCompleteTotals() {
        val ledger=VacationLedger(VacationConfig(10.0,2.0,1.0,10.0,FuelGrade.DIESEL,"USD"))
        ledger.complete(RouteStop(station,1000,"Fuel",StopKind.FUEL,FuelStop(10.0,1.0,"EUR")))
        ledger.complete(RouteStop(hotel,1000,"Hotel",StopKind.HOTEL,hotel=HotelStop(0,1000,0,1,100.0,null,"USD")))
        assertNull(ledger.refillCash);assertNull(ledger.hotelCost);assertNull(ledger.snapshot(null).consumedFuelValue)
    }
    @Test fun undoFiltersStopsCompletedAfterEditAndNeverRestoresFuelOrCosts() {
        val r=running();val e=r.beginRemainingEdit(0)
        val p=r.prepareRemaining(e.id,request().copy(origin=e.confirmedOrigin))
        r.applyRemaining(e.id,p.id,0)
        r.jumpToNextStop();r.tick(1000);r.acknowledgePlacement(true);r.tick(2000);r.acknowledgePlacement(true)
        val spent=r.snapshot().vacation!!.refillCash
        val undo=r.beginRemainingEdit(2000,true)
        assertTrue(undo.choices.stops.none {it.id=="fuel"})
        assertEquals(spent,r.snapshot().vacation!!.refillCash)
        r.cancelRemaining(undo.id,2000)
    }
    @Test fun editingFutureDestinationDuringHotelKeepsCurrentCheckoutAndCostEvent() {
        val r=running();r.jumpToNextStop();r.tick(1000);r.acknowledgePlacement(true);r.tick(2000);r.acknowledgePlacement(true)
        r.jumpToNextStop();r.tick(3000);r.acknowledgePlacement(true);r.tick(4000);r.acknowledgePlacement(true)
        val stage=r.snapshot().waitingFor
        val e=r.beginRemainingEdit(4000)
        assertTrue(e.choices.stops.none {it.id=="hotel"})
        val request=PlanRequest(Mode.DRIVE,e.confirmedOrigin,end,customPath=listOf(e.confirmedOrigin,end),vacation=request().vacation)
        val p=r.prepareRemaining(e.id,request);r.applyRemaining(e.id,p.id,4000)
        assertEquals(hotel,r.snapshot().point);assertEquals(stage,r.snapshot().waitingFor)
        r.leaveStop();assertEquals("CHECK_OUT",r.snapshot().waitingFor)
        r.tick(5000);r.acknowledgePlacement(true);r.tick(6000);r.acknowledgePlacement(true)
        assertEquals(110.0,r.snapshot().vacation!!.hotelCost!!)
        val undo=r.beginRemainingEdit(6000,true)
        assertTrue(undo.choices.stops.none {it.id=="hotel"});assertEquals(110.0,r.snapshot().vacation!!.hotelCost!!)
    }

    @Test fun explicitFuelCorrectionIsAtomicAndKeepsPreviouslySpentEstimates() {
        val r=running();r.tick(1000);r.acknowledgePlacement(true)
        val before=r.snapshot().vacation!!
        r.updateVacation(remainingFuelLiters=3.0,litersPer100Km=12.0,reserveLiters=1.5)
        val corrected=r.snapshot().vacation!!
        assertEquals(3.0,corrected.remainingFuelLiters);assertEquals(before.consumedFuelLiters,corrected.consumedFuelLiters);assertEquals(before.refillCash,corrected.refillCash)
        assertEquals(12.0,r.snapshot().activeChoices!!.vacation!!.litersPer100Km)
        val revision=r.snapshot().revision
        assertFails {r.updateVacation(remainingFuelLiters=1.5,litersPer100Km=200.0)}
        assertEquals(corrected,r.snapshot().vacation);assertEquals(revision,r.snapshot().revision)
        assertFails {r.updateVacation(remainingFuelLiters=11.0)}
        assertEquals(corrected,r.snapshot().vacation)
    }

    @Test fun multiDayHotelHoldDoesNotFailGenericWaypointBounds() {
        val hotelStop=RouteStop(hotel,172803000,"Long stay",StopKind.HOTEL,hotel=HotelStop(1000,172800000,2000,2,100.0,0.0,"USD"))
        val r=Coordinator();val p=r.prepare(PlanRequest(Mode.DRIVE,a,end,customPath=listOf(a,hotel,end),stops=listOf(hotelStop)))
        r.commit(p.id);r.start(0);r.tick(0);r.acknowledgePlacement(true);r.jumpToNextStop();r.tick(1000);r.acknowledgePlacement(true)
        r.tick(2000);r.acknowledgePlacement(true);assertEquals("HOTEL_STAY",r.snapshot().waitingFor)
        assertEquals(hotel,r.snapshot().point)
    }

}
