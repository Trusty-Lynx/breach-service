package de.trustylynx.domain

import de.trustylynx.clients.LeakCheckClient
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Leak-Check für Konten über LeakCheck, mit Cache davor.
 *
 * Wird nur noch vom Worker der [de.trustylynx.jobs.CheckJobQueue] aufgerufen, nie direkt aus einer Route.
 * Das Rate-Limit von LeakCheck (1 Anfrage pro Sekunde pro IP) hält deshalb die Queue ein, nicht diese Klasse.
 */
class BreachService(
    private val leakCheck: LeakCheckClient,
    // So lange cached auch Cloudflare vor LeakCheck (Cache-Control: max-age=43200).
    private val cacheTtlMillis: Long = 12 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, CachedResult>()

    /** Ergebnis aus dem Cache oder null. Fragt LeakCheck nie. */
    fun cached(query: AccountQuery): AccountBreaches? =
        cache[cacheKey(query)]?.takeIf { it.expiresAt > now() }?.result

    /**
     * Fragt LeakCheck und merkt sich das Ergebnis.
     * Wirft UpstreamException (nicht erreichbar, kaputte Antwort) oder TooManyRequests (LeakCheck-Rate-Limit).
     */
    suspend fun lookup(query: AccountQuery): AccountBreaches {
        val result = leakCheck.lookup(query.value)
        remember(cacheKey(query), result)
        return result
    }

    private fun remember(key: String, result: AccountBreaches) {
        if (cache.size >= MAX_CACHE_ENTRIES) {
            val t = now()
            cache.entries.removeIf { it.value.expiresAt <= t }
            // Immer noch voll → alles weg. Kostet nur erneute Anfragen an LeakCheck.
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
        }
        cache[key] = CachedResult(result, now() + cacheTtlMillis)
    }

    // Benutzernamen liegen nicht im Klartext im RAM-Cache, nur als Hash.
    private fun cacheKey(query: AccountQuery): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${query.type}|${query.value}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class CachedResult(val result: AccountBreaches, val expiresAt: Long)

    private companion object {
        const val MAX_CACHE_ENTRIES = 10_000
    }
}
