package de.trustylynx.api

import de.trustylynx.clients.VaultServiceClient
import de.trustylynx.common.Caller
import de.trustylynx.common.VaultLocked
import de.trustylynx.common.vaultToken
import de.trustylynx.domain.BreachService
import de.trustylynx.security.LoginTokenVerifier
import de.trustylynx.validation.validate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

// TODO: GET /breach/range/{prefix} (HIBP, US-23) implementieren.
fun Route.breachRoutes(breaches: BreachService, loginTokens: LoginTokenVerifier, vault: VaultServiceClient) {
    // POST statt GET, damit der Benutzername nicht im Pfad und damit nicht in Access-Logs landet.
    post("/breach/account") {
        // Vor dem Body prüfen: ohne Login-Token und entsperrten Tresor gibt es immer 401, auch bei kaputtem Body.
        call.unlockedCaller(loginTokens, vault)
        val query = call.receive<AccountCheckRequest>().validate()
        call.respond(breaches.checkAccount(query).toResponse())
    }
}

/**
 * Login-Token beim Auth-Service, dann Tresor-Token beim Vault-Service prüfen. Die Benutzernamen stammen aus dem Tresor,
 * deshalb nur bei entsperrtem Tresor. Ohne Tresor-Token wird der Vault gar nicht erst gefragt.
 */
private suspend fun ApplicationCall.unlockedCaller(loginTokens: LoginTokenVerifier, vault: VaultServiceClient): Caller {
    val caller = loginTokens.verify(this)
    val vaultToken = vaultToken() ?: throw VaultLocked()
    if (!vault.isUnlocked(caller, vaultToken)) throw VaultLocked()
    return caller
}
