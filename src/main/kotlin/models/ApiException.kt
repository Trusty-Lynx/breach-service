package de.trustylynx.models

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

open class ApiException(
    val status: HttpStatusCode,
    val code: String,
    val reason: String? = null,
) : RuntimeException(code, null, false, false)

class BadRequest(reason: String) : ApiException(HttpStatusCode.BadRequest, "bad_request", reason)

class Unauthorized : ApiException(HttpStatusCode.Unauthorized, "unauthorized")

// same code as the vault-service, the client then asks for the master password again
class VaultLocked : ApiException(HttpStatusCode.Unauthorized, "vault_locked")

class NotFound : ApiException(HttpStatusCode.NotFound, "not_found")

class TooManyRequests(val retryAfterSeconds: Long) :
    ApiException(HttpStatusCode.TooManyRequests, "too_many_requests")

// another service didn't answer or sent garbage, becomes 502
class UpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// LeakCheck is up but refuses this query, so trying again won't help
class QueryRejected(message: String) : RuntimeException(message)

@Serializable
data class ErrorResponse(
    val error: String,
    val reason: String? = null,
)
