package de.trustylynx.routes

import de.trustylynx.models.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

const val HEALTH_PATH = "/health"

// no token needed. There's no database, so if the process runs, the service is healthy.
fun Route.healthRoute() {
    get(HEALTH_PATH) {
        call.respond(HealthResponse("ok"))
    }
}
