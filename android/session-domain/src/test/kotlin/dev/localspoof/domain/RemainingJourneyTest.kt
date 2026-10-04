package dev.localspoof.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class RemainingJourneyTest {
    private val a=GeoPoint(0.0,0.0)
    private val b=GeoPoint(0.0,0.01)
    private val c=GeoPoint(0.01,0.01)
    private fun running():Coordinator = Coordinator().also { r ->
        val p=r.prepare(PlanRequest(Mode.DRIVE,a,b,customPath=listOf(a,b)));r.commit(p.id);r.start(0)
        r.tick(0);r.acknowledgePlacement(true);r.tick(1000);r.acknowledgePlacement(true)
    }
    @Test fun heldEditAppliesAtCurrentPointAndKeepsElapsedSpeeds() {
        val r=running(); val before=r.snapshot(); val e=r.beginRemainingEdit(1000)
        r.tick(2000);assertEquals(before.point,r.snapshot().point)
        val p=r.prepareRemaining(e.id,PlanRequest(Mode.DRIVE,e.confirmedOrigin,c,customPath=listOf(e.confirmedOrigin,c)))
        r.applyRemaining(e.id,p.id,2000)
        assertEquals(before.point,r.snapshot().point);assertEquals(before.elapsedMs,r.snapshot().elapsedMs)
        assertEquals(SessionState.ACTIVE,r.snapshot().state);assertTrue(r.snapshot().canUndo)
        r.tick(3000); assertTrue(distanceMeters(before.point!!,r.snapshot().point!!) < 20)
    }
    @Test fun cancelRespectsExistingPauseAndStopInvalidatesLateApply() {
        val r=running();r.pause();val e=r.beginRemainingEdit(1000);r.cancelRemaining(e.id,1000)
        assertEquals(SessionState.PAUSED,r.snapshot().state)
        val e2=r.beginRemainingEdit(1000);val p=r.prepareRemaining(e2.id,PlanRequest(Mode.WALK,e2.confirmedOrigin,c,customPath=listOf(e2.confirmedOrigin,c)))
        r.stop();assertFails {r.applyRemaining(e2.id,p.id,1000)};assertEquals(SessionState.IDLE,r.snapshot().state)
    }
    @Test fun timedPauseExpiresWithoutCatchingUpAndExplicitPauseCancelsTimer() {
        val r=running();val point=r.snapshot().point;r.timedPause(60_000,1000)
        r.tick(60000);assertEquals(point,r.snapshot().point);r.tick(61000);assertEquals(SessionState.ACTIVE,r.snapshot().state)
        assertEquals(point,r.snapshot().point);r.tick(62000);assertTrue(distanceMeters(point!!,r.snapshot().point!!) < 20)
        r.timedPause(60_000,62000);r.pause();r.tick(130000);assertEquals(SessionState.PAUSED,r.snapshot().state)
    }
    @Test fun airborneDiversionKeepsRadioOwnershipAndStartsAirLegAtCurrentFix() {
        val departure=GeoPoint(0.0,0.01);val arrival=GeoPoint(0.0,0.03);val diversion=GeoPoint(0.0,0.04);val destination=GeoPoint(0.0,0.06)
        val r=Coordinator(listOf(Airport("depart","Departure",departure,"large_airport"),Airport("arrive","Arrival",arrival,"large_airport"),Airport("divert","Diversion",diversion,"large_airport")))
        val options=FlightOptions(false,boardingMs=0,settlingMs=1000,originAirportId="depart",destinationAirportId="arrive",departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT)
        val p=r.prepare(PlanRequest(Mode.FLIGHT,a,destination,flight=options));r.commit(p.id);r.observeRadio(false);r.start(0)
        r.tick(0);r.acknowledgePlacement(true);r.tick(1000);r.acknowledgePlacement(true);r.tick(2000);r.acknowledgePlacement(true)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE,r.snapshot().phase)
        r.observeRadio(true);r.tick(3000);r.acknowledgePlacement(true);r.tick(4000);r.acknowledgePlacement(true)
        val before=r.snapshot().point!!;val e=r.beginRemainingEdit(4000)
        val changed=r.prepareRemaining(e.id,PlanRequest(Mode.FLIGHT,e.confirmedOrigin,destination,flight=options.copy(destinationAirportId="divert"),remainingFlight=true))
        r.applyRemaining(e.id,changed.id,4000)
        assertEquals(before,r.snapshot().point);assertEquals(FlightPhase.FLIGHT_INTERVAL,r.snapshot().phase)
        assertEquals(before,r.snapshot().activePreview!!.segments.first().points.first())
        r.tick(5000);r.acknowledgePlacement(true);assertEquals(230.0,distanceMeters(before,r.snapshot().point!!),0.1)
        r.jumpToNextStop();r.tick(6000);r.acknowledgePlacement(true);r.tick(7000);r.acknowledgePlacement(true);r.tick(8000);r.acknowledgePlacement(true)
        assertEquals(FlightPhase.RESTORE_CONNECTIVITY,r.snapshot().phase);assertEquals(false,r.snapshot().desiredAirplaneMode)
        r.observeRadio(false);r.tick(9000);r.acknowledgePlacement(true)
        assertEquals(FlightPhase.DESTINATION_GROUND,r.snapshot().phase)
    }
    @Test fun staleFixAndUnexpectedRadioChangesRejectHeldEdits() {
        val r=running();assertFails {r.beginRemainingEdit(5000)}
        r.observeRadio(false);val e=r.beginRemainingEdit(1000);r.observeRadio(true)
        assertFails {r.cancelRemaining(e.id,1000)};assertNull(r.snapshot().editId)
        assertEquals(SessionState.PAUSED,r.snapshot().state)
    }

    @Test fun liveRemainingDraftNeverHoldsOrCreatesACommitHandle() {
        val r=running();val before=r.snapshot();val draft=r.prepareRemainingDraft(PlanRequest(Mode.DRIVE,before.point!!,c,customPath=listOf(before.point!!,c)))
        assertEquals(SessionState.ACTIVE,r.snapshot().state);assertEquals(before.activePreview,r.snapshot().activePreview)
        assertFails {r.commit(draft.id)}
        r.tick(2000);r.acknowledgePlacement(true)
        assertTrue(distanceMeters(before.point!!,r.snapshot().point!!)>0)
    }

}
