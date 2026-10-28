package de.trustylynx.domain

import de.trustylynx.clients.LeakCheckClient
import de.trustylynx.common.TooManyRequests
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Leak-Check für Konten über LeakCheck.
 *
 * LeakCheck erlaubt 1 Anfrage pro Sekunde, und zwar pro IP, also für alle Nutzer zusammen.
 * Deshalb vergibt der Service Zeitfenster: Wer höchstens [maxWaitMillis] warten müsste, wartet.
 * Wer länger warten müsste, bekommt sofort [TooManyRequests] und der Client versucht es nach `Retry-After` erneut.
 */
class BreachService(
    private val leakCheck: LeakCheckClient,
    // Etwas mehr als 1 s Abstand, damit wir nicht durch Ungenauigkeit über das Limit rutschen.
    private val minIntervalMillis: Long = 1_100,
    private val maxWaitMillis: Long = 3_000,
    // So lange cached auch Cloudflare vor LeakCheck (Cache-Control: max-age=43200).
    private val cacheTtlMillis: Long = 12 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, CachedResult>()
    private val slotLock = Mutex()
    private var nextFreeSlot = 0L

    suspend fun checkAccount(query: AccountQuery): AccountBreaches {
        val key = cacheKey(query)
        cache[key]?.takeIf { it.expiresAt > now() }?.let { return it.result }

        awaitSlot()
        val result = leakCheck.lookup(query.value)
        remember(key, result)
        return result
    }

    private suspend fun awaitSlot() {
        val wait = slotLock.withLock {
            val t = now()
            val slot = maxOf(t, nextFreeSlot)
            val wait = slot - t
            if (wait > maxWaitMillis) throw TooManyRequests(retryAfterSeconds = (wait + 999) / 1000)
            nextFreeSlot = slot + minIntervalMillis
            wait
        }
        if (wait > 0) delay(wait)
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
