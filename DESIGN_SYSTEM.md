# Interfaccia e design system

Non è presente un design system formale né una libreria di componenti web. L’interfaccia principale è Android nativa e i componenti sono costruiti in `MainActivity.kt` e `UiIcon.kt`; la pagina GitHub Pages (`docs/index.html`, `docs/site.css`, `docs/site.js`) è un’interfaccia distinta.

## Pattern implementati

- Tema chiaro predefinito con superfici chiare, cartelle ambra e azioni blu; tema scuro e dimensione testo configurabili. Le risorse Android contengono stili e colori, mentre le icone sono vettoriali locali.
- Navigazione per posizioni, preferiti, recenti e cestino. Elenco/griglia adattabile; selezione multipla e appunti hanno barra contestuale. Ordinamento, vista e menu sono associati alla lista.
- Operazioni lunghe mostrano notifica foreground, avanzamento per file e annullamento. Conflitti e azioni distruttive presentano scelte/conferme; il cestino Drive e quello locale restano distinti.
- Stati di permesso e accesso non disponibile sono spiegati nella UI. Gli errori di operazione e i residui sono presentati per recupero esplicito. Le funzioni vuote sono descritte nel README; non è definita una specifica comune per tutte le schermate.
- Dialoghi, posizioni, impostazioni e input seguono componenti Android standard. Sono presenti descrizioni accessibili, supporto ridimensionamento testo e shortcut alla Home secondo README.

## Responsive e accessibilità

La griglia/lista e le dimensioni testo si adattano a larghezza e impostazioni Android. La pagina download documenta anteprime separate per lista, griglia e tema scuro; non rappresenta tutte le schermate dell’app. Gli elementi non definiti in modo centralizzato (scala di spaziatura, palette numerica completa, regole per contrasto misurato) vanno ricavati con cautela dai layout e dalle risorse; non sono specificati valori di conformità accessibilità.

Per modifiche UI riusa stili, drawable e dialoghi già presenti; mantieni etichette e descrizioni accessibili, feedback di annullamento/conflitto e stati di errore. Non introdurre una palette o una libreria grafica nuova per deduzione.
