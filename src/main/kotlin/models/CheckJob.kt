package de.trustylynx.models

// immutable: every status change replaces the job in the map, so route and worker never see a half-updated job
data class CheckJob(
    val id: String,
    // userId from the login token, only this user may read the job
    val ownerId: String,
    // set to null once finished, so the username doesn't stay in memory longer than needed
    val query: AccountQuery?,
    val status: JobStatus,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long? = null,
    val result: AccountBreaches? = null,
    // set for DONE and FAILED, the retention time starts here
    val finishedAt: Long? = null,
)

enum class JobStatus {
    QUEUED,
    RUNNING,
    // LeakCheck was unreachable or answered 429, the worker tries again at nextAttemptAt
    WAITING_FOR_UPSTREAM,
    DONE,
    FAILED,
}
