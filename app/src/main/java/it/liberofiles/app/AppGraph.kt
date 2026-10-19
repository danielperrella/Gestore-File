package it.liberofiles.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import it.liberofiles.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FilesApplication : Application() {
    lateinit var graph: AppGraph
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}

data class Outcome(val message: String = "Operazione completata", val report: SpaceReport? = null)
data class Work(val title: String, val execute: (Cancellation, (Progress) -> Unit) -> Outcome)
data class JobState(val busy: Boolean = false, val title: String = "", val progress: Progress? = null,
                    val message: String? = null, val failed: Boolean = false, val report: SpaceReport? = null,
                    val revision: Long = 0)

class AppGraph(val context: Context) {
    val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val local = LocalStorage(Environment.getExternalStorageDirectory().toPath())
    val journal = Journal(File(context.filesDir, "transactions"))
    val reads = Executors.newFixedThreadPool(2)
    val operations = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    val pending = ConcurrentLinkedQueue<Work>()
    val running = AtomicBoolean(false)
    val listeners = CopyOnWriteArrayList<(JobState) -> Unit>()
    @Volatile var state = JobState()
    @Volatile var cancellation: Cancellation? = null
    var clipboard: List<Node> = emptyList()
    var clipboardMove = false
    @Volatile var transferProgress: ((Progress) -> Unit)? = null
    val drive = DriveAccounts(context,check={ cancellation?.check() },onUpload={ bytes ->
        transferProgress?.invoke(Progress(state.progress?.file ?: "File",bytes,"Caricamento Google Drive"))
    })
    init {
        val base=File(context.cacheDir,"drive").canonicalFile
        val expiry=System.currentTimeMillis()-24*60*60*1000L
        base.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("drive-[A-Za-z0-9_-]+")) }?.forEach { directory ->
            if(directory.canonicalFile.parentFile == base) directory.listFiles()?.forEach { file ->
                if(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && file.lastModified() < expiry) file.delete()
            }
        }
    }

    fun stores(): Stores {
        val map = mutableMapOf<String, Storage>(local.key to local)
        val saved = preferences.getString("trees", "[]") ?: "[]"
        val array = JSONArray(saved)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            try { val store = DocumentStorage(context.contentResolver, Uri.parse(item.getString("uri")), item.getString("key")); map[store.key] = store }
            catch (_: Exception) { }
        }
        drive.accounts().forEach { map[it.key] = drive.storage(it.key) }
        return Stores(map)
    }
    fun roots(): List<Pair<String, Node>> {
        val roots = mutableListOf("Memoria interna" to local.rootNode)
        val array = JSONArray(preferences.getString("trees", "[]"))
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            try {
                val store = DocumentStorage(context.contentResolver, Uri.parse(item.getString("uri")), item.getString("key"))
                roots.add(item.optString("name", "USB / cartella autorizzata") to store.rootNode)
            } catch (_: Exception) { }
        }
        roots.addAll(drive.roots())
        return roots
    }
    fun addTree(uri: Uri, name: String) {
        require(uri.authority == "com.android.externalstorage.documents") { "Scegli una memoria fisica, non un servizio cloud" }
        val array = JSONArray(preferences.getString("trees", "[]"))
        for (i in 0 until array.length()) if (array.getJSONObject(i).getString("uri") == uri.toString()) return
        array.put(JSONObject().put("uri", uri.toString()).put("name", name).put("key", "usb-${java.util.UUID.randomUUID()}"))
        preferences.edit().putString("trees", array.toString()).apply()
    }
    fun removeTree(key: String) {
        val old = JSONArray(preferences.getString("trees", "[]")); val saved = JSONArray()
        for (i in 0 until old.length()) {
            val entry = old.getJSONObject(i)
            if (entry.getString("key") == key) {
                try { context.contentResolver.releasePersistableUriPermission(Uri.parse(entry.getString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                catch (_: Exception) { }
            } else saved.put(entry)
        }
        preferences.edit().putString("trees", saved.toString()).apply()
    }
    fun engine(stores: Stores = stores()) = FileEngine(stores, journal)
    fun publish(next: JobState) { state = next; main.post { listeners.forEach { it(next) } } }
    fun enqueue(work: Work) {
        pending.add(work)
        try { context.startForegroundService(Intent(context, OperationService::class.java)) }
        catch (e: Exception) {
            pending.remove(work)
            publish(state.copy(message = "Impossibile avviare l'operazione: ${e.message}", failed = true, revision = state.revision + 1))
        }
    }
    fun nodes(key: String): List<Node> {
        val a = JSONArray(preferences.getString(key, "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { Node(it.getString("store"), it.getString("id")) } }
    }
    fun saveNodes(key: String, nodes: List<Node>) {
        val a = JSONArray(); nodes.distinct().take(30).forEach { a.put(JSONObject().put("store", it.store).put("id", it.id)) }
        preferences.edit().putString(key, a.toString()).apply()
    }
}
