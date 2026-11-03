package de.trustylynx.services

import de.trustylynx.models.AccountBreaches
import de.trustylynx.models.AccountQuery
import de.trustylynx.models.CheckJob
import de.trustylynx.models.CheckJobResponse
import de.trustylynx.models.JobStatus
import de.trustylynx.models.QueryRejected
import de.trustylynx.models.TooManyRequests
import de.trustylynx.models.UpstreamException
import de.trustylynx.models.toResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(CheckJobQueue::class.java)

private const val QUEUE_FULL_RETRY_AFTER_SECONDS = 30L

// Exactly one worker, because LeakCheck allows 1 request per second per IP, for all users together.
// With one worker a simple gap between two calls is enough, no locks or time windows.
// If LeakCheck is down or answers 429, the job stays at the front and the other jobs just wait with it
// instead of hammering LeakCheck even more. Everything lives in memory, open jobs are gone after a restart.
class CheckJobQueue(
    private val breaches: BreachService,
    private val settings: QueueSettings = QueueSettings(),
) {
    // all jobs (open and finished) by id, finished ones get removed after keepFinishedMillis
    private val jobs = ConcurrentHashMap<String, CheckJob>()

    // order of the open jobs, bounded so a long LeakCheck outage can't fill up the memory
    private val queue = Channel<String>(settings.capacity)

    // only the worker reads and writes this, so no lock
    private var lastCallAt: Long? = null

    // a cached result makes the job DONE right away, it never enters the queue
    fun submit(ownerId: String, query: AccountQuery): CheckJobResponse {
        val t = now()
        val cached = breaches.cached(query)
        if (cached != null) {
            val job = CheckJob(newId(), ownerId, null, JobStatus.DONE, t, result = cached, finishedAt = t)
            jobs[job.id] = job
            return job.toResponse()
        }

        val job = CheckJob(newId(), ownerId, query, JobStatus.QUEUED, t)
        jobs[job.id] = job
        if (queue.trySend(job.id).isFailure) {
            jobs.remove(job.id)
            throw TooManyRequests(retryAfterSeconds = QUEUE_FULL_RETRY_AFTER_SECONDS)
        }
        return job.toResponse()
    }

    // other users' jobs look like missing ones, so nobody can guess ids
    fun get(ownerId: String, id: String): CheckJobResponse? {
        val job = jobs[id] ?: return null
        if (job.ownerId != ownerId) return null
        return job.toResponse()
    }

    // in the app the scope is the Application, so both coroutines get cancelled when it stops
    fun start(scope: CoroutineScope) {
        scope.launch(CoroutineName("breach-check-worker")) { work() }
        scope.launch(CoroutineName("breach-job-cleanup")) {
            while (isActive) {
                delay(settings.cleanupIntervalMillis)
                removeExpired()
            }
        }
    }

    private suspend fun work() {
        for (id in queue) {
            val job = jobs[id] ?: continue
            try {
                process(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // only the type, message or cause could contain the username.
                // The worker must not die here, otherwise every following job stays QUEUED forever.
                log.error("Check job {} failed unexpectedly: {}", job.id, e::class.simpleName)
                finish(job.id, JobStatus.FAILED)
            }
        }
    }

    private suspend fun process(job: CheckJob) {
        val query = job.query ?: return
        while (true) {
            // a job further up in the queue might have fetched the same thing already
            val cached = breaches.cached(query)
            if (cached != null) {
                finish(job.id, JobStatus.DONE, cached)
                return
            }

            awaitRateLimit()
            val attempt = update(job.id) {
                it.copy(status = JobStatus.RUNNING, attempts = it.attempts + 1, nextAttemptAt = null)
            }?.attempts ?: return

            val wait = try {
                finish(job.id, JobStatus.DONE, breaches.lookup(query))
                return
            } catch (e: QueryRejected) {
                // fail right away, otherwise this job would block the single worker for up to an hour
                log.warn("LeakCheck rejected check job {}: {}", job.id, e.message)
                finish(job.id, JobStatus.FAILED)
                return
            } catch (e: TooManyRequests) {
                log.warn("LeakCheck rate limit hit (job {}, attempt {})", job.id, attempt)
                maxOf(e.retryAfterSeconds * 1000, settings.retryBaseMillis)
            } catch (e: UpstreamException) {
                // the message has no username in it, see HttpLeakCheckClient.send()
                log.warn("LeakCheck unavailable (job {}, attempt {}): {}", job.id, attempt, e.message)
                backoff(attempt)
            }

            if (now() + wait - job.createdAt > settings.giveUpAfterMillis) {
                log.warn("Giving up on check job {} after {} attempts", job.id, attempt)
                finish(job.id, JobStatus.FAILED)
                return
            }
            update(job.id) { it.copy(status = JobStatus.WAITING_FOR_UPSTREAM, nextAttemptAt = now() + wait) }
            delay(wait)
        }
    }

    // minimum gap between two LeakCheck calls, also across jobs
    private suspend fun awaitRateLimit() {
        val last = lastCallAt
        if (last != null) {
            val wait = last + settings.minIntervalMillis - now()
            if (wait > 0) delay(wait)
        }
        lastCallAt = now()
    }

    // 5 s, 10 s, 20 s, 40 s and so on, up to retryMaxMillis
    private fun backoff(attempt: Int): Long {
        val factor = 1L shl (attempt - 1).coerceIn(0, 20)
        return (settings.retryBaseMillis * factor).coerceAtMost(settings.retryMaxMillis)
    }

    private fun finish(id: String, status: JobStatus, result: AccountBreaches? = null) {
        update(id) {
            it.copy(status = status, result = result, query = null, nextAttemptAt = null, finishedAt = now())
        }
    }

    private fun update(id: String, change: (CheckJob) -> CheckJob): CheckJob? =
        jobs.computeIfPresent(id) { _, job -> change(job) }

    private fun removeExpired() {
        val t = now()
        jobs.values.removeIf { job ->
            val finishedAt = job.finishedAt
            finishedAt != null && t - finishedAt >= settings.keepFinishedMillis
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun newId(): String = UUID.randomUUID().toString()
}

// production defaults, the tests use small values
data class QueueSettings(
    // at most this many jobs wait at the same time, after that it's 429
    val capacity: Int = 1_000,
    // a bit more than 1 s, so timing inaccuracy can't push us over LeakCheck's limit
    val minIntervalMillis: Long = 1_100,
    // first wait after a failure, doubles with every further attempt
    val retryBaseMillis: Long = 5_000,
    val retryMaxMillis: Long = 5 * 60_000L,
    // counted from creation, after that the job becomes FAILED
    val giveUpAfterMillis: Long = 60 * 60_000L,
    // how long the client can still fetch a finished result
    val keepFinishedMillis: Long = 60 * 60_000L,
    val cleanupIntervalMillis: Long = 60_000,
)
