# Gestore File — Android / Kotlin

Gestore file locale per Android 11 e successivi, progettato per Xiaomi 11T Pro con Android 14. Versione di prova **0.1.1**: il codice e il motore sono verificabili; la compatibilità con il telefono e con la specifica chiavetta USB va provata sul dispositivo.

## Modifiche nella versione 0.1.1

- Nome visibile aggiornato a **Gestore File** in launcher, interfaccia e notifica.
- Versione 0.1.1 / codice 2; identificatore e chiave originali conservati per consentire aggiornamenti.
- Firma v1, v2 e v3 esplicitamente abilitate per ampliare la compatibilità dei verificatori. Non è stata dimostrata una firma errata nella versione precedente.
- La revoca dell'accesso alla memoria interna ora nasconde e invalida subito le liste già caricate.
- Nessuna seconda lettura della cartella all'avvio per il solo fatto che la prima lettura è ancora in corso.
- Risposte asincrone scartate quando l'Activity è stata distrutta, evitando dialoghi su una finestra non più valida.
- ZIP di installazione separato con il solo APK.

La chiusura dell'installer sul telefono non è ancora diagnosticata: verifica dei byte e dei test in laboratorio non equivale a una prova sul dispositivo.

## Funzioni

- Navigazione della memoria interna, USB OTG tramite selettore Android, percorso navigabile, preferiti e cartelle recenti.
- Elenco/griglia senza miniature dei contenuti; ordinamento per nome, dimensione dei file, data e tipo; file nascosti.
- Selezione multipla, copia, spostamento, rinomina, creazione cartelle, eliminazione.
- Conflitti: conserva entrambi, salta, sostituisci **file**. Le cartelle esistenti non vengono sostituite o unite automaticamente.
- Operazioni seriali in un servizio foreground con notifica, avanzamento per file, coda e annullamento. Un errore interrompe la coda.
- ZIP: creazione con compressione rapida, estrazione in una nuova cartella e protezione dai percorsi fuori dalla destinazione. ZIP cifrati e altri formati esclusi.
- Rinomina multipla: prefisso, suffisso, sostituzione letterale, numerazione, anteprima e rollback in caso di errore ordinario.
- Analisi manuale dello spazio della cartella, distribuzione per estensione, 20 file più grandi, segnalazione di percorsi non accessibili.
- Cestino nella memoria interna: recupero senza sovrascrivere, eliminazione definitiva, nessuno svuotamento automatico. USB: eliminazione definitiva con conferma.
- Apertura e condivisione tramite altre app, accesso concesso in sola lettura. Nessun lettore interno.
- Tema scuro/chiaro, dimensione caratteri, supporto al ridimensionamento del testo di Android, descrizioni accessibili e scorciatoie alle cartelle sulla Home.

**Esclusi:** pubblicità, tracciamento, permesso Internet, account, cloud, ricerca, root, editor, metadati multimediali, duplicati, pulizia automatica, sincronizzazione, rete, cassaforte e strumenti APK.

## Installazione dell'APK

1. Scarica `GestoreFile-0.1.1-installazione.zip`, estrai `GestoreFile-0.1.1.apk` nella cartella Download e apri il file estratto dal gestore Xiaomi. Quando richiesto da Android, autorizza l'installazione dalla sola app con cui lo stai aprendo.
2. Apri **Gestore File**. È un'app separata dal gestore Xiaomi e non ne sostituisce il pacchetto.
3. Premi **Autorizza memoria interna** e abilita l'accesso ai file per Gestore File.
4. Per USB: collega la chiavetta, scegli **Le tue posizioni → Aggiungi USB / cartella**, seleziona la memoria e concedi l'accesso alla cartella.
5. Alla prima operazione puoi autorizzare la notifica di avanzamento. Il rifiuto non concede altri permessi e non disattiva le protezioni del motore.

Pressione prolungata su un elemento per selezionarlo. **Copia/Sposta → naviga alla destinazione → Incolla**. Per ZIP e rinomina multipla: **Altro** nella selezione.

Prima dell'uso sui dati importanti, esegui i controlli in `VERIFICA_DISPOSITIVO.md` con una cartella di prova.

## Compilazione

Apri questa cartella in Android Studio. Toolchain fissata per riproducibilità: JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.1.21, SDK 35 e Build Tools 35.0.0. `minSdk=30`, `targetSdk=35`.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Windows: `gradlew.bat` con gli stessi argomenti. Android Studio richiede una connessione per scaricare gli strumenti; **l'app installata non ha accesso a Internet**.

Il repository non contiene chiavi private. Le build debug e release usano la chiave debug locale generata da Android Gradle Plugin. Se disponi della chiave originale inclusa nello ZIP del progetto consegnato, puoi copiarla localmente in `signing/development.jks` (alias/password di sviluppo `androiddebugkey` / `android`): Gradle la userà per mantenere la firma dell'APK in `dist/`. Il file è escluso da Git.

L'APK consegnato è ottimizzato con R8 e firmato con la chiave di sviluppo originale: è una **versione di prova**. Una build con una chiave locale diversa non può aggiornare direttamente quell'APK. Per conservare la compatibilità degli aggiornamenti usa la stessa chiave locale. Prima di distribuire una versione definitiva genera una chiave privata distinta, custodiscila fuori dal repository e sostituisci la configurazione `signingConfig` della release.

## Architettura e velocità

