package de.trustylynx.models

// only sources and data categories from LeakCheck, never the leaked data itself
data class AccountBreaches(
    val found: Int,
    val fields: List<String>,
    val sources: List<BreachSource>,
) {
    companion object {
        val NONE = AccountBreaches(found = 0, fields = emptyList(), sources = emptyList())
    }
}

// date is YYYY-MM, null if LeakCheck doesn't know it
data class BreachSource(
    val name: String,
    val date: String?,
)
