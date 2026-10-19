package it.liberofiles.app

import android.content.Context
import android.net.Uri
import it.liberofiles.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveTest {
    private lateinit var directory: File
    @Before fun setup() { directory=Files.createTempDirectory("drive-tests").toFile() }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun jsonReply(json: JSONObject, status: Int = 200) = DriveReply(status,json.toString().toByteArray())
    private fun metadata(id: String="file-1", name: String="file.txt", mime: String="text/plain", parent: String="root-id") = JSONObject()
        .put("id",id).put("name",name).put("mimeType",mime).put("size",3).put("version","1").put("parents",JSONArray().put(parent))
        .put("modifiedTime","2026-01-01T00:00:00Z").put("capabilities",JSONObject().put("canDownload",true).put("canAddChildren",true)
            .put("canRename",true).put("canTrash",true).put("canUntrash",true).put("canMoveItemWithinDrive",true))
    private class Fixture(val handler: (String,String,Map<String,String>,ByteArray?) -> DriveReply) : DriveTransport {
        var content="abc".toByteArray()
        override fun request(method: String,url: String,headers: Map<String,String>,body: ByteArray?) = handler(method,url,headers,body)
        override fun read(url: String): InputStream = ByteArrayInputStream(content)
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Operazione accettata") } catch(expected: Exception) { /* expected */ } }
    @Test fun paginatesAndPreservesDuplicateNames() {
        var calls=0
        val api=DriveApi(Fixture { _,url,_,_ ->
            calls++
            if(url.contains("pageToken")) jsonReply(JSONObject().put("files",JSONArray().put(metadata("second"))))
            else jsonReply(JSONObject().put("nextPageToken","next").put("files",JSONArray().put(metadata("first"))))
        })
        val files=api.children("parent'\\value")
        assertEquals(2,calls); assertEquals(listOf("first","second"),files.map { it.getString("id") })
        assertEquals("'parent\\'\\\\value'",DriveApi.literal("parent'\\value"))
    }
    @Test fun rejectsLoopingPageTokens() {
        val api=DriveApi(Fixture { _,_,_,_ -> jsonReply(JSONObject().put("nextPageToken","same").put("files",JSONArray())) })
        rejects { api.children("root") }
    }
    @Test fun onlyOfficialHttpsApiReceivesBearerTokens() {
        assertTrue(GoogleDriveTransport.trusted(DriveApi.BASE+"files"))
        listOf("http://www.googleapis.com/drive/v3/files","https://www.googleapis.com.evil.test/drive/v3/files",
            "https://evil@www.googleapis.com/drive/v3/files","https://www.googleapis.com:443/drive/v3/files",
            "https://www.googleapis.com/other").forEach { assertFalse(GoogleDriveTransport.trusted(it)) }
    }
    @Test fun metadataEnforcesReadOnlyAndGoogleDocumentExport() {
        val fixture=Fixture { _,_,_,_ -> jsonReply(metadata(mime="application/vnd.google-apps.document").apply {
            getJSONObject("capabilities").put("canRename",false).put("canTrash",false).put("canMoveItemWithinDrive",false)
        }) }
        val store=DriveStorage(DriveApi(fixture),"drive-one",directory)
        val file=store.stat(Node("drive-one","file-1"))
        assertFalse(file.capabilities.read); assertFalse(file.capabilities.rename); assertFalse(file.capabilities.delete)
        rejects { store.read(file.node) }; rejects { store.rename(file.node,"other") }; rejects { store.trash(file.node) }
        rejects { store.stat(Node("drive-two","file-1")) }
    }
    @Test fun cloudIdsAreNotPathsAndParentWalkFindsRoot() {
        val api=DriveApi(Fixture { _,url,_,_ ->
            val id=URLDecoder.decode(URL(url).path.substringAfterLast('/'),"UTF-8")
            jsonReply(when(id) {
                "root", "root-id" -> metadata("root-id","Il mio Drive",DriveApi.FOLDER).put("parents",JSONArray())
                "opaque-child" -> metadata("opaque-child","child",DriveApi.FOLDER,"opaque-parent")
                else -> metadata("opaque-parent","parent",DriveApi.FOLDER)
            })
        })
        val store=DriveStorage(api,"drive-one",directory)
        assertEquals(Node("drive-one","opaque-parent"),store.parent(Node("drive-one","opaque-child")))
        assertTrue(store.contains(store.rootNode,Node("drive-one","opaque-child")))
        assertNull(store.parent(store.rootNode))
    }
    @Test fun ambiguousReplacementRequiresChosenId() {
        val fixture=Fixture { _,url,_,_ ->
            if(URL(url).path.endsWith("/files")) jsonReply(JSONObject().put("files",JSONArray().put(metadata("one")).put(metadata("two"))))
            else jsonReply(metadata("root-id","root",DriveApi.FOLDER).put("parents",JSONArray()))
        }
        val store=DriveStorage(DriveApi(fixture),"drive-one",directory)
        rejects { store.requireUnambiguous(store.rootNode,"file.txt") }
        store.selectReplacement(store.rootNode,"file.txt","two")
        store.requireUnambiguous(store.rootNode,"file.txt")
        assertEquals("two",store.child(store.rootNode,"file.txt")!!.id)
    }
    @Test fun uploadRecoversCommittedOffsetAfterDroppedResponse() {
        val file=File(directory,"upload.bin").apply { writeBytes(ByteArray(300000) { (it%251).toByte() }) }
        val ranges=mutableListOf<String>(); var failed=false
        val fixture=Fixture { method,_,headers,_ ->
            if(method == "PATCH") DriveReply(200,"{}".toByteArray(),mapOf("location" to "https://www.googleapis.com/upload/drive/v3/files/file?upload_id=test"))
            else {
                val range=headers.getValue("Content-Range"); ranges.add(range)
                when {
                    range == "bytes */300000" -> DriveReply(308,byteArrayOf(),mapOf("range" to "bytes=0-262143"))
                    !failed -> { failed=true; throw IOException("Response lost after chunk committed") }
                    else -> jsonReply(JSONObject())
                }
            }
        }
        DriveApi(fixture).upload("file",file)
        assertEquals(listOf("bytes 0-262143/300000","bytes */300000","bytes 262144-299999/300000"),ranges)
    }
    @Test fun zeroByteUploadCanComplete() {
        val file=File(directory,"empty").apply { writeBytes(byteArrayOf()) }; var count=0
        val fixture=Fixture { method,_,headers,body ->
            if(method == "PATCH") DriveReply(200,"{}".toByteArray(),mapOf("location" to "https://www.googleapis.com/upload/drive/v3/files/empty?upload_id=test"))
            else { count++; assertEquals("bytes */0",headers["Content-Range"]); assertEquals(0,body!!.size); jsonReply(JSONObject()) }
        }
        DriveApi(fixture).upload("empty",file); assertEquals(1,count)
    }
    @Test fun cancellationStopsBeforeNextUploadChunk() {
        val cancel=Cancellation(); val file=File(directory,"cancel.bin").apply { writeBytes(ByteArray(300000)) }; var chunks=0
        val fixture=Fixture { method,_,_,_ ->
            if(method == "PATCH") DriveReply(200,"{}".toByteArray(),mapOf("location" to "https://www.googleapis.com/upload/drive/v3/files/file?upload_id=test"))
            else { chunks++; cancel.cancel(); DriveReply(308,byteArrayOf(),mapOf("range" to "bytes=0-262143")) }
        }
        try { DriveApi(fixture) { cancel.check() }.upload("file",file); fail() }
        catch(expected: Cancelled) { assertEquals(1,chunks) }
    }
    @Test fun uncertainMetadataMutationIsNeverRetried() {
        var writes=0
        val api=DriveApi(Fixture { _,_,_,_ -> writes++; throw IOException("Response lost") })
        rejects { api.patch("file",JSONObject().put("name","new.txt")) }
        assertEquals(1,writes)
    }
    @Test fun uncertainCreateUsesAllocatedIdAndNeverCreatesTwice() {
        var creates=0
        val fixture=Fixture { method,url,_,_ -> when {
            url.contains("generateIds") -> jsonReply(JSONObject().put("ids",JSONArray().put("allocated")))
            method == "POST" -> { creates++; throw IOException("Response lost") }
            else -> jsonReply(metadata("allocated"))
        } }
        assertEquals("allocated",DriveApi(fixture).create("root","file.txt",false).getString("id")); assertEquals(1,creates)
    }
    @Test fun trashAndRestoreUsePatchNeverPermanentDelete() {
        val requests=mutableListOf<String>()
        val fixture=Fixture { method,url,_,body ->
            requests.add(method)
            val data=if(url.contains("/root?")) metadata("root-id","root",DriveApi.FOLDER).put("parents",JSONArray()) else metadata()
            if(method == "PATCH") data.put("trashed",JSONObject(String(body!!)).getBoolean("trashed"))
            jsonReply(data)
        }
        val store=DriveStorage(DriveApi(fixture),"drive-one",directory)
        store.trash(Node("drive-one","file-1")); store.restore(Node("drive-one","file-1"))
        assertEquals(2,requests.count { it == "PATCH" }); assertFalse(requests.contains("DELETE"))
    }
    @Test fun errorMessagesDoNotExposeServerContent() {
        val api=DriveApi(Fixture { _,_,_,_ -> jsonReply(JSONObject().put("error",JSONObject().put("message","secret-token").put("errors",JSONArray().put(JSONObject().put("reason","storageQuotaExceeded")))),403) })
        try { api.about(); fail() } catch(e: DriveFailure) { assertTrue(e.message!!.contains("esaurito")); assertFalse(e.message!!.contains("secret-token")) }
    }
    @Test fun scopedAccountCacheCannotEscapeOrSurviveDisconnect() {
        val context=RuntimeEnvironment.getApplication()
        val account=JSONObject().put("key","drive-fixture").put("permissionId","fixture").put("email","test@example.invalid")
        context.getSharedPreferences("drive-accounts",Context.MODE_PRIVATE).edit().putString("accounts",JSONArray().put(account).toString()).commit()
        val private=File(context.cacheDir,"drive/drive-fixture").apply { mkdirs() }
        val file=File(private,"example.txt").apply { writeText("abc") }
        val uri=ShareProvider.cachedUri(context,"drive-fixture",file)
        assertEquals(ShareProvider.AUTHORITY,uri.authority)
        rejects { ShareProvider.cachedUri(context,"drive-fixture",File(directory,"outside")) }
        (context as FilesApplication).graph.drive.forget("drive-fixture")
        assertFalse(file.exists())
        rejects { ShareProvider.cachedUri(context,"drive-fixture",file) }
    }
}
