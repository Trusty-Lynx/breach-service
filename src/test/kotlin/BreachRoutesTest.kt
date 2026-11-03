package de.trustylynx

import de.trustylynx.clients.VerifiedSession
import de.trustylynx.models.AccountBreaches
import de.trustylynx.models.BreachSource
import de.trustylynx.models.NO_DEVICE_ID
import de.trustylynx.models.QueryRejected
import de.trustylynx.models.TooManyRequests
import de.trustylynx.models.UpstreamException
import de.trustylynx.routes.DEVICE_ID_HEADER
import de.trustylynx.routes.VAULT_TOKEN_HEADER
import de.trustylynx.security.LoginTokenVerifier
import de.trustylynx.services.BreachService
import de.trustylynx.services.CheckJobQueue
import de.trustylynx.services.QueueSettings
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// POST /breach/account and GET /breach/account/jobs/{jobId}, see documentation/api/breach-service/check-account.md
class BreachRoutesTest {

    private val auth = FakeAuthService()
    private val vault = FakeVaultService()
    private var leakCheck = FakeLeakCheck()
    private var breaches = BreachService(leakCheck)

    // small times, so retries only take milliseconds
    private var queueSettings = QueueSettings(
        minIntervalMillis = 0,
        retryBaseMillis = 10,
        retryMaxMillis = 20,
        giveUpAfterMillis = 10_000,
    )

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
            val json = checkAccount(body("email_hash", HASH.uppercase()))

