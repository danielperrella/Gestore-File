package it.liberofiles.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.io.OutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileEngineTest {
    private lateinit var root: Path
    private lateinit var a: LocalStorage
    private lateinit var b: LocalStorage
    private lateinit var journal: Journal
    private lateinit var engine: FileEngine
    @Before fun setup() {
        root = Files.createTempDirectory("liberofiles-tests")
        a = LocalStorage(Files.createDirectory(root.resolve("a")), "a")
        b = LocalStorage(Files.createDirectory(root.resolve("b")), "b")
        journal = Journal(root.resolve("journal").toFile())
        engine = FileEngine(Stores(mapOf("a" to a, "b" to b)), journal)
    }
    @After fun cleanup() { root.toFile().deleteRecursively() }
    private fun file(name: String, content: String = "originale"): Node {
        Files.writeString(a.root.resolve(name), content); return Node("a", a.root.resolve(name).toString())
    }
    private fun contents(node: Node): String = engine.stores[node].read(node).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun fails(action: () -> Unit) { try { action(); fail("Expected failure") } catch (e: Exception) { } }
    @Test fun copyKeepsSource() {
        val src = file("test.txt"); val result = engine.transfer(src, b.rootNode, false, Conflict.KEEP_BOTH, Cancellation())
        assertEquals("originale", contents(result.destination!!)); assertTrue(Files.exists(a.path(src))); assertTrue(journal.pending().isEmpty())
    }
    @Test fun remoteCopiesAlwaysVerifyDestinationEvenWhenOptionIsOff() {
        val src=file("cloud.txt")
        val corrupt=object : Storage by b {
            override val remote = true
            override fun read(node: Node): java.io.InputStream = java.io.ByteArrayInputStream("corrupted".toByteArray())
        }
        val remoteEngine=FileEngine(Stores(mapOf("a" to a,"b" to corrupt)),journal)
        fails { remoteEngine.transfer(src,b.rootNode,false,Conflict.KEEP_BOTH,Cancellation(),false) }
        assertEquals("originale",contents(src)); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun deniedDownloadCannotCreateDestinationOrDeleteSource() {
        val src=file("restricted.txt")
        val restricted=object : Storage by a {
            override fun stat(node: Node) = a.stat(node).copy(capabilities=Capabilities(read=false))
        }
        fails { FileEngine(Stores(mapOf("a" to restricted,"b" to b)),journal).transfer(src,b.rootNode,true,Conflict.KEEP_BOTH,Cancellation()) }
        assertTrue(b.list(b.rootNode).isEmpty()); assertEquals("originale",contents(src))
    }
    @Test fun deniedRemovalCannotStartCrossStorageMove() {
        val src=file("shared.txt")
        val restricted=object : Storage by a {
            override fun stat(node: Node) = a.stat(node).copy(capabilities=Capabilities(delete=false))
        }
        fails { FileEngine(Stores(mapOf("a" to restricted,"b" to b)),journal).transfer(src,b.rootNode,true,Conflict.KEEP_BOTH,Cancellation()) }
        assertTrue(b.list(b.rootNode).isEmpty()); assertTrue(Files.exists(a.path(src)))
    }
    @Test fun explicitDownloadNameDoesNotRenameSource() {
        val src=file("original.txt")
        val result=engine.transfer(src,b.rootNode,false,Conflict.KEEP_BOTH,Cancellation(),targetName="chosen.txt")
        assertEquals("chosen.txt",b.stat(result.destination!!).name)
        assertTrue(Files.exists(a.path(src))); assertEquals("originale",contents(result.destination))
    }
    @Test fun concurrentlyModifiedDestinationIsPreservedDuringReplacement() {
        val src=file("same.txt"); val destination=b.create(b.rootNode,"same.txt",false)
        b.writeNew(destination).use { it.stream.write("old".toByteArray()); it.sync() }
        var changed=false
        fails { engine.transfer(src,b.rootNode,false,Conflict.REPLACE,Cancellation()) {
            if(!changed) { changed=true; Files.writeString(b.root.resolve("same.txt"),"changed destination") }
        } }
        assertEquals("changed destination",Files.readString(b.root.resolve("same.txt")))
        assertEquals("originale",contents(src))
    }
    @Test fun verifiedCrossStorageMove() {
        val src = file("test.txt", "a".repeat(2_000_000))
        val result = engine.transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation())
        assertFalse(Files.exists(a.path(src))); assertEquals(2_000_000, contents(result.destination!!).length)
    }
    @Test fun atomicMovePreservesBytes() {
        val src = file("test.txt"); val dir = a.create(a.rootNode, "dest", true)
        val result = engine.transfer(src, dir, true, Conflict.KEEP_BOTH, Cancellation())
        assertEquals("originale", contents(result.destination!!)); assertFalse(Files.exists(a.path(src)))
    }
    @Test fun directoryMove() {
        Files.createDirectories(a.root.resolve("tree/sub")); file("tree/sub/file.bin", "abc"); file("tree/top.txt", "xyz")
        val result = engine.transfer(Node("a", a.root.resolve("tree").toString()), b.rootNode, true, Conflict.KEEP_BOTH, Cancellation())
        assertFalse(Files.exists(a.root.resolve("tree"))); assertEquals("abc", Files.readString(b.path(result.destination!!).resolve("sub/file.bin")))
    }
    @Test fun cancellationKeepsOriginalAndOldDestination() {
        val src = file("test.txt", "a".repeat(1_500_000)); Files.writeString(b.root.resolve("test.txt"), "old")
        val cancel = Cancellation()
        fails { engine.transfer(src, b.rootNode, true, Conflict.REPLACE, cancel) { cancel.cancel() } }
        assertTrue(Files.exists(a.path(src))); assertEquals("old", Files.readString(b.root.resolve("test.txt")))
        assertEquals(1, b.list(b.rootNode).size); assertTrue(journal.pending().isEmpty())
    }
    @Test fun corruptedDestinationCannotDeleteOriginal() {
        val corrupt = object : Storage by b {
            override fun writeNew(node: Node): DurableOutput {
                val real = b.writeNew(node)
                return object : DurableOutput {
                    override val stream = object : OutputStream() {
                        override fun write(value: Int) { real.stream.write(value xor 1) }
                        override fun write(bytes: ByteArray, offset: Int, count: Int) {
                            val corrupted = bytes.copyOfRange(offset, offset + count); if (count > 0) corrupted[0] = (corrupted[0].toInt() xor 1).toByte()
                            real.stream.write(corrupted)
                        }
                    }
                    override fun sync() = real.sync()
                    override fun close() = real.close()
                }
            }
        }
        val e = FileEngine(Stores(mapOf("a" to a, "b" to corrupt)), journal); val src = file("test.txt")
        fails { e.transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) }
        assertEquals("originale", contents(src)); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun diskFullCannotDamageOldFile() {
        val broken = object : Storage by b {
            override fun writeNew(node: Node): DurableOutput = throw IOException("ENOSPC")
        }
        val e = FileEngine(Stores(mapOf("a" to a, "b" to broken)), journal)
        Files.writeString(b.root.resolve("test.txt"), "old"); val src = file("test.txt")
        fails { e.transfer(src, b.rootNode, true, Conflict.REPLACE, Cancellation()) }
        assertEquals("originale", contents(src)); assertEquals("old", Files.readString(b.root.resolve("test.txt")))
    }
    @Test fun failedPublicationRestoresReplacedFile() {
        val broken = object : Storage by b {
            override fun rename(node: Node, name: String): Node {
                if (b.stat(node).name.startsWith(".lf-stage")) throw IOException("USB scollegata")
                return b.rename(node, name)
            }
        }
        Files.writeString(b.root.resolve("test.txt"), "old"); val src = file("test.txt")
        fails { FileEngine(Stores(mapOf("a" to a, "b" to broken)), journal).transfer(src, b.rootNode, false, Conflict.REPLACE, Cancellation()) }
        assertEquals("old", Files.readString(b.root.resolve("test.txt"))); assertEquals("originale", contents(src))
    }
    @Test fun sourceModificationAbortsMove() {
        val src = file("test.txt", "before")
        fails { engine.transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) { p ->
            if (p.phase == "Copia") Files.writeString(a.path(src), "changed")
        } }
        assertEquals("changed", contents(src)); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun sameSizeSourceModificationIsDetected() {
        val src = file("test.txt", "before"); val time = Files.getLastModifiedTime(a.path(src))
        fails { engine.transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) { p ->
            if (p.phase == "Copia") { Files.writeString(a.path(src), "alter!"); Files.setLastModifiedTime(a.path(src), time) }
        } }
        assertEquals("alter!", contents(src)); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun newFileDuringDirectoryMoveIsPreserved() {
        Files.createDirectory(a.root.resolve("tree")); file("tree/a.txt")
        fails { engine.transfer(Node("a", a.root.resolve("tree").toString()), b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) { p ->
            if (p.phase == "Copia") file("tree/new.txt")
        } }
        assertTrue(Files.exists(a.root.resolve("tree/new.txt"))); assertTrue(Files.exists(a.root.resolve("tree/a.txt")))
    }
    @Test fun keepsBothOrSkips() {
        val src = file("test.txt"); Files.writeString(b.root.resolve("test.txt"), "old")
        assertTrue(engine.transfer(src, b.rootNode, false, Conflict.SKIP, Cancellation()).skipped)
        val result = engine.transfer(src, b.rootNode, false, Conflict.KEEP_BOTH, Cancellation())
        assertEquals("test (1).txt", b.stat(result.destination!!).name); assertEquals("old", Files.readString(b.root.resolve("test.txt")))
    }
    @Test fun rejectsOwnDescendant() {
        val dir = a.create(a.rootNode, "folder", true); val child = a.create(dir, "child", true)
        fails { engine.transfer(dir, child, false, Conflict.KEEP_BOTH, Cancellation()) }
    }
    @Test fun rejectsUnsafeNamesAndEscape() {
        for (name in listOf("../x", ".", "..", "a/b", "a\\b", "a\u0000b")) fails { validName(name) }
        fails { a.stat(Node("a", root.resolve("outside").toString())) }
    }
    @Test fun rejectsSymlinks() {
        val original = file("real"); Files.createSymbolicLink(a.root.resolve("link"), a.path(original))
        fails { engine.transfer(Node("a", a.root.resolve("link").toString()), b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) }
        assertTrue(Files.exists(a.path(original)))
    }
    @Test fun zipRoundTrip() {
        val src = file("hello.txt", "ciao")
        val tools = ArchiveTools(engine); tools.zip(listOf(src), b.rootNode, "archive", Cancellation()) {}
        val zip = b.child(b.rootNode, "archive.zip")!!
        tools.unzip(zip, b.rootNode, Cancellation()) {}
        assertEquals("ciao", Files.readString(b.root.resolve("archive/hello.txt")))
    }
    @Test fun zipSlipRejectedWithoutOutsideWrites() {
        val path = a.root.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(path)).use { it.putNextEntry(ZipEntry("../escape.txt")); it.write("evil".toByteArray()); it.closeEntry() }
        fails { ArchiveTools(engine).unzip(Node("a", path.toString()), b.rootNode, Cancellation()) {} }
        assertFalse(Files.exists(root.resolve("escape.txt"))); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun duplicateZipEntriesRejected() {
        for (path in listOf("/absolute", "C:/file", "a/../../b", "a\\b", "a//b")) fails { ArchiveTools.zipSegments(path) }
    }
    @Test fun renamePreviewAndCommit() {
        val one = file("one.txt"); val two = file("two.txt")
        val rule = RenameRule(prefix = "new_", number = true)
        val renamer = BatchRenamer(engine.stores, journal)
        assertEquals("new_one_001.txt", renamer.preview(listOf(one, two), rule).first().second)
        renamer.rename(listOf(one, two), rule, Cancellation())
        assertTrue(Files.exists(a.root.resolve("new_one_001.txt"))); assertTrue(Files.exists(a.root.resolve("new_two_002.txt")))
    }
    @Test fun conflictingRenameLeavesFilesIntact() {
        val one = file("one.txt"); file("new_one.txt", "old")
        fails { BatchRenamer(engine.stores, journal).rename(listOf(one), RenameRule(prefix = "new_"), Cancellation()) }
        assertEquals("originale", contents(one)); assertEquals("old", Files.readString(a.root.resolve("new_one.txt")))
    }
    @Test fun trashAndRestoreAvoidOverwrite() {
        val src = file("test.txt"); val trash = Trash(a); val item = trash.put(src)
        assertFalse(Files.exists(a.path(src))); file("test.txt", "new")
        val restored = trash.restore(item)
        assertEquals("test (1).txt", a.stat(restored).name); assertEquals("originale", contents(restored)); assertTrue(trash.items().isEmpty())
    }
    @Test fun journalSurvivesReopening() {
        val r = journal.begin(mapOf("phase" to "copying", "source" to "important"))
        val reopened = Journal(root.resolve("journal").toFile())
        assertEquals("important", reopened.pending().single().values["source"]); reopened.finish(r); assertTrue(journal.pending().isEmpty())
    }
    @Test fun spaceReportCountsRealBytes() {
        file("a.txt", "abcd"); file("b.bin", "123456")
        val report = analyze(engine.stores, a.rootNode, Cancellation()) {}
        assertEquals(10L, report.bytes); assertEquals(2L, report.files); assertEquals("b.bin", report.largest.first().name)
    }
    @Test fun modificationJustBeforeSourceCleanupIsPreserved() {
        val src = file("test.txt", "original")
        fails { engine.transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) { p ->
            if (p.phase == "Rimozione origine verificata") Files.writeString(a.path(src), "changed!")
        } }
        assertEquals("changed!", contents(src)); assertEquals("original", Files.readString(b.root.resolve("test.txt")))
        assertFalse(journal.pending().isEmpty())
    }
    @Test fun syncFailureDoesNotRemoveSource() {
        val broken = object : Storage by b {
            override fun writeNew(node: Node): DurableOutput {
                val real = b.writeNew(node)
                return object : DurableOutput {
                    override val stream = real.stream
                    override fun sync() { throw IOException("fsync failed") }
                    override fun close() = real.close()
                }
            }
        }
        val src = file("test.txt")
        fails { FileEngine(Stores(mapOf("a" to a, "b" to broken)), journal).transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) }
        assertEquals("originale", contents(src)); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun emptyFileCanBeCopiedAndMoved() {
        val src = file("empty", "")
        val result = engine.transfer(src, b.rootNode, true, Conflict.KEEP_BOTH, Cancellation())
        assertEquals("", contents(result.destination!!)); assertFalse(Files.exists(a.path(src)))
    }
    @Test fun symlinkInsideDirectoryAbortsBeforeSourceDeletion() {
        Files.createDirectory(a.root.resolve("tree")); val original = file("tree/file.txt")
        Files.createSymbolicLink(a.root.resolve("tree/link"), a.path(original))
        fails { engine.transfer(Node("a", a.root.resolve("tree").toString()), b.rootNode, true, Conflict.KEEP_BOTH, Cancellation()) }
        assertEquals("originale", contents(original)); assertTrue(b.list(b.rootNode).isEmpty())
    }
    @Test fun directoryReplacementIsExplicitlyRejected() {
        val src = a.create(a.rootNode, "folder", true); b.create(b.rootNode, "folder", true)
        fails { engine.transfer(src, b.rootNode, true, Conflict.REPLACE, Cancellation()) }
        assertTrue(Files.exists(a.path(src))); assertTrue(Files.exists(b.root.resolve("folder")))
    }
    @Test fun renameFailureRollsBackEveryFile() {
        val one = file("one.txt"); val two = file("two.txt", "second")
        val broken = object : Storage by a {
            override fun rename(node: Node, name: String): Node {
                if (name == "new_two.txt") throw IOException("rename failed")
                return a.rename(node, name)
            }
        }
        fails { BatchRenamer(Stores(mapOf("a" to broken)), journal).rename(listOf(one, two), RenameRule(prefix = "new_"), Cancellation()) }
        assertEquals("originale", contents(one)); assertEquals("second", contents(two)); assertTrue(journal.pending().isEmpty())
    }
}
