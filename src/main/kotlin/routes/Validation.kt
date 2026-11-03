package de.trustylynx.routes

import de.trustylynx.models.AccountQuery
import de.trustylynx.models.AccountType
import de.trustylynx.models.BadRequest

// SHA-256 of the email, cut to 24 hex characters, like LeakCheck expects it
private val EMAIL_HASH = Regex("^[0-9a-f]{24}$")

// LeakCheck only searches from 3 characters on, the upper limit is the max email length
private const val MIN_USERNAME_LENGTH = 3
private const val MAX_USERNAME_LENGTH = 254

fun validateAccountQuery(type: String, value: String): AccountQuery = when (type) {
    "email_hash" -> AccountQuery(AccountType.EMAIL_HASH, validateEmailHash(value))
    "username" -> AccountQuery(AccountType.USERNAME, validateUsername(value))
    else -> throw BadRequest("type must be email_hash or username")
}

private fun validateEmailHash(value: String): String {
    val hash = value.lowercase()
    if (!EMAIL_HASH.matches(hash)) throw BadRequest("email_hash must be 24 hex characters")
    return hash
}

private fun validateUsername(value: String): String {
    val username = value.trim()
    if (username.length !in MIN_USERNAME_LENGTH..MAX_USERNAME_LENGTH) {
        throw BadRequest("username must be $MIN_USERNAME_LENGTH to $MAX_USERNAME_LENGTH characters")
    }
    if (username.any { it.isISOControl() }) throw BadRequest("username contains control characters")
    // LeakCheck would search this as a plain email, emails only go out hashed
    if ('@' in username) throw BadRequest("emails must be sent as email_hash")
    return username
}
