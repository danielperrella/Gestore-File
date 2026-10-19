package it.liberofiles.core

import java.security.MessageDigest
import java.util.UUID

/** All mutating calls run on one worker. Originals are never opened for writing. */
class FileEngine(val stores: Stores, private val journal: Journal) {
    data class Snapshot(val relative: String, val entry: Entry, val digest: ByteArray?)
    private val bufferSize = 512 * 1024
    private val buffer by lazy { ByteArray(bufferSize) }

    fun transfer(source: Node, destination: Node, move: Boolean, conflict: Conflict,
                 cancellation: Cancellation, verifyCopy: Boolean = false,
                 targetName: String? = null, progress: (Progress) -> Unit = {}): TransferResult {
        cancellation.check()
        val src = stores[source]; val dst = stores[destination]
        val original = src.stat(source)
        require(original.capabilities.read) { "Download non consentito; per documenti Google usa Esporta" }
        require(!move || original.capabilities.move) { "Spostamento non consentito" }
        require(!move || original.capabilities.delete) { "Non puoi rimuovere l’origine dopo il trasferimento" }
        require(dst.stat(destination).capabilities.create) { "Non puoi creare file in questa cartella" }
        require(dst.stat(destination).directory) { "La destinazione non è una cartella" }
        require(source.store != destination.store || !src.contains(source, destination)) { "Impossibile copiare una cartella dentro sé stessa" }
        val requestedName = validName(targetName ?: original.name)
        val existing = dst.child(destination, requestedName)
        require(existing != source) { "Origine e destinazione coincidono" }
        if (existing != null && conflict == Conflict.SKIP) return TransferResult(null, true)
        val name = if (conflict == Conflict.KEEP_BOTH) uniqueName(dst, destination, requestedName) else requestedName
        if (existing != null && conflict == Conflict.REPLACE) {
            dst.requireUnambiguous(destination, requestedName)
            require(!original.directory && !dst.stat(existing).directory) { "Per le cartelle usa Conserva entrambi o Salta. La sostituzione completa delle cartelle è disabilitata." }
        }
        if (move && source.store == destination.store && dst.child(destination, name) == null) {
            var record = journal.begin(mapOf("operation" to "move", "source" to source.id, "store" to source.store,
                "destination" to destination.id, "name" to name, "phase" to "atomic-move"))
            try {
                val result = src.moveAtomic(source, destination, name)
                if (result != null) { journal.finish(record); return TransferResult(result) }
                journal.finish(record)
            } catch (e: Exception) { throw e }
        }
        return staged(destination, name, original.directory, conflict, "${if (move) "move" else "copy"}: ${source.id}") { stage ->
            val manifest = ArrayList<Snapshot>()
            val verify = move || verifyCopy || src.remote || dst.remote
            copyTree(source, stage, "", manifest, verify, cancellation, progress, 0)
            if (verify) verifyTree(stage, manifest, cancellation, progress)
            // Check the complete source before committing or deleting anything.
            assertUnchanged(source, manifest, move, cancellation, progress)
            if (move) {
                // Runs only AFTER publication. A failure leaves a complete destination and surviving originals.
                { deleteVerifiedSource(source, manifest, cancellation, progress) }
            } else null
        }
    }

    /** Stage, backup an old file if needed, publish, then perform optional source cleanup. */
    fun staged(destination: Node, name: String, directory: Boolean, conflict: Conflict,
               operation: String, build: (Node) -> (() -> Unit)?): TransferResult {
        val store = stores[destination]
        validName(name)
        store.refresh(destination)
        val old = store.child(destination, name)
        val oldEntry = old?.let { store.stat(it) }
        if (old != null && conflict == Conflict.REPLACE) store.requireUnambiguous(destination, name)
        if (old != null && conflict == Conflict.SKIP) return TransferResult(null, true)
        require(old == null || (conflict == Conflict.REPLACE && !directory && !store.stat(old).directory)) { "La destinazione esiste già" }
        val token = UUID.randomUUID().toString()
        val stageName = ".lf-stage-$token"
        val backupName = ".lf-backup-$token"
        var record = journal.begin(mapOf("operation" to operation, "store" to destination.store,
            "parent" to destination.id, "name" to name, "stageName" to stageName,
            "backupName" to backupName, "phase" to "prepare"))
        var stage: Node? = null; var backup: Node? = null; var committed = false
        try {
            stage = store.create(destination, stageName, directory)
            record = journal.update(record, "stage" to stage.id, "phase" to "copying")
            val afterCommit = build(stage)
            record = journal.update(record, "phase" to "ready")
            // Recheck conflict: a file could have appeared while copying.
            store.refresh(destination)
            val current = store.child(destination, name)
            require(current == old) { "La destinazione è cambiata durante la copia; originali conservati" }
            require(current == null || sameMetadata(oldEntry!!,store.stat(current))) { "Il file destinazione è stato modificato durante la copia; originali conservati" }
            if (old != null) {
                record = journal.update(record, "phase" to "backup")
                backup = store.rename(old, backupName)
                record = journal.update(record, "backup" to backup.id)
            }
            record = journal.update(record, "phase" to "publishing")
            val published = store.rename(stage, name)
            stage = null; committed = true
            record = journal.update(record, "published" to published.id, "phase" to "committed")
            afterCommit?.invoke()
            backup?.let { removeTree(it, Cancellation()) }; backup = null
            journal.finish(record)
            return TransferResult(published)
        } catch (error: Exception) {
            if (!committed) {
                var clean = true
                stage?.let { try { removeTree(it, Cancellation()) } catch (_: Exception) { clean = false } }
                backup?.let { b ->
                    try {
                        if (store.child(destination, name) == null) store.rename(b, name) else clean = false
                    } catch (_: Exception) { clean = false }
                }
                if (clean) journal.finish(record)
            }
            throw error
        }
    }

