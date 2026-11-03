package de.trustylynx.security

import de.trustylynx.clients.AuthServiceClient
import de.trustylynx.models.Caller
import de.trustylynx.models.NO_DEVICE_ID
import de.trustylynx.models.Unauthorized
import de.trustylynx.models.isValidId
import de.trustylynx.routes.DEVICE_ID_HEADER
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

// same limit as the auth-service, it rejects longer tokens with 400
private const val MAX_TOKEN_LENGTH = 512
private const val BEARER = "Bearer "

// Asks the auth-service on every request (issue #8, option A). userId and sessionId only come from its answer,
// never from headers or the body. When the login session ends, the vault counts as locked as well.
class LoginTokenVerifier(private val auth: AuthServiceClient) {

    suspend fun verify(call: ApplicationCall): Caller {
        val token = call.bearerToken() ?: throw Unauthorized()
        // optional for now, as long as the clients don't always send it (see NO_DEVICE_ID)
        val deviceId = call.request.headers[DEVICE_ID_HEADER]
        if (deviceId != null && !isValidId(deviceId)) throw Unauthorized()

        val session = auth.verifySession(token) ?: throw Unauthorized()

        return Caller(
            userId = session.userId,
            deviceId = deviceId ?: NO_DEVICE_ID,
            sessionId = session.sessionId,
        )
    }
}

// null for a missing or malformed header, without asking the auth-service
private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith(BEARER, ignoreCase = true)) return null
    val token = header.substring(BEARER.length).trim()
    if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH || token.any { it.isWhitespace() }) return null
    return token
}
