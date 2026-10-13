package it.liberofiles.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class Node(val store: String, val id: String)
data class Entry(val node: Node, val name: String, val directory: Boolean, val size: Long?, val modified: Long?, val identity: String? = null)

interface DurableOutput : AutoCloseable {
    val stream: OutputStream
    fun sync()
}

interface Storage {
    val key: String
    fun stat(node: Node): Entry
    fun list(directory: Node): List<Entry>
    fun parent(node: Node): Node?
    fun child(directory: Node, name: String): Node?
    fun create(directory: Node, name: String, isDirectory: Boolean): Node
    fun read(node: Node): InputStream
    /** May open only a newly-created staging file, never an existing user file. */
    fun writeNew(node: Node): DurableOutput
    fun rename(node: Node, name: String): Node
    /** Fast same-filesystem move; false means copy-and-verify is required. */
    fun moveAtomic(node: Node, destination: Node, name: String): Node?
    fun deleteEmpty(node: Node)
    fun contains(ancestor: Node, node: Node): Boolean
    fun availableBytes(directory: Node): Long? = null
    fun refresh(directory: Node) { }
}

class Stores(private val stores: Map<String, Storage>) {
    operator fun get(node: Node): Storage = stores[node.store] ?: error("Memoria non disponibile: ${node.store}")
}

class Cancelled : Exception("Operazione annullata. Gli originali non copiati restano al loro posto.")
class Cancellation {
    private val flag = AtomicBoolean(false)
    fun cancel() { flag.set(true) }
    fun check() { if (flag.get()) throw Cancelled() }
}
enum class Conflict { KEEP_BOTH, SKIP, REPLACE }
data class Progress(val file: String, val bytes: Long = 0, val phase: String = "Copia")
data class TransferResult(val destination: Node?, val skipped: Boolean = false)

fun validName(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray(Charsets.UTF_8).size <= 240 &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 }) { "Nome non valido" }
    return name
}

fun uniqueName(store: Storage, directory: Node, original: String): String {
    validName(original)
    if (store.child(directory, original) == null) return original
    val dot = original.lastIndexOf('.').takeIf { it > 0 } ?: original.length
    val stem = original.substring(0, dot)
    val ext = original.substring(dot)
    for (i in 1..10000) {
        val name = "${stem.take((230 - ext.length).coerceAtLeast(1))} ($i)$ext"
        if (store.child(directory, name) == null) return name
    }
    error("Troppi nomi duplicati")
}
