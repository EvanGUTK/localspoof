package dev.localspoof.android

import dev.localspoof.domain.*
import org.junit.Assert.*
import org.junit.Test

class MotionTelemetryTest {
    private val p=GeoPoint(1.0,2.0)
    private fun snapshot(state:SessionState=SessionState.ACTIVE,error:String?=null,waiting:String?=null)=SessionSnapshot(1,state,Mode.DRIVE,p,null,null,error,null,waitingFor=waiting)
    private fun location(age:Long=0,speed:Double=8.0)=PhoneLocation(p,PhoneLocationSource.SIMULATION,5.0,age,speed,90.0,3)
    @Test fun gaugeUsesOnlyFreshSubmittedSpeedAndNeverConfiguredTarget() {
        assertEquals(8.0,motionTelemetry(snapshot(),location(),true).speedMps!!,0.0)
        assertEquals("LIVE",motionTelemetry(snapshot(),location(),true).health)
        assertNull(motionTelemetry(snapshot(),null,true).speedMps)
        assertEquals("STALE",motionTelemetry(snapshot(),location(1501),true).health)
        assertNull(motionTelemetry(snapshot(),location(1501),true).speedMps)
        assertNull(motionTelemetry(snapshot(),PhoneLocation(p,PhoneLocationSource.DEVICE,5.0,0),true).speedMps)
    }
    @Test fun pausedAndWaitingNeverDisplayOldMovingSpeed() {
        assertNull(motionTelemetry(snapshot(SessionState.PAUSED),location(speed=40.0),true).speedMps)
        assertEquals("PAUSED",motionTelemetry(snapshot(SessionState.PAUSED),location(),true).health)
        assertNull(motionTelemetry(snapshot(waiting="AIRPLANE_MODE"),location(speed=40.0),true).speedMps)
        assertEquals("WAITING",motionTelemetry(snapshot(waiting="AIRPLANE_MODE"),location(),true).health)
        assertEquals(0.0,motionTelemetry(snapshot(waiting="STOP_DWELL"),location(speed=0.0),true).speedMps!!,0.0)
        assertEquals("STALE",motionTelemetry(snapshot(SessionState.PAUSED,"SCHEDULING_GAP"),location(),true).health)
    }
    @Test fun duplicateOrOutOfOrderSubmissionCannotRenewSpeedFreshness() {
        val owner=Any(); val state=PhoneLocationState(); state.attach(owner);state.preparing(owner)
        val sample=Sample(p,speedMps=12.0,bearingDegrees=45.0,elapsedRealtimeMs=1000,wallTimeMillis=0)
        state.submitted(owner,sample,1_000_000_000)
        state.submitted(owner,sample,2_000_000_000)
        assertEquals(1000L,state.snapshot(2_000_000_000)!!.ageMs)
        assertEquals(1L,state.snapshot(2_000_000_000)!!.sampleSequence)
        state.submitted(owner,sample.copy(elapsedRealtimeMs=999,speedMps=50.0),2_000_000_000)
        assertEquals(12.0,state.snapshot(2_000_000_000)!!.speedMps!!,0.0)
    }
}
