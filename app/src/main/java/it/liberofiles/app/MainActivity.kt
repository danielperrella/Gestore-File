package it.liberofiles.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import it.liberofiles.core.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val updater by lazy { AppUpdater(this) }
    private var updateBusy = false
    private val graph get() = (application as FilesApplication).graph
    private var current: Node? = null
    private var entries = emptyList<Entry>()
    private var presented = emptyList<Entry>()
    private var presentationGeneration = 0
    private val dateFormatter = DateFormat.getDateInstance(DateFormat.SHORT)
    private val selected = linkedSetOf<Node>()
    private val cache = object : android.util.LruCache<Node, List<Entry>>(12000) {
        override fun sizeOf(key: Node, value: List<Entry>) = value.size.coerceAtLeast(1)
    }
    private var loadGeneration = 0
    private var revision = -1L
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var bottom: LinearLayout
    private lateinit var crumbs: LinearLayout
    private lateinit var location: TextView
    private lateinit var status: TextView
    private lateinit var jobLine: LinearLayout
    private lateinit var jobText: TextView
    private var collection: AbsListView? = null
    private var waitingForPermission = false
    private var dark = true
    private var font = 1f
    private var bg = Color.rgb(16, 21, 21)
    private var panel = Color.rgb(27, 35, 35)
    private var ink = Color.rgb(236, 244, 242)
    private var muted = Color.rgb(150, 171, 165)
    private var accent = Color.rgb(112, 221, 197)
    private var hidden = false
    private var grid = false
    private var sort = "name"
    private var descending = false
    private val observer: (JobState) -> Unit = { showJob(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = graph.preferences.getBoolean("dark", true)
        if (!dark) setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        font = graph.preferences.getFloat("font", 1f)
        hidden = graph.preferences.getBoolean("hidden", false)
        grid = graph.preferences.getBoolean("grid", false)
        sort = graph.preferences.getString("sort", "name") ?: "name"
        descending = graph.preferences.getBoolean("descending", false)
        if (!dark) { bg = Color.rgb(245, 248, 247); panel = Color.WHITE; ink = Color.rgb(20, 42, 37); muted = Color.rgb(82, 106, 98); accent = Color.rgb(0, 117, 92) }
        val savedStore = savedInstanceState?.getString("store") ?: intent.getStringExtra("store")
        val savedId = savedInstanceState?.getString("node") ?: intent.getStringExtra("node")
        current = if (savedStore != null && savedId != null) Node(savedStore, savedId) else graph.local.rootNode
        buildLayout()
        if (current?.store == "local" && !Environment.isExternalStorageManager()) showPermissionScreen() else navigate(current!!)
        if (savedInstanceState == null) checkUpdates(false)
        revision = graph.state.revision
        if (savedInstanceState == null) graph.reads.execute {
            val records = graph.journal.pending()
            if (records.isNotEmpty()) graph.main.post { if (!canUseUi()) return@post
 if (!isFinishing) recoveryDialog(records) }
        }
    }

    private fun buildLayout() {
        window.statusBarColor = bg; window.navigationBarColor = bg
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(12), dp(8)) }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(label(getString(R.string.app_name), 24f, bold = true))
        titleBox.addView(label("LOCALE · SENZA PUBBLICITÀ", 10f, muted).apply { letterSpacing = .12f })
        heading.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(button("☰", "Memorie, preferiti e recenti") { locations() })
        heading.addView(button("⋮", "Menu della cartella") { menu() })
        root.addView(heading)
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(12), 0) }
        nav.addView(button("↑", "Cartella superiore") { up() })
        location = label("Memoria interna", 18f, bold = true).apply { setOnClickListener { locations() }; setPadding(dp(6), dp(8), 0, dp(8)) }
        nav.addView(location, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(button("+", "Crea cartella") { newFolder() })
        root.addView(nav)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        crumbs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        scroll.addView(crumbs); root.addView(scroll)
        status = label("", 12f, muted).apply { setPadding(dp(18), dp(8), dp(18), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(status)
        jobLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(4), dp(8), dp(4)); background = shape(panel); visibility = View.GONE }
        jobText = label("", 12f).apply { maxLines = 2 }
        jobLine.addView(jobText, LinearLayout.LayoutParams(0, -2, 1f))
        jobLine.addView(button("Annulla") { graph.cancellation?.cancel(); graph.pending.clear() })
        root.addView(jobLine)
        body = FrameLayout(this); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(8)); setBackgroundColor(panel) }
        root.addView(bottom)
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        root.requestApplyInsets(); updateActions()
    }
    override fun onStart() { super.onStart(); graph.listeners.add(observer); showJob(graph.state) }
    override fun onStop() { graph.listeners.remove(observer); super.onStop() }
    override fun onDestroy() {
        loadGeneration++; presentationGeneration++
        super.onDestroy()
    }
    private fun canUseUi() = !isFinishing && !isDestroyed
    override fun onResume() {
        super.onResume()
        updater.resume()
        if (::body.isInitialized && current?.store == "local") {
            val allowed = Environment.isExternalStorageManager()
            if (!allowed && !waitingForPermission) showPermissionScreen()
            else if (allowed && waitingForPermission) navigate(current!!)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        current?.let { outState.putString("store", it.store); outState.putString("node", it.id) }; super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        val store = intent.getStringExtra("store"); val node = intent.getStringExtra("node")
        if (store != null && node != null) navigate(Node(store, node))
    }
    @Deprecated("Android compatibility back handling")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) { selected.clear(); refreshSelection() }
        else if (!up()) super.onBackPressed()
    }

    private fun navigate(node: Node) {
        if (node.store == "local" && !Environment.isExternalStorageManager()) { current = node; showPermissionScreen(); return }
        waitingForPermission = false
        current = node; selected.clear(); collection?.isEnabled = false; updateCrumbs(); location.text = displayName(node)
        val cached = cache.get(node)
        if (cached != null) { entries = cached; preparePresentation() } else { entries = emptyList(); presented = emptyList(); showEmpty("Caricamento…"); status.text = "Lettura cartella" }
        val generation = ++loadGeneration
        val stores = graph.stores()
        graph.reads.execute {
            try {
                require(stores[node].stat(node).directory) { "Questa posizione non è una cartella" }
                val storage = stores[node]
                val result = (if (storage is LocalStorage) storage.browse(node) else storage.list(node)).filter { !it.name.startsWith(".lf-") && it.name != ".LiberoFilesTrash" }
                graph.main.post { if (!canUseUi()) return@post

                    if (generation != loadGeneration || isFinishing) return@post
                    cache.put(node, result); entries = result; preparePresentation()
                    graph.saveNodes("recent", listOf(node) + graph.nodes("recent"))
                }
            } catch (e: Exception) { graph.main.post { if (!canUseUi()) return@post

                if (generation == loadGeneration && !isFinishing) { collection = null; showEmpty("Cartella non disponibile\n${e.message}\n\nSe Android limita l'accesso, scegli un'altra cartella."); status.text = "Accesso non riuscito" }
            } }
        }
        updateActions()
    }
    private fun visibleEntries(): List<Entry> = presented
    private fun preparePresentation() {
        val raw = entries; val showHidden = hidden; val order = sort; val reverse = descending
        val generation = ++presentationGeneration; val node = current
        graph.reads.execute {
        val comparator: Comparator<Entry> = when (order) {
            "size" -> compareBy<Entry> { it.size ?: -1 }
            "date" -> compareBy { it.modified ?: 0 }
            "type" -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (reverse) comparator.reversed() else comparator
        val result = raw.filter { showHidden || !it.name.startsWith('.') }.sortedWith(compareBy<Entry> { !it.directory }.then(ordered).thenBy { it.name })
        graph.main.post { if (!canUseUi()) return@post

            if (generation == presentationGeneration && node == current && !isFinishing) { presented = result; renderEntries() }
        }
        }
    }
    private fun renderEntries() {
        val visible = visibleEntries()
        status.text = if (selected.isEmpty()) "${visible.size} elementi · ${if (sort == "name") "nome" else if (sort == "size") "dimensione" else if (sort == "date") "data" else "tipo"} ${if (descending) "↓" else "↑"}"
            else "${selected.size} selezionati"
        if (visible.isEmpty()) { showEmpty("Cartella vuota"); updateActions(); return }
        val oldPosition = collection?.firstVisiblePosition ?: 0
        body.removeAllViews()
        val list: AbsListView = if (grid) GridView(this).apply { numColumns = 2; horizontalSpacing = dp(8); verticalSpacing = dp(8); setPadding(dp(12), 0, dp(12), 0) }
            else ListView(this).apply { divider = null; dividerHeight = 0; setPadding(dp(10), 0, dp(10), 0) }
        list.adapter = FileAdapter(visible)
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = visible[position]
            if (selected.isNotEmpty()) { toggle(entry.node) }
            else if (entry.directory) navigate(entry.node) else open(entry.node, entry.name)
        }
        list.setOnItemLongClickListener { _, _, position, _ -> toggle(visible[position].node); true }
        body.addView(list, FrameLayout.LayoutParams(-1, -1)); collection = list
        list.setSelection(oldPosition.coerceAtMost(visible.lastIndex)); updateActions()
    }
    private fun toggle(node: Node) { if (!selected.add(node)) selected.remove(node); refreshSelection() }
    private fun refreshSelection() {
        status.text = if (selected.isEmpty()) "${presented.size} elementi" else "${selected.size} selezionati"
        (collection?.adapter as? BaseAdapter)?.notifyDataSetChanged(); updateActions()
    }

    private inner class FileAdapter(private val rows: List<Entry>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].node.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = rows[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
                val icon = label("", 15f, accent, true).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
                addView(icon, LinearLayout.LayoutParams(if (grid) -1 else dp(48), dp(42)))
                val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(if (grid) 0 else dp(10), if (grid) dp(6) else 0, 0, 0) }
                texts.addView(label("", 15f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                texts.addView(label("", 11f, muted).apply { maxLines = 2 })
                addView(texts, if (grid) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
                minimumHeight = dp(if (grid) 132 else 80)
            }
            row.background = shape(if (entry.node in selected) if (dark) Color.rgb(35, 77, 66) else Color.rgb(199, 237, 222) else if (grid) panel else bg)
            val icon = row.getChildAt(0) as TextView
            icon.text = if (entry.node in selected) "✓" else if (entry.directory) "DIR" else entry.name.substringAfterLast('.', "FILE").take(4).uppercase()
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = entry.name
            (texts.getChildAt(1) as TextView).text = if (entry.directory) "Cartella" else "${formatBytes(entry.size)}${entry.modified?.let { " · ${dateFormatter.format(Date(it))}" } ?: ""}"
            row.contentDescription = "${entry.name}, ${if (entry.directory) "cartella" else formatBytes(entry.size)}${if (entry.node in selected) ", selezionato" else ""}"
            return row
        }
    }

    private fun updateCrumbs() {
        crumbs.removeAllViews(); val node = current ?: return
        val path = ArrayList<Node>(); var cursor: Node? = node
        try { val stores = graph.stores(); while (cursor != null && path.size < 128) { path.add(cursor); cursor = stores[cursor].parent(cursor) } }
        catch (_: Exception) { path.add(node) }
        for ((i, part) in path.asReversed().withIndex()) {
            if (i > 0) crumbs.addView(label("›", 12f, muted))
            crumbs.addView(label(displayName(part), 12f, if (part == node) accent else muted).apply {
                setPadding(dp(6), dp(8), dp(6), dp(8)); setOnClickListener { navigate(part) }
            })
        }
    }
    private fun displayName(node: Node): String = graph.roots().firstOrNull { it.second == node }?.first
        ?: node.id.substringAfterLast('/').substringAfter(':').ifBlank { "Memoria" }
    private fun up(): Boolean {
        val node = current ?: return false
        val parent = try { graph.stores()[node].parent(node) } catch (_: Exception) { null }
        return if (parent != null) { navigate(parent); true } else false
    }
    private fun updateActions() {
        if (!::bottom.isInitialized) return
        bottom.removeAllViews()
        fun add(text: String, action: () -> Unit) { bottom.addView(button(text, action = action), LinearLayout.LayoutParams(0, dp(48), 1f)) }
        if (selected.isNotEmpty()) {
            add("Copia") { clipboard(false) }; add("Sposta") { clipboard(true) }; add("Elimina") { deleteSelected() }; add("Altro") { selectionMenu() }
        } else if (graph.clipboard.isNotEmpty()) {
            add("Incolla (${graph.clipboard.size})") { paste() }; add("Svuota") { graph.clipboard = emptyList(); updateActions() }
        } else {
            add("Preferiti") { savedLocations("favorites", "Preferiti") }; add(if (grid) "Elenco" else "Griglia") {
                grid = !grid; graph.preferences.edit().putBoolean("grid", grid).apply(); renderEntries()
            }; add("Ordina") { sorting() }
        }
    }
    private fun menu() {
        val choices = arrayOf("Aggiorna", "Seleziona tutti", if (hidden) "Nascondi file nascosti" else "Mostra file nascosti",
            "Aggiungi ai preferiti", "Analizza spazio della cartella", "Cestino", "Scorciatoia sulla schermata Home", "Impostazioni")
        AlertDialog.Builder(this).setTitle("Cartella").setItems(choices) { _, which -> when (which) {
            0 -> current?.let { cache.remove(it); navigate(it) }
            1 -> { selected.addAll(visibleEntries().map { it.node }); refreshSelection() }
            2 -> { hidden = !hidden; graph.preferences.edit().putBoolean("hidden", hidden).apply(); preparePresentation() }
            3 -> current?.let { graph.saveNodes("favorites", graph.nodes("favorites") + it); toast("Cartella aggiunta ai preferiti") }
            4 -> analyzeCurrent()
            5 -> trashDialog()
            6 -> shortcut()
            7 -> settings()
        } }.show()
    }
    private fun sorting() {
        val choices = arrayOf("Nome", "Dimensione dei file", "Data", "Tipo", if (descending) "Crescente" else "Decrescente")
        AlertDialog.Builder(this).setTitle("Ordinamento").setItems(choices) { _, which ->
            if (which == 4) descending = !descending else sort = arrayOf("name", "size", "date", "type")[which]
            graph.preferences.edit().putString("sort", sort).putBoolean("descending", descending).apply(); preparePresentation()
        }.show()
    }
    private fun locations() {
        val roots = graph.roots(); val labels = roots.map { it.first } + listOf("Aggiungi USB / cartella", "Preferiti", "Recenti", "Cestino", "Rimuovi una memoria autorizzata")
        AlertDialog.Builder(this).setTitle("Le tue posizioni").setItems(labels.toTypedArray()) { _, which -> when {
            which < roots.size -> navigate(roots[which].second)
            which == roots.size -> selectTree()
            which == roots.size + 1 -> savedLocations("favorites", "Preferiti")
            which == roots.size + 2 -> savedLocations("recent", "Cartelle recenti")
            which == roots.size + 3 -> trashDialog()
            else -> removeTreeDialog()
        } }.show()
    }
    private fun savedLocations(key: String, title: String) {
        val nodes = graph.nodes(key)
        if (nodes.isEmpty()) { toast("Nessuna cartella salvata"); return }
        val dialog = AlertDialog.Builder(this).setTitle(title).setItems(nodes.map { "${displayName(it)}\n${it.id}" }.toTypedArray()) { _, which -> navigate(nodes[which]) }
        if (key == "favorites") dialog.setNeutralButton("Rimuovi…") { _, _ ->
            AlertDialog.Builder(this).setTitle("Rimuovi preferito").setItems(nodes.map { displayName(it) }.toTypedArray()) { _, i -> graph.saveNodes(key, nodes - nodes[i]) }.show()
        }
        dialog.setNegativeButton("Chiudi", null).show()
    }
    private fun removeTreeDialog() {
        val roots = graph.roots().filter { it.second.store != "local" }
        if (roots.isEmpty()) { toast("Nessuna memoria aggiunta"); return }
        AlertDialog.Builder(this).setTitle("Revoca accesso").setItems(roots.map { it.first }.toTypedArray()) { _, i ->
            confirm("Rimuovere ${roots[i].first}?", "I file restano nella memoria. Verrà revocato l'accesso dell'app.") {
                graph.removeTree(roots[i].second.store); navigate(graph.local.rootNode)
            }
        }.show()
    }
    private fun showPermissionScreen() {
        waitingForPermission = true
        loadGeneration++; presentationGeneration++
        entries = emptyList(); presented = emptyList(); selected.clear(); cache.evictAll()
        collection = null; body.removeAllViews(); crumbs.removeAllViews(); status.text = "Accesso alla memoria da autorizzare"
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), dp(24), dp(28), dp(24)) }
        content.addView(label("I tuoi file.\nSenza distrazioni.", 30f, bold = true))
        content.addView(label("Per gestire la memoria interna autorizza l'accesso ai file nelle impostazioni Android. Per USB puoi scegliere una cartella dal selettore di sistema.\n\nNessun account. Internet solo per gli aggiornamenti; i tuoi file restano sul dispositivo.", 15f, muted).apply { setPadding(0, dp(20), 0, dp(20)) })
        content.addView(button("Autorizza memoria interna") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        })
        content.addView(button("Scegli USB / cartella") { selectTree() })
        body.addView(content, FrameLayout.LayoutParams(-1, -1)); updateActions()
    }
    private fun selectTree() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }, 42)
    }
    @Deprecated("Platform activity result for dependency-free Android app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            require(uri.authority == "com.android.externalstorage.documents") { "Seleziona una memoria fisica locale o USB" }
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Serve accesso in lettura e scrittura" }
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val nodeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            prompt("Nome della memoria", "USB / ${nodeId.substringAfterLast('/').ifBlank { "memoria" }}") { name ->
                graph.addTree(uri, name); navigate(graph.roots().last().second)
            }
        } catch (e: Exception) { error(e.message ?: "Autorizzazione non riuscita") }
    }

    private fun newFolder() {
        val parent = current ?: return
        prompt("Nuova cartella", "Nuova cartella") { name ->
            val stores = graph.stores()
            operation("Crea cartella") { cancel, _ -> cancel.check(); stores[parent].create(parent, validName(name), true); Outcome("Cartella creata") }
        }
    }
    private fun clipboard(move: Boolean) {
        graph.clipboard = selected.toList(); graph.clipboardMove = move; selected.clear(); refreshSelection()
        toast("Scegli la cartella di destinazione e premi Incolla")
    }
    private fun paste() {
        val sources = graph.clipboard.toList(); val destination = current ?: return; val move = graph.clipboardMove
        AlertDialog.Builder(this).setTitle(if (move) "Sposta ${sources.size} elementi" else "Copia ${sources.size} elementi")
            .setMessage("Destinazione: ${displayName(destination)}\n\nSe il nome esiste già:")
            .setPositiveButton("Conserva entrambi") { _, _ -> transfer(sources, destination, move, Conflict.KEEP_BOTH) }
            .setNeutralButton("Salta") { _, _ -> transfer(sources, destination, move, Conflict.SKIP) }
            .setNegativeButton("Sostituisci…") { _, _ -> confirm("Sostituire i file esistenti?", "La sostituzione riguarda i file, non le cartelle. La versione precedente sarà rimossa solo dopo la copia riuscita.") { transfer(sources, destination, move, Conflict.REPLACE) } }
            .show()
    }
    private fun transfer(sources: List<Node>, destination: Node, move: Boolean, conflict: Conflict) {
        val engine = graph.engine(); val verify = graph.preferences.getBoolean("verifyCopy", false)
        graph.clipboard = emptyList(); updateActions()
        operation(if (move) "Spostamento" else "Copia") { cancel, progress ->
            var completed = 0; var skipped = 0
            for (source in sources) {
                cancel.check()
                val result = engine.transfer(source, destination, move, conflict, cancel, verify, progress)
                if (result.skipped) skipped++ else completed++
            }
            Outcome("$completed elementi completati${if (skipped > 0) ", $skipped saltati" else ""}")
        }
    }
    private fun deleteSelected() {
        val sources = selected.toList(); if (sources.isEmpty()) return
        val localOnly = sources.all { it.store == "local" }
        val message = if (localOnly) "Gli elementi andranno nel cestino locale e potranno essere ripristinati."
            else "Su USB l'eliminazione è DEFINITIVA. Gli elementi nella memoria interna andranno nel cestino."
        confirm("Elimina ${sources.size} elementi?", message) {
            val engine = graph.engine(); val trash = Trash(graph.local)
            operation("Eliminazione") { cancel, progress ->
                for (node in sources) { cancel.check(); progress(Progress(displayName(node), phase = "Eliminazione")); if (node.store == "local") trash.put(node) else engine.removeTree(node, cancel) }
                Outcome(if (localOnly) "Elementi spostati nel cestino" else "Eliminazione completata")
            }
        }
    }
    private fun selectionMenu() {
        val nodes = selected.toList(); if (nodes.isEmpty()) return
        val options = mutableListOf("Rinomina", "Rinomina multipla", "Crea ZIP", "Condividi", "Dettagli", "Deseleziona")
        if (nodes.size == 1 && entries.firstOrNull { it.node == nodes.first() }?.name?.endsWith(".zip", true) == true) options.add("Estrai ZIP qui")
        AlertDialog.Builder(this).setTitle("${nodes.size} selezionati").setItems(options.toTypedArray()) { _, i -> when (i) {
            0 -> if (nodes.size == 1) renameOne(nodes.first()) else toast("Per più file usa Rinomina multipla")
            1 -> renameMany(nodes)
            2 -> createZip(nodes)
            3 -> share(nodes)
            4 -> details(nodes)
            5 -> { selected.clear(); refreshSelection() }
            6 -> extractZip(nodes.first())
        } }.show()
    }
    private fun renameOne(node: Node) {
        val name = entries.firstOrNull { it.node == node }?.name ?: displayName(node)
        prompt("Rinomina", name) { newName ->
            val stores = graph.stores()
            operation("Rinomina") { cancel, _ -> cancel.check(); if (newName != name) stores[node].rename(node, validName(newName)); Outcome("Elemento rinominato") }
        }
    }
    private fun renameMany(nodes: List<Node>) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun input(hint: String): EditText = EditText(this).apply { this.hint = hint; isSingleLine = true; textSize = 14f * font; layout.addView(this) }
        val prefix = input("Prefisso"); val suffix = input("Suffisso"); val find = input("Testo da sostituire"); val replace = input("Sostituzione")
        val number = CheckBox(this).apply { text = "Aggiungi numerazione _001, _002…"; layout.addView(this) }
        val renamer = BatchRenamer(graph.stores(), graph.journal)
        AlertDialog.Builder(this).setTitle("Rinomina multipla").setView(layout).setNegativeButton("Annulla", null).setPositiveButton("Anteprima") { _, _ ->
            val rule = RenameRule(prefix.text.toString(), suffix.text.toString(), find.text.toString(), replace.text.toString(), number.isChecked)
            graph.reads.execute {
                try {
                    val preview = renamer.preview(nodes, rule)
                    graph.main.post { if (!canUseUi()) return@post
 confirm("Conferma rinomina", preview.take(40).joinToString("\n\n") { "${it.first.name}\n→ ${it.second}" } + if (preview.size > 40) "\n… e altri ${preview.size - 40}" else "") {
                        operation("Rinomina multipla") { cancel, _ -> renamer.rename(nodes, rule, cancel); Outcome("Elementi rinominati") }
                    } }
                } catch (e: Exception) { graph.main.post { if (!canUseUi()) return@post
 error(e.message ?: "Regola non valida") } }
            }
        }.show()
    }
    private fun createZip(nodes: List<Node>) {
        val destination = current ?: return; val tools = ArchiveTools(graph.engine())
        prompt("Crea ZIP", "Archivio.zip") { name -> operation("Compressione ZIP") { cancel, progress -> tools.zip(nodes, destination, validName(name), cancel, progress); Outcome("ZIP creato") } }
    }
    private fun extractZip(node: Node) {
        val destination = current ?: return; val tools = ArchiveTools(graph.engine())
        operation("Estrazione ZIP") { cancel, progress -> tools.unzip(node, destination, cancel, progress); Outcome("ZIP estratto in una nuova cartella") }
    }
    private fun open(node: Node, name: String) {
        try {
            val uri = ShareProvider.uri(this, node)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ShareProvider.mime(name)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = ClipData.newRawUri(name, uri)
            }
            startActivity(Intent.createChooser(intent, "Apri con"))
        } catch (e: Exception) { error(if (e is ActivityNotFoundException) "Nessuna app disponibile per questo formato" else e.message ?: "Apertura non riuscita") }
    }
    private fun share(nodes: List<Node>) {
        val details = entries.filter { it.node in nodes }
        if (details.any { it.directory }) { error("Per condividere cartelle crea prima uno ZIP"); return }
        try {
            val uris = ArrayList(nodes.map { ShareProvider.uri(this, it) })
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (uris.size == 1) ShareProvider.mime(details.firstOrNull()?.name ?: "") else "*/*"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri("File", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Condividi file"))
        } catch (e: Exception) { error(e.message ?: "Condivisione non riuscita") }
    }
    private fun details(nodes: List<Node>) {
        val stores = graph.stores()
        graph.reads.execute {
            try {
                val result = nodes.take(50).joinToString("\n\n") { node -> val e = stores[node].stat(node)
                    "${e.name}\n${if (e.directory) "Cartella" else formatBytes(e.size)}\n${e.modified?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Data non disponibile"}\n${node.id}"
                }
                graph.main.post { if (!canUseUi()) return@post
 AlertDialog.Builder(this).setTitle("Dettagli").setMessage(result).setPositiveButton("Chiudi", null).setNeutralButton("Copia percorsi") { _, _ ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Percorsi", nodes.joinToString("\n") { it.id })); toast("Percorsi copiati")
                }.show() }
            } catch (e: Exception) { graph.main.post { if (!canUseUi()) return@post
 error(e.message ?: "File non disponibile") } }
        }
    }
    private fun analyzeCurrent() {
        val node = current ?: return; val stores = graph.stores()
        operation("Analisi spazio") { cancel, progress -> val report = analyze(stores, node, cancel, progress); Outcome("Analisi completata", report) }
    }
    private fun reportDialog(report: SpaceReport) {
        val text = "${formatBytes(report.bytes)} · ${report.files} file · ${report.directories} cartelle" +
            (if (report.inaccessible > 0) "\nAnalisi parziale: ${report.inaccessible} percorsi non accessibili." else "") + "\n\nPER ESTENSIONE\n" +
            report.categories.entries.sortedByDescending { it.value }.take(12).joinToString("\n") { "${it.key}: ${formatBytes(it.value)}" } +
            "\n\nFILE PIÙ GRANDI\n" + report.largest.joinToString("\n") { "${formatBytes(it.size)} — ${it.name}" }
        AlertDialog.Builder(this).setTitle("Spazio della cartella").setMessage(text).setPositiveButton("Chiudi", null).show()
    }
    private fun trashDialog() {
        if (!Environment.isExternalStorageManager()) { toast("Autorizza prima la memoria interna"); return }
        val trash = Trash(graph.local)
        graph.reads.execute {
            try {
                val items = trash.items()
                graph.main.post { if (!canUseUi()) return@post

                    if (items.isEmpty()) { toast("Il cestino è vuoto"); return@post }
                    AlertDialog.Builder(this).setTitle("Cestino · ${items.size} elementi")
                        .setItems(items.map { "${it.original.substringAfterLast('/')}\n${DateFormat.getDateTimeInstance().format(Date(it.time))}" }.toTypedArray()) { _, i ->
                            val item = items[i]
                            AlertDialog.Builder(this).setTitle(item.original.substringAfterLast('/')).setMessage("Percorso originale:\n${item.original}")
                                .setPositiveButton("Ripristina") { _, _ -> operation("Ripristino") { _, _ -> trash.restore(item); Outcome("Elemento ripristinato; eventuali duplicati conservati") } }
                                .setNegativeButton("Elimina…") { _, _ -> confirm("Eliminazione definitiva", "L'elemento non sarà più recuperabile dal cestino.") {
                                    val engine = graph.engine(); operation("Svuotamento cestino") { cancel, _ -> trash.delete(item, engine, cancel); Outcome("Elemento eliminato definitivamente") }
                                } }.setNeutralButton("Chiudi", null).show()
                        }.setNegativeButton("Chiudi", null).setNeutralButton("Svuota…") { _, _ -> confirm("Svuotare tutto il cestino?", "${items.size} elementi verranno eliminati definitivamente.") {
                            val engine = graph.engine(); operation("Svuotamento cestino") { cancel, _ -> for (item in items) trash.delete(item, engine, cancel); Outcome("Cestino svuotato") }
                        } }.show()
                }
            } catch (e: Exception) { graph.main.post { if (!canUseUi()) return@post
 error(e.message ?: "Cestino non disponibile") } }
        }
    }
    private fun shortcut() {
        val node = current ?: return; val manager = getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) { toast("Il launcher non supporta questa funzione"); return }
        val intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("store", node.store).putExtra("node", node.id)
        val shortcut = ShortcutInfo.Builder(this, "folder-${node.hashCode()}").setShortLabel(displayName(node).take(35))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_app)).setIntent(intent).build()
        manager.requestPinShortcut(shortcut, null)
    }
    fun updateInstallError() {
        if (canUseUi()) toast("Impossibile aprire l’installer. Riprova da Impostazioni → Controlla aggiornamenti.")
    }

    private fun checkUpdates(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        Thread {
            try {
                val result = updater.check()
                runOnUiThread {
                    updateBusy = false
                    if (!canUseUi()) return@runOnUiThread
                    if (result.getBoolean("available")) {
                        AlertDialog.Builder(this).setTitle("Gestore File ${result.getString("versionName")}")
                            .setMessage("È disponibile un aggiornamento.\n\n${result.optString("notes")}\n\nAndroid chiederà conferma per installarlo. I tuoi dati vengono conservati.")
                            .setNegativeButton("Più tardi", null)
                            .setPositiveButton("Aggiorna") { _, _ -> downloadUpdate() }.show()
                    } else if (manual) toast("Gestore File è aggiornato")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateBusy = false
                    if (manual && canUseUi()) toast("Controllo non riuscito. Verifica la connessione e riprova.")
                }
            }
        }.start()
    }

    private fun downloadUpdate() {
        if (updateBusy) return
        updateBusy = true
        toast("Download aggiornamento…")
        Thread {
            try {
                val result = updater.downloadAndInstall()
                runOnUiThread {
                    if (canUseUi() && result.getString("status") == "permission")
                        toast("Autorizza Gestore File a installare app, poi torna qui per continuare.")
                }
            } catch (e: Exception) {
                runOnUiThread { if (canUseUi()) toast(e.message ?: "Download non riuscito. Riprova.") }
            } finally { runOnUiThread { updateBusy = false } }
        }.start()
    }

    private fun settings() {
        val labels = arrayOf(if (dark) "Tema chiaro" else "Tema scuro", "Dimensione caratteri", "Verifica completa anche nelle copie: ${if (graph.preferences.getBoolean("verifyCopy", false)) "sì" else "no"}", "Privacy e sicurezza", "Operazioni interrotte", "Controlla aggiornamenti")
        AlertDialog.Builder(this).setTitle("Impostazioni").setItems(labels) { _, i -> when (i) {
            0 -> { graph.preferences.edit().putBoolean("dark", !dark).apply(); recreate() }
            1 -> AlertDialog.Builder(this).setTitle("Caratteri").setItems(arrayOf("Compatto", "Normale", "Grande", "Molto grande")) { _, index ->
                graph.preferences.edit().putFloat("font", floatArrayOf(.9f, 1f, 1.15f, 1.3f)[index]).apply(); recreate()
            }.show()
            2 -> { val old = graph.preferences.getBoolean("verifyCopy", false); graph.preferences.edit().putBoolean("verifyCopy", !old).apply(); toast(if (old) "Copie: controllo dimensione" else "Copie: verifica SHA-256 completa") }
            3 -> AlertDialog.Builder(this).setTitle("Privacy e sicurezza").setMessage("Internet usato solo per controllare e scaricare aggiornamenti da GitHub Pages. Nessuna pubblicità, account o tracciamento.\n\nSpostamenti tra memorie: verifica SHA-256 prima di rimuovere gli originali.\n\nCestino solo locale, nessuno svuotamento automatico.\n\nEvita di modificare gli stessi file con altre app durante le operazioni. Nessun software protegge dai guasti fisici del supporto.\n\nVersione 0.2.0 (prova)").setPositiveButton("Chiudi", null).show()
            5 -> checkUpdates(true)
            4 -> graph.reads.execute { val records = graph.journal.pending(); graph.main.post { if (!canUseUi()) return@post
 recoveryDialog(records) } }
        } }.show()
    }
    private fun recoveryDialog(records: List<Journal.Record>) {
        if (graph.running.get() || graph.pending.isNotEmpty()) { toast("Attendi la fine delle operazioni prima di verificare i residui"); return }
        if (records.isEmpty()) { toast("Nessuna operazione interrotta"); return }
        val details = records.joinToString("\n\n") { r -> "${r.values["operation"]}\nFase: ${r.values["phase"]}\n${r.values["parent"] ?: r.values["destination"] ?: ""}\n${r.values["stageName"] ?: ""}\n${r.values["backupName"] ?: ""}" }
        AlertDialog.Builder(this).setTitle("${records.size} operazioni da verificare")
            .setMessage("Un'operazione è stata interrotta. Non elimino automaticamente originali, copie parziali o backup.\n\n$details\n\nMostra file nascosti per ispezionare i residui .lf-. Verifica le copie prima di rimuovere un backup.")
            .setPositiveButton("Chiudi", null).setNegativeButton("Archivia avvisi…") { _, _ ->
                confirm("Archiviare questi avvisi?", "Rimuove solo le segnalazioni. File, copie temporanee e backup resteranno al loro posto: verifica prima di archiviare.") {
                    graph.reads.execute {
                        try { records.forEach { graph.journal.finish(it) }; graph.main.post { if (!canUseUi()) return@post
 toast("Avvisi archiviati; nessun file utente eliminato") } }
                        catch (e: Exception) { graph.main.post { if (!canUseUi()) return@post
 error(e.message ?: "Archiviazione non riuscita") } }
                    }
                }
            }.setNeutralButton("Mostra residui") { _, _ ->
                // Recovery opens the affected parent and reveals protected transaction artifacts.
                showRecoveryArtifacts(records)
            }.show()
    }
    private fun showRecoveryArtifacts(records: List<Journal.Record>) {
        if (records.size > 1) {
            AlertDialog.Builder(this).setTitle("Scegli l'operazione").setItems(records.map { "${it.values["operation"]}\n${it.values["parent"] ?: it.values["destination"] ?: ""}" }.toTypedArray()) { _, i -> showRecoveryArtifacts(listOf(records[i])) }.show()
            return
        }
        val record = records.firstOrNull() ?: return
        val storeKey = record.values["store"] ?: return
        val parentId = record.values["parent"] ?: record.values["destination"] ?: return
        val parent = Node(storeKey, parentId)
        val stores = graph.stores()
        graph.reads.execute {
            try {
                val artifacts = stores[parent].list(parent).filter { it.name.startsWith(".lf-") }
                graph.main.post { if (!canUseUi()) return@post

                    if (artifacts.isEmpty()) { toast("Nessun residuo presente in questa cartella"); return@post }
                    AlertDialog.Builder(this).setTitle("Residui: ${displayName(parent)}").setItems(artifacts.map { it.name }.toTypedArray()) { _, i ->
                        val entry = artifacts[i]
                        AlertDialog.Builder(this).setTitle(entry.name).setMessage("Recupera rinominando con un nome nuovo, senza sovrascrivere altri file. Oppure elimina definitivamente questo residuo dopo aver verificato gli originali.")
                            .setPositiveButton("Recupera…") { _, _ -> prompt("Nome recuperato", "Recuperato-${System.currentTimeMillis()}") { name ->
                                operation("Recupero residuo") { _, _ -> stores[entry.node].rename(entry.node, validName(name)); Outcome("Residuo recuperato") }
                            } }.setNegativeButton("Elimina…") { _, _ -> confirm("Elimina residuo?", "Eliminazione definitiva: verifica prima gli originali e la copia finale.") {
                                val engine = graph.engine(stores); operation("Pulizia residuo") { cancel, _ -> engine.removeTree(entry.node, cancel); Outcome("Residuo eliminato") }
                            } }.setNeutralButton("Chiudi", null).show()
                    }.setNegativeButton("Chiudi", null).show()
                }
            } catch (e: Exception) { graph.main.post { if (!canUseUi()) return@post
 error(e.message ?: "Memoria non disponibile") } }
        }
    }
    private fun operation(title: String, execute: (Cancellation, (Progress) -> Unit) -> Outcome) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !graph.preferences.getBoolean("notificationAsked", false)) {
            graph.preferences.edit().putBoolean("notificationAsked", true).apply(); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        selected.clear(); refreshSelection(); graph.enqueue(Work(title, execute)); updateActions()
    }
    private fun showJob(state: JobState) {
        if (!::jobLine.isInitialized) return
        jobLine.visibility = if (state.busy) View.VISIBLE else View.GONE
        val p = state.progress
        jobText.text = state.title + if (p == null) " · preparazione" else "\n${p.phase}: ${p.file}${if (p.bytes > 0) " · ${formatBytes(p.bytes)}" else ""}"
        if (state.revision > revision) {
            revision = state.revision; cache.evictAll(); current?.let { navigate(it) }
            state.message?.let { if (state.failed) error(it) else toast(it) }
            state.report?.let(::reportDialog)
        }
    }
    private fun label(text: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size * font; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(text: String, description: String = text, action: () -> Unit): TextView = label(text, 14f, accent, true).apply {
        gravity = Gravity.CENTER; minimumWidth = dp(48); minimumHeight = dp(48); setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true; isFocusable = true; contentDescription = description
        val out = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true); setBackgroundResource(out.resourceId)
        setOnClickListener { action() }
    }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun showEmpty(message: String) {
        body.removeAllViews(); collection = null
        body.addView(label(message, 16f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(16), dp(24), dp(16)) }, FrameLayout.LayoutParams(-1, -1))
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun error(message: String) { if (canUseUi()) AlertDialog.Builder(this).setTitle("Operazione non completata").setMessage(message).setPositiveButton("Chiudi", null).show() }
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Annulla", null).setPositiveButton("Conferma") { _, _ -> action() }.show()
    }
    private fun prompt(title: String, initial: String, action: (String) -> Unit) {
        val edit = EditText(this).apply { setText(initial); isSingleLine = true; selectAll() }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(edit).setNegativeButton("Annulla", null).setPositiveButton("Conferma", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try { val value = validName(edit.text.toString()); action(value); dialog.dismiss() } catch (e: Exception) { edit.error = e.message }
        } }
        dialog.show()
    }
    companion object {
        fun formatBytes(bytes: Long?): String {
            if (bytes == null) return "Dimensione non disponibile"
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KiB", "MiB", "GiB", "TiB"); var n = bytes.toDouble() / 1024; var i = 0
            while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }
            return String.format(java.util.Locale.getDefault(), "%.1f %s", n, units[i])
        }
    }
}
