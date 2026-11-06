package de.trustylynx.models

private val ID = Regex("^[A-Za-z0-9._-]{1,128}$")

// same format for userId and sessionId, whether they come from a header or from the auth-service
fun isValidId(value: String): Boolean = ID.matches(value)

// only ever built from the verified login token, never from the body
class Caller(
    val userId: String,
    val sessionId: String,
)
