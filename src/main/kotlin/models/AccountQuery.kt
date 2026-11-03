package de.trustylynx.models

// no data class, so toString() can't write the username into a log
class AccountQuery(
    val type: AccountType,
    val value: String,
)

enum class AccountType {
    // SHA-256 of the email, cut to 24 hex characters. The email itself never reaches the server.
    EMAIL_HASH,

    // plain username, the client only sends it after the user agreed
    USERNAME,
}
