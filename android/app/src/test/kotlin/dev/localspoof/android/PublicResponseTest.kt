package dev.localspoof.android

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PublicResponseTest {
    @Test fun publicErrorHasNoCurrentSessionOrLocationFields() {
        val response = publicResponse("UNAUTHORIZED")
        assertEquals(setOf("ok", "error"), response.keys)
        assertFalse(response.containsKey("point"))
        assertFalse(response.containsKey("revision"))
        assertFalse(response.containsKey("sessionId"))
        assertFalse(response.containsKey("phase"))
    }
    @Test fun approvedCredentialDeliveryIncludesOnlyPairingFields() {
        val response = publicResponse(payload = buildJsonObject { put("paired", true); put("token", "synthetic") }, approvedSessionId = "synthetic-session")
        assertEquals(setOf("ok", "payload", "sessionId"), response.keys)
        assertFalse(response.toString().contains("preparedPreview"))
    }
}
