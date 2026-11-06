package de.trustylynx.clients

import de.trustylynx.models.Caller
import de.trustylynx.models.UpstreamException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

private const val WHAT = "vault-service vault/unlocked"

interface VaultServiceClient {
    // false if the vault is locked, expired or belongs to someone else
    suspend fun isUnlocked(caller: Caller, vaultToken: String): Boolean
}

// serviceToken is SERVICE_TOKEN_BREACH
class HttpVaultServiceClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val serviceToken: String,
) : VaultServiceClient {

    override suspend fun isUnlocked(caller: Caller, vaultToken: String): Boolean {
        val response = callUpstream(WHAT) {
            http.post(serviceUrl(baseUrl, "internal", "vault", "unlocked")) {
                header(SERVICE_TOKEN_HEADER, serviceToken)
                contentType(ContentType.Application.Json)
                setBody(VerifyUnlockedRequest(caller.userId, caller.sessionId, vaultToken))
            }
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> true
            // 401 vault_locked isn't an outage, it means "vault locked"
            HttpStatusCode.Unauthorized -> false
            else -> throw UpstreamException("$WHAT answered ${response.status.value}")
        }
    }
}

@Serializable
private data class VerifyUnlockedRequest(
    val userId: String,
    val sessionId: String,
    val vaultToken: String,
) {
    // without the vault token, so it never ends up in a log
    override fun toString() = "VerifyUnlockedRequest(userId=$userId)"
}
