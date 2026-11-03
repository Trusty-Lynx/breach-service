package de.trustylynx.plugins

import de.trustylynx.routes.HEALTH_PATH
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.util.UUID

private val REQUEST_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

// Every request gets an X-Request-Id (or keeps the one it brought). It's in every log line and goes on to the
// other services, LeakCheck included. That's fine, it's a random UUID with no link to the user.
fun Application.configureRequestLogging() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { REQUEST_ID.matches(it) }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        callIdMdc("requestId")
        filter { it.request.path() != HEALTH_PATH }
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
}
