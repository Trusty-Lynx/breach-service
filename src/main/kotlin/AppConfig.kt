package de.trustylynx

import de.trustylynx.plugins.CorsConfig

private val ORIGIN = Regex("""^https?://[a-z0-9.-]+(:\d+)?$""")

class AppConfig(
    val authServiceUrl: String,
    val vaultServiceUrl: String,
    val serviceTokenGateway: String,
    val serviceTokenBreach: String,
    val leakCheckUrl: String,
    val cors: CorsConfig,
) {
    companion object {
        fun fromEnv() = AppConfig(
            // default is the container in trustylynx-net, locally http://localhost:7491
            authServiceUrl = env("AUTH_SERVICE_URL", "http://auth-service:8080"),
            // locally http://localhost:7493
            vaultServiceUrl = env("VAULT_SERVICE_URL", "http://vault-service:8080"),
            // The auth-service only accepts the gateway token for /internal/sessions/verify, so we use the same one.
            // The dev default matches the auth-service's dev default.
            serviceTokenGateway = envOrDev("SERVICE_TOKEN_GATEWAY", "dev-service-token-gateway"),
            // has to match SERVICE_TOKEN_BREACH in the vault-service
            serviceTokenBreach = envOrDev("SERVICE_TOKEN_BREACH", "dev-service-token-breach"),
            leakCheckUrl = env("LEAKCHECK_URL", "https://leakcheck.io"),
            cors = corsFromEnv(),
        )
    }
}

private fun corsFromEnv(): CorsConfig {
    val origins = env("CORS_ALLOWED_ORIGINS", "")
    return CorsConfig(
        allowedOrigins = parseAllowedOrigins(origins),
        allowNullOrigin = parseFlag("CORS_ALLOW_NULL_ORIGIN", env("CORS_ALLOW_NULL_ORIGIN", "false")),
        allowAnyOrigin = origins.isBlank(),
    )
}

private fun parseAllowedOrigins(value: String): Set<String> =
    value.split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .onEach {
            require(ORIGIN.matches(it)) {
                "CORS_ALLOWED_ORIGINS: invalid origin '$it', expected http(s)://host[:port]"
            }
        }
        .toSet()

private fun parseFlag(name: String, value: String): Boolean =
    value.toBooleanStrictOrNull() ?: error("$name must be true or false")

private fun isDev(): Boolean = System.getProperty("io.ktor.development")?.toBoolean() == true

// blank counts as not set
private fun envValue(name: String): String? {
    val value = System.getenv(name)
    if (value.isNullOrBlank()) return null
    return value
}

private fun env(name: String, default: String): String = envValue(name) ?: default

// required in production, the default only applies in development mode
private fun envOrDev(name: String, devDefault: String): String {
    val value = envValue(name)
    if (value != null) return value
    if (isDev()) return devDefault
    error("Missing required environment variable $name")
}
