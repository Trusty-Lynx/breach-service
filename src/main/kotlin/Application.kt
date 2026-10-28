package de.trustylynx

import de.trustylynx.api.breachRoutes
import de.trustylynx.clients.HttpAuthServiceClient
import de.trustylynx.clients.HttpLeakCheckClient
import de.trustylynx.clients.HttpVaultServiceClient
import de.trustylynx.clients.VaultServiceClient
import de.trustylynx.common.CorsConfig
import de.trustylynx.common.configureCors
import de.trustylynx.common.configureRequestLogging
import de.trustylynx.common.configureSerialization
import de.trustylynx.common.configureStatusPages
import de.trustylynx.common.createServiceHttpClient
import de.trustylynx.common.healthRoute
import de.trustylynx.domain.BreachService
import de.trustylynx.security.LoginTokenVerifier
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    EngineMain.main(args)
}

/** Einziger Ort, an dem Abhängigkeiten gebaut und verdrahtet werden. */
fun Application.module() {
    val config = AppConfig.fromEnv()

    val httpClient = createServiceHttpClient()
    monitor.subscribe(ApplicationStopped) {
        httpClient.close()
    }

    val breaches = BreachService(HttpLeakCheckClient(httpClient, config.leakCheckUrl))
    val loginTokens = LoginTokenVerifier(HttpAuthServiceClient(httpClient, config.authServiceUrl, config.serviceTokenGateway))
    val vault = HttpVaultServiceClient(httpClient, config.vaultServiceUrl, config.serviceTokenBreach)

    configureApp(breaches, loginTokens, vault, config.cors)
}

/** Plugins und Routen, getrennt von [module], damit Tests Fakes einsetzen können. */
fun Application.configureApp(
    breaches: BreachService,
    loginTokens: LoginTokenVerifier,
    vault: VaultServiceClient,
    cors: CorsConfig = CorsConfig(),
    // Keine eigene DB: Läuft der Prozess, ist der Service gesund.
    isHealthy: suspend () -> Boolean = { true },
) {
    configureRequestLogging()
    // Vor allem anderen, damit auch Fehlerantworten (401, 502 …) die CORS-Header tragen, sonst sieht der Browser nur "CORS error".
    configureCors(cors)
    configureSerialization()
    configureStatusPages()
    routing {
        healthRoute(isHealthy)
        breachRoutes(breaches, loginTokens, vault)
    }
}
