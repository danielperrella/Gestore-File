# Google Drive — configurazione e collaudo

Sorgenti: candidata **0.4.0-rc1 / codice 5**. Il canale pubblico resta **0.3.0 / codice 4** fino al collaudo reale e alla verifica OAuth. Il progetto Cloud dedicato creato per questa integrazione è `gestore-file-android`; non sono necessari backend, service account, API key, client secret o chiavi da inserire nell’APK.

## Configurazione nel progetto del proprietario

1. Apri [Google Cloud](https://console.cloud.google.com/apis/dashboard?project=gestore-file-android) e abilita **Google Drive API**.
2. Configura Google Auth Platform: nome **Gestore File**, pubblico **Esterno**, email supporto del proprietario e contatto sviluppatore del proprietario. Homepage `https://danielperrella.github.io/Gestore-File/`; privacy `https://danielperrella.github.io/Gestore-File/privacy.html`. Completa la verifica del dominio richiesta da Google; un dominio dedicato può essere necessario se Google non accetta la proprietà di GitHub Pages.
3. Crea un client OAuth di tipo **Android**: pacchetto `it.liberofiles.app`; SHA-1 della firma di distribuzione `CC:5D:CA:40:20:4B:B6:3E:CA:EC:AE:25:55:D1:A3:6B:E7:9E:48:88`. Verifica la firma con `apksigner verify --print-certs` prima di registrarla. Non cambiare chiave per questa integrazione.
4. Aggiungi lo scope `https://www.googleapis.com/auth/drive`: è necessario per navigare tutti i file esistenti, non soltanto quelli creati dall’app. Nella fase **Testing** aggiungi esplicitamente due account di prova. Non richiedere scope di contatti, Gmail, Calendar o accesso offline del server.
5. Dopo la configurazione, compila con `gradlew.bat -PdriveConfigured=true :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`. Non basta il flag per autorizzare l’app: il client Android e il consenso devono essere configurati su Google.
6. Senza `-PdriveConfigured=true`, la candidata spiega la configurazione mancante quando si preme **Aggiungi Google Drive**, senza avviare un login non funzionante. Le funzioni locali restano utilizzabili.

Riferimenti ufficiali: [AuthorizationClient](https://developer.android.com/identity/authorization), [scope Drive](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [verifica scope ristretti](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification).

## Collaudo con dati dedicati

- Su un dispositivo con Play Services collega entrambi gli account di test. Verifica che ciascuno abbia una posizione distinta, anche dopo riavvio, e che riconnettere A non cambi l’identità di B.
- Prepara in entrambi una cartella `GestoreFile-Test`, file binari vuoti, piccoli e grandi, sottocartelle, due file con lo stesso nome e un documento Google per ciascun formato Docs/Sheets/Slides. Usa solo dati di prova.
- Prova navigazione e ordinamento, paginazione con più di 1.000 elementi, preferiti e ritorno alla cartella superiore. Drive condivisi aziendali e scorciatoie ricorsive sono esclusi: le scorciatoie si aprono esternamente.
- Copia telefono ↔ Drive, USB ↔ Drive e account A ↔ B; confronta SHA-256 dei file scaricati. Negli spostamenti verifica che la rimozione avvenga solo dopo la copia completa; su Drive si usa il cestino.
- Prova copia e sostituzione con nomi duplicati: il dialogo deve richiedere l’ID preciso da sostituire, preservando gli altri file. Gli spostamenti nello stesso Drive con Conserva entrambi/Salta modificano i genitori tramite API; la sostituzione di file binari passa invece dalla copia verificata. Per i documenti Google usa Conserva entrambi/Salta: non vengono convertiti implicitamente per una sostituzione.
- Prova creazione cartelle, rinomina anche multipla, cestino e ripristino. L’app non offre eliminazione definitiva né svuotamento di Drive. Cartelle contenenti documenti Google non scaricabili o nomi duplicati incompatibili con una copia gerarchica interrompono la copia e conservano gli originali.
- Apri e condividi un binario; esporta esplicitamente documenti Google in PDF e DOCX/XLSX/PPTX. I limiti di esportazione di Google si applicano. Nessuna trasformazione automatica dei documenti negli spostamenti.
- Interrompi rete e annulla durante un upload a blocchi. Dopo una risposta persa la sessione deve interrogare l’offset prima di rispedire byte. Dopo arresto processo, verifica il registro e i residui; non è prevista ripresa automatica di transazioni incerte fra riavvii.
- Prova permessi read-only, download negato, quota esaurita, scadenza token e revoca da Google. Verifica che dati, file e appunti non vengano associati a un altro account.
- Scollega A: B resta disponibile, file online di A intatti, cache/preferiti/recenti di A rimossi e URI già condivisi invalidati. Lo scollegamento richiede rete per completare la revoca.

## Promozione pubblica

Esegui `node scripts/package-release.mjs` per preparare `candidate/gestorefile.apk` e i metadati locali, poi `node scripts/verify-release.mjs --candidate`. La cartella candidate è esclusa da Git e non modifica `docs/update.json`.

Non pubblicare la candidata nel manifest automatico. Completa configurazione branding, documentazione privacy, verifica OAuth per lo scope ristretto e test reali. Non dichiarare la verifica completata sulla base dei soli test simulati. Il fatto che i file transitino direttamente dal telefono a Google non elimina automaticamente tutti i requisiti di verifica del progetto.

Solo dopo queste verifiche: versione stabile **0.4.0 / codice 6**, build con `-PdriveConfigured=true`, test/lint/release, `node scripts/package-release.mjs "Google Drive disponibile" --drive-approved`, verifica firma e metadati, commit con APK/manifest e release GitHub. Chi usa la 0.3.0 riceverà allora l’aggiornamento all’apertura senza disinstallare.

> Aggiornamento distribuzione: su richiesta del proprietario, la 0.4.0-rc1 è ora offerta anche dal controllo all’apertura come versione di prova (channel preview). L’APK pubblicato è compilato con -PdriveConfigured=true. Configurazione OAuth e collaudo con account reali restano incompleti; non equivale a una release stabile verificata.


## Stato aggiornato: 0.4.0-rc2

Consenso sviluppatore completato su Google Cloud. Registrato client Android per `it.liberofiles.app` con la SHA-1 originale sopra indicata. Configurato solo lo scope `https://www.googleapis.com/auth/drive`, home page e privacy pubbliche; stato Google **Test**, un account del proprietario abilitato come tester. Non equivale alla verifica Google per disponibilità generale. Ulteriori account vanno aggiunti esplicitamente alla lista tester prima del collegamento.

La candidata corrente è **0.4.0-rc2 / codice 6**, compilata con `-PdriveConfigured=true` e offerta dal manifest pubblico `channel=preview`. Conserva gli errori restituiti da Google anche quando l’Activity ritorna RESULT_CANCELED; mostra il codice numerico senza includere messaggi sensibili. Propagazione impostazioni Google e prova reale dal telefono restano da verificare. Per una futura versione stabile usare almeno codice **7**.
