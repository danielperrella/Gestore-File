package it.liberofiles.core

import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveTools(private val engine: FileEngine) {
    fun zip(sources: List<Node>, destination: Node, name: String, cancellation: Cancellation, progress: (Progress) -> Unit) {
        val dst = engine.stores[destination]
        val finalName = uniqueName(dst, destination, if (name.endsWith(".zip", true)) name else "$name.zip")
        val roots = sources.map { engine.stores[it].stat(it) }
        require(roots.map { it.name }.distinct().size == roots.size) { "Due elementi hanno lo stesso nome" }
        for (root in roots) require(root.node.store != destination.store || !engine.stores[root.node].contains(root.node, destination)) {
            "Salva lo ZIP fuori dalle cartelle da comprimere"
        }
        engine.staged(destination, finalName, false, Conflict.KEEP_BOTH, "zip") { stage ->
            engine.stores[stage].writeNew(stage).use { output ->
                val zip = ZipOutputStream(java.io.BufferedOutputStream(output.stream, 512 * 1024))
                zip.setLevel(1) // Prefer speed; no recompression tricks that alter input content.
                val buffer = ByteArray(512 * 1024); var entries = 0
                fun write(node: Node, path: String, depth: Int) {
                    cancellation.check(); require(depth <= 128 && ++entries <= 250000) { "Archivio troppo grande" }
                    val store = engine.stores[node]; val before = store.stat(node)
                    zip.putNextEntry(ZipEntry(path + if (before.directory) "/" else "").apply {
                        before.modified?.let { time = it }
                    })
                    if (!before.directory) {
                        var bytes = 0L
                        store.read(node).use { input ->
                            while (true) {
                                cancellation.check(); val n = input.read(buffer); if (n < 0) break
                                if (n > 0) { zip.write(buffer, 0, n); bytes += n; progress(Progress(before.name, bytes, "Compressione ZIP")) }
                            }
                        }
                        val after = store.stat(node)
                        require((before.size == null || bytes == before.size) && before.modified == after.modified && before.size == after.size) {
                            "File modificato durante la compressione: ${before.name}"
                        }
                    }
                    zip.closeEntry()
                    if (before.directory) for (child in store.list(node)) write(child.node, "$path/${child.name}", depth + 1)
                }
                try { for (root in roots) write(root.node, root.name, 0); zip.finish(); zip.flush(); output.sync() }
                finally { zip.close() }
            }
            null
        }
    }

    fun unzip(source: Node, destination: Node, cancellation: Cancellation, progress: (Progress) -> Unit) {
        val src = engine.stores[source]; val dst = engine.stores[destination]
        val base = src.stat(source).name.removeSuffix(".zip").removeSuffix(".ZIP").ifBlank { "Archivio" }
        val name = uniqueName(dst, destination, base)
        engine.staged(destination, name, true, Conflict.KEEP_BOTH, "unzip: ${source.id}") { stage ->
            val stageStore = engine.stores[stage]
            val buffer = ByteArray(512 * 1024); var count = 0; var total = 0L
            val cap = minOf(16L * 1024 * 1024 * 1024, dst.availableBytes(destination) ?: Long.MAX_VALUE)
            val filesSeen = HashSet<String>()
            ZipInputStream(java.io.BufferedInputStream(src.read(source), buffer.size)).use { zip ->
                while (true) {
                    cancellation.check(); val entry = zip.nextEntry ?: break
                    require(++count <= 100000) { "Archivio con troppi elementi" }
                    val path = zipSegments(entry.name)
                    var parent = stage
                    for (part in path.dropLast(1)) {
                        parent = stageStore.child(parent, part)?.also { require(stageStore.stat(it).directory) { "Conflitto nell'archivio" } }
                            ?: stageStore.create(parent, part, true)
                    }
                    if (entry.isDirectory) {
                        stageStore.child(parent, path.last())?.let { require(stageStore.stat(it).directory) }
                            ?: stageStore.create(parent, path.last(), true)
                    } else {
                        require(filesSeen.add(path.joinToString("/"))) { "Nome duplicato nell'archivio" }
                        val file = stageStore.create(parent, path.last(), false)
                        stageStore.writeNew(file).use { output ->
                            var bytes = 0L
                            while (true) {
                                cancellation.check(); val n = zip.read(buffer); if (n < 0) break
                                total += n; bytes += n
                                require(total <= cap) { "Estrazione oltre il limite di spazio (massimo 16 GiB per archivio)" }
                                if (n > 0) output.stream.write(buffer, 0, n)
                                progress(Progress(entry.name, bytes, "Estrazione ZIP"))
                            }
                            require(entry.size < 0 || bytes == entry.size) { "Archivio incompleto" }
                            output.sync()
                        }
                    }
                    zip.closeEntry() // Validates CRC, including data descriptors.
                }
            }
            require(count > 0) { "ZIP vuoto o formato non riconosciuto" }
            null
        }
    }

    companion object {
        fun zipSegments(path: String): List<String> {
            require(!path.startsWith('/') && !path.startsWith('\\') && !path.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
                "Percorso assoluto nell'archivio"
            }
            val segments = path.trimEnd('/').split('/')
            require(segments.isNotEmpty() && segments.size <= 128) { "Percorso ZIP non valido" }
            segments.forEach(::validName)
            return segments
        }
    }
}

