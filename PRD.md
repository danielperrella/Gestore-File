# PRD — Gestore File

## Scopo e utenti

Gestore File è un’app Android per navigare e gestire file della memoria interna e di memorie autorizzate via USB/SAF. La build candidata integra Google Drive opzionalmente. È rivolta a utenti Android che devono copiare, spostare, organizzare, comprimere, ripristinare e condividere file. Il README descrive la release 0.3.0 come versione di prova e Drive come candidata 0.4.0-rc3; queste etichette non attestano collaudo su ogni dispositivo.

## Funzionalità implementate nel codice/documentate

- Navigazione locale e USB, elenco/griglia, ordinamento, file nascosti, preferiti, recenti, selezione multipla.
- Copia, spostamento, rinomina, creazione cartelle, eliminazione; conflitti conserva entrambi/salta/sostituisci file. Operazioni seriali con avanzamento, annullamento e recupero dei residui.
- ZIP create/estrazione con limiti e difesa dai percorsi di uscita; rinomina multipla con anteprima; analisi spazio; cestino locale, apertura e condivisione esterna.
- Tema chiaro/scuro, testo ridimensionabile, scorciatoie. Aggiornamento APK con controlli di integrità e firma prima della conferma Android.
- Google Drive nella build candidata: account multipli, navigazione, trasferimenti, gestione cestino e export esplicito Docs/Sheets/Slides. Richiede OAuth configurato e collaudo; Drive condivisi aziendali e sync offline sono esclusi.

## Requisiti verificabili

- **R1:** un trasferimento incompleto non deve rimuovere l’originale; una destinazione incompleta non deve essere pubblicata come completa. Evidenza: `FileEngine` e test core.
- **R2:** conflitti file richiedono una scelta; la sostituzione delle cartelle non è automatica.
- **R3:** le operazioni annullabili rispettano il token di cancellazione e segnalano residui incerti per ispezione manuale.
- **R4:** l’accesso a memoria e USB dipende dai permessi Android/Safely granted URI; la revoca invalida le liste esistenti.
- **R5:** update APK controlla dimensione, SHA-256, package, versione e firma installata, poi lascia ad Android la conferma.
- **R6:** i documenti Google vengono esportati solo su azione esplicita e non convertiti implicitamente durante gli spostamenti.

## Limitazioni e stato prodotto

Limitazioni note: niente ricerca, sincronizzazione offline, root, editor o duplicati; API SAF/USB variano tra provider; non è una garanzia contro guasti fisici o modifiche concorrenti; metadati filesystem non equivalgono a un backup completo. I limiti operativi dettagliati sono nel README.

Metriche di successo, analytics e target prestazionali non sono definiti. Roadmap ulteriore non determinabile dai file consultati; le note di release e `GOOGLE_DRIVE_SETUP.md` sono istruzioni/collaudi, non una roadmap generale. Questioni aperte: prova sul telefono/USB specifico, verifica OAuth/Google e stato di pubblicazione stabile della candidata Drive.
