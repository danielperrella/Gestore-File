# Esempi di sviluppo e verifica

Gli esempi usano soltanto comandi e flussi presenti nel repository; crea cartelle e file di prova prima di usarli con operazioni distruttive.

## Verifica locale

```sh
gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

La stessa sequenza è documentata nel README. Per Drive di test, completa prima i passaggi OAuth in [`GOOGLE_DRIVE_SETUP.md`](../GOOGLE_DRIVE_SETUP.md), poi aggiungi `-PdriveConfigured=true` alla riga Gradle.

## Flussi d’uso

- Memoria USB: apri **Le tue posizioni → Aggiungi USB / cartella**, seleziona la cartella nel selettore Android e concedi l’accesso.
- Copia/spostamento: seleziona file, scegli **Copia/Sposta**, raggiungi la destinazione e incolla. Usa file di prova e controlla l’esito prima di rimuovere gli originali esternamente.
- Archivio: seleziona elementi e apri **Altro** per creare ZIP o estrarre in una nuova cartella.
- Recupero: vai a **Cestino locale** per ripristinare; gli elementi Drive hanno il cestino dell’account separato.

Per il collaudo su dispositivo segui `VERIFICA_DISPOSITIVO.md`; per verificare una release usa `node scripts/verify-release.mjs`. Non inserire credenziali o chiavi private negli esempi.
