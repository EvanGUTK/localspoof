package dev.localspoof.domain

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class MotionControlsTest {
    private val a = GeoPoint(0.0, 0.0)
    private val b = GeoPoint(0.0, 0.1)
    private fun runtime(request: PlanRequest) = Coordinator(wallClock = { 0 }).also {
        it.commit(it.prepare(request).id); it.start(0); it.tick(0)
    }
    @Test fun variableCruiseSupports125MphAndVariesCoherentlyWithoutTeleporting() {
        val controls = DrivingSpeedControls(DrivingSpeedMode.VARIABLE, 40.2336, 55.88, false)
        val r = runtime(PlanRequest(Mode.DRIVE, a, b, 55.88, customPath=listOf(a,b), speedControls=controls))
        val speeds = mutableListOf<Double>(); var last = a; var lastSpeed = 0.0
        for (time in 200L..100_000L step 200) {
            val sample = r.tick(time)!!
            assertTrue(distanceMeters(last, sample.point) <= 56.0 * 0.2 + 0.01)
            assertTrue(abs(sample.speedMps-lastSpeed) <= 2.0 * 0.2 + 0.02)
            if (time >= 40_000) speeds.add(sample.speedMps)
            assertTrue(sample.speedMps in 0.0..55.88)
            last = sample.point; lastSpeed = sample.speedMps
        }
        assertTrue(speeds.max()-speeds.min() > 10)
        assertTrue(speeds.min() >= 40.2336-0.1)
        assertEquals(SessionState.ACTIVE, r.snapshot().state)
        for (controlsBad in listOf({ DrivingSpeedControls(driveMinMps=56.1,driveMaxMps=56.1) }, { DrivingSpeedControls(driveMinMps=20.0,driveMaxMps=10.0) }, { DrivingSpeedControls(driveMinMps=Double.NaN) })) assertFailsWith<IllegalArgumentException> { controlsBad() }
    }
    @Test fun mappedLimitFollowingIsOptionalAndDoesNotAffectWalking() {
        val road = OnlineRoute(Mode.DRIVE,"Fixture",listOf(a,b),listOf(10.0))
        val r = runtime(PlanRequest(Mode.DRIVE,a,b,30.0,onlineRoute=road))
        for (time in 200L..10_000L step 200) assertTrue(r.tick(time)!!.speedMps <= 10)
        assertEquals(10.0,r.snapshot().roadLimitMps)
        val before=r.snapshot(); r.setSpeeds(followRoadLimits=false)
        assertEquals(before.point,r.snapshot().point)
        assertTrue(r.snapshot().remainingMs!! < before.remainingMs!!)
        for (time in 10_200L..20_000L step 200) r.tick(time)
        assertTrue(r.snapshot().sample!!.speedMps > 20)
        val walking = runtime(PlanRequest(Mode.WALK,a,b,1.4,onlineRoute=OnlineRoute(Mode.WALK,"Fixture",listOf(a,b),listOf(0.2))))
        for(time in 200L..5_000L step 200) walking.tick(time)
        assertEquals(1.4,walking.snapshot().sample!!.speedMps,0.01)
        assertNull(walking.snapshot().roadLimitMps)
    }
    @Test fun brakingBeginsBeforeLowerMappedLimitRatherThanAtBoundary() {
        val turn = GeoPoint(0.0,0.005); val end = GeoPoint(0.0,0.03)
        val r=runtime(PlanRequest(Mode.DRIVE,a,end,30.0,onlineRoute=OnlineRoute(Mode.DRIVE,"Fixture",listOf(a,turn,end),listOf(30.0,5.0))))
        var lastSpeed=0.0; var braked=false; var crossed=false
        for(time in 200L..40_000L step 200) {
            val sample=r.tick(time)!!
            assertTrue(abs(sample.speedMps-lastSpeed)<=0.45, "time=$time previous=$lastSpeed current=${sample.speedMps} longitude=${sample.point.lon}")
            if(sample.point.lon<turn.lon && lastSpeed>sample.speedMps) braked=true
            if(sample.point.lon>=turn.lon) { crossed=true; assertTrue(sample.speedMps<=5.01) }
            lastSpeed=sample.speedMps
        }
        assertTrue(braked); assertTrue(crossed)
    }
    @Test fun sharpGeometryTurnSlowsCarButKeepsProgressing() {
        val vertex=GeoPoint(0.0,0.005); val end=GeoPoint(0.03,0.005)
        val r=runtime(PlanRequest(Mode.DRIVE,a,end,30.0,customPath=listOf(a,vertex,end)))
        var nearTurnSpeed:Double?=null
        for(time in 200L..45_000L step 200) {
            val sample=r.tick(time)!!
            if(distanceMeters(sample.point,vertex)<5) nearTurnSpeed=sample.speedMps
        }
        assertNotNull(nearTurnSpeed); assertTrue(nearTurnSpeed!!<25)
        assertTrue(r.snapshot().point!!.lat>0)
    }
    @Test fun delayedSchedulingNeverCatchesUpAcrossMissingMinutes() {
        val r=runtime(PlanRequest(Mode.DRIVE,a,b,55.88,customPath=listOf(a,b)))
        r.tick(1_000); val before=r.snapshot().point
        val held=r.tick(180_000)!!
        assertEquals(before,held.point); assertEquals(0.0,held.speedMps)
        assertEquals(SessionState.PAUSED,r.snapshot().state)
        assertEquals("SCHEDULING_GAP",r.snapshot().waitingFor)
        r.resume(180_000); val next=r.tick(180_200)!!
        assertTrue(distanceMeters(held.point,next.point)<0.1)
    }
    @Test fun controlValidationIsAtomicAndRevisionBound() {
        val r=runtime(PlanRequest(Mode.DRIVE,a,b,20.0,customPath=listOf(a,b)))
        val before=r.snapshot()
        assertFailsWith<IllegalArgumentException>{ r.setSpeeds(driveMps=25.0,driveMinMps=40.0,driveMaxMps=30.0) }
        assertEquals(before,r.snapshot())
        r.setSpeeds(drivingSpeedMode=DrivingSpeedMode.VARIABLE,driveMinMps=40.2336,driveMaxMps=55.88)
        assertFailsWith<IllegalArgumentException>{ r.setSpeeds(driveMps=25.0,expectedRevision=before.revision) }
        assertEquals(before.point,r.snapshot().point)
    }
    @Test fun placementGenerationChangesOnlyForIntentionalDiscontinuities() {
        val stop=GeoPoint(0.0,0.001)
        val r=runtime(PlanRequest(Mode.DRIVE,a,b,20.0,customPath=listOf(a,stop,b),stops=listOf(RouteStop(stop))))
        val initial=r.snapshot().sample!!.placementGeneration
        assertEquals(initial,r.tick(200)!!.placementGeneration)
        r.setSpeeds(driveMps=25.0);assertEquals(initial,r.tick(400)!!.placementGeneration)
        r.jumpToNextStop();val jump=r.tick(600)!!
        assertTrue(jump.placementGeneration>initial);r.acknowledgePlacement(true)
        assertEquals(jump.placementGeneration,r.tick(800)!!.placementGeneration)
        r.pause();assertEquals(jump.placementGeneration,r.tick(1000)!!.placementGeneration)
        r.resume(1000);assertEquals(jump.placementGeneration,r.tick(1200)!!.placementGeneration)
        r.commit(r.prepare(PlanRequest(Mode.INSTANT,a,a)).id)
        assertTrue(r.tick(1400)!!.placementGeneration>jump.placementGeneration)
    }

}
