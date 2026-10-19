package it.liberofiles.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

class DriveFailure(val status: Int, message: String) : IOException(message)
data class DriveReply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    fun json() = if (body.isEmpty()) JSONObject() else JSONObject(String(body, UTF_8))
}

/** Injectable boundary for deterministic protocol tests. Production never accepts custom API origins. */
interface DriveTransport {
    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): DriveReply
    fun read(url: String): InputStream
}

class GoogleDriveTransport(private val token: () -> String, private val check: () -> Unit) : DriveTransport {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
            .writeTimeout(20,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        fun trusted(address: String): Boolean = try {
            val url = URL(address)
            url.protocol == "https" && url.host == "www.googleapis.com" && url.port == -1 && url.userInfo == null &&
                (url.path.startsWith("/drive/v3/") || url.path.startsWith("/upload/drive/v3/"))
        } catch (_: Exception) { false }
    }
    private fun connection(method: String, address: String, headers: Map<String, String>, body: ByteArray? = null): Response {
        check(); require(trusted(address)) { "Origine Google Drive non valida" }
        val request=Request.Builder().url(address).header("Authorization","Bearer ${token()}")
        headers.forEach { (name,value) -> request.header(name,value) }
        val content = body?.toRequestBody((headers["Content-Type"] ?: "application/octet-stream").toMediaType())
        request.method(method,content)
        try { return client.newCall(request.build()).execute() }
        catch(e: IOException) { throw IOException("Connessione Google Drive interrotta. Verifica la rete e riprova; gli originali sono conservati.",e) }
    }
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): DriveReply {
        connection(method,url,headers,body).use { connection ->
            val status = connection.code
            val stream = connection.body?.byteStream()
            val bytes = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    check(); val count = input.read(buffer); if (count < 0) break
                    require(bytes.size() + count <= 4 * 1024 * 1024) { "Risposta Drive troppo grande" }
                    bytes.write(buffer,0,count)
                }
            }
            return DriveReply(status,bytes.toByteArray(),connection.headers.toMultimap().mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() })
        }
    }
    override fun read(url: String): InputStream {
        val connection = connection("GET",url,emptyMap())
        try {
            if (connection.code != 200) throw failure(connection.code)
            return object : FilterInputStream(connection.body?.byteStream() ?: throw IOException("Risposta Drive vuota")) {
                override fun read(): Int { check(); return super.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return `in`.read(b,off,len) }
                override fun close() { try { super.close() } finally { connection.close() } }
            }
        } catch (e: Exception) { connection.close(); throw e }
    }
}

fun failure(status: Int, reason: String = ""): DriveFailure = DriveFailure(status, when {
    status == 401 -> "Accesso Google scaduto o revocato. Ricollega l’account da Posizioni → Account Google Drive."
    reason == "storageQuotaExceeded" -> "Spazio Google Drive esaurito. Gli originali sono conservati."
    status == 403 -> "Google Drive: permesso insufficiente o limite di utilizzo raggiunto."
    status == 404 -> "File Drive non disponibile o accesso revocato."
    status == 409 || status == 412 -> "Il file Drive è cambiato. Aggiorna la cartella e riprova."
    status == 429 || status >= 500 -> "Google Drive temporaneamente non disponibile. Riprova più tardi."
    else -> "Operazione Google Drive non riuscita (HTTP $status). Gli originali sono conservati."
})

