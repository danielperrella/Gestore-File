package it.liberofiles.app;

import android.content.Intent;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Only the project's HTTPS distribution is trusted; hashes and the installed signer are checked. */
final class AppUpdater {
    static final String BASE="https://danielperrella.github.io/Gestore-File/";
    private final MainActivity activity;
    private JSONObject candidate;
    private File pendingInstall;
    AppUpdater(MainActivity activity) { this.activity=activity; }

    private static HttpURLConnection open(String address) throws Exception {
        URL url=new URL(address);
        if(!url.getProtocol().equals("https")||!url.getHost().equals("danielperrella.github.io")
            ||url.getPort()!=-1||url.getUserInfo()!=null||!url.getPath().startsWith("/Gestore-File/"))
            throw new IllegalArgumentException("Origine aggiornamento non valida.");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(20000);
        connection.setRequestProperty("Cache-Control","no-cache");
        if(connection.getResponseCode()!=200){connection.disconnect();throw new IOException("Aggiornamento non disponibile. Riprova più tardi.");}
        return connection;
    }
    static void validate(JSONObject manifest) throws Exception {
        if(manifest.getLong("versionCode")<=0||manifest.getString("versionName").length()>40
            ||!manifest.getString("sha256").matches("[a-f0-9]{64}")||manifest.getLong("size")<=0
            ||manifest.getLong("size")>40_000_000||!manifest.getString("apkUrl").equals(BASE+"gestorefile.apk"))
            throw new IllegalArgumentException("Informazioni di aggiornamento non valide.");
    }
    JSONObject check() throws Exception {
        HttpURLConnection connection=open(BASE+"update.json?time="+System.currentTimeMillis());
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>32768)throw new IOException("Manifest troppo grande.");}
            JSONObject manifest=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));validate(manifest);
            candidate=manifest;
            return new JSONObject().put("available",manifest.getLong("versionCode")>installedVersion())
                .put("versionName",manifest.getString("versionName")).put("size",manifest.getLong("size"))
                .put("notes",manifest.optString("notes"));
        }finally{connection.disconnect();}
    }
    static String digest(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    private static Set<String> signatures(PackageInfo info) {
        Set<String> signatures=new HashSet<>();
        Signature[] values=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;
        if(values!=null)for(Signature value:values)signatures.add(value.toCharsString());
        return signatures;
    }
    void verify(File apk,JSONObject manifest) throws Exception {
        validate(manifest);
        if(apk.length()!=manifest.getLong("size")||!digest(apk).equals(manifest.getString("sha256")))
            throw new SecurityException("Il file scaricato non è integro. Riprova l’aggiornamento.");
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageManager manager=activity.getPackageManager();
        PackageInfo archive=manager.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        PackageInfo installed=manager.getPackageInfo(activity.getPackageName(),flags);
        long version=archive==null?0:Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        Set<String> expected=signatures(installed);
        if(archive==null||!activity.getPackageName().equals(archive.packageName)||version!=manifest.getLong("versionCode")
            ||version<=installedVersion()||expected.isEmpty()||!expected.equals(signatures(archive)))
            throw new SecurityException("Questa versione non è compatibile con la firma dell’app installata.");
    }
    JSONObject downloadAndInstall() throws Exception {
        if(candidate==null)check();
        JSONObject manifest=candidate;
        if(manifest.getLong("versionCode")<=installedVersion())throw new IllegalStateException("L’app è già aggiornata.");
        File directory=new File(activity.getCacheDir(),"updates");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Spazio per il download non disponibile.");
        File apk=new File(directory,"gestorefile.apk");
        HttpURLConnection connection=open(manifest.getString("apkUrl")+"?v="+manifest.getLong("versionCode"));
        try(InputStream input=connection.getInputStream();OutputStream output=new FileOutputStream(apk)) {
            byte[] buffer=new byte[65536];int count;long total=0;
            while((count=input.read(buffer))!=-1){total+=count;if(total>manifest.getLong("size"))throw new IOException("Dimensione aggiornamento non valida.");output.write(buffer,0,count);}
        }catch(Exception error){apk.delete();throw error;}finally{connection.disconnect();}
        try{verify(apk,manifest);}catch(Exception error){apk.delete();throw error;}
        pendingInstall=apk;
        boolean permission=activity.getPackageManager().canRequestPackageInstalls();
        activity.runOnUiThread(()->{
            try{
                if(activity.isFinishing()||activity.isDestroyed())return;
                if(permission)installPending();
                else activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));
            }catch(Exception error){activity.updateInstallError();}
        });
        return new JSONObject().put("status",permission?"installer":"permission");
    }
    void resume() {
        if(pendingInstall!=null&&activity.getPackageManager().canRequestPackageInstalls()) {
            try{installPending();}catch(Exception error){activity.updateInstallError();}
        }
    }
    private long installedVersion() throws Exception { return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).getLongVersionCode(); }
    private void installPending() {
        File apk=pendingInstall;pendingInstall=null;
        Uri uri=Uri.parse("content://"+activity.getPackageName()+".updates/gestorefile.apk");
        Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
    }
}
