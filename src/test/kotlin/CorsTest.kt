package de.trustylynx

import de.trustylynx.common.CorsConfig
import de.trustylynx.domain.BreachService
import de.trustylynx.security.LoginTokenVerifier
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LIVE_SERVER = "http://127.0.0.1:5500"
private const val APP_ORIGIN = "https://app.nexvent.de"

/** Nach dem CorsTest im vault-service. POST /breach/account ohne Login-Token gibt 401, das reicht für CORS. */
class CorsTest {

    @Test
    fun `eine lokale Origin darf aufrufen, auch Fehlerantworten tragen den CORS-Header`() = corsTest {
        val response = postAccountFrom(LIVE_SERVER)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(LIVE_SERVER, response.allowOrigin())
    }

    @Test
    fun `Preflight von lokaler Origin erlaubt JSON und unsere Header`() = corsTest {
        val response = preflight("http://localhost:3000", "content-type,authorization,x-device-id,x-vault-token,x-request-id")

        assertTrue(response.status.value in 200..299, "status ${response.status}")
        assertEquals("http://localhost:3000", response.allowOrigin())
        val allowed = assertNotNull(response.headers[HttpHeaders.AccessControlAllowHeaders]).lowercase()
        for (h in listOf("content-type", "authorization", "x-device-id", "x-vault-token", "x-request-id")) {
            assertTrue(h in allowed, "$h fehlt in $allowed")
        }
    }

    @Test
    fun `Retry-After ist fuer den Browser lesbar`() = corsTest {
        val exposed = postAccountFrom(LIVE_SERVER).headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().lowercase()

        assertTrue("retry-after" in exposed, "retry-after fehlt in $exposed")
    }

    @Test
    fun `Preflight mit unbekanntem Header wird abgelehnt`() = corsTest {
        val response = preflight("http://localhost:3000", "content-type,x-evil")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.allowOrigin())
    }

    @Test
    fun `eine konfigurierte Origin darf aufrufen`() = corsTest(CorsConfig(allowedOrigins = setOf(APP_ORIGIN))) {
        assertEquals(APP_ORIGIN, postAccountFrom(APP_ORIGIN).allowOrigin())
    }

    @Test
    fun `ohne CORS_ALLOWED_ORIGINS darf vorerst jede Origin aufrufen`() = corsTest(CorsConfig(allowAnyOrigin = true)) {
        assertEquals("*", postAccountFrom("https://website.example").allowOrigin())
    }

    @Test
    fun `eine fremde Origin wird abgelehnt`() = corsTest {
        val response = postAccountFrom("https://evil.example")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.allowOrigin())
    }

    @Test
    fun `Anfragen ohne Origin (Bruno, curl, andere Services) sind nicht betroffen`() = corsTest {
        val response = client.post("/breach/account")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.headers.names().none { it.startsWith("Access-Control-", ignoreCase = true) })
    }

    private fun corsTest(cors: CorsConfig = CorsConfig(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application { configureApp(BreachService(FakeLeakCheck()), LoginTokenVerifier(FakeAuthService()), FakeVaultService(), cors) }
        block()
    }

    private suspend fun ApplicationTestBuilder.postAccountFrom(origin: String): HttpResponse = client.post("/breach/account") {
        header(HttpHeaders.Origin, origin)
    }

    private suspend fun ApplicationTestBuilder.preflight(origin: String, requestHeaders: String): HttpResponse =
        client.options("/breach/account") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, requestHeaders)
        }

    private fun HttpResponse.allowOrigin(): String? = headers[HttpHeaders.AccessControlAllowOrigin]
}
