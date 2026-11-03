package de.trustylynx.jobs

import de.trustylynx.common.TooManyRequests
import de.trustylynx.common.UpstreamException
import de.trustylynx.domain.AccountBreaches
import de.trustylynx.domain.AccountQuery
import de.trustylynx.domain.BreachService
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

/**
 * Asynchrone Leak-Checks: Die Route legt nur einen Job an ([submit]) und antwortet sofort mit 202.
 * EIN Worker arbeitet die Queue der Reihe nach ab und fragt LeakCheck.
 *
 * Warum genau ein Worker? LeakCheck erlaubt 1 Anfrage pro Sekunde pro IP, also für alle Nutzer zusammen.
 * Mit einem Worker reicht ein einfacher Abstand zwischen zwei Aufrufen, ohne Locks und Zeitfenster.
 *
 * LeakCheck nicht erreichbar (UpstreamException) oder 429 (TooManyRequests): Der Job bleibt vorne in der Queue,
 * der Worker wartet mit wachsendem Abstand (Backoff) und versucht es erneut. Weil LeakCheck dann für ALLE Jobs weg ist,
 * warten die anderen Jobs einfach mit, statt LeakCheck zusätzlich zu bombardieren. Erst nach
 * [QueueSettings.giveUpAfterMillis] gibt der Worker einen Job auf (FAILED).
 *
 * Alles liegt im RAM: Nach einem Neustart sind offene Jobs weg, der Client muss sie neu anlegen.
 * Wer das ändern will, ersetzt [jobs] und [queue] durch etwas Dauerhaftes (z. B. Redis), die Routen bleiben gleich.
 */
class CheckJobQueue(
    private val breaches: BreachService,
    private val settings: QueueSettings = QueueSettings(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Alle Jobs (offen und fertig) nach ID. Fertige werden nach [QueueSettings.keepFinishedMillis] gelöscht. */
    private val jobs = ConcurrentHashMap<String, CheckJob>()

    /** Reihenfolge der offenen Jobs, nur IDs. Begrenzt, damit bei langem LeakCheck-Ausfall der RAM nicht vollläuft. */
    private val queue = Channel<String>(settings.capacity)

    /** Nur der Worker liest und schreibt das, deshalb ohne Lock. */
    private var lastCallAt: Long? = null

    /**
     * Legt einen Job an. Steht das Ergebnis schon im Cache, ist der Job sofort DONE und kommt gar nicht in die Queue.
     * Queue voll → [TooManyRequests].
     */
    fun submit(ownerId: String, query: AccountQuery): CheckJob {
        val t = now()
        breaches.cached(query)?.let { result ->
            val job = CheckJob(newId(), ownerId, null, JobStatus.DONE, t, result = result, finishedAt = t)
            jobs[job.id] = job
            return job
        }

        val job = CheckJob(newId(), ownerId, query, JobStatus.QUEUED, t)
        jobs[job.id] = job
        if (queue.trySend(job.id).isFailure) {
            jobs.remove(job.id)
            throw TooManyRequests(retryAfterSeconds = QUEUE_FULL_RETRY_AFTER_SECONDS)
        }
        return job
    }

    /** Job von [ownerId], oder null. Fremde Jobs sind wie nicht vorhanden, damit man keine IDs erraten kann. */
    fun get(ownerId: String, id: String): CheckJob? = jobs[id]?.takeIf { it.ownerId == ownerId }

    /**
     * Startet Worker und Aufräumer in [scope]. In der App ist das die Application:
     * Beim Stoppen werden beide Coroutines automatisch abgebrochen.
     */
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
                // Nur der Typ, nicht die Exception: Message oder cause könnten den Benutzernamen enthalten.
                // Der Worker darf hier nicht sterben, sonst bleiben alle folgenden Jobs für immer QUEUED.
                log.error("Check job {} failed unexpectedly: {}", job.id, e::class.simpleName)
                finish(job.id, JobStatus.FAILED)
            }
        }
    }

    /** Fragt LeakCheck für [job], bis es klappt oder der Job aufgegeben wird. */
    private suspend fun process(job: CheckJob) {
        val query = job.query ?: return
        while (true) {
            // Ein Job weiter vorne in der Queue hat vielleicht dasselbe schon geholt.
            breaches.cached(query)?.let {
                finish(job.id, JobStatus.DONE, it)
                return
            }

            awaitRateLimit()
            val attempt = update(job.id) {
                it.copy(status = JobStatus.RUNNING, attempts = it.attempts + 1, nextAttemptAt = null)
            }?.attempts ?: return

            val wait = try {
                finish(job.id, JobStatus.DONE, breaches.lookup(query))
                return
            } catch (e: TooManyRequests) {
                log.warn("LeakCheck rate limit hit (job {}, attempt {})", job.id, attempt)
                maxOf(e.retryAfterSeconds * 1000, settings.retryBaseMillis)
            } catch (e: UpstreamException) {
                // Die Message enthält keinen Benutzernamen, siehe HttpLeakCheckClient.send().
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

    /** Mindestabstand zwischen zwei LeakCheck-Aufrufen, auch über Jobs hinweg. */
    private suspend fun awaitRateLimit() {
        lastCallAt?.let { last ->
            val wait = last + settings.minIntervalMillis - now()
            if (wait > 0) delay(wait)
        }
        lastCallAt = now()
    }

    /** 5 s, 10 s, 20 s, 40 s … höchstens [QueueSettings.retryMaxMillis]. */
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
        jobs.values.removeIf { job -> job.finishedAt?.let { t - it >= settings.keepFinishedMillis } == true }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CheckJobQueue::class.java)
        const val QUEUE_FULL_RETRY_AFTER_SECONDS = 30L
    }
}

/** Stellschrauben der Queue. Defaults für Prod, Tests setzen kleine Werte. */
data class QueueSettings(
    /** Höchstens so viele Jobs warten gleichzeitig. Danach gibt es 429. */
    val capacity: Int = 1_000,
    // Etwas mehr als 1 s Abstand, damit wir nicht durch Ungenauigkeit über das Limit von LeakCheck rutschen.
    val minIntervalMillis: Long = 1_100,
    /** Erste Wartezeit nach einem Fehlschlag, verdoppelt sich mit jedem weiteren Versuch. */
    val retryBaseMillis: Long = 5_000,
    val retryMaxMillis: Long = 5 * 60_000L,
    /** So lange (ab Anlegen) versucht es der Worker, dann FAILED. */
    val giveUpAfterMillis: Long = 60 * 60_000L,
    /** So lange kann der Client ein fertiges Ergebnis noch abholen. */
    val keepFinishedMillis: Long = 60 * 60_000L,
    val cleanupIntervalMillis: Long = 60_000,
)
