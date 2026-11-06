package de.trustylynx.clients

import de.trustylynx.VAULT_TOKEN
import de.trustylynx.models.Caller
import de.trustylynx.models.UpstreamException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpVaultServiceClientTest {

    @Test
    fun `isUnlocked schickt Caller und Tresor-Token, 204 heisst entsperrt`() = runBlocking<Unit> {
        val client = clientRespondingWith { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://vault-service/internal/vault/unlocked", request.url.toString())
            assertEquals(SERVICE_TOKEN, request.headers[SERVICE_TOKEN_HEADER])

            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("userId", "sessionId", "vaultToken"), body.keys)
            assertEquals("u42", body.getValue("userId").jsonPrimitive.content)
            assertEquals("s-1", body.getValue("sessionId").jsonPrimitive.content)
            assertEquals(VAULT_TOKEN, body.getValue("vaultToken").jsonPrimitive.content)

            respond("", HttpStatusCode.NoContent)
        }

        assertTrue(client.isUnlocked(CALLER, VAULT_TOKEN))
    }

    @Test
    fun `401 vom Vault heisst gesperrt`() = runBlocking<Unit> {
        val client = clientRespondingWith { respond("""{"error":"vault_locked"}""", HttpStatusCode.Unauthorized) }

        assertFalse(client.isUnlocked(CALLER, VAULT_TOKEN))
    }

    @Test
    fun `403 (falscher Breach-Token) wird zu UpstreamException`() = runBlocking<Unit> {
        val client = clientRespondingWith { respond("""{"error":"forbidden"}""", HttpStatusCode.Forbidden) }

        assertFailsWith<UpstreamException> { client.isUnlocked(CALLER, VAULT_TOKEN) }
    }

    @Test
    fun `Vault nicht erreichbar wird zu UpstreamException`() = runBlocking<Unit> {
        val client = clientRespondingWith { throw java.net.ConnectException("Connection refused") }

        assertFailsWith<UpstreamException> { client.isUnlocked(CALLER, VAULT_TOKEN) }
    }

    private fun clientRespondingWith(handler: MockRequestHandler): HttpVaultServiceClient {
        // same settings as createServiceHttpClient, just with MockEngine instead of CIO
        val http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(ServiceJson) }
        }
        return HttpVaultServiceClient(http, "http://vault-service/", SERVICE_TOKEN)
    }

    private companion object {
        const val SERVICE_TOKEN = "test-service-token-breach"
        val CALLER = Caller(userId = "u42", sessionId = "s-1")
    }
}