data class RenameRule(val prefix: String = "", val suffix: String = "", val find: String = "", val replace: String = "", val number: Boolean = false) {
    fun name(original: String, index: Int, directory: Boolean): String {
        val dot = if (directory) original.length else original.lastIndexOf('.').takeIf { it > 0 } ?: original.length
        var stem = original.substring(0, dot)
        if (find.isNotEmpty()) stem = stem.replace(find, replace)
        return validName(prefix + stem + suffix + if (number) "_${(index + 1).toString().padStart(3, '0')}" + original.substring(dot) else original.substring(dot))
    }
}

class BatchRenamer(private val stores: Stores, private val journal: Journal) {
    fun preview(nodes: List<Node>, rule: RenameRule): List<Pair<Entry, String>> = nodes.mapIndexed { i, node ->
        stores[node].stat(node).let { it to rule.name(it.name, i, it.directory) }
    }
    fun rename(nodes: List<Node>, rule: RenameRule, cancellation: Cancellation) {
        val plan = preview(nodes, rule).filter { it.first.name != it.second }
        if (plan.isEmpty()) return
        require(plan.map { it.first.node.store }.distinct().size <= 1) { "Seleziona elementi della stessa memoria" }
        require(plan.map { stores[it.first.node].parent(it.first.node) }.distinct().size <= 1) { "Seleziona elementi della stessa cartella" }
        require(plan.map { it.second.lowercase(java.util.Locale.ROOT) }.distinct().size == plan.size) { "La rinomina produce nomi duplicati" }
        val originals = plan.map { it.first.node }.toSet()
        for ((entry, name) in plan) {
            val store = stores[entry.node]; val parent = store.parent(entry.node) ?: error("Impossibile rinominare una radice")
            require(store.child(parent, name)?.let { it in originals } != false) { "Nome già esistente: $name" }
        }
        val moved = ArrayList<Triple<Node, Entry, String>>()
        val first = plan.first().first.node
        var record = journal.begin(mapOf("operation" to "batch-rename", "phase" to "prepare", "store" to first.store,
            "parent" to stores[first].parent(first)!!.id))
        try {
            for ((entry, name) in plan) {
                cancellation.check()
                val tempName = ".lf-rename-${UUID.randomUUID()}"
                record = journal.update(record, "original.${moved.size}" to "${entry.node.store}|${entry.node.id}",
                    "temporary.${moved.size}" to tempName, "final.${moved.size}" to name)
                moved.add(Triple(stores[entry.node].rename(entry.node, tempName), entry, name))
            }
            // Complete without cancellation between individual commits; journal preserves recovery information.
            for (i in moved.indices) {
                val (temp, entry, name) = moved[i]
                val result = stores[temp].rename(temp, name)
                moved[i] = Triple(result, entry, name)
                record = journal.update(record, "result.$i" to result.id)
            }
            journal.finish(record)
        } catch (e: Exception) {
            var restored = true
            // Two phases also allow rollback of swaps.
            val rollback = moved.map { (node, original, _) ->
                try { stores[node].rename(node, ".lf-rollback-${UUID.randomUUID()}") to original }
                catch (_: Exception) { restored = false; node to original }
            }
            for ((node, original) in rollback) try { stores[node].rename(node, original.name) } catch (_: Exception) { restored = false }
            if (restored) journal.finish(record)
            throw e
        }
    }
}

data class SpaceReport(val bytes: Long, val files: Long, val directories: Long, val largest: List<Entry>, val categories: Map<String, Long>, val inaccessible: Long = 0)
fun analyze(stores: Stores, root: Node, cancellation: Cancellation, progress: (Progress) -> Unit): SpaceReport {
    var bytes = 0L; var files = 0L; var directories = 0L; var inaccessible = 0L
    val largest = java.util.PriorityQueue<Entry>(compareBy { it.size ?: 0 })
    val categories = HashMap<String, Long>()
    fun walk(node: Node, depth: Int) {
        cancellation.check(); require(depth <= 128) { "Cartelle troppo profonde" }
        val entry = stores[node].stat(node)
        if (entry.directory) { directories++; for (child in stores[node].list(node)) {
            if (!child.name.startsWith(".lf-") && child.name != ".LiberoFilesTrash") {
                try { walk(child.node, depth + 1) }
                catch (e: Cancelled) { throw e }
                catch (_: Exception) { inaccessible++ }
            }
        } } else {
            val size = entry.size ?: 0L; files++; bytes += size
            val ext = entry.name.substringAfterLast('.', "senza estensione").lowercase(java.util.Locale.ROOT)
            categories[ext] = (categories[ext] ?: 0L) + size
            largest.add(entry); if (largest.size > 20) largest.poll()
            progress(Progress(entry.name, bytes, "Analisi spazio"))
        }
    }
    walk(root, 0)
    return SpaceReport(bytes, files, directories, largest.sortedByDescending { it.size ?: 0 }, categories, inaccessible)
}
