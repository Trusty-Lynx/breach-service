package de.trustylynx.plugins

import de.trustylynx.routes.VAULT_TOKEN_HEADER
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

// the website (MPA and SPA) is always allowed, same as in the auth-service, so the server needs no CORS config
private const val FRONTEND_ORIGIN = "https://trustylynx.nexvent.de"
private val LOCAL_ORIGIN = Regex("""^http://(localhost|127\.0\.0\.1):\d+$""")

data class CorsConfig(
    val allowedOrigins: Set<String> = emptySet(),
    val allowNullOrigin: Boolean = false,
)

fun Application.configureCors(config: CorsConfig) {
    install(CORS) {
        allowOrigins { origin ->
            LOCAL_ORIGIN.matches(origin) ||
                origin == FRONTEND_ORIGIN ||
                origin in config.allowedOrigins ||
                // `Origin: null` isn't only sent by local files, but also by sandboxed iframes and some redirects,
                // so potentially by any website. Local development only, stays false on the server.
                // Never use it together with allowCredentials or cookies.
                (config.allowNullOrigin && origin == "null")
        }
        // Ktor allows GET, POST and HEAD by default, that's all the breach-service needs
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(VAULT_TOKEN_HEADER)
        allowHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.RetryAfter)
    }
}
