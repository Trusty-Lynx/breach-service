package de.trustylynx.api

import de.trustylynx.clients.VaultServiceClient
import de.trustylynx.common.Caller
import de.trustylynx.common.NotFound
import de.trustylynx.common.VaultLocked
import de.trustylynx.common.isValidId
import de.trustylynx.common.vaultToken
import de.trustylynx.jobs.CheckJobQueue
import de.trustylynx.security.LoginTokenVerifier
import de.trustylynx.validation.validate
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

// TODO: GET /breach/range/{prefix} (HIBP, US-23) implementieren.
fun Route.breachRoutes(jobs: CheckJobQueue, loginTokens: LoginTokenVerifier, vault: VaultServiceClient) {
    // Asynchron: Die Abfrage landet in der Queue, die Antwort kommt sofort mit 202 und einer jobId.
    // Das Ergebnis holt der Client über GET /breach/account/jobs/{jobId} ab (Polling).
    // POST statt GET, damit der Benutzername nicht im Pfad und damit nicht in Access-Logs landet.
    post("/breach/account") {
        // Vor dem Body prüfen: ohne Login-Token und entsperrten Tresor gibt es immer 401, auch bei kaputtem Body.
        val caller = call.unlockedCaller(loginTokens, vault)
        val query = call.receive<AccountCheckRequest>().validate()
        val job = jobs.submit(caller.userId, query)
        call.response.header(HttpHeaders.Location, "/breach/account/jobs/${job.id}")
        call.respond(HttpStatusCode.Accepted, job.toResponse())
    }

    // Gleiche Prüfung wie beim Anlegen: Das Ergebnis gehört zu Benutzernamen aus dem Tresor.
    get("/breach/account/jobs/{jobId}") {
        val caller = call.unlockedCaller(loginTokens, vault)
        val jobId = call.parameters["jobId"]?.takeIf(::isValidId) ?: throw NotFound()
        val job = jobs.get(caller.userId, jobId) ?: throw NotFound()
        call.respond(job.toResponse())
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
