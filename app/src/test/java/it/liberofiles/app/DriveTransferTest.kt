package it.liberofiles.app

import it.liberofiles.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest

/** Exercises the actual core engine and Drive adapter against a stateful protocol fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveTransferTest {
    private lateinit var folder: File
    private lateinit var fixture: StatefulDrive
    private lateinit var drive: DriveStorage
    private lateinit var local: LocalStorage
    private lateinit var engine: FileEngine
    @Before fun setup() {
        folder=Files.createTempDirectory("drive-transfer").toFile()
        fixture=StatefulDrive(); drive=DriveStorage(DriveApi(fixture),"drive-test",File(folder,"cache"))
        local=LocalStorage(File(folder,"local").apply { mkdirs() }.toPath())
        engine=FileEngine(Stores(mapOf(local.key to local,drive.key to drive)),Journal(File(folder,"journal")))
    }
    @After fun cleanup() { folder.deleteRecursively() }
    private fun source(name: String,bytes: ByteArray): Node = local.create(local.rootNode,name,false).also { node -> local.writeNew(node).use { it.stream.write(bytes); it.sync() } }
    private fun rejects(block: () -> Unit) { try { block(); fail("Unexpected success") } catch(expected: Exception) { /* expected */ } }

    @Test fun uploadsVerifiesAndPublishesLargeFileWithoutRemovingLocalSource() {
        val bytes=ByteArray(700000) { (it%251).toByte() }; val node=source("large.bin",bytes)
        val result=engine.transfer(node,drive.rootNode,false,Conflict.KEEP_BOTH,Cancellation())
        assertEquals("large.bin",drive.stat(result.destination!!).name)
        assertArrayEquals(bytes,drive.read(result.destination!!).use { it.readBytes() })
        assertArrayEquals(bytes,local.read(node).use { it.readBytes() })
        assertEquals(3,fixture.uploadChunks)
    }
    @Test fun corruptUploadNeverDeletesOriginalOrPublishesFinalName() {
        fixture.corruptUpload=true; val node=source("important.txt","original".toByteArray())
        rejects { engine.transfer(node,drive.rootNode,true,Conflict.KEEP_BOTH,Cancellation()) }
        assertEquals("original",local.read(node).reader().readText())
        assertNull(drive.child(drive.rootNode,"important.txt"))
    }
    @Test fun replacementSelectsOneDuplicateAndPreservesTheOther() {
        fixture.add("keep","same.txt","untouched".toByteArray()); fixture.add("replace","same.txt","old".toByteArray())
        drive.selectReplacement(drive.rootNode,"same.txt","replace")
        val node=source("same.txt","new".toByteArray())
        val result=engine.transfer(node,drive.rootNode,false,Conflict.REPLACE,Cancellation())
        assertEquals("untouched",String(fixture.contents.getValue("keep")))
        assertFalse(fixture.files.getValue("keep").optBoolean("trashed"))
        assertTrue(fixture.files.getValue("replace").getBoolean("trashed"))
        assertEquals("new",drive.read(result.destination!!).reader().readText())
    }
    @Test fun crossStorageMoveTrashesSourceOnlyAfterVerifiedPublication() {
        fixture.add("source","download.txt","abc".toByteArray())
        val result=engine.transfer(Node(drive.key,"source"),local.rootNode,true,Conflict.KEEP_BOTH,Cancellation())
        assertEquals("abc",local.read(result.destination!!).reader().readText())
        assertTrue(fixture.files.getValue("source").optBoolean("trashed"))
        assertFalse(fixture.methods.contains("DELETE"))
    }
    @Test fun concurrentSourceChangeAtFinalTrashKeepsChangedOriginalAndCompletedCopy() {
        fixture.add("source","download.txt","abc".toByteArray()); fixture.raceOnTrash=true
        rejects { engine.transfer(Node(drive.key,"source"),local.rootNode,true,Conflict.KEEP_BOTH,Cancellation()) }
        assertEquals("abc",File(local.root.toFile(),"download.txt").readText())
        assertFalse(fixture.files.getValue("source").optBoolean("trashed"))
        assertEquals("changed",String(fixture.contents.getValue("source")))
    }
}

