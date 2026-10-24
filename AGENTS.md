# Istruzioni per agenti — Gestore File

## Contesto

App Android per gestire file locali, memorie USB concesse tramite SAF e, nella build candidata, account Google Drive. Il motore riutilizzabile è `core/`; UI, provider e integrazioni Android sono in `app/`. Il README alla radice e `GOOGLE_DRIVE_SETUP.md` descrivono dettagli di prodotto e distribuzione.

## Prima di intervenire

- Controlla branch, `git status` e diff. Non includere modifiche locali preesistenti.
- Esamina i moduli e i test relativi alla modifica. Il comportamento del motore è definito dal codice e dai test in `core/src/test`.
- Per permessi e storage Android leggi `AndroidManifest.xml`, `DocumentStorage.kt` e le implementazioni dello store; per Drive consulta `DriveAccounts.kt`, `DriveApi.kt`, `DriveStorage.kt` e `GOOGLE_DRIVE_SETUP.md`.
- Non confondere le verifiche JVM/Robolectric con una prova su telefono, USB o account Google reali.

## Struttura e convenzioni

- `core/src/main/kotlin/it/liberofiles/core`: motore indipendente da Android, astrazioni `Storage`, trasferimenti, journal e cestino.
- `app/src/main/java/it/liberofiles/app`: Activity, servizi, adapter per filesystem/SAF/Drive e componenti Android.
- `app/src/test` e `core/src/test`: test JUnit; app usa Robolectric.
- Gradle Kotlin DSL, Kotlin style ufficiale (`kotlin.code.style=official`), package `it.liberofiles.*`. Mantieni il core senza dipendenze Android.
- Conserva trasferimenti in streaming, worker seriale per le scritture, controlli di integrità e politiche di conflitto esistenti.

## Comandi verificati dal progetto

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Su Windows: `gradlew.bat` con gli stessi task. Drive nelle build di test richiede la configurazione OAuth Android descritta in `GOOGLE_DRIVE_SETUP.md` e `-PdriveConfigured=true`; il solo flag non crea la configurazione Google.

## Vincoli

- Non rimuovere o indebolire le verifiche prima di eliminare gli originali. In caso di copia/move fallito o annullato preserva i dati sorgente e i residui incerti.
- Le API SAF dipendono dal provider: non assumere atomicità o supporto uniforme a rename/fsync.
- Non aggiungere segreti, chiavi di firma, token Google o credenziali al repository, all’APK o ai log. Non ampliare scope OAuth senza requisito documentato.
- Gestisci credenziali/token Drive in memoria secondo l’implementazione corrente; non introdurre persistenza non documentata.
- Non introdurre SDK, dipendenze, comportamento o refactoring fuori ambito. Non cambiare file applicativi per una richiesta documentale.

## Verifica e Definition of Done

- Aggiungi o aggiorna test per le modifiche al motore, ai provider e ai flussi UI interessati.
- Esegui i task Gradle pertinenti e riporta quelli non eseguibili; i test automatici non sostituiscono i collaudi fisici descritti nei documenti.
- Rivedi `git diff`, `git diff --check`, permessi e manifest se pertinenti. La modifica è pronta quando preserva le invarianti di integrità, i test passano e le limitazioni sono dichiarate senza promettere compatibilità hardware non verificata.
