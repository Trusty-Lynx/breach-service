package de.trustylynx

import de.trustylynx.clients.AuthServiceClient
import de.trustylynx.clients.LeakCheckClient
import de.trustylynx.clients.VaultServiceClient
import de.trustylynx.clients.VerifiedSession
import de.trustylynx.models.AccountBreaches
import de.trustylynx.models.Caller
import de.trustylynx.models.UpstreamException
import java.time.Instant
import java.util.Collections

// login token of u42 (session s-1)
internal const val LOGIN_TOKEN = "login-token-u42"

internal class FakeAuthService : AuthServiceClient {
    var fail = false
    var calls = 0
    val sessions = mutableMapOf(
        LOGIN_TOKEN to VerifiedSession("u42", "s-1", Instant.parse("2030-01-01T00:00:00Z")),
    )

    override suspend fun verifySession(token: String): VerifiedSession? {
        calls++
        if (fail) throw UpstreamException("auth-service down")
        return sessions[token]
    }
}

// vault token u42 unlocked the vault with
internal const val VAULT_TOKEN = "vault-token-u42"

internal class FakeVaultService : VaultServiceClient {
    var fail = false
    var calls = 0
    var lastCaller: Caller? = null

    // unlocked vaults: vault token to userId
    val unlocked = mutableMapOf(VAULT_TOKEN to "u42")

    override suspend fun isUnlocked(caller: Caller, vaultToken: String): Boolean {
        calls++
        lastCaller = caller
        if (fail) throw UpstreamException("vault-service down")
        return unlocked[vaultToken] == caller.userId
    }
}

// remembers what was looked up
internal class FakeLeakCheck(
    private val answer: (String) -> AccountBreaches = { AccountBreaches.NONE },
) : LeakCheckClient {
    val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun lookup(check: String): AccountBreaches {
        lookups += check
        return answer(check)
    }
}
