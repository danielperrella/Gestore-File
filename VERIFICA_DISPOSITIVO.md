# Prova sul dispositivo — usare esclusivamente file di test

## Preparazione

1. Installa l'APK e autorizza l'accesso alla memoria interna.
2. Crea `Download/LiberoTest` con sottocartelle, un file vuoto, testo, una foto e un video. Conserva una copia separata.
3. Collega una chiavetta USB, se disponibile, e autorizzala dal selettore Android.
4. Per un controllo indipendente dell'integrità confronta SHA-256 sul PC prima e dopo. Esempi: Linux `sha256sum file`; PowerShell `Get-FileHash file -Algorithm SHA256`.

## Comportamento

| Prova | Risultato atteso |
|---|---|
| Copia normale e con verifica completa | Stessi byte; origine presente; nessun temporaneo dopo il successo. |
| Sposta nella stessa memoria | Contenuto invariato; destinazione presente; origine assente solo dopo il successo. |
| Sposta su USB | Verifica prima della rimozione; nessun originale rimosso se la copia è incompleta. |
| Annulla una copia di un file grande | Origine intatta; eventuali residui segnalati se la memoria non permette la pulizia. |
| Annulla dopo la pubblicazione di uno spostamento | Possibile copia completa con originali ancora presenti; nessuna rimozione cieca. |
| Scollega USB durante copia/verifica | Errore chiaro; originali non ancora rimossi conservati. Ricollegare e controllare i residui. |
| Destinazione piena | Errore; origine e vecchio file destinazione conservati. |
| Conserva entrambi / salta / sostituisci | Nomi distinti / nessuna modifica / vecchio file conservato fino alla nuova copia riuscita. |
| Cartella con nome già esistente | Nessuna unione o sostituzione implicita; conserva entrambi oppure salta. |
| Forza arresto dell'app durante copia | All'avvio compare l'operazione interrotta; niente pulizia automatica rischiosa. |
| Rinomina multipla con conflitto | Anteprima; rifiuto o rollback senza sovrascrivere file estranei. |
| Elimina e ripristina dalla memoria interna | Cestino; eventuale omonimo conservato con nuovo nome al ripristino. |
| Elimina da USB | Conferma esplicita di eliminazione definitiva. |
| ZIP creato e riestratto | Contenuto uguale ai file di test; estrazione in una cartella nuova. |
| App in background / schermo spento | Notifica operazione attiva; completamento o errore esplicito. Verificare le impostazioni batteria HyperOS se il sistema la interrompe. |
| Ruota lo schermo | Navigazione recuperata; operazioni del servizio non riavviate né duplicate. |
| Rifiuta permesso notifiche | App funzionante; servizio ancora soggetto alle regole Android. |
| Revoca accesso ai file | Errore o schermata autorizzazione; niente crash o cancellazione di originali. |

## Misure di velocità

Confronta sullo stesso telefono, nella stessa memoria, con gli stessi file e condizioni termiche. Misura separatamente:

- Avvio a freddo e ritorno a una cartella già visitata.
- Cartelle con 1.000 e 10.000 file: apertura, ordinamento e scorrimento.
- Copia di un file da 1 GiB e di 10.000 file piccoli.
- Spostamento nella stessa memoria e tra memoria interna e USB.
- Modalità copia normale e verificata.

Esegui almeno tre misure, riporta mediana e intervallo. Non confondere la copia da cache con la scrittura completa sul supporto: il motore attende la sincronizzazione supportata prima di dichiarare il successo.
