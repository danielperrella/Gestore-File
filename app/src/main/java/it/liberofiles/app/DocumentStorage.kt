package it.liberofiles.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as DC
import it.liberofiles.core.*
import java.io.FileOutputStream
import java.io.InputStream

/** Only Android's physical external-storage provider is accepted; no cloud integration. */
class DocumentStorage(private val resolver: ContentResolver, val tree: Uri, override val key: String) : Storage {
    init { require(tree.authority == "com.android.externalstorage.documents") { "Seleziona una memoria fisica locale o USB" } }
    private val rootId = DC.getTreeDocumentId(tree)
    private val childrenIndex = HashMap<String, MutableMap<String, Node>>()
    private fun normalized(name: String) = name.lowercase(java.util.Locale.ROOT)
    override fun refresh(directory: Node) { childrenIndex.remove(directory.id) }
    val rootNode = Node(key, rootId)
    fun uri(node: Node): Uri {
        require(node.store == key && within(rootId, node.id)) { "Documento fuori dalla cartella autorizzata" }
        return DC.buildDocumentUriUsingTree(tree, node.id)
    }
    private val projection = arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME,
        DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_SIZE, DC.Document.COLUMN_LAST_MODIFIED)
    private fun entry(cursor: android.database.Cursor): Entry = Entry(Node(key, cursor.getString(0)),
        cursor.getString(1), cursor.getString(2) == DC.Document.MIME_TYPE_DIR,
        if (cursor.isNull(3)) null else cursor.getLong(3), if (cursor.isNull(4) || cursor.getLong(4) == 0L) null else cursor.getLong(4))
    override fun stat(node: Node): Entry = resolver.query(uri(node), projection, null, null)?.use {
        require(it.moveToFirst()) { "Documento non trovato o memoria scollegata" }; entry(it)
    } ?: error("Memoria non disponibile")
    override fun list(directory: Node): List<Entry> {
        uri(directory) // Check scope.
        val children = DC.buildChildDocumentsUriUsingTree(tree, directory.id)
        return resolver.query(children, projection, null, null)?.use { cursor ->
            val result = ArrayList<Entry>(); while (cursor.moveToNext()) result.add(entry(cursor))
            childrenIndex[directory.id] = result.associate { normalized(it.name) to it.node }.toMutableMap()
            result
        } ?: error("Impossibile leggere la cartella")
    }
    override fun parent(node: Node): Node? {
        if (node == rootNode) return null
        val colon = node.id.indexOf(':'); val slash = node.id.lastIndexOf('/')
        val parentId = if (slash > colon) node.id.substring(0, slash) else node.id.substring(0, colon + 1)
        return Node(key, parentId).takeIf { within(rootId, it.id) }
    }
    override fun child(directory: Node, name: String): Node? {
        uri(directory)
        val normalized = normalized(validName(name))
        if (!childrenIndex.containsKey(directory.id)) list(directory)
        return childrenIndex[directory.id]?.get(normalized)
    }
    override fun create(directory: Node, name: String, isDirectory: Boolean): Node {
        require(child(directory, validName(name)) == null) { "Nome già esistente" }
        val result = DC.createDocument(resolver, uri(directory), if (isDirectory) DC.Document.MIME_TYPE_DIR else "application/octet-stream", name)
            ?: error("Creazione non riuscita")
        val node = Node(key, DC.getDocumentId(result))
        require(stat(node).name == name) { "La memoria ha cambiato il nome richiesto; operazione interrotta" }
        childrenIndex[directory.id]?.set(normalized(name), node)
        if (isDirectory) childrenIndex[node.id] = mutableMapOf()
        return node
    }
    override fun read(node: Node): InputStream = resolver.openInputStream(uri(node)) ?: error("Lettura non riuscita")
    override fun writeNew(node: Node): DurableOutput {
        require(stat(node).size?.let { it == 0L } != false) { "Rifiutata la scrittura su un file esistente" }
        val descriptor = resolver.openFileDescriptor(uri(node), "rw") ?: error("Scrittura non riuscita")
        val stream = android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        return object : DurableOutput {
            override val stream = stream
            override fun sync() { stream.flush(); descriptor.fileDescriptor.sync() }
            override fun close() = stream.close()
        }
    }
    override fun rename(node: Node, name: String): Node {
        val oldName = stat(node).name
        val parent = parent(node) ?: error("Radice non rinominabile")
        require(child(parent, validName(name)) == null) { "Nome già esistente" }
        val result = DC.renameDocument(resolver, uri(node), name) ?: error("La memoria non supporta la rinomina")
        val renamed = Node(key, DC.getDocumentId(result))
        require(stat(renamed).name == name) { "Rinomina non confermata dal sistema" }
        childrenIndex[parent.id]?.remove(normalized(oldName)); childrenIndex[parent.id]?.set(normalized(name), renamed)
        childrenIndex.remove(node.id)
        return renamed
    }
    override fun moveAtomic(node: Node, destination: Node, name: String): Node? = null // SAF offers no general atomicity guarantee.
    override fun deleteEmpty(node: Node) {
        val name = stat(node).name
        if (stat(node).directory) require(list(node).isEmpty()) { "Cartella non vuota: conservata" }
        require(DC.deleteDocument(resolver, uri(node))) { "Eliminazione non riuscita" }
        parent(node)?.let { childrenIndex[it.id]?.remove(normalized(name)) }; childrenIndex.remove(node.id)
    }
    override fun contains(ancestor: Node, node: Node): Boolean = node.store == key && within(ancestor.id, node.id)
    private fun within(parent: String, child: String): Boolean = child == parent ||
        child.startsWith(if (parent.endsWith(':')) parent else "$parent/")
}