            assertEquals(2, json.getValue("found").jsonPrimitive.int)
            assertEquals(listOf("password", "username"), json.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
            val sources = json.getValue("sources").jsonArray.map { it.jsonObject }
            assertEquals("2015-11", sources[0].getValue("date").jsonPrimitive.content)
            assertNull(sources[1]["date"])
            // the hash is passed on in lower case
            assertEquals(listOf(HASH), leakCheck.lookups)
        }
    }

    @Test
    fun `ohne Treffer kommt found 0`() = breachTest {
        val json = checkAccount(body("username", "zq9xk2v7"))

        assertEquals(0, json.getValue("found").jsonPrimitive.int)
        assertTrue(json.getValue("sources").jsonArray.isEmpty())
    }

    @Test
    fun `Benutzername wird vor der Abfrage getrimmt`() = breachTest {
        checkAccount(body("username", "  johndoe "))

        assertEquals(listOf("johndoe"), leakCheck.lookups)
    }

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

    @Test
    fun `ungueltige Eingaben ergeben 400 und erreichen LeakCheck nie`() = breachTest {
        val bad = listOf(
            body("email_hash", HASH.dropLast(1)),
            body("email_hash", "g".repeat(24)),
            body("username", "ab"),
            body("username", "a".repeat(255)),
            body("username", "max@example.com"),
            body("username", "max mustermann"),
            body("username", "jörg"),
            body("username", "max+1"),
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

    @Test
    fun `POST antwortet sofort mit 202, jobId und Location`() = breachTest {
        val response = postAccount(body("username", "johndoe"))

        assertEquals(HttpStatusCode.Accepted, response.status)
        val json = response.json()
        val jobId = json.getValue("jobId").jsonPrimitive.content
        assertEquals("$JOBS_PATH/$jobId", response.headers[HttpHeaders.Location])
        assertTrue(json.status() in setOf("queued", "running", "done"))
    }

    @Test
    fun `fertiger Job hat status done und attempts 1`() = breachTest {
        val job = awaitJob(postAccount(body("username", "johndoe")))

        assertEquals("done", job.status())
        assertEquals(1, job.getValue("attempts").jsonPrimitive.int)
        assertNull(job["error"])
    }

    @Test
    fun `gleiche Abfrage kommt aus dem Cache und ist sofort done`() = breachTest {
        checkAccount(body("username", "johndoe"))
        val second = postAccount(body("username", "johndoe")).json()

        assertEquals("done", second.status())
        assertEquals(0, second.getValue("attempts").jsonPrimitive.int)
        assertEquals(1, leakCheck.lookups.size)
    }

    @Test
    fun `Cache laeuft nach der TTL ab`() {
        var now = 0L
        breaches = BreachService(leakCheck, cacheTtlMillis = 1_000, now = { now })
        breachTest {
            checkAccount(body("username", "johndoe"))
            now = 1_000
            checkAccount(body("username", "johndoe"))

            assertEquals(2, leakCheck.lookups.size)
        }
    }

    @Test
    fun `LeakCheck kurz weg - der Worker versucht es spaeter erneut`() {
        var calls = 0
        leakCheck = FakeLeakCheck {
            calls++
            if (calls <= 2) throw UpstreamException("leakcheck down")
            AccountBreaches(1, listOf("password"), listOf(BreachSource("Twitter.com", "2015-11")))
        }
        breaches = BreachService(leakCheck)
        // slow retry, so we're sure to see the waiting state
        queueSettings = queueSettings.copy(retryBaseMillis = 300, retryMaxMillis = 300)
        breachTest {
            val response = postAccount(body("username", "johndoe"))

            val waiting = awaitJob(response) { it == "waiting_for_upstream" }
            assertTrue(waiting.getValue("nextAttemptAt").jsonPrimitive.content.endsWith("Z"))

            val done = awaitJob(response)
            assertEquals("done", done.status())
            assertEquals(3, done.getValue("attempts").jsonPrimitive.int)
            assertEquals(1, done.getValue("result").jsonObject.getValue("found").jsonPrimitive.int)
        }
    }

    @Test
    fun `429 von LeakCheck wird ebenfalls spaeter wiederholt`() {
        var calls = 0
        leakCheck = FakeLeakCheck {
            calls++
            if (calls == 1) throw TooManyRequests(retryAfterSeconds = 0)
            AccountBreaches.NONE
        }
        breaches = BreachService(leakCheck)
        breachTest {
            val done = awaitJob(postAccount(body("username", "johndoe")))

            assertEquals("done", done.status())
            assertEquals(2, done.getValue("attempts").jsonPrimitive.int)
        }
    }

    @Test
    fun `LeakCheck zu lange weg - Job wird aufgegeben`() {
        breaches = BreachService(FakeLeakCheck { throw UpstreamException("leakcheck down") })
        queueSettings = queueSettings.copy(giveUpAfterMillis = 100)
        breachTest {
            val job = awaitJob(postAccount(body("username", "johndoe")))

            assertEquals("failed", job.status())
            assertEquals("upstream_unavailable", job.getValue("error").jsonPrimitive.content)
            assertNull(job["result"])
        }
    }

    @Test
    fun `von LeakCheck abgelehnte Abfrage ist sofort failed und blockiert die Queue nicht`() {
        leakCheck = FakeLeakCheck { check ->
            if (check == "johndoe") throw QueryRejected("leakcheck public: Your query contains invalid characters")
            AccountBreaches.NONE
        }
        breaches = BreachService(leakCheck)
        // with retries this long the second job would wait for minutes if the first one were retried
        queueSettings = queueSettings.copy(retryBaseMillis = 60_000, retryMaxMillis = 60_000, giveUpAfterMillis = 120_000)
        breachTest {
            val rejected = postAccount(body("username", "johndoe"))
            val next = postAccount(body("username", "janedoe"))

            val job = awaitJob(rejected)
            assertEquals("failed", job.status())
            assertEquals(1, job.getValue("attempts").jsonPrimitive.int)
            assertEquals("done", awaitJob(next).status())
            assertEquals(listOf("johndoe", "janedoe"), leakCheck.lookups)
        }
    }

    @Test
    fun `Jobs hinter einem haengenden Job laufen danach weiter`() {
        var calls = 0
        leakCheck = FakeLeakCheck {
            calls++
            if (calls == 1) throw UpstreamException("leakcheck down")
            AccountBreaches.NONE
        }
        breaches = BreachService(leakCheck)
        breachTest {
            val first = postAccount(body("username", "johndoe"))
            val second = postAccount(body("username", "janedoe"))

            assertEquals("done", awaitJob(first).status())
            assertEquals("done", awaitJob(second).status())
            assertEquals(listOf("johndoe", "johndoe", "janedoe"), leakCheck.lookups)
        }
    }

    @Test
    fun `volle Queue ergibt 429 mit Retry-After`() {
        // the worker is stuck on the first job, the queue only holds one more
        breaches = BreachService(FakeLeakCheck { throw UpstreamException("leakcheck down") })
        queueSettings = queueSettings.copy(
            capacity = 1,
            retryBaseMillis = 60_000,
            retryMaxMillis = 60_000,
            // otherwise the worker gives up right away, because the next attempt would be past the deadline
            giveUpAfterMillis = 120_000,
        )
        breachTest {
            awaitJob(postAccount(body("username", "johndoe"))) { it == "waiting_for_upstream" }
            assertEquals(HttpStatusCode.Accepted, postAccount(body("username", "janedoe")).status)

            val response = postAccount(body("username", "maxmuster"))

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("too_many_requests", response.errorCode())
            assertEquals("30", response.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun `Job eines anderen Nutzers ist 404`() = breachTest {
        val jobId = postAccount(body("username", "johndoe")).json().getValue("jobId").jsonPrimitive.content
        auth.sessions["login-token-u77"] = VerifiedSession("u77", "s-9", Instant.parse("2030-01-01T00:00:00Z"))
        vault.unlocked["vault-token-u77"] = "u77"

        val response = client.get("$JOBS_PATH/$jobId") {
            header(HttpHeaders.Authorization, "Bearer login-token-u77")
            header(VAULT_TOKEN_HEADER, "vault-token-u77")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.errorCode())
    }

    @Test
    fun `unbekannte jobId ist 404`() = breachTest {
        assertEquals(HttpStatusCode.NotFound, getJob("gibt-es-nicht").status)
    }

    @Test
    fun `Job abfragen braucht Login- und Tresor-Token`() = breachTest {
        val jobId = postAccount(body("username", "johndoe")).json().getValue("jobId").jsonPrimitive.content

        assertEquals(HttpStatusCode.Unauthorized, client.get("$JOBS_PATH/$jobId").status)
        val locked = client.get("$JOBS_PATH/$jobId") { header(HttpHeaders.Authorization, "Bearer $LOGIN_TOKEN") }
        assertEquals("vault_locked", locked.errorCode())
    }

    @Test
    fun `health braucht keinen Token`() = breachTest {
        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.json().getValue("status").jsonPrimitive.content)
    }

    private fun breachTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        // empty config, so application.yaml isn't loaded and module() doesn't start with the real clients
        environment { config = MapApplicationConfig() }
        application { configureApp(CheckJobQueue(breaches, queueSettings), LoginTokenVerifier(auth), vault) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postAccount(body: String): HttpResponse = client.post(PATH) {
        callerHeaders()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.getJob(jobId: String): HttpResponse = client.get("$JOBS_PATH/$jobId") {
        callerHeaders()
    }

    // polls the job until `until` is true (default: finished), like the client does
    private suspend fun ApplicationTestBuilder.awaitJob(
        created: HttpResponse,
        until: (String) -> Boolean = { it == "done" || it == "failed" },
    ): JsonObject {
        assertEquals(HttpStatusCode.Accepted, created.status)
        val jobId = created.json().getValue("jobId").jsonPrimitive.content
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val job = getJob(jobId).json()
            if (until(job.status())) return job
            // real waiting, the worker runs with real delays and not with virtual test time
            Thread.sleep(10)
        }
        fail("job $jobId did not reach the expected status")
    }

    private suspend fun ApplicationTestBuilder.checkAccount(body: String): JsonObject {
        val job = awaitJob(postAccount(body))
        assertEquals("done", job.status())
        return job.getValue("result").jsonObject
    }

    private fun JsonObject.status(): String = getValue("status").jsonPrimitive.content

    private fun HttpRequestBuilder.callerHeaders() {
        header(HttpHeaders.Authorization, "Bearer $LOGIN_TOKEN")
        header(VAULT_TOKEN_HEADER, VAULT_TOKEN)
        header(DEVICE_ID_HEADER, "dev-mac-01")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.errorCode(): String = json().getValue("error").jsonPrimitive.content

    private companion object {
        const val PATH = "/breach/account"
        const val JOBS_PATH = "/breach/account/jobs"

        // SHA-256("test@example.com"), first 24 hex characters
        const val HASH = "973dfe463ec85785f5f95af5"

        fun body(type: String, value: String) = """{"type":"$type","value":"$value"}"""
    }
}
