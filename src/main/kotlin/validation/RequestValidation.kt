package de.trustylynx.validation

import de.trustylynx.api.AccountCheckRequest
import de.trustylynx.common.BadRequest
import de.trustylynx.domain.AccountQuery
import de.trustylynx.domain.AccountType

// Ganze Requests prüfen und in Domain-Objekte übersetzen. Wirft [BadRequest].
// Ein Request läuft immer api → validation → domain, die Domain bekommt nur geprüfte Daten.

/** POST /breach/account */
fun AccountCheckRequest.validate(): AccountQuery = when (type) {
    "email_hash" -> AccountQuery(AccountType.EMAIL_HASH, validateEmailHash(value))
    "username" -> AccountQuery(AccountType.USERNAME, validateUsername(value))
    else -> throw BadRequest("type must be email_hash or username")
}
