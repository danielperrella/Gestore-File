# Sicurezza

## Superfici e controlli osservabili

- **File locali:** manifest richiede `MANAGE_EXTERNAL_STORAGE`; il sistema Android governa la concessione. Accesso USB tramite Storage Access Framework e URI concessi dall’utente. Android limita comunque directory private di altre app e alcuni percorsi protetti.
- **Trasferimenti:** `FileEngine` usa staging, controlli di dimensione/hash e verifica dell’origine prima della rimozione; include limiti su nomi, profondità, quantità e dimensione ZIP e difesa ZIP Slip. Questi controlli riducono i rischi ma non coprono guasti hardware, provider non conformi o modifiche esterne concorrenti.
- **Rete:** il manifest abilita Internet per aggiornamenti e Drive. Il download APK confronta hash, package, versione e firma con quella installata; l’installazione è confermata dall’utente/Android. Non è presente un backend proprietario.
- **Drive:** OAuth Android e scope Drive completi sono previsti per la candidata. L’utente autorizza l’account; gli access token non vanno salvati nei log o nei documenti. L’app effettua trasferimenti diretti verso Google; OAuth e verifica pubblica restano da completare secondo `GOOGLE_DRIVE_SETUP.md`.
- **Dati:** file e contenuti non vengono inviati a un servizio dell’app. Journal/cache e preferiti sono locali. Il cestino locale è nella memoria condivisa, non in quella privata; la disinstallazione non lo elimina necessariamente.

## Segreti e dipendenze

Non aggiungere password, token, client secret o chiavi di firma al repository o all’APK. La chiave originale di sviluppo e la chiave di distribuzione devono restare fuori da Git. I token Drive sono credenziali bearer: limitane la vita e l’esposizione secondo il flusso Google esistente. Le dipendenze sono dichiarate nei file Gradle; verifica aggiornamenti e provenienza prima di modificarle.

## Rischi e segnalazioni

Rischi specifici: accesso ampio alla memoria richiesto dalla funzione di file manager; supporti rimovibili non affidabili; differenze SAF; conflitti dovuti a modifiche concorrenti; consegna APK fuori dal Play Store; configurazione OAuth e verifica Google ancora candidata. Il repository non definisce una procedura dedicata per segnalare vulnerabilità: non inventare un indirizzo. I controlli verificabili includono test su traversal/ZIP, mutazioni origine, firme e manifest; non costituiscono attestazione di sicurezza o conformità.
