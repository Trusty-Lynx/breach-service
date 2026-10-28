package de.trustylynx

import de.trustylynx.common.CorsConfig
import de.trustylynx.common.env
import de.trustylynx.common.envOrDev

/** Alle Umgebungsvariablen des breach-service an einer Stelle. */
class AppConfig(
    val authServiceUrl: String,
    val vaultServiceUrl: String,
    val serviceTokenGateway: String,
    val serviceTokenBreach: String,
    val leakCheckUrl: String,
    val cors: CorsConfig,
) {
    // Ohne Service-Tokens, damit sie nie im Log landen.
    override fun toString(): String =
        "AppConfig(authServiceUrl=$authServiceUrl, vaultServiceUrl=$vaultServiceUrl, leakCheckUrl=$leakCheckUrl, cors=$cors)"

    companion object {
        fun fromEnv() = AppConfig(
            // Login-Token prüfen über POST /internal/sessions/verify, wie im vault-service. Default ist der Container im
            // trustylynx-net, für lokale Entwicklung AUTH_SERVICE_URL=http://localhost:7491 setzen.
            authServiceUrl = env("AUTH_SERVICE_URL", "http://auth-service:8080"),
            // Tresor entsperrt? Über POST /internal/vault/unlocked. Lokal: VAULT_SERVICE_URL=http://localhost:7493
            vaultServiceUrl = env("VAULT_SERVICE_URL", "http://vault-service:8080"),
            // Für den Auth-Service: Er kennt für /internal/sessions/verify nur den Gateway-Token, deshalb nehmen wir denselben.
            // Muss gleich SERVICE_TOKEN_GATEWAY im Auth-Service sein. Dev-Default = Dev-Default des Auth-Service.
            serviceTokenGateway = envOrDev("SERVICE_TOKEN_GATEWAY", "dev-service-token-gateway"),
            // Eigener Token für den Vault-Service. Muss gleich SERVICE_TOKEN_BREACH im Vault-Service sein.
            serviceTokenBreach = envOrDev("SERVICE_TOKEN_BREACH", "dev-service-token-breach"),
            // Public API von LeakCheck, ohne API-Key.
            leakCheckUrl = env("LEAKCHECK_URL", "https://leakcheck.io"),
            // Optional: CORS_ALLOWED_ORIGINS (kommagetrennt), CORS_ALLOW_NULL_ORIGIN (nur lokal true). localhost geht immer.
            cors = CorsConfig.fromEnv(),
        )
    }
}
