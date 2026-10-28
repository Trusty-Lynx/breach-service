package de.trustylynx

import de.trustylynx.common.DEVICE_ID_HEADER
import de.trustylynx.common.NO_DEVICE_ID
import de.trustylynx.common.UpstreamException
import de.trustylynx.common.VAULT_TOKEN_HEADER
import de.trustylynx.domain.AccountBreaches
import de.trustylynx.domain.BreachService
import de.trustylynx.domain.BreachSource
import de.trustylynx.security.LoginTokenVerifier
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// POST /breach/account, documentation/api/breach-service/check-account.md
class BreachRoutesTest {

    private val auth = FakeAuthService()
    private val vault = FakeVaultService()
    private var leakCheck = FakeLeakCheck()
    private var breaches = BreachService(leakCheck)

    // ---------- Fälle aus der Doku ----------

    @Test
    fun `E-Mail-Hash mit Treffern liefert Quellen und Datenarten`() {
        leakCheck = FakeLeakCheck {
            AccountBreaches(
                found = 2,
                fields = listOf("password", "username"),
                sources = listOf(BreachSource("Twitter.com", "2015-11"), BreachSource("CDEK", null)),
            )
        }
        breaches = BreachService(leakCheck)
        breachTest {
            val response = postAccount(body("email_hash", HASH.uppercase()))

            assertEquals(HttpStatusCode.OK, response.status)
            val json = response.json()
            assertEquals(2, json.getValue("found").jsonPrimitive.int)
            assertEquals(listOf("password", "username"), json.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
            val sources = json.getValue("sources").jsonArray.map { it.jsonObject }
            assertEquals("2015-11", sources[0].getValue("date").jsonPrimitive.content)
            assertNull(sources[1]["date"])
            // Hash wird in Kleinbuchstaben weitergereicht
            assertEquals(listOf(HASH), leakCheck.lookups)
        }
    }

    @Test
    fun `ohne Treffer kommt found 0`() = breachTest {
        val response = postAccount(body("username", "zq9xk2v7"))

        assertEquals(HttpStatusCode.OK, response.status)
        val json = response.json()
        assertEquals(0, json.getValue("found").jsonPrimitive.int)
        assertTrue(json.getValue("sources").jsonArray.isEmpty())
    }

    @Test
    fun `Benutzername wird vor der Abfrage getrimmt`() = breachTest {
        postAccount(body("username", "  johndoe "))

        assertEquals(listOf("johndoe"), leakCheck.lookups)
    }

    // ---------- Login-Token ----------

    @Test
    fun `ohne Login-Token gibt es 401 und LeakCheck wird nicht gefragt`() = breachTest {
        val response = client.post(PATH) {
            contentType(ContentType.Application.Json)
            setBody(body("username", "johndoe"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, auth.calls)
        assertTrue(leakCheck.lookups.isEmpty())
    }

    @Test
    fun `unbekannter Login-Token ergibt 401`() = breachTest {
        val response = client.post(PATH) {
            header(HttpHeaders.Authorization, "Bearer unbekannter-token")
            contentType(ContentType.Application.Json)
            setBody(body("username", "johndoe"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(leakCheck.lookups.isEmpty())
    }

    @Test
    fun `X-User-Id aus der Anfrage ersetzt keinen Login-Token`() = breachTest {
        val response = client.post(PATH) {
            header("X-User-Id", "u42")
            header("X-Session-Id", "s-1")
            contentType(ContentType.Application.Json)
            setBody(body("username", "johndoe"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `ohne gueltigen Login-Token gibt es 401 auch bei kaputtem Body`() = breachTest {
        val response = client.post(PATH) {
            contentType(ContentType.Application.Json)
            setBody("{kaputt")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `Auth-Service nicht erreichbar ergibt 502`() = breachTest {
        auth.fail = true

        assertEquals(HttpStatusCode.BadGateway, postAccount(body("username", "johndoe")).status)
        assertTrue(leakCheck.lookups.isEmpty())
    }

    // ---------- Tresor-Token ----------

    @Test
    fun `ohne Tresor-Token gibt es 401 vault_locked und der Vault wird nicht gefragt`() = breachTest {
        val response = client.post(PATH) {
            header(HttpHeaders.Authorization, "Bearer $LOGIN_TOKEN")
            contentType(ContentType.Application.Json)
            setBody(body("username", "johndoe"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("vault_locked", response.errorCode())
        assertEquals(0, vault.calls)
        assertTrue(leakCheck.lookups.isEmpty())
    }

    @Test
    fun `gesperrter Tresor ergibt 401 vault_locked und LeakCheck wird nicht gefragt`() = breachTest {
        vault.unlocked.clear()

        val response = postAccount(body("username", "johndoe"))

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("vault_locked", response.errorCode())
        assertTrue(leakCheck.lookups.isEmpty())
    }

    @Test
    fun `Vault bekommt userId und sessionId vom Auth-Service und die deviceId der Anfrage`() = breachTest {
        postAccount(body("username", "johndoe"))

        val caller = vault.lastCaller!!
        assertEquals("u42", caller.userId)
        assertEquals("s-1", caller.sessionId)
        assertEquals("dev-mac-01", caller.deviceId)
    }

    @Test
    fun `ohne X-Device-Id fragt der Vault mit dem Platzhalter wie beim Entsperren`() = breachTest {
        client.post(PATH) {
            header(HttpHeaders.Authorization, "Bearer $LOGIN_TOKEN")
            header(VAULT_TOKEN_HEADER, VAULT_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body("username", "johndoe"))
        }

        assertEquals(NO_DEVICE_ID, vault.lastCaller!!.deviceId)
    }

    @Test
    fun `Vault nicht erreichbar ergibt 502`() = breachTest {
        vault.fail = true

        assertEquals(HttpStatusCode.BadGateway, postAccount(body("username", "johndoe")).status)
        assertTrue(leakCheck.lookups.isEmpty())
    }

    // ---------- Validierung ----------

    @Test
    fun `ungueltige Eingaben ergeben 400 und erreichen LeakCheck nie`() = breachTest {
        val bad = listOf(
            body("email_hash", HASH.dropLast(1)),
            body("email_hash", "g".repeat(24)),
            body("username", "ab"),
            body("username", "a".repeat(255)),
            body("username", "max@example.com"),
            body("email", "max@example.com"),
            """{"type":"username"}""",
        )
        for (b in bad) {
            val response = postAccount(b)
            assertEquals(HttpStatusCode.BadRequest, response.status, b)
            assertEquals("bad_request", response.errorCode())
        }
        assertTrue(leakCheck.lookups.isEmpty())
    }

    // ---------- Cache und Rate-Limit ----------

    @Test
    fun `gleiche Abfrage kommt aus dem Cache`() {
        breaches = BreachService(leakCheck, minIntervalMillis = 0)
        breachTest {
            postAccount(body("username", "johndoe"))
            postAccount(body("username", "johndoe"))

            assertEquals(1, leakCheck.lookups.size)
        }
    }

    @Test
    fun `Cache laeuft nach der TTL ab`() {
        var now = 0L
        breaches = BreachService(leakCheck, minIntervalMillis = 0, cacheTtlMillis = 1_000, now = { now })
        breachTest {
            postAccount(body("username", "johndoe"))
            now = 1_000
            postAccount(body("username", "johndoe"))

            assertEquals(2, leakCheck.lookups.size)
        }
    }

    @Test
    fun `zu viele Abfragen hintereinander ergeben 429 mit Retry-After`() {
        // Uhr steht still: Das zweite Zeitfenster liegt 1,1 s in der Zukunft, warten ist nicht erlaubt.
        breaches = BreachService(leakCheck, maxWaitMillis = 0, now = { 0L })
        breachTest {
            assertEquals(HttpStatusCode.OK, postAccount(body("username", "johndoe")).status)
            val response = postAccount(body("username", "janedoe"))

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("too_many_requests", response.errorCode())
            assertEquals("2", response.headers[HttpHeaders.RetryAfter])
            assertEquals(listOf("johndoe"), leakCheck.lookups)
        }
    }

    @Test
    fun `LeakCheck-Ausfall ergibt 502`() {
        breaches = BreachService(FakeLeakCheck { throw UpstreamException("leakcheck down") })
        breachTest {
            val response = postAccount(body("username", "johndoe"))

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertEquals("upstream_error", response.errorCode())
        }
    }

    // ---------- Health ----------

    @Test
    fun `health braucht keinen Token`() = breachTest {
        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.json().getValue("status").jsonPrimitive.content)
    }

    // ---------- Hilfen ----------

    private fun breachTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        // Leere Config, damit nicht application.yaml geladen wird und module() mit echten Clients startet.
        environment { config = MapApplicationConfig() }
        application { configureApp(breaches, LoginTokenVerifier(auth), vault) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postAccount(body: String): HttpResponse = client.post(PATH) {
        callerHeaders()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.callerHeaders() {
        header(HttpHeaders.Authorization, "Bearer $LOGIN_TOKEN")
        header(VAULT_TOKEN_HEADER, VAULT_TOKEN)
        header(DEVICE_ID_HEADER, "dev-mac-01")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** Fehlercode aus {"error": "..."} */
    private suspend fun HttpResponse.errorCode(): String = json().getValue("error").jsonPrimitive.content

    private companion object {
        const val PATH = "/breach/account"

        // SHA-256("test@example.com"), erste 24 Hex-Zeichen
        const val HASH = "973dfe463ec85785f5f95af5"

        fun body(type: String, value: String) = """{"type":"$type","value":"$value"}"""
    }
}
