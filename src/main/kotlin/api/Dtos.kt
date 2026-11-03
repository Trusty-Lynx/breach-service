package de.trustylynx.api

import de.trustylynx.domain.AccountBreaches
import de.trustylynx.jobs.CheckJob
import de.trustylynx.jobs.JobStatus
import kotlinx.serialization.Serializable
import java.time.Instant

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

/** Antwort von POST /breach/account (202) und GET /breach/account/jobs/{jobId} (200). */
@Serializable
data class CheckJobResponse(
    val jobId: String,
    /** `queued`, `running`, `waiting_for_upstream`, `done` oder `failed`. */
    val status: String,
    /** Wie oft LeakCheck für diesen Job schon gefragt wurde. */
    val attempts: Int,
    /** Nur bei `waiting_for_upstream`: nächster Versuch, ISO-8601 in UTC. */
    val nextAttemptAt: String? = null,
    /** Nur bei `done`. */
    val result: AccountCheckResponse? = null,
    /** Nur bei `failed`: `upstream_unavailable`. */
    val error: String? = null,
)

fun CheckJob.toResponse() = CheckJobResponse(
    jobId = id,
    status = status.name.lowercase(),
    attempts = attempts,
    nextAttemptAt = nextAttemptAt?.let { Instant.ofEpochMilli(it).toString() },
    result = result?.toResponse(),
    error = if (status == JobStatus.FAILED) "upstream_unavailable" else null,
)
