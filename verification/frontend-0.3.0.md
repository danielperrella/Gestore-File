# Verifica interfaccia 0.3.0

19 ottobre 2026.

- `:core:test`: 29 test, nessun errore.
- `:app:testDebugUnitTest`: 12 test, nessun errore.
- `:app:lintDebug` e `:app:assembleRelease`: completati. Restano avvisi di compatibilità Android già presenti; nessun errore bloccante.
- `scripts/verify-release.mjs`: controlla hash, dimensione, versione, pacchetto e certificato originale.
- Emulatore Android API 34: installazione release, navigazione elenco/griglia, tema chiaro/scuro, impostazioni, selezione multipla, preparazione copia e annullamento appunti, caratteri molto grandi, schermata di autorizzazione.
- Browser: pagina a larghezza desktop, 390 px e 320 px, nessuno scorrimento orizzontale; caricamento immagini e cambio anteprima griglia verificati.
- Le immagini in `docs/app-*.png` provengono dall’app release sull’emulatore, con dati di prova.

Questi controlli non certificano il comportamento su Xiaomi e USB fisica. Il motore di operazioni sui file non è stato modificato da questa revisione.
