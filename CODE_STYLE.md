# Convenzioni di codice

Le regole seguenti descrivono la configurazione e i pattern presenti; non introducono un formatter o un linter ulteriore.

- **Kotlin/Java:** package sotto `it.liberofiles.app` e `it.liberofiles.core`; Kotlin segue `kotlin.code.style=official`. Classi e tipi in PascalCase, funzioni e proprietà in camelCase, costanti in maiuscolo quando già usate.
- **Moduli:** regole di file e trasferimento in `core`; accesso Android, lifecycle e UI in `app`. Le astrazioni di storage passano da `Storage`/`Stores`, evitando riferimenti Android nel core.
- **Responsabilità:** usa adapter/provider per differenze fra filesystem, SAF e Drive; mantieni verifiche e politiche comuni nel motore. Preferisci funzioni mirate e risultati/exception espliciti già adottati dal modulo.
- **Concorrenza:** non eseguire scansioni o trasferimenti pesanti sul thread UI. Rispetta cancellazione, lifecycle Activity e serializzazione delle scritture esistenti.
- **Errori e logging:** mostra errori comprensibili senza includere token, contenuti o credenziali. Non registrare segreti. Conserva il journal operativo e i messaggi di recupero già usati.
- **Dipendenze:** Gradle Kotlin DSL e versioni dichiarate nei file Gradle; nessuna nuova dipendenza senza requisito autorizzato. Non incorporare SDK o chiavi di distribuzione.
- **Test:** JUnit nel core; JUnit/Robolectric per app. Usa fixture temporanee e trasporti finti per API remote; test reali su Android/SAF/Drive sono verifiche manuali distinte.
- **Commit:** nessuna convenzione automatica di messaggio commit rilevata nei file esaminati. Segui la richiesta del task; non inventare vincoli.

Prima di cambiare un pattern, cerca i chiamanti e i test e mantieni compatibilità tra moduli. I comandi disponibili sono nel README e in `AGENTS.md`.
