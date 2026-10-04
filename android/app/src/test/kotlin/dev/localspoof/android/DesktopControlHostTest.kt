package dev.localspoof.android

import org.junit.Assert.*
import org.junit.Test

class DesktopControlHostTest {
    @Test fun repeatedEnableDoesNotCloseOrRecreateTheServerOrRotateControl() {
        var enables = 0; var opens = 0; var closes = 0
        val host = DesktopControlHost({ enables++ }) { opens++; AutoCloseable { closes++ } }
        host.enable(); host.enable(); host.enable()
        assertEquals(1, enables); assertEquals(1, opens); assertEquals(0, closes)
        host.close(); host.close()
        assertEquals(1, closes)
    }
    @Test fun explicitlyClosedHostCanCreateAFreshServerOnce() {
        var enables = 0; var opens = 0; var closes = 0
        val host = DesktopControlHost({ enables++ }) { opens++; AutoCloseable { closes++ } }
        host.enable(); host.close(); host.enable(); host.enable()
        assertEquals(2, enables); assertEquals(2, opens); assertEquals(1, closes)
        host.close(); assertEquals(2, closes)
    }
    @Test fun failedBindNeverPublishesEnabledAndNextExplicitEnableRetries() {
        var enabled=false;var attempts=0;var failures=0
        val host=DesktopControlHost({enabled=true}, {enabled=false;failures++}) {
            if(++attempts==1) throw java.io.IOException("synthetic bind failure")
            AutoCloseable {}
        }
        host.enable();assertFalse(enabled);assertEquals(1,failures)
        host.enable();assertTrue(enabled);assertEquals(2,attempts)
        host.enable();assertEquals(2,attempts);host.close()
    }
    @Test fun enablePublishesOnlyAfterBindAndBeforeAcceptWorkerActivation() {
        val order=mutableListOf<String>()
        val host=DesktopControlHost({order.add("enable")}, activateServer={order.add("accept")}) { order.add("bind");AutoCloseable{} }
        host.enable();assertEquals(listOf("bind","enable","accept"),order);host.close()
    }
    @Test fun deadCachedServerIsClosedAndRecreatedWhileHealthyRepeatedEnableIsInert() {
        class Server:AutoCloseable {var healthy=true;var closes=0;override fun close(){healthy=false;closes++}}
        val opened=mutableListOf<Server>();var enables=0
        val host=DesktopControlHost({enables++},isServerHealthy={ (it as Server).healthy }) {Server().also {opened.add(it)}}
        host.enable();host.enable();assertEquals(1,opened.size);assertEquals(1,enables)
        opened[0].healthy=false
        host.enable();assertEquals(1,opened[0].closes);assertEquals(2,opened.size);assertEquals(2,enables)
        host.enable();assertEquals(0,opened[1].closes);host.close();assertEquals(1,opened[1].closes)
    }
    @Test fun failedWorkerActivationRevokesEnabledStateAndClosesTheBoundSocket() {
        var enabled=false;var closes=0;var activates=0;var failures=0
        val host=DesktopControlHost({enabled=true},{enabled=false;failures++},activateServer={ if(++activates==1) throw IllegalStateException("synthetic stopped executor") }) {AutoCloseable{closes++}}
        host.enable();assertFalse(enabled);assertEquals(1,closes);assertEquals(1,failures)
        host.enable();assertTrue(enabled);assertEquals(2,activates);host.close();assertEquals(2,closes)
    }
}