class DriveApi(val transport: DriveTransport, private val check: () -> Unit = {}) {
    companion object {
        const val BASE = "https://www.googleapis.com/drive/v3/"
        const val FOLDER = "application/vnd.google-apps.folder"
        const val SHORTCUT = "application/vnd.google-apps.shortcut"
        const val FIELDS = "id,name,mimeType,size,modifiedTime,version,parents,trashed,explicitlyTrashed,sha256Checksum,md5Checksum,webViewLink,capabilities(canDownload,canAddChildren,canRename,canTrash,canUntrash,canMoveItemWithinDrive)"
        fun encode(value: String): String = URLEncoder.encode(value,"UTF-8")
        fun literal(value: String): String = "'" + value.replace("\\","\\\\").replace("'","\\'") + "'"
    }
    fun url(path: String, query: Map<String,String> = emptyMap()) = BASE + path + if (query.isEmpty()) "" else query.entries.joinToString("&","?") { encode(it.key)+"="+encode(it.value) }
    private fun success(reply: DriveReply): JSONObject {
        if (reply.status !in 200..299) {
            val reason = try { reply.json().optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty() } catch (_: Exception) { "" }
            throw failure(reply.status,reason)
        }
        return reply.json()
    }
    fun get(id: String): JSONObject {
        check(); val reply=transport.request("GET",url("files/${encode(id)}",mapOf("fields" to FIELDS)))
        return success(reply).apply { reply.headers["etag"]?.let { put("_etag",it) } }
    }
    fun about(): JSONObject = success(transport.request("GET",url("about",mapOf("fields" to "user(permissionId,emailAddress,displayName),storageQuota"))))
    fun files(query: String): List<JSONObject> {
        val result = ArrayList<JSONObject>(); val seen = HashSet<String>(); var page = ""
        do {
            check(); val parameters = mutableMapOf("q" to query,"fields" to "nextPageToken,files($FIELDS)","pageSize" to "1000","spaces" to "drive","corpora" to "user")
            if (page.isNotEmpty()) parameters["pageToken"] = page
            val json = success(transport.request("GET",url("files",parameters)))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) { require(result.size < 250000) { "Troppi elementi nella cartella Drive" }; result.add(files.getJSONObject(i)) }
            page = json.optString("nextPageToken")
            require(page.isEmpty() || seen.add(page)) { "Paginazione Drive non valida" }
        } while (page.isNotEmpty())
        return result
    }
    fun children(parent: String) = files("${literal(parent)} in parents and trashed = false")
    fun patch(id: String, body: JSONObject, parameters: Map<String,String> = emptyMap(), etag: String = ""): JSONObject {
        check()
        // No automatic retry: an interrupted mutation might already have succeeded.
        val headers=mutableMapOf("Content-Type" to "application/json; charset=UTF-8")
        if(etag.isNotBlank()) headers["If-Match"]=etag
        return success(transport.request("PATCH",url("files/${encode(id)}",parameters + ("fields" to FIELDS)),headers,body.toString().toByteArray(UTF_8)))
    }
    fun create(parent: String, name: String, directory: Boolean): JSONObject {
        check()
        val ids = success(transport.request("GET",url("files/generateIds",mapOf("count" to "1","space" to "drive"))))
        val id = ids.getJSONArray("ids").getString(0)
        val body = JSONObject().put("id",id).put("name",name).put("parents",JSONArray().put(parent)).put("mimeType",if(directory) FOLDER else "application/octet-stream")
        try {
            return success(transport.request("POST",url("files",mapOf("fields" to FIELDS)),mapOf("Content-Type" to "application/json; charset=UTF-8"),body.toString().toByteArray(UTF_8)))
        } catch (e: IOException) {
            // Resolve uncertain creation by its preallocated ID, never by creating a second file.
            try { return get(id) } catch (_: Exception) { throw e }
        }
    }
    fun content(id: String): InputStream = transport.read(url("files/${encode(id)}",mapOf("alt" to "media")))
    fun export(id: String, mime: String): InputStream = transport.read(url("files/${encode(id)}/export",mapOf("mimeType" to mime)))

    /** Retry only resumable chunks; recover the committed offset before resending any bytes. */
    fun upload(id: String, file: File, progress: (Long) -> Unit = {}) {
        check()
        val start = transport.request("PATCH","https://www.googleapis.com/upload/drive/v3/files/${encode(id)}?uploadType=resumable",mapOf("Content-Type" to "application/json","X-Upload-Content-Type" to "application/octet-stream","X-Upload-Content-Length" to file.length().toString()),"{}".toByteArray(UTF_8))
        success(start)
        val session = start.headers["location"] ?: error("Sessione di caricamento Drive mancante")
        require(GoogleDriveTransport.trusted(session)) { "Sessione di caricamento non valida" }
        val total = file.length(); var offset = 0L; var retries = 0
        RandomAccessFile(file,"r").use { input ->
            do {
                check(); input.seek(offset)
                val bytes = ByteArray(minOf(256*1024L,total-offset).toInt()); input.readFully(bytes)
                val range = if(bytes.isEmpty()) "bytes */$total" else "bytes $offset-${offset+bytes.size-1}/$total"
                val reply = try { transport.request("PUT",session,mapOf("Content-Type" to "application/octet-stream","Content-Range" to range),bytes) }
                    catch (e: IOException) {
                        if(++retries > 3) throw e
                        check(); transport.request("PUT",session,mapOf("Content-Range" to "bytes */$total"),byteArrayOf())
                    }
                if(reply.status in 200..299) { require(offset+bytes.size == total || get(id).optLong("size",-1) == total) { "Caricamento Drive incompleto" }; progress(total); return }
                if(reply.status == 308) {
                    val previous = offset
                    val committed = reply.headers["range"]
                    offset = if(committed == null) 0 else Regex("bytes=0-(\\d+)").matchEntire(committed)?.groupValues?.get(1)?.toLong()?.plus(1) ?: error("Risposta di ripresa Drive non valida")
                    require(offset in 0..total) { "Offset Drive non valido" }
                    progress(offset)
                    if(offset <= previous && ++retries > 3) throw IOException("Caricamento Drive senza avanzamento")
                } else if(reply.status == 429 || reply.status >= 500) {
                    if(++retries > 3) throw failure(reply.status)
                    // Query status on the next iteration before retrying writes.
                    val status = transport.request("PUT",session,mapOf("Content-Range" to "bytes */$total"),byteArrayOf())
                    if(status.status in 200..299) { progress(total); return }
                    require(status.status == 308) { "Impossibile riprendere il caricamento Drive" }
                    offset = status.headers["range"]?.let { Regex("bytes=0-(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toLong()?.plus(1) ?: error("Offset Drive non valido") } ?: 0
                    require(offset in 0..total)
                } else { success(reply) }
            } while (offset <= total)
        }
    }
}
