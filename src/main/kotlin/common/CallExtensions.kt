package de.trustylynx.common

import io.ktor.server.application.ApplicationCall

// Wer anfragt, steht im Login-Token (Authorization: Bearer …), geprüft vom LoginTokenVerifier beim Auth-Service.
// Die Header hier sagen nur, WAS gemeint ist (Gerät, Tresor-Token), nie WER.

/** Gerät, an das der Tresor-Token gebunden ist. Vorübergehend optional, siehe [NO_DEVICE_ID]. */
const val DEVICE_ID_HEADER = "X-Device-Id"

/** Tresor-Token aus POST /vault/unlock. Der Leak-Check läuft nur bei entsperrtem Tresor, geprüft beim Vault-Service. */
const val VAULT_TOKEN_HEADER = "X-Vault-Token"

private val ID = Regex("^[A-Za-z0-9._-]{1,128}$")

/** Gleiches Format für userId, deviceId und sessionId, egal ob sie aus einem Header oder vom Auth-Service kommen. */
fun isValidId(value: String): Boolean = ID.matches(value)

/**
 * VORÜBERGEHEND: Platzhalter, wenn der Client keine X-Device-Id schickt. Muss gleich sein wie im vault-service,
 * sonst passt der Tresor-Token nicht (er ist dort an diese deviceId gebunden).
 * TODO: Entfernen und X-Device-Id Pflicht machen, sobald die Clients die deviceId immer mitschicken.
 */
const val NO_DEVICE_ID = "no-device"

/** Wer die Anfrage stellt. Kommt nur aus dem geprüften Login-Token (LoginTokenVerifier), nie aus dem Body. */
class Caller(
    val userId: String,
    val deviceId: String,
    val sessionId: String,
)

/** Der Tresor-Token aus [VAULT_TOKEN_HEADER], oder null. Geprüft wird er beim Vault-Service. */
fun ApplicationCall.vaultToken(): String? = request.headers[VAULT_TOKEN_HEADER]
