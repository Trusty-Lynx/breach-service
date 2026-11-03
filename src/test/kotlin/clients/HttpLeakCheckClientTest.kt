package de.trustylynx.clients

import de.trustylynx.models.AccountBreaches
import de.trustylynx.models.BreachSource
import de.trustylynx.models.TooManyRequests
import de.trustylynx.models.UpstreamException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

// answers as returned by https://leakcheck.io/api/public (checked live on 2026-10-28)
class HttpLeakCheckClientTest {

    @Test
    fun `Treffer werden uebernommen und leeres Datum wird null`() = runBlocking<Unit> {
        val client = clientRespondingWith { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://leakcheck.io/api/public?check=johndoe", request.url.toString())
            respondJson(
                """{"success":true,"found":2,"fields":["password"],"sources":[{"name":"Twitter.com","date":"2015-11"},{"name":"CDEK","date":""}],"quota":0}""",
            )
        }

        assertEquals(
            AccountBreaches(2, listOf("password"), listOf(BreachSource("Twitter.com", "2015-11"), BreachSource("CDEK", null))),
            client.lookup("johndoe"),
        )
    }

    @Test
    fun `Not found ist kein Treffer`() = runBlocking<Unit> {
        val client = clientRespondingWith { respondJson("""{"success": false, "error": "Not found"}""") }

        assertEquals(AccountBreaches.NONE, client.lookup("zq9xk2v7"))
    }

    @Test
    fun `andere Fehler von LeakCheck werden zu UpstreamException`() = runBlocking<Unit> {
        val client = clientRespondingWith { respondJson("""{"success": false, "error": "Enter at least 3 characters to search"}""") }

        assertFailsWith<UpstreamException> { client.lookup("ab") }
    }

    @Test
    fun `429 von LeakCheck wird zu TooManyRequests`() = runBlocking<Unit> {
        val client = clientRespondingWith { respondJson("""{"success": false}""", HttpStatusCode.TooManyRequests) }

        assertFailsWith<TooManyRequests> { client.lookup("johndoe") }
    }

    @Test
    fun `kaputter Body wird zu UpstreamException`() = runBlocking<Unit> {
        val client = clientRespondingWith { respondJson("""{"nope":1}""") }

        assertFailsWith<UpstreamException> { client.lookup("johndoe") }
    }

    @Test
    fun `Netzwerkfehler verraten den Benutzernamen nicht`() = runBlocking<Unit> {
        val client = clientRespondingWith { throw java.io.IOException("timeout for https://leakcheck.io/api/public?check=johndoe") }

        val e = assertFailsWith<UpstreamException> { client.lookup("johndoe") }
        assertFalse("johndoe" in e.stackTraceToString())
    }

    private fun clientRespondingWith(handler: MockRequestHandler): HttpLeakCheckClient {
        // same settings as createServiceHttpClient, just with MockEngine instead of CIO
        val http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(ServiceJson) }
        }
        return HttpLeakCheckClient(http, "https://leakcheck.io/")
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        json: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))
}
