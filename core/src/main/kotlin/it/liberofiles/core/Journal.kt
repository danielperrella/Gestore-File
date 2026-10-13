package it.liberofiles.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Persistent checkpoints. Recovery never deletes originals or completes a move automatically. */
class Journal(private val directory: File) {
    init { directory.mkdirs() }
    data class Record(val id: String, val values: Map<String, String>)
    fun begin(values: Map<String, String>): Record = Record(UUID.randomUUID().toString(), values).also(::save)
    fun update(record: Record, vararg values: Pair<String, String>): Record =
        record.copy(values = record.values + values).also(::save)
    fun finish(record: Record) { Files.deleteIfExists(File(directory, "${record.id}.properties").toPath()); syncDirectory() }
    fun pending(): List<Record> = directory.listFiles().orEmpty().filter { it.extension == "properties" }.map { file ->
        val p = Properties(); file.inputStream().use(p::load)
        Record(file.nameWithoutExtension, p.stringPropertyNames().associateWith { p.getProperty(it) })
    }
    private fun save(record: Record) {
        val properties = Properties().apply { putAll(record.values) }
        val target = File(directory, "${record.id}.properties")
        val tmp = File(directory, "${record.id}.tmp")
        FileOutputStream(tmp).use { properties.store(it, "LiberoFiles transaction"); it.fd.sync() }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory()
    }
    private fun syncDirectory() {
        try { java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
        catch (_: java.io.IOException) { }
        catch (_: UnsupportedOperationException) { }
    }
}
