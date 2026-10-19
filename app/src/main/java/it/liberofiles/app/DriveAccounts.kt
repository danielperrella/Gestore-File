package it.liberofiles.app

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import it.liberofiles.core.Node
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DriveAccount(val key: String, val permissionId: String, val email: String) {
    fun androidAccount() = Account(email,"com.google")
}

class DriveAccounts(private val context: Context, private val check: () -> Unit, private val onUpload: (Long) -> Unit = {}) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive"
        fun authorizationError(error: Exception?): String {
            val code = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
            val explanation = when(code) {
                10 -> "Configurazione Google dell’app non valida. Occorre completare il client OAuth Android con pacchetto e firma corretti."
                7 -> "Google non è raggiungibile. Verifica la connessione e riprova."
                16 -> "Google ha interrotto il collegamento: consenso chiuso o accesso non disponibile."
                else -> "Google non ha completato il collegamento."
            }
            val detail = code?.let { " Codice Google: $it." } ?: ""
            return explanation + detail + " Questa versione è in test Google: usa un account abilitato come tester. Se hai appena ricevuto l’accesso, attendi la propagazione delle impostazioni Google."
        }
    }
    private val preferences = context.getSharedPreferences("drive-accounts",Context.MODE_PRIVATE)
    private data class Token(val value: String, val until: Long)
    private val tokens = ConcurrentHashMap<String,Token>()
    private val client get() = Identity.getAuthorizationClient(context)
    fun accounts(): List<DriveAccount> {
        val array = JSONArray(preferences.getString("accounts","[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            DriveAccount(it.getString("key"),it.getString("permissionId"),it.getString("email"))
        }
    }
    fun account(key: String) = accounts().firstOrNull { it.key == key } ?: error("Account Drive scollegato")
    fun available() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    fun request(account: DriveAccount? = null): AuthorizationRequest = AuthorizationRequest.builder().apply {
        setRequestedScopes(listOf(Scope(SCOPE)))
        if(account != null) setAccount(account.androidAccount())
        else setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
    }.build()
    fun authorize(activity: Activity, account: DriveAccount?, ready: (AuthorizationResult) -> Unit, failed: (Exception) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request(account)).addOnSuccessListener(activity,ready).addOnFailureListener(activity) { failed(it) }
    }
    fun result(data: Intent?) = client.getAuthorizationResultFromIntent(data)
    fun connect(result: AuthorizationResult, expectedKey: String?): DriveAccount {
        checkNotNull(result.accessToken) { "Accesso Google non completato" }
        require(result.grantedScopes.contains(SCOPE)) { "Permesso Google Drive non concesso" }
        val api = DriveApi(GoogleDriveTransport({result.accessToken!!},check),check)
        val user = api.about().getJSONObject("user")
        val id = user.getString("permissionId")
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Identità Drive non valida" }
        val account = DriveAccount("drive-$id",id,user.getString("emailAddress"))
        require(expectedKey == null || expectedKey == account.key) { "Hai selezionato un account diverso. Aggiungilo come nuova posizione." }
        synchronized(this) {
            val array = JSONArray()
            (accounts().filterNot { it.key == account.key } + account).forEach {
                array.put(JSONObject().put("key",it.key).put("permissionId",it.permissionId).put("email",it.email))
            }
            check(preferences.edit().putString("accounts",array.toString()).commit()) { "Impossibile salvare l’account" }
            tokens[account.key] = Token(result.accessToken!!,SystemClock.elapsedRealtime()+45*60*1000)
        }
        return account
    }
    @Synchronized private fun token(key: String): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Accesso Drive richiesto sul thread UI" }
        val account = account(key)
        tokens[key]?.takeIf { it.until > SystemClock.elapsedRealtime() }?.let { return it.value }
        val result = Tasks.await(client.authorize(request(account)),30,TimeUnit.SECONDS)
        if(result.hasResolution()) throw failure(401)
        val linked = connect(result,key)
        return tokens[linked.key]!!.value
    }
    fun storage(key: String) = DriveStorage(DriveApi(GoogleDriveTransport({token(key)},check),check),key,File(context.cacheDir,"drive"),check,onUpload)
    fun disconnect(key: String) {
        val account = account(key)
        Tasks.await(client.revokeAccess(RevokeAccessRequest.builder().setAccount(account.androidAccount()).setScopes(listOf(Scope(SCOPE))).build()),30,TimeUnit.SECONDS)
        forget(key)
    }
    @Synchronized fun forget(key: String) {
        val array = JSONArray()
        accounts().filterNot { it.key == key }.forEach { array.put(JSONObject().put("key",it.key).put("permissionId",it.permissionId).put("email",it.email)) }
        check(preferences.edit().putString("accounts",array.toString()).commit())
        tokens.remove(key)
        // Only this account's private working files; no remote deletions.
        val base = File(context.cacheDir,"drive").canonicalFile
        val directory = File(base,key).canonicalFile
        require(directory.parentFile == base)
        directory.deleteRecursively()
    }
    fun roots() = accounts().map { "Google Drive · ${it.email}" to Node(it.key,"root") }
}
