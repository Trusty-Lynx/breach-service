package de.trustylynx.plugins

import de.trustylynx.routes.DEVICE_ID_HEADER
import de.trustylynx.routes.VAULT_TOKEN_HEADER
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.cors.routing.CORS

private val LOCAL_ORIGIN = Regex("""^http://(localhost|127\.0\.0\.1):\d+$""")

data class CorsConfig(
    val allowedOrigins: Set<String> = emptySet(),
    val allowNullOrigin: Boolean = false,
    // Temporary: true when CORS_ALLOWED_ORIGINS isn't set, so nothing has to be configured on the server yet.
    // Acceptable because we don't use cookies, a foreign website doesn't know the login or vault token.
    // TODO: remove once the website domain is fixed and CORS_ALLOWED_ORIGINS is set on the server
    val allowAnyOrigin: Boolean = false,
)

fun Application.configureCors(config: CorsConfig) {
    if (config.allowAnyOrigin) {
        log.warn("CORS_ALLOWED_ORIGINS is not set: every origin may call the breach-service (temporary)")
    }
    install(CORS) {
        // answers with "Access-Control-Allow-Origin: *", only works because we don't use allowCredentials
        if (config.allowAnyOrigin) anyHost()
        allowOrigins { origin ->
            LOCAL_ORIGIN.matches(origin) ||
                origin in config.allowedOrigins ||
                // `Origin: null` isn't only sent by local files, but also by sandboxed iframes and some redirects,
                // so potentially by any website. Local development only, stays false on the server.
                // Never use it together with allowCredentials or cookies.
                (config.allowNullOrigin && origin == "null")
        }
        // Ktor allows GET, POST and HEAD by default, that's all the breach-service needs
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        // Temporary: the website (dashboard.js) still sends the old placeholders from before #8. The server ignores
        // them, but without these lines the browser would already block the preflight and with it every request.
        // TODO: remove once dashboard.js stops sending these headers
        allowHeader("X-User-Id")
        allowHeader("X-Session-Id")
        allowHeader(DEVICE_ID_HEADER)
        allowHeader(VAULT_TOKEN_HEADER)
        allowHeader("X-Timestamp")
        allowHeader("X-Device-Sig")
        allowHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.XRequestId)
        exposeHeader(HttpHeaders.RetryAfter)
    }
}
