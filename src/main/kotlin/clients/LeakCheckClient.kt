package de.trustylynx.clients

import de.trustylynx.common.TooManyRequests
import de.trustylynx.common.UpstreamException
import de.trustylynx.common.bodyOrThrow
import de.trustylynx.common.serviceUrl
import de.trustylynx.domain.AccountBreaches
import de.trustylynx.domain.BreachSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Zugriff auf LeakCheck. Als Interface, damit Tests einen Fake einsetzen können. */
interface LeakCheckClient {
    /**
     * Fragt die Public API von LeakCheck. Kein Treffer → [AccountBreaches.NONE].
     * LeakCheck nicht erreichbar oder kaputte Antwort → UpstreamException, LeakCheck-Rate-Limit → TooManyRequests.
     */
    suspend fun lookup(check: String): AccountBreaches
}

/** Public API von LeakCheck, ohne API-Key: https://docs.leakcheck.io/public-api/lookup */
class HttpLeakCheckClient(
    private val http: HttpClient,
    private val baseUrl: String,
) : LeakCheckClient {

    override suspend fun lookup(check: String): AccountBreaches {
        val response = send(check)
        if (response.status == HttpStatusCode.TooManyRequests) throw TooManyRequests(retryAfterSeconds = 1)

        val body = response.bodyOrThrow<LeakCheckResponse>(WHAT)
        if (!body.success) {
            // Kein Treffer kommt als HTTP 200 mit success = false
            if (body.error == NOT_FOUND) return AccountBreaches.NONE
            throw UpstreamException("$WHAT: ${body.error ?: "success = false"}")
        }
        return AccountBreaches(
            found = body.found ?: 0,
            fields = body.fields.orEmpty(),
            sources = body.sources.orEmpty().map { BreachSource(it.name, it.date?.takeIf(String::isNotBlank)) },
        )
    }

    // Absichtlich nicht callUpstream: Das hängt e.message und die Exception als cause an, und Ktor schreibt dort die
    // URL mit ?check=<Benutzername> hinein (z. B. bei Timeouts). StatusPages loggt die ganze Kette, der Benutzername
    // soll aber nie ins Log. Deshalb nur der Typ der Exception, ohne cause.
    private suspend fun send(check: String): HttpResponse = try {
        http.get(serviceUrl(baseUrl, "api", "public")) {
            parameter("check", check)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("$WHAT unreachable: ${e::class.simpleName}")
    }

    private companion object {
        const val WHAT = "leakcheck public"
        const val NOT_FOUND = "Not found"
    }
}

@Serializable
private data class LeakCheckResponse(
    val success: Boolean,
    val found: Int? = null,
    val fields: List<String>? = null,
    val sources: List<LeakCheckSource>? = null,
    val error: String? = null,
)

@Serializable
private data class LeakCheckSource(
    val name: String,
    val date: String? = null,
)
