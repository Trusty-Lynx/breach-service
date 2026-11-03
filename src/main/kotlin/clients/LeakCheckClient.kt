package de.trustylynx.clients

import de.trustylynx.models.AccountBreaches
import de.trustylynx.models.BreachSource
import de.trustylynx.models.QueryRejected
import de.trustylynx.models.TooManyRequests
import de.trustylynx.models.UpstreamException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

private const val WHAT = "leakcheck public"
private const val NOT_FOUND = "Not found"

// answers for queries LeakCheck can't search, they come as HTTP 200 with success = false
private val REJECTED = setOf(
    "Your query contains invalid characters",
    "Could not determine search type automatically",
)

interface LeakCheckClient {
    suspend fun lookup(check: String): AccountBreaches
}

// public API without an API key: https://docs.leakcheck.io/public-api/lookup
class HttpLeakCheckClient(
    private val http: HttpClient,
    private val baseUrl: String,
) : LeakCheckClient {

    override suspend fun lookup(check: String): AccountBreaches {
        val response = send(check)
        if (response.status == HttpStatusCode.TooManyRequests) throw TooManyRequests(retryAfterSeconds = 1)

        val body = response.bodyOrThrow<LeakCheckResponse>(WHAT)
        if (!body.success) {
            // no match comes as HTTP 200 with success = false
            if (body.error == NOT_FOUND) return AccountBreaches.NONE
            if (body.error in REJECTED) throw QueryRejected("$WHAT: ${body.error}")
            throw UpstreamException("$WHAT: ${body.error ?: "success = false"}")
        }
        return AccountBreaches(
            found = body.found ?: 0,
            fields = body.fields.orEmpty(),
            sources = body.sources.orEmpty().map { BreachSource(it.name, if (it.date.isNullOrBlank()) null else it.date) },
        )
    }

    // On purpose not callUpstream: that adds e.message and the cause, and Ktor puts the URL with
    // ?check=<username> in there (on timeouts, for example). StatusPages logs the whole chain,
    // but the username must never reach the log. So only the exception type, without cause.
    private suspend fun send(check: String): HttpResponse = try {
        http.get(serviceUrl(baseUrl, "api", "public")) {
            parameter("check", check)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw UpstreamException("$WHAT unreachable: ${e::class.simpleName}")
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
