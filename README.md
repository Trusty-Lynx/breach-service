# breach-service

Prüft, ob ein Konto in bekannten Datenlecks vorkommt (über die Public API von LeakCheck), und liefert nur Quellen und Datenarten, nie die geleakten Daten selbst. Er speichert nichts dauerhaft und kennt weder Passwörter noch E-Mails im Klartext: E-Mails kommen nur als gekürzter SHA-256-Hash an.

Kotlin 2.4 · Ktor 3.6 · Java 21

## Endpoints

| Methode | Pfad | Zweck | Wer darf das aufrufen? |
|---|---|---|---|
| `GET` | `/health` | Läuft der Service? | alle, ohne Token |
| `POST` | `/breach/account` | Leak-Check für `email_hash` oder `username` anlegen, antwortet sofort mit `202`, `jobId` und `Location` | angemeldete Nutzer mit entsperrtem Tresor (`Authorization: Bearer`, `X-Vault-Token`) |
| `GET` | `/breach/account/jobs/{jobId}` | Status und Ergebnis eines Leak-Checks abfragen (Polling) | nur der Nutzer, der den Job angelegt hat, mit entsperrtem Tresor |

Ablauf: Der Login-Token wird bei jeder Anfrage beim auth-service geprüft (`POST /internal/sessions/verify`), danach der Tresor-Token beim vault-service (`POST /internal/vault/unlocked`). Erst dann landet die Abfrage in einer Queue. Genau ein Worker fragt LeakCheck, damit das Limit von einer Anfrage pro Sekunde für alle Nutzer zusammen eingehalten wird. Ist LeakCheck nicht erreichbar, wartet der Job mit Backoff und gibt nach einer Stunde auf. Ergebnisse werden 12 Stunden im RAM gecacht, Benutzernamen dabei nur als Hash.

## Struktur

```text
src/main/kotlin/
├── Application.kt     Start: liest die Konfiguration, baut alle Teile, verbindet sie
├── AppConfig.kt       alle Umgebungsvariablen an einer Stelle
├── routes/            HTTP: Request lesen, Eingaben prüfen, Service aufrufen, antworten
├── services/          BreachService (Cache vor LeakCheck), CheckJobQueue (Queue und Worker)
├── clients/           auth-service, vault-service, LeakCheck
├── models/            Requests/Responses, Jobs, Fehler
├── security/          LoginTokenVerifier
└── plugins/           JSON, Fehlerantworten, CORS, Logging
src/main/resources/
├── application.yaml
└── logback.xml
src/test/kotlin/       Endpoint-Tests und Fakes, clients/ und security/ wie in main
```

Die Clients sind Interfaces, damit die Tests Fakes einsetzen können. `configureApp(...)` ist von `module()` getrennt, damit die Tests die App mit diesen Fakes starten.

## Lokal starten

```bash
# auth-service und vault-service müssen laufen
AUTH_SERVICE_URL=http://localhost:7491 VAULT_SERVICE_URL=http://localhost:7493 ./gradlew run -Pdevelopment
```

Mit `-Pdevelopment` gelten die Dev-Defaults für `SERVICE_TOKEN_GATEWAY` und `SERVICE_TOKEN_BREACH`, die zu den Dev-Defaults von auth- und vault-service passen. Der Service läuft dann auf Port 8080. Alle Variablen stehen in `.env.example`.

Fat-Jar bauen: `./gradlew buildFatJar`, danach liegt es unter `build/libs/breach-service-all.jar`.

## Tests

```bash
./gradlew test
```

Die Tests brauchen weder Docker noch einen anderen Service. Auth-Service, Vault-Service und LeakCheck werden durch Fakes oder die Ktor-`MockEngine` ersetzt, deshalb wird nichts übersprungen.

## Deploy

Ein Push auf den Branch `deploy` startet `.github/workflows/deploy.yml`. Die Action kopiert das Repo per `rsync` nach `/opt/trustylynx/breach-service/` und führt dort `docker compose build` und `docker compose up -d` aus. Der Container hängt im Netz `trustylynx-net` und ist auf Port 7497 erreichbar. Die `.env` liegt nur auf dem Server, als Vorlage dient `.env.example`.

## Offen

- `GET /breach/range/{prefix}` (Passwort-Leak-Check über HIBP, US-23) ist bewusst nicht umgesetzt, US-23 ist Won't.
- Jobs und Cache liegen nur im RAM. Nach einem Neustart sind offene Jobs weg, und der Client muss sie neu anlegen.
- Ein 429 von LeakCheck wird immer mit 1 Sekunde angesetzt, ein mitgeschicktes `Retry-After` wird nicht gelesen. Wegen der Mindestwartezeit von 5 Sekunden fällt das bisher nicht auf.
- Benutzernamen dürfen nur `A–Z a–z 0–9 . _ -` enthalten, alles andere lehnt LeakCheck ab (am 2026-11-03 geprüft). Solche Einträge aus dem Tresor bekommen `400` und werden nicht geprüft.
- Lehnt LeakCheck eine Abfrage trotzdem ab („invalid characters“, „Could not determine search type“), ist der Job sofort `failed`. Unbekannte Fehlermeldungen gelten weiter als Ausfall und werden bis zu einer Stunde wiederholt. In der Zeit blockiert so ein Job den einzigen Worker.
- Struktur: `security/LoginTokenVerifier` benutzt `clients/AuthServiceClient`. Das weicht von der Tabelle in SERVICE-STRUKTUR.md ab und bleibt vorerst so, weil es sich nur mit einer Logikänderung auflösen ließe.
