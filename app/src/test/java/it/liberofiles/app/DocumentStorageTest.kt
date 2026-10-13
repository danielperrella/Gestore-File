package it.liberofiles.app

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as DC
import android.provider.DocumentsProvider
import it.liberofiles.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/** A physical-storage contract double exercises the SAF adapter, not USB hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentStorageTest {
    private lateinit var fixture: Path
    private lateinit var provider: FixtureDocuments
    private lateinit var storage: DocumentStorage
    private lateinit var local: LocalStorage
    private lateinit var engine: FileEngine
    @Before fun setup() {
        fixture = Files.createTempDirectory("saf-fixture")
        provider = FixtureDocuments(Files.createDirectory(fixture.resolve("usb")))
        val context = RuntimeEnvironment.getApplication()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "com.android.externalstorage.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider)
        storage = DocumentStorage(context.contentResolver, DC.buildTreeDocumentUri("com.android.externalstorage.documents", "TEST:"), "usb")
        local = LocalStorage(Files.createDirectory(fixture.resolve("local")))
        engine = FileEngine(Stores(mapOf("local" to local, "usb" to storage)), Journal(fixture.resolve("journal").toFile()))
    }
    @After fun cleanup() {
        fixture.toFile().deleteRecursively()
        val graph = (RuntimeEnvironment.getApplication() as FilesApplication).graph
        graph.reads.shutdownNow(); graph.operations.shutdownNow()
    }
    @Test fun verifiedMoveThroughDocumentsContract() {
        val source = local.create(local.rootNode, "file.txt", false)
        Files.write(local.path(source), "SAF contents".toByteArray())
        val result = engine.transfer(source, storage.rootNode, true, Conflict.KEEP_BOTH, Cancellation())
        assertFalse(Files.exists(local.path(source)))
        assertEquals("SAF contents", storage.read(result.destination!!).use { String(it.readBytes()) })
    }
    @Test fun newDestinationFileCannotBeOverwrittenByStaleIndex() {
        val source = local.create(local.rootNode, "file.txt", false)
        Files.write(local.path(source), "original".toByteArray())
        try {
            engine.transfer(source, storage.rootNode, false, Conflict.KEEP_BOTH, Cancellation()) { p ->
                if (p.phase == "Copia") Files.write(provider.root.resolve("file.txt"), "created by other app".toByteArray())
            }
            fail("Destination changes must abort publication")
        } catch (e: Exception) { assertTrue(e.message.orEmpty().contains("destinazione è cambiata")) }
        assertEquals("original", String(Files.readAllBytes(local.path(source))))
        assertEquals("created by other app", String(Files.readAllBytes(provider.root.resolve("file.txt"))))
    }
    @Test fun creatingManyStagedChildrenDoesNotScanTheWholeFolderForEachFile() {
        val stage = storage.create(storage.rootNode, ".lf-stage-fixture", true)
        val before = provider.childQueries
        for (i in 0 until 50) storage.create(stage, "file-$i", false)
        assertEquals("New private staging folders must avoid quadratic listings", before, provider.childQueries)
        storage.refresh(stage)
        assertNotNull(storage.child(stage, "file-25"))
        assertTrue(provider.childQueries > before)
    }
}

class FixtureDocuments(val root: Path) : DocumentsProvider() {
    var childQueries = 0
    private val columns = arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME,
        DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_SIZE, DC.Document.COLUMN_LAST_MODIFIED)
    private fun path(id: String): Path {
        require(id.startsWith("TEST:")); val p = root.resolve(id.removePrefix("TEST:")).normalize()
        require(p.startsWith(root)); if (!Files.exists(p)) throw FileNotFoundException(id); return p
    }
    private fun id(path: Path) = "TEST:" + root.relativize(path).toString()
    private fun add(cursor: MatrixCursor, path: Path) {
        val directory = Files.isDirectory(path)
        val values = mapOf<String, Any>(DC.Document.COLUMN_DOCUMENT_ID to id(path), DC.Document.COLUMN_DISPLAY_NAME to path.fileName.toString(),
            DC.Document.COLUMN_MIME_TYPE to if (directory) DC.Document.MIME_TYPE_DIR else "application/octet-stream",
            DC.Document.COLUMN_SIZE to if (directory) 0L else Files.size(path), DC.Document.COLUMN_LAST_MODIFIED to Files.getLastModifiedTime(path).toMillis())
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DC.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: columns).apply { add(this, path(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries++
        return MatrixCursor(projection ?: columns).apply { Files.newDirectoryStream(path(parentDocumentId)).use { for (p in it) add(this, p) } }
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = path(documentId).startsWith(path(parentDocumentId))
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(path(documentId).toFile(), if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_READ_WRITE)
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val p = path(parentDocumentId).resolve(validName(displayName))
        if (mimeType == DC.Document.MIME_TYPE_DIR) Files.createDirectory(p) else Files.createFile(p)
        return id(p)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val source = path(documentId); val destination = source.parent.resolve(validName(displayName))
        require(!Files.exists(destination)); Files.move(source, destination); return id(destination)
    }
    override fun deleteDocument(documentId: String) { Files.delete(path(documentId)) }
}
