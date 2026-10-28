package de.trustylynx.clients

import de.trustylynx.common.Caller
import de.trustylynx.common.SERVICE_TOKEN_HEADER
import de.trustylynx.common.UpstreamException
import de.trustylynx.common.callUpstream
import de.trustylynx.common.serviceUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** Zugriff auf den Vault-Service. Als Interface, damit Tests einen Fake einsetzen können. */
interface VaultServiceClient {
    /**
     * Ist der Tresor von [caller] mit diesem Tresor-Token entsperrt? Über `POST /internal/vault/unlocked`.
     * Gesperrt, abgelaufen oder fremd → false. Vault nicht erreichbar oder unerwartete Antwort → UpstreamException.
     */
    suspend fun isUnlocked(caller: Caller, vaultToken: String): Boolean
}

class HttpVaultServiceClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val serviceToken: String,
) : VaultServiceClient {

    // POST /internal/vault/unlocked, siehe vault-service/README.md. [serviceToken] = SERVICE_TOKEN_BREACH.
    override suspend fun isUnlocked(caller: Caller, vaultToken: String): Boolean {
        val response = callUpstream(WHAT) {
            http.post(serviceUrl(baseUrl, "internal", "vault", "unlocked")) {
                header(SERVICE_TOKEN_HEADER, serviceToken)
                contentType(ContentType.Application.Json)
                setBody(VerifyUnlockedRequest(caller.userId, caller.sessionId, caller.deviceId, vaultToken))
            }
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> true
            // 401 vault_locked ist kein Ausfall, sondern "Tresor gesperrt".
            HttpStatusCode.Unauthorized -> false
            else -> throw UpstreamException("$WHAT answered ${response.status.value}")
        }
    }

    private companion object {
        const val WHAT = "vault-service vault/unlocked"
    }
}

@Serializable
private data class VerifyUnlockedRequest(
    val userId: String,
    val sessionId: String,
    val deviceId: String,
    val vaultToken: String,
) {
    // Ohne Tresor-Token, damit er nie im Log landet.
    override fun toString() = "VerifyUnlockedRequest(userId=$userId)"
}
