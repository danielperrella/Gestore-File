package it.liberofiles.app

import it.liberofiles.core.*
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.time.Instant

class DriveStorage(val api: DriveApi, override val key: String, private val cache: File,
                   private val check: () -> Unit = {}, private val uploadProgress: (Long) -> Unit = {}) : Storage {
    override val remote = true
    val rootNode = Node(key,"root")
    private val created = HashSet<String>()
    private val replacements = HashMap<Pair<String,String>,String>()
    private fun id(node: Node): String { require(node.store == key) { "Account Drive diverso" }; return node.id }
    fun metadata(node: Node) = api.get(id(node))
    fun entry(json: JSONObject): Entry {
        val mime = json.getString("mimeType"); val folder = mime == DriveApi.FOLDER
        val native = mime.startsWith("application/vnd.google-apps.") && !folder
        val caps = json.optJSONObject("capabilities") ?: JSONObject()
        return Entry(Node(key,json.getString("id")),json.optString("name","Il mio Drive"),folder,
            if(json.has("size")) json.getLong("size") else null,
            json.optString("modifiedTime").takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() },
            json.optString("version").ifBlank { null },mime,
            Capabilities(read = folder || (!native && caps.optBoolean("canDownload")),
                create = folder && caps.optBoolean("canAddChildren"), rename = caps.optBoolean("canRename"),
                move = caps.optBoolean("canMoveItemWithinDrive"), delete = caps.optBoolean("canTrash"), export = native && caps.optBoolean("canDownload")))
    }
    override fun stat(node: Node): Entry {
        val data=metadata(node)
        require(!data.optBoolean("trashed")) { "Il file è nel cestino Google Drive" }
        return entry(data)
    }
    override fun list(directory: Node): List<Entry> {
        val actual = metadata(directory)
        require(actual.getString("mimeType") == DriveApi.FOLDER && !actual.optBoolean("trashed")) { "Cartella Drive non disponibile" }
        return api.children(actual.getString("id")).map(::entry)
    }
    override fun parent(node: Node): Node? {
        if(node.id == "root") return null
        val json = metadata(node); val rootId = metadata(rootNode).getString("id")
        if(json.getString("id") == rootId) return null
        return json.optJSONArray("parents")?.optString(0)?.takeIf { it.isNotBlank() }?.let { Node(key,if(it == rootId) "root" else it) }
    }
    fun matches(directory: Node, name: String) = list(directory).filter { it.name == name }
    fun selectReplacement(directory: Node, name: String, fileId: String) {
        require(matches(directory,name).any { it.node.id == fileId && !it.directory }) { "File da sostituire non più disponibile" }
        replacements[directory.id to name] = fileId
    }
    override fun child(directory: Node, name: String): Node? {
        val matches=matches(directory,name)
        val chosen=replacements[directory.id to name]
        return if(chosen != null) matches.firstOrNull { it.node.id == chosen }?.node else matches.firstOrNull()?.node
    }
    override fun requireUnambiguous(directory: Node, name: String) {
        val chosen=replacements[directory.id to name]
        require(matches(directory,name).size <= 1 || (chosen != null && matches(directory,name).any { it.node.id == chosen })) { "Più file Drive si chiamano '$name'. Scegli quello da sostituire oppure Conserva entrambi." }
    }
    override fun create(directory: Node, name: String, isDirectory: Boolean): Node {
        check(); require(stat(directory).capabilities.create) { "Cartella Drive in sola lettura" }
        require(child(directory,validName(name)) == null) { "Nome già esistente su Drive" }
        return Node(key,api.create(id(directory),name,isDirectory).getString("id")).also { created.add(it.id) }
    }
    override fun read(node: Node): InputStream {
        check(); require(stat(node).capabilities.read) { "Download non consentito. Per i documenti Google usa Esporta." }
        return api.content(id(node))
    }
    override fun writeNew(node: Node): DurableOutput {
        check(); require(id(node) in created && stat(node).size?.let { it == 0L } != false) { "Scrittura Drive consentita solo su file temporanei nuovi" }
        val directory = File(cache,key).apply { require(isDirectory || mkdirs()) { "Cache Drive non disponibile" } }
        val file = File.createTempFile("upload-",".part",directory)
        val output = FileOutputStream(file); var completed = false
        return object : DurableOutput {
            override val stream = output
            override fun sync() {
                if(completed) return
                output.flush(); output.fd.sync(); check()
                api.upload(id(node),file,uploadProgress)
                val before = metadata(node)
                require(before.optLong("size",-1) == file.length()) { "Dimensione Drive non confermata" }
                val localHash = file.inputStream().use { digest(it) }
                val remoteHash = before.optString("sha256Checksum").ifBlank { api.content(id(node)).use { digest(it) } }
                require(localHash == remoteHash && before.optString("version") == metadata(node).optString("version")) { "Verifica Drive fallita: originale conservato" }
                completed = true
            }
            override fun close() { try { output.close() } finally { file.delete() } }
        }
    }
    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while(true) { check(); val count=input.read(bytes); if(count<0) break; hash.update(bytes,0,count) }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    override fun rename(node: Node, name: String): Node {
        check(); val before=metadata(node); require(entry(before).capabilities.rename) { "Rinomina Drive non consentita" }
        val parent = parent(node) ?: error("Radice Drive non rinominabile")
        val replacement=replacements[parent.id to validName(name)]
        require(matches(parent,name).all { it.node == node } || (replacement != null && (node.id in created || node.id == replacement))) { "Nome già presente su Drive" }
        val body=JSONObject().put("name",name)
        if(before.optString("mimeType") == "application/octet-stream" && !name.startsWith(".lf-")) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.',"").lowercase())?.let { body.put("mimeType",it) }
        }
        val result = api.patch(id(node),body,etag=before.optString("_etag"))
        require(result.getString("name") == name) { "Rinomina non confermata" }
        return Node(key,result.getString("id"))
    }
    // Drive moves are not advertised as filesystem-atomic. The engine copies, verifies, then trashes.
    override fun moveAtomic(node: Node, destination: Node, name: String): Node? = null
    fun move(node: Node, destination: Node, conflict: Conflict): TransferResult {
        val source = metadata(node); val entry = entry(source)
        require(entry.capabilities.move && stat(destination).capabilities.create) { "Spostamento Drive non consentito" }
        require(!contains(node,destination) && parent(node) != destination) { "Origine e destinazione coincidono o la destinazione è interna all’origine" }
        val existing = child(destination,entry.name)
        if(existing != null && conflict == Conflict.SKIP) return TransferResult(null,true)
        require(conflict != Conflict.REPLACE || existing == null) { "Per lo spostamento nello stesso Drive scegli Conserva entrambi o Salta" }
        val name = if(conflict == Conflict.KEEP_BOTH) uniqueName(this,destination,entry.name) else entry.name
        val parents = source.optJSONArray("parents") ?: error("Cartella origine Drive mancante")
        val removed = (0 until parents.length()).joinToString(",") { parents.getString(it) }
        val result = api.patch(id(node),JSONObject().put("name",name),mapOf("addParents" to id(destination),"removeParents" to removed),source.optString("_etag"))
        require(result.optJSONArray("parents")?.let { array -> (0 until array.length()).any { array.getString(it) == (if(destination.id == "root") metadata(rootNode).getString("id") else destination.id) } } == true) { "Spostamento Drive non confermato. Aggiorna la cartella prima di riprovare." }
        return TransferResult(Node(key,result.getString("id")),false)
    }
    override fun deleteEmpty(node: Node) {
        check(); val entry = stat(node)
        require(entry.capabilities.delete) { "Spostamento nel cestino Drive non consentito" }
        if(entry.directory) require(list(node).isEmpty()) { "Cartella Drive non vuota: conservata" }
        trash(node)
    }
    override fun deleteVerified(node: Node, expected: Entry) {
        val before=metadata(node)
        require(before.optString("version") == expected.identity) { "Origine Drive modificata: conservata. La copia completa resta nella destinazione." }
        if(expected.directory) require(list(node).isEmpty()) { "Nuovi contenuti Drive: cartella conservata" }
        require(before.optJSONObject("capabilities")?.optBoolean("canTrash") == true) { "Rimozione origine Drive non consentita" }
        require(api.patch(id(node),JSONObject().put("trashed",true),etag=before.optString("_etag")).optBoolean("trashed")) { "Rimozione origine non confermata" }
    }
    fun trash(node: Node) {
        check(); require(node != rootNode && parent(node) != null) { "Radice Drive protetta" }
        val before=metadata(node)
        require(entry(before).capabilities.delete) { "Cestino Drive non consentito" }
        require(api.patch(id(node),JSONObject().put("trashed",true),etag=before.optString("_etag")).optBoolean("trashed")) { "Cestino Drive non confermato" }
    }
    fun trashed(): List<Entry> = api.files("trashed = true").filter { it.optBoolean("explicitlyTrashed") }.map(::entry)
    fun restore(node: Node) {
        val file = metadata(node)
        require(file.optJSONObject("capabilities")?.optBoolean("canUntrash") == true) { "Ripristino Drive non consentito" }
        require(!api.patch(id(node),JSONObject().put("trashed",false),etag=file.optString("_etag")).optBoolean("trashed")) { "Ripristino non confermato" }
    }
    override fun contains(ancestor: Node, node: Node): Boolean {
        if(node.store != key || ancestor.store != key) return false
        val target = if(ancestor.id == "root") metadata(rootNode).getString("id") else ancestor.id
        var cursor: Node? = node; val seen = HashSet<String>()
        while(cursor != null) {
            check(); require(seen.size < 128 && seen.add(cursor.id)) { "Gerarchia Drive ciclica o troppo profonda" }
            val actual = if(cursor.id == "root") metadata(rootNode).getString("id") else cursor.id
            if(actual == target) return true
            cursor = parent(cursor)
        }
        return false
    }
    override fun availableBytes(directory: Node): Long? {
        val quota = api.about().optJSONObject("storageQuota") ?: return null
        return if(quota.has("limit")) (quota.getLong("limit")-quota.optLong("usage")).coerceAtLeast(0) else null
    }
}
