package de.trustylynx.models

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class AccountCheckRequest(
    // email_hash or username
    val type: String,
    val value: String,
) {
    // without value, so a username never ends up in a log
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
    val date: String? = null,
)

@Serializable
data class CheckJobResponse(
    val jobId: String,
    // queued, running, waiting_for_upstream, done or failed
    val status: String,
    val attempts: Int,
    // only for waiting_for_upstream, ISO-8601 in UTC
    val nextAttemptAt: String? = null,
    val result: AccountCheckResponse? = null,
    val error: String? = null,
)

@Serializable
data class HealthResponse(val status: String)

fun AccountBreaches.toResponse() = AccountCheckResponse(
    found = found,
    fields = fields,
    sources = sources.map { BreachSourceDto(it.name, it.date) },
)

fun CheckJob.toResponse() = CheckJobResponse(
    jobId = id,
    status = status.name.lowercase(),
    attempts = attempts,
    nextAttemptAt = nextAttemptAt?.let { Instant.ofEpochMilli(it).toString() },
    result = result?.toResponse(),
    error = if (status == JobStatus.FAILED) "upstream_unavailable" else null,
)
