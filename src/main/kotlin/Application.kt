package de.trustylynx

import de.trustylynx.clients.HttpAuthServiceClient
import de.trustylynx.clients.HttpLeakCheckClient
import de.trustylynx.clients.HttpVaultServiceClient
import de.trustylynx.clients.VaultServiceClient
import de.trustylynx.clients.createServiceHttpClient
import de.trustylynx.plugins.CorsConfig
import de.trustylynx.plugins.configureCors
import de.trustylynx.plugins.configureRequestLogging
import de.trustylynx.plugins.configureSerialization
import de.trustylynx.plugins.configureStatusPages
import de.trustylynx.routes.breachRoutes
import de.trustylynx.routes.healthRoute
import de.trustylynx.security.LoginTokenVerifier
import de.trustylynx.services.BreachService
import de.trustylynx.services.CheckJobQueue
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.routing

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val config = AppConfig.fromEnv()

    val httpClient = createServiceHttpClient()
    monitor.subscribe(ApplicationStopped) {
        httpClient.close()
    }

    val jobs = CheckJobQueue(BreachService(HttpLeakCheckClient(httpClient, config.leakCheckUrl)))
    val loginTokens = LoginTokenVerifier(HttpAuthServiceClient(httpClient, config.authServiceUrl, config.serviceTokenGateway))
    val vault = HttpVaultServiceClient(httpClient, config.vaultServiceUrl, config.serviceTokenBreach)

    configureApp(jobs, loginTokens, vault, config.cors)
}

fun Application.configureApp(
    jobs: CheckJobQueue,
    loginTokens: LoginTokenVerifier,
    vault: VaultServiceClient,
    cors: CorsConfig = CorsConfig(),
) {
    configureRequestLogging()
    // before everything else, so error answers (401, 502 ...) carry the CORS headers too,
    // otherwise the browser only shows "CORS error"
    configureCors(cors)
    configureSerialization()
    configureStatusPages()
    // the worker runs in the Application coroutine and gets cancelled when it stops
    jobs.start(this)
    routing {
        healthRoute()
        breachRoutes(jobs, loginTokens, vault)
    }
}
