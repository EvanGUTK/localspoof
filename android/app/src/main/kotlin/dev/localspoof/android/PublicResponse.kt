package dev.localspoof.android

import kotlinx.serialization.json.*

/** Unauthenticated replies never consult the live runtime or disclose activity. */
internal fun publicResponse(error: String? = null, payload: JsonObject? = null, approvedSessionId: String? = null): JsonObject = buildJsonObject {
    put("ok", error == null)
    if (error != null) put("error", error)
    if (approvedSessionId != null) put("sessionId", approvedSessionId)
    if (payload != null) put("payload", payload)
}
