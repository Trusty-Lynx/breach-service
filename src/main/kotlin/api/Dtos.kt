package de.trustylynx.api

import de.trustylynx.domain.AccountBreaches
import kotlinx.serialization.Serializable

@Serializable
data class AccountCheckRequest(
    /** `email_hash` oder `username`. */
    val type: String,
    val value: String,
) {
    // Ohne value, damit ein Benutzername nie im Log landet.
    override fun toString() = "AccountCheckRequest(type=$type)"
}

@Serializable
data class AccountCheckResponse(
    val found: Int,
    val fields: List<String>,
    val sources: List<BreachSourceDto>,
)

@Serializable
data class BreachSourceDto(
    val name: String,
    /** `YYYY-MM`, fehlt, wenn LeakCheck das Datum nicht kennt. */
    val date: String? = null,
)

fun AccountBreaches.toResponse() = AccountCheckResponse(
    found = found,
    fields = fields,
    sources = sources.map { BreachSourceDto(it.name, it.date) },
)
