# Test e verifica

## Suite presenti

- `core/src/test/.../FileEngineTest.kt`: test JUnit JVM su trasferimenti, integrità, conflitti, annullamento, rollback, cestino, ZIP Slip, mutazioni sorgente e limiti.
- `app/src/test/...`: test JUnit/Robolectric per storage, UI/permessi, autorizzazione Drive e trasferimenti Drive con fixture di trasporto. I test remoti non sostituiscono prove OAuth reali.
- `verification/`: report XML e verifiche APK/dispositivo conservati come evidenza storica. Le date nei report identificano una specifica esecuzione, non lo stato del branch corrente.
- `.github/workflows/checks.yml`: controlli automatici su Gradle; `.github/workflows/pages.yml`: build/pubblicazione del sito e verifica release (consultare i workflow prima di modificarne i comandi).

## Comando documentato

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Su Windows usare `gradlew.bat`. Per la candidata Drive, il setup OAuth e il flag sono descritti in `GOOGLE_DRIVE_SETUP.md`. Le prove dispositivo e chiavetta sono in `VERIFICA_DISPOSITIVO.md`.

## Limiti e accettazione

Non risultano test E2E automatizzati sul telefono nel set di sorgenti esaminato. Compatibilità con modelli, filesystem USB e provider SAF non è deducibile dai test locali. Prima di accettare modifiche al core, verifica i test e lint pertinenti e che errori/cancellazioni non eliminino originali o backup. Per UI verifica lifecycle, revoca permessi e stati di errore. Per Drive usa dati di prova e conferma account/scope/configurazione; non dichiarare completato il collaudo dalla sola fixture.

Non è definita una soglia numerica di copertura. Non sono stati eseguiti test durante la creazione di questa documentazione.
