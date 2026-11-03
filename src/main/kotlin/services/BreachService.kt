package de.trustylynx.services

import de.trustylynx.clients.LeakCheckClient
import de.trustylynx.models.AccountBreaches
import de.trustylynx.models.AccountQuery
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CACHE_ENTRIES = 10_000

// Only the CheckJobQueue worker calls this, never a route directly.
// That's why the queue keeps LeakCheck's rate limit (1 request per second per IP), not this class.
class BreachService(
    private val leakCheck: LeakCheckClient,
    // Cloudflare in front of LeakCheck caches just as long (Cache-Control: max-age=43200)
    private val cacheTtlMillis: Long = 12 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, CachedResult>()

    // never asks LeakCheck
    fun cached(query: AccountQuery): AccountBreaches? {
        val entry = cache[cacheKey(query)] ?: return null
        if (entry.expiresAt <= now()) return null
        return entry.result
    }

    suspend fun lookup(query: AccountQuery): AccountBreaches {
        val result = leakCheck.lookup(query.value)
        remember(cacheKey(query), result)
        return result
    }

    private fun remember(key: String, result: AccountBreaches) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            val t = now()
            cache.entries.removeIf { it.value.expiresAt <= t }
            // still full, so drop everything. Only costs a few more requests to LeakCheck.
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        }
        cache[key] = CachedResult(result, now() + cacheTtlMillis)
    }

    // usernames are only kept as a hash in the cache, never in plain text
    private fun cacheKey(query: AccountQuery): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${query.type}|${query.value}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class CachedResult(val result: AccountBreaches, val expiresAt: Long)
}
