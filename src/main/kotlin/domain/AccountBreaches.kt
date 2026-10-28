package de.trustylynx.domain

/** Ergebnis von LeakCheck: nur Quellen und Datenkategorien, nie die geleakten Daten selbst. */
data class AccountBreaches(
    val found: Int,
    val fields: List<String>,
    val sources: List<BreachSource>,
) {
    companion object {
        val NONE = AccountBreaches(found = 0, fields = emptyList(), sources = emptyList())
    }
}

/** [date] ist `YYYY-MM` oder null, wenn LeakCheck das Datum nicht kennt. */
data class BreachSource(
    val name: String,
    val date: String?,
)
