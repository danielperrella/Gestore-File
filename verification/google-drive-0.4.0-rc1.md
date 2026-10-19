# Verifiche Google Drive 0.4.0-rc1

19 ottobre 2026. Versione candidata, codice 5; pacchetto e certificato originali conservati.

## Eseguito

- Core: 34 test JVM superati, incluse copie remote sempre verificate, permessi negati, nome esplicito della copia e modifica concorrente della destinazione.
- App: 31 test superati (19 specifici Drive), inclusi paginazione, nomi duplicati, isolamento account, cache privata, origine HTTPS, upload riprendibile, cancellazione, creazione incerta, nessuna ripetizione automatica delle mutazioni, cestino/ripristino e messaggi senza contenuto riservato del server.
- La fixture con stato esercita insieme FileEngine, DriveStorage e protocollo API: upload di 700.000 byte in tre blocchi, verifica prima della pubblicazione, corruzione in upload, sostituzione di un solo ID duplicato, trasferimento Drive → locale e modifica concorrente all’ultimo passaggio nel cestino.
- `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`: riusciti; lint senza errori bloccanti.
- `node scripts/package-release.mjs`: candidata in `candidate/`, senza cambiare manifest o APK stabili.
- `node scripts/verify-release.mjs --candidate` e verifica della distribuzione stabile: riusciti; controllo hash, dimensione, pacchetto, versione e certificato originale.
- Emulatore API 34: installazione release sopra la precedente, elenco locale, apertura Posizioni e messaggio esplicito della build non ancora configurata per OAuth.
- Creato progetto Google Cloud `gestore-file-android`; Google Drive API abilitata. Preparati nome app, email proprietario e pubblico esterno del consenso OAuth.

## Da completare prima della disponibilità pubblica

Il consenso OAuth è fermo all’accettazione delle norme Google, da completare personalmente dal proprietario. Client OAuth Android, account di test, collaudo con due account reali e verifica dello scope ristretto non sono completati. I test simulati non dimostrano che un account Google reale possa accedere alla candidata.

Il canale pubblico rimane alla 0.3.0. Il collegamento Google nella build predefinita è disabilitato finché non viene completata la configurazione; `-PdriveConfigured=true` è riservato alla build di collaudo dopo la configurazione. Vedere `GOOGLE_DRIVE_SETUP.md`.