private class StatefulDrive : DriveTransport {
    val files=linkedMapOf<String,JSONObject>()
    val contents=linkedMapOf<String,ByteArray>()
    val methods=mutableListOf<String>()
    private val uploads=HashMap<String,ByteArrayOutputStream>()
    var corruptUpload=false
    var raceOnTrash=false
    var uploadChunks=0
    private var next=0
    init { add("root-id","Il mio Drive",byteArrayOf(),DriveApi.FOLDER); files.getValue("root-id").put("parents",JSONArray()) }
    fun add(id: String,name: String,bytes: ByteArray,mime: String="application/octet-stream") {
        contents[id]=bytes
        files[id]=JSONObject().put("id",id).put("name",name).put("mimeType",mime).put("parents",JSONArray().put("root-id"))
            .put("version","1").put("size",bytes.size).put("sha256Checksum",sha(bytes)).put("trashed",false)
            .put("capabilities",JSONObject().put("canDownload",true).put("canAddChildren",true).put("canRename",true)
                .put("canTrash",true).put("canUntrash",true).put("canMoveItemWithinDrive",true))
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun reply(data: JSONObject,status: Int=200) = DriveReply(status,data.toString().toByteArray(),if(data.has("version")) mapOf("etag" to "\"${data.getString("version")}\"") else emptyMap())
    override fun read(url: String): InputStream {
        val id=URLDecoder.decode(URL(url).path.substringAfterLast('/'),"UTF-8")
        return ByteArrayInputStream(contents.getValue(id))
    }
    override fun request(method: String,url: String,headers: Map<String,String>,body: ByteArray?): DriveReply {
        methods.add(method)
        val parsed=URL(url)
        val query=parsed.query.orEmpty().split('&').filter { it.contains('=') }.associate { val pair=it.split('=',limit=2); URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair[1],"UTF-8") }
        val raw=URLDecoder.decode(parsed.path.substringAfterLast('/'),"UTF-8"); val id=if(raw == "root") "root-id" else raw
        if(raw == "about") return reply(JSONObject().put("storageQuota",JSONObject().put("limit",100000000).put("usage",0)))
        if(raw == "generateIds") return reply(JSONObject().put("ids",JSONArray().put("generated-${++next}")))
        if(parsed.path.contains("/upload/")) {
            if(method == "PATCH") {
                uploads[id]=ByteArrayOutputStream()
                return DriveReply(200,"{}".toByteArray(),mapOf("location" to "https://www.googleapis.com/upload/drive/v3/files/$id?upload_id=fixture"))
            }
            uploadChunks++
            val range=headers.getValue("Content-Range"); val total=range.substringAfterLast('/').toLong()
            val output=uploads.getValue(id)
            if(!range.startsWith("bytes */")) output.write(body!!)
            if(output.size().toLong() < total) return DriveReply(308,byteArrayOf(),mapOf("range" to "bytes=0-${output.size()-1}"))
            val bytes=output.toByteArray(); if(corruptUpload && bytes.isNotEmpty()) bytes[0]=(bytes[0].toInt() xor 1).toByte()
            contents[id]=bytes
            files.getValue(id).put("size",bytes.size).put("sha256Checksum",sha(bytes)).put("version","2")
            return reply(files.getValue(id))
        }
        if(method == "GET" && raw == "files") {
            val q=query["q"].orEmpty(); val trashed=q.contains("trashed = true")
            val parent=Regex("'([^']+)' in parents").find(q)?.groupValues?.get(1)?.let { if(it == "root") "root-id" else it }
            val values=files.values.filter { it.getString("id") != "root-id" && it.optBoolean("trashed") == trashed && (parent == null || it.optJSONArray("parents")?.optString(0) == parent) }
            return reply(JSONObject().put("files",JSONArray(values)))
        }
        if(method == "POST") {
            val data=JSONObject(String(body!!)); val created=data.getString("id")
            add(created,data.getString("name"),byteArrayOf(),data.getString("mimeType"))
            files.getValue(created).put("parents",JSONArray().put(data.getJSONArray("parents").getString(0).let { if(it == "root") "root-id" else it }))
            return reply(files.getValue(created))
        }
        val data=files[id] ?: return reply(JSONObject(),404)
        if(method == "PATCH") {
            val update=JSONObject(String(body!!))
            if(update.optBoolean("trashed") && raceOnTrash) {
                raceOnTrash=false; contents[id]="changed".toByteArray(); data.put("version",data.getLong("version")+1)
            }
            if(headers["If-Match"] != null && headers["If-Match"] != "\"${data.getString("version")}\"") return reply(JSONObject(),412)
            update.keys().forEach { name -> data.put(name,update.get(name)) }
            data.put("version",data.getLong("version")+1)
        }
        return reply(data)
    }
}