    private fun copyTree(source: Node, target: Node, relative: String, manifest: MutableList<Snapshot>,
                         hash: Boolean, cancellation: Cancellation, progress: (Progress) -> Unit, depth: Int) {
        cancellation.check()
        require(depth <= 128 && manifest.size < 250000) { "Albero troppo profondo o troppo grande" }
        val store = stores[source]; val dest = stores[target]
        val before = store.stat(source)
        require(before.capabilities.read) { "${before.name}: usa Esporta per i documenti Google; download non consentito" }
        if (before.directory) {
            manifest.add(Snapshot(relative, before, null))
            for (entry in store.list(source)) {
                val child = dest.create(target, entry.name, entry.directory)
                copyTree(entry.node, child, if (relative.isEmpty()) entry.name else "$relative/${entry.name}",
                    manifest, hash, cancellation, progress, depth + 1)
            }
        } else {
            val available = dest.availableBytes(target)
            require(before.size == null || available == null || before.size <= available) { "Spazio insufficiente" }
            val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
            var count = 0L
            store.read(source).use { input -> dest.writeNew(target).use { output ->
                while (true) {
                    cancellation.check()
                    val n = input.read(buffer); if (n < 0) break; if (n == 0) continue
                    output.stream.write(buffer, 0, n); digest?.update(buffer, 0, n); count += n
                    progress(Progress(before.name, count))
                }
                output.sync()
            } }
            require(before.size == null || count == before.size) { "Dimensione dell'origine cambiata durante la copia" }
            val after = store.stat(source)
            require(sameMetadata(before, after)) { "File modificato durante la copia: ${before.name}" }
            require(dest.stat(target).size?.let { it == count } != false) { "Copia incompleta: ${before.name}" }
            manifest.add(Snapshot(relative, before, digest?.digest()))
        }
    }

    private fun resolve(root: Node, relative: String): Node {
        var node = root
        if (relative.isNotEmpty()) for (segment in relative.split('/')) {
            node = stores[node].child(node, segment) ?: error("File mancante: $relative")
        }
        return node
    }

    private fun verifyTree(root: Node, manifest: List<Snapshot>, cancellation: Cancellation, progress: (Progress) -> Unit) {
        for (snapshot in manifest) if (!snapshot.entry.directory) {
            val node = resolve(root, snapshot.relative)
            progress(Progress(snapshot.entry.name, phase = "Verifica destinazione"))
            require(snapshot.digest!!.contentEquals(hash(node, cancellation))) { "Verifica fallita: ${snapshot.entry.name}. Originale conservato." }
        }
    }
    private fun assertUnchanged(root: Node, manifest: List<Snapshot>, digest: Boolean, cancellation: Cancellation, progress: (Progress) -> Unit) {
        val expected = manifest.associateBy { it.relative }
        var seen = 0
        fun walk(node: Node, relative: String) {
            cancellation.check()
            val snapshot = expected[relative] ?: error("L'origine è cambiata: nuovo file $relative")
            val entry = stores[node].stat(node)
            require(sameMetadata(snapshot.entry, entry)) { "L'origine è cambiata: ${entry.name}" }
            seen++
            if (entry.directory) {
                for (child in stores[node].list(node)) walk(child.node, if (relative.isEmpty()) child.name else "$relative/${child.name}")
            } else if (digest) {
                progress(Progress(entry.name, phase = "Verifica origine"))
                require(snapshot.digest!!.contentEquals(hash(node, cancellation))) { "L'origine è stata modificata: ${entry.name}" }
            }
        }
        walk(root, "")
        require(seen == manifest.size) { "File rimossi dall'origine durante l'operazione" }
    }
    private fun deleteVerifiedSource(root: Node, manifest: List<Snapshot>, cancellation: Cancellation, progress: (Progress) -> Unit) {
        for (snapshot in manifest.asReversed()) {
            cancellation.check()
            val node = resolve(root, snapshot.relative)
            val entry = stores[node].stat(node)
            progress(Progress(entry.name, phase = "Rimozione origine verificata"))
            if (!entry.directory) {
                require(sameMetadata(snapshot.entry, entry) && snapshot.digest!!.contentEquals(hash(node, cancellation)) &&
                    sameMetadata(entry, stores[node].stat(node))) {
                    "Origine modificata: conservata. La copia completa è nella destinazione."
                }
            }
            stores[node].deleteVerified(node,entry) // New directory contents prevent deletion.
        }
    }
    fun hash(node: Node, cancellation: Cancellation): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        stores[node].read(node).use { input ->
            while (true) { cancellation.check(); val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
        }
        return digest.digest()
    }
    fun removeTree(node: Node, cancellation: Cancellation, depth: Int = 0) {
        cancellation.check(); require(depth <= 128) { "Albero troppo profondo" }
        val store = stores[node]
        require(store.stat(node).capabilities.delete) { "Eliminazione non consentita" }
        if (store.stat(node).directory) for (entry in store.list(node)) removeTree(entry.node, cancellation, depth + 1)
        store.deleteEmpty(node)
    }
    private fun sameMetadata(a: Entry, b: Entry): Boolean = a.directory == b.directory && (a.identity == null || a.identity == b.identity) &&
        (a.directory || ((a.size == null || b.size == a.size) && (a.modified == null || b.modified == a.modified)))
}
