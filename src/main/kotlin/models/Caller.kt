package de.trustylynx.models

private val ID = Regex("^[A-Za-z0-9._-]{1,128}$")

// same format for userId, deviceId and sessionId, whether they come from a header or from the auth-service
fun isValidId(value: String): Boolean = ID.matches(value)

// placeholder while clients don't always send X-Device-Id. Has to match the vault-service,
// otherwise the vault token doesn't fit (it's bound to this deviceId there).
// TODO: remove and make X-Device-Id required once all clients send the deviceId
const val NO_DEVICE_ID = "no-device"

// only ever built from the verified login token, never from the body
class Caller(
    val userId: String,
    val deviceId: String,
    val sessionId: String,
)
