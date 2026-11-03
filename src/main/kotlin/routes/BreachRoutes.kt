package de.trustylynx.routes

import de.trustylynx.clients.VaultServiceClient
import de.trustylynx.models.AccountCheckRequest
import de.trustylynx.models.Caller
import de.trustylynx.models.NotFound
import de.trustylynx.models.VaultLocked
import de.trustylynx.models.isValidId
import de.trustylynx.security.LoginTokenVerifier
import de.trustylynx.services.CheckJobQueue
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

// who is asking always comes from the login token, these headers only say which device and vault token is meant
const val DEVICE_ID_HEADER = "X-Device-Id"
const val VAULT_TOKEN_HEADER = "X-Vault-Token"

// TODO: implement GET /breach/range/{prefix} (HIBP, US-23)
fun Route.breachRoutes(jobs: CheckJobQueue, loginTokens: LoginTokenVerifier, vault: VaultServiceClient) {
    // POST instead of GET, so the username doesn't end up in the path and in access logs.
    // Answers 202 right away, the client polls the result from /breach/account/jobs/{jobId}.
    post("/breach/account") {
        // before the body: without login token and unlocked vault it's always 401, even with a broken body
        val caller = call.unlockedCaller(loginTokens, vault)
        val request = call.receive<AccountCheckRequest>()

        val job = jobs.submit(caller.userId, validateAccountQuery(request.type, request.value))
        call.response.header(HttpHeaders.Location, "/breach/account/jobs/${job.jobId}")
        call.respond(HttpStatusCode.Accepted, job)
    }

    // the result belongs to usernames from the vault, so the same check as when creating it
    get("/breach/account/jobs/{jobId}") {
        val caller = call.unlockedCaller(loginTokens, vault)
        val jobId = call.parameters["jobId"]
        if (jobId == null || !isValidId(jobId)) throw NotFound()

        val job = jobs.get(caller.userId, jobId) ?: throw NotFound()
        call.respond(job)
    }
}

// the usernames come from the vault, so only with an unlocked vault.
// Without a vault token we don't even ask the vault-service.
private suspend fun ApplicationCall.unlockedCaller(loginTokens: LoginTokenVerifier, vault: VaultServiceClient): Caller {
    val caller = loginTokens.verify(this)
    val vaultToken = request.headers[VAULT_TOKEN_HEADER] ?: throw VaultLocked()
    if (!vault.isUnlocked(caller, vaultToken)) throw VaultLocked()
    return caller
}
