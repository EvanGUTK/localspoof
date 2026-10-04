package dev.localspoof.android

import dev.localspoof.domain.*
import org.junit.Assert.*
import org.junit.Test

class FlightRadioPromptTest {
    private fun snapshot(phase:FlightPhase?,desired:Boolean?=null,state:SessionState=SessionState.ACTIVE)=SessionSnapshot(1,state,Mode.FLIGHT,null,phase,null,null,null,desiredAirplaneMode=desired)
    @Test fun guidanceUsesActualGateAndOriginalRestorationState() {
        val prompts=FlightRadioPrompts()
        assertNull(prompts.next(snapshot(FlightPhase.BOARDING)))
        assertEquals(true,prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true)))
        assertNull(prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true)))
        assertNull(prompts.next(snapshot(FlightPhase.FLIGHT_INTERVAL)))
        assertEquals(false,prompts.next(snapshot(FlightPhase.RESTORE_CONNECTIVITY,false)))
        assertNull(prompts.next(snapshot(FlightPhase.RESTORE_CONNECTIVITY,false)))
        prompts.next(snapshot(FlightPhase.ORIGIN_GROUND))
        assertEquals(true,prompts.next(snapshot(FlightPhase.RESTORE_CONNECTIVITY,true)))
    }
    @Test fun simulationOnlyPausedAndCompletedGatesDoNotAlert() {
        val prompts=FlightRadioPrompts()
        assertNull(prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE)))
        assertNull(prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true,SessionState.PAUSED)))
        assertEquals(true,prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true)))
        assertNull(prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true,SessionState.PAUSED)))
        assertNull(prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true)))
        prompts.next(snapshot(null,null,SessionState.IDLE))
        assertEquals(true,prompts.next(snapshot(FlightPhase.AWAIT_AIRPLANE_MODE,true)))
    }
    @Test fun radioObservationRatherThanNotificationControlsAdvancement() {
        val a=GeoPoint(0.0,0.0); val b=GeoPoint(0.0,0.01)
        val r=Coordinator(listOf(Airport("a","A",a,"large_airport"),Airport("b","B",b,"large_airport")))
        r.commit(r.prepare(PlanRequest(Mode.FLIGHT,a,b,flight=FlightOptions(false,boardingMs=0,departureGroundMode=GroundLegMode.INSTANT,arrivalGroundMode=GroundLegMode.INSTANT))).id)
        r.observeRadio(false);r.start(0);r.tick(0);r.acknowledgePlacement(true);r.tick(200);r.tick(400)
        assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE,r.snapshot().phase)
        FlightRadioPrompts().next(r.snapshot())
        r.tick(600);assertEquals(FlightPhase.AWAIT_AIRPLANE_MODE,r.snapshot().phase)
        r.observeRadio(true);r.tick(800);assertEquals(FlightPhase.FLIGHT_INTERVAL,r.snapshot().phase)
    }
}
