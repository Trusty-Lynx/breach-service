package de.trustylynx.validation

import de.trustylynx.common.BadRequest

// Einzelne Felder prüfen. Ganze Requests setzt RequestValidation.kt daraus zusammen.

// LeakCheck: SHA-256 der E-Mail, gekürzt auf 24 Hex-Zeichen
private val EMAIL_HASH = Regex("^[0-9a-f]{24}$")

// LeakCheck sucht erst ab 3 Zeichen. Nach oben wie die maximale E-Mail-Länge begrenzt.
internal const val MIN_USERNAME_LENGTH = 3
internal const val MAX_USERNAME_LENGTH = 254

/** Hash in Kleinbuchstaben oder [BadRequest]. */
internal fun validateEmailHash(value: String): String {
    val hash = value.lowercase()
    if (!EMAIL_HASH.matches(hash)) throw BadRequest("email_hash must be 24 hex characters")
    return hash
}

/** Benutzername ohne Leerzeichen am Rand oder [BadRequest]. */
internal fun validateUsername(value: String): String {
    val username = value.trim()
    if (username.length !in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH) {
        throw BadRequest("username must be $MIN_USERNAME_LENGTH to $MAX_USERNAME_LENGTH characters")
    }
    if (username.any { it.isISOControl() }) throw BadRequest("username contains control characters")
    // LeakCheck würde das als E-Mail im Klartext suchen. E-Mails gehen nur gehasht raus.
    if ('@' in username) throw BadRequest("emails must be sent as email_hash")
    return username
}
