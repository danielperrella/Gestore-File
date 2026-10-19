# Candidata Google Drive 0.4.0-rc2

- Versione 0.4.0-rc2, codice 6; firma originale e Drive abilitato.
- Test core e app, lint debug, assembleRelease riusciti. Tre test aggiuntivi verificano distinzione degli errori OAuth e assenza di dati sensibili nei messaggi; totale 68 test.
- Verificati APK candidato e pubblico: hash, dimensione, pacchetto, versione, firma.
- Creato client Android in Google Cloud per pacchetto e SHA-1 della distribuzione. Consenso, scope Drive e branding salvati, account proprietario presente nei tester, stato Google Test.
- Nessun test di login con account reale eseguito in questa sessione; il proprietario deve riprovare dal telefono dopo la propagazione Google. Non dichiarare la verifica pubblica OAuth completata.
