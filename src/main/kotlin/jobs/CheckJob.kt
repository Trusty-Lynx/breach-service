package de.trustylynx.jobs

import de.trustylynx.domain.AccountBreaches
import de.trustylynx.domain.AccountQuery

/**
 * Eine Leak-Check-Abfrage in der Queue. Unveränderlich: Jede Statusänderung ersetzt den Job in der Map
 * (siehe [CheckJobQueue]), damit Route und Worker nie einen halb geänderten Job sehen.
 */
data class CheckJob(
    val id: String,
    /** userId aus dem Login-Token. Nur dieser Nutzer darf den Job abfragen. */
    val ownerId: String,
    /** Wird nach dem Abschluss auf null gesetzt, damit der Benutzername nicht länger als nötig im RAM liegt. */
    val query: AccountQuery?,
    val status: JobStatus,
    val createdAt: Long,
    /** Wie oft LeakCheck für diesen Job schon gefragt wurde. */
    val attempts: Int = 0,
    /** Nur bei [JobStatus.WAITING_FOR_UPSTREAM]: wann der Worker es wieder versucht. */
    val nextAttemptAt: Long? = null,
    /** Nur bei [JobStatus.DONE]. */
    val result: AccountBreaches? = null,
    /** Gesetzt bei [JobStatus.DONE] und [JobStatus.FAILED]. Ab dann läuft die Aufbewahrungszeit. */
    val finishedAt: Long? = null,
) {
    val isFinished: Boolean get() = status == JobStatus.DONE || status == JobStatus.FAILED
}

enum class JobStatus {
    /** Wartet in der Queue, bis der Worker dran ist. */
    QUEUED,

    /** Der Worker fragt gerade LeakCheck. */
    RUNNING,

    /** LeakCheck war nicht erreichbar oder hat 429 geantwortet. Der Worker versucht es um `nextAttemptAt` erneut. */
    WAITING_FOR_UPSTREAM,

    /** Fertig, Ergebnis liegt in `result`. */
    DONE,

    /** Aufgegeben: LeakCheck war zu lange nicht erreichbar oder es gab einen unerwarteten Fehler. */
    FAILED,
}
