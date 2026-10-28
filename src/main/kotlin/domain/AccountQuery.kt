package de.trustylynx.domain

/** Was geprüft wird. Der Wert ist bereits validiert und normalisiert. */
class AccountQuery(
    val type: AccountType,
    val value: String,
) {
    // Kein data class: toString() darf den Benutzernamen nicht in Logs schreiben.
    override fun toString(): String = "AccountQuery(type=$type)"
}

enum class AccountType {
    /** SHA-256 der E-Mail, gekürzt auf 24 Hex-Zeichen. Die E-Mail selbst kommt nie beim Server an. */
    EMAIL_HASH,

    /** Benutzername im Klartext. Der Client schickt ihn nur nach Zustimmung des Nutzers. */
    USERNAME,
}