`core/` contiene il motore Kotlin/JVM senza dipendenze Android. `app/` contiene Activity, UI nativa ListView/GridView con riuso delle righe, servizio delle operazioni, adapter SAF e provider di condivisione. L'unica dipendenza dell'app oltre al core è la libreria standard Kotlin; niente framework web o SDK pubblicitari.

- Letture della memoria, ordinamento delle cartelle e scansioni fuori dal thread UI.
- Nessuna scansione ricorsiva all'avvio, nessun conteggio automatico delle dimensioni delle cartelle, nessuna miniatura.
- Cache delle liste con limite ponderato di 12.000 elementi; invalidazione dopo le operazioni e aggiornamento della cartella visualizzata.
- Due worker per letture UI; un solo writer per le operazioni, per ridurre contesa e semplificare i conflitti.
- Buffer di copia riutilizzato da 512 KiB; aggiornamento dell'avanzamento limitato a circa 150 ms.
- Spostamento atomico locale sullo stesso filesystem quando supportato; fallback a copia verificata.
- File grandi trattati in streaming, mai caricati interamente in RAM.

Non sono dichiarati benchmark sul telefono senza misure reali. Le impostazioni del buffer e della concorrenza sono punti di partenza da misurare, non valori universalmente ottimali.

## Integrità e recupero

Il motore non apre mai l'origine in scrittura. Copia in un elemento `.lf-stage-*` nella destinazione, controlla dimensioni/errori, esegue `fsync`/`force` del file dove il provider lo permette e pubblica solo il risultato completo. La sostituzione preserva temporaneamente il vecchio file in `.lf-backup-*`; il backup viene eliminato solo dopo il completamento. La pubblicazione locale usa rinomina atomica dove disponibile; SAF non promette atomicità generale.

Gli spostamenti che richiedono copia calcolano SHA-256 durante la lettura, rileggono la destinazione e ricontrollano gli originali prima della rimozione. La verifica include nomi e struttura delle cartelle; le cancellazioni delle cartelle sono non ricorsive dopo la rimozione controllata dei file. Nuovi contenuti impediscono la cancellazione finale. La modalità **Verifica completa anche nelle copie** estende il controllo ai trasferimenti che conservano l'origine.

Le verifiche forti aumentano le letture: negli spostamenti con copia si controllano nuovamente anche gli originali. L'integrità ha priorità sulla velocità della cancellazione finale.

Un registro persistente in memoria privata segnala le operazioni interrotte. Il recupero **non elimina né completa automaticamente** una transazione incerta. `Impostazioni → Operazioni interrotte → Mostra residui` consente di recuperare un elemento temporaneo con un nuovo nome o rimuoverlo dopo verifica. La verifica dei residui è disponibile soltanto quando non ci sono operazioni attive. **Archivia avvisi** rimuove le segnalazioni già verificate senza eliminare file. Una copia già pubblicata può restare insieme agli originali se lo spostamento viene annullato durante la pulizia: è intenzionale.

Il cestino è nella cartella nascosta `.LiberoFilesTrash` della memoria interna, non nella memoria privata dell'app. Disinstallare l'app non cancella automaticamente quei file. La cartella non viene mostrata nel browser ordinario per evitare manipolazioni accidentali.

### Limiti concreti

- Nessun software garantisce protezione dai guasti fisici, da firmware che non rispetta le scritture o da altre app che modificano gli stessi file contemporaneamente. Evita modifiche concorrenti durante trasferimenti e rinomine. I controlli riducono il rischio, non costituiscono una transazione tra processi indipendenti.
- I collegamenti simbolici e i file speciali non vengono trasferiti: il motore interrompe l'operazione. Il browser omette i collegamenti simbolici.
- Android limita `Android/data`, `Android/obb` e i dati privati delle altre app anche con accesso a tutti i file.
- SAF dipende dal provider e dalla chiavetta: rinomina, scrittura sincronizzata e aggiornamento dei metadati possono fallire. In tal caso si conserva l'origine e si segnala l'errore.
- Limiti di difesa: nomi creati/rinominati fino a 240 byte UTF-8, profondità 128, 250.000 elementi per copia/ZIP; estrazione ZIP massimo 100.000 voci e 16 GiB complessivi o lo spazio disponibile, se inferiore. La creazione di ZIP vuoti e l'estrazione di archivi ZIP senza voci non sono previste.
- FAT32 può rifiutare file di circa 4 GiB o più; è un limite del filesystem, non della RAM dell'app.
- Annullamento cooperativo tra i blocchi: un provider bloccato può ritardarlo. Nessuna ripresa automatica dopo l'arresto del processo; l'originale viene conservato e i residui sono segnalati.
- Il servizio dataSync rispetta il timeout previsto da Android 15 e successivi.
- L'app non cancella cache private di altre app, non installa APK e non modifica i contenuti dei file aperti con app esterne.
- Date di modifica e permessi non vengono replicati come in un backup completo: viene preservato il contenuto, non ogni metadato del filesystem.

## Verifiche

I test JVM esercitano copie/spostamenti, integrità, cancellazione, conflitti, errori di scrittura, rollback, ZIP Slip, mutazioni dell'origine, rinomina, cestino e persistenza del registro. Il documento `RISULTATI_VERIFICA.md` registra solo verifiche effettivamente eseguite. I test del core non sostituiscono una prova su Android e USB reali.
