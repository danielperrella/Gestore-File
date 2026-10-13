# Verifica — Gestore File 0.1.1

Eseguita 2026-10-13T21:09:07.027955+00:00 (UTC). Build ottimizzata con R8, versione 0.1.1 / codice 2.

| Controllo | Esito |
|---|---|
| Compilazione release | Riuscita |
| Test motore JVM | 29 passati |
| Test UI Android 14 simulato | 6 passati |
| Test SAF con provider simulato | 3 passati |
| Totale test | 38 passati, zero errori |
| Android Lint debug | 0 errori, 8 avvisi |
| Android Lint vital release | Superato |
| Firma su verificatore Android 14 / API 34 | v3 valida |
| Firme compatibili sui verificatori API 23 e 27 | v1 e v2 valide |
| Chiave rispetto all'APK precedente | Identica |
| Allineamento APK e integrità ZIP | Validati |
| Nome nel manifest compilato | Gestore File |
| Permesso Internet | Assente |
| Xiaomi e chiavetta USB fisici | Da provare |

Le prove di firma su API precedenti verificano esclusivamente gli schemi crittografici: **l'app richiede comunque Android 11 / API 30 o successivi**. Il target SDK resta 35. Gli avvisi Lint riguardano permesso speciale, attributi per versioni successive e localizzazione; nessun baseline nasconde errori.

## Revisione

- Nome aggiornato nel launcher, nell'interfaccia e nelle notifiche.
- Cache dei file invalidata e schermata autorizzazione mostrata quando l'accesso alla memoria interna viene revocato. Il nuovo test riproduce una lista già caricata e la successiva revoca.
- Risposte asincrone ignorate dopo la distruzione dell'Activity: il nuovo test verifica che il recupero non apra una finestra su un'Activity distrutta.
- Evitata la seconda lettura della cartella iniziale mentre la prima è ancora in corso.
- Conservati identificatore, certificato e cartella del cestino della versione precedente.
- Il motore di trasferimento conserva i controlli di integrità della versione verificata; restano validi i test su corruzione, annullamento, spazio insufficiente, conflitti, rollback e ZIP Slip.

## Problema di installazione riferito

Il vecchio APK dentro lo ZIP allegato ha SHA-256 `acc8d0ba584ce25895a782a522691ae475aa2d1b9b4caebc42f52b7f0c5b6e3c` e supera verifica della firma API 34, integrità ZIP e allineamento. **Non è stato dimostrato un errore strutturale dell'APK precedente, né identificata la causa della chiusura dell'installer sul telefono.** La nuova confezione ZIP consente di estrarre un vero file locale nei Download prima di aprirlo.

APK nuovo: `GestoreFile-0.1.1.apk` — 161387 byte.

SHA-256: `0c38e594d4423a6921c77d3f8f4e0e4496256495867016fa641d9977bf558d33`.

Firma di sviluppo, distribuzione di prova. Le verifiche in laboratorio non garantiscono assenza di ogni bug né sostituiscono il test sul telefono.
