package com.nodo.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app updates for NODO Beta distributed from our public GitHub repository.
 * Checks are automatic, but APK installation is always initiated/approved by the user.
 * Transport is HTTPS, metadata is bounded, APK SHA-256 is verified, and Android's
 * package installer verifies that the APK uses the same signing certificate.
 */
public final class NodeUpdater {
    static final String MANIFEST_URL = "https://raw.githubusercontent.com/Marcoglez98/Nodo-Android/nodo-modular-v06/updates/beta.json";
    static final String URL_PREFIX = "https://raw.githubusercontent.com/Marcoglez98/Nodo-Android/";
    private static final int JOB_ID = 72611;
    private static final int UPDATE_NOTIFICATION = 72612;
    private static final int PROGRESS_NOTIFICATION = 72613;
    private static final String CHANNEL = "nodo_updates_beta_v1";
    private static final String PREFS = "nodo_updates";
    private static final ExecutorService POOL=Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long PERIOD_MS = 12L*60*60*1000;
    private static final long CHECK_COOLDOWN_MS = 4L*60*60*1000;

    private NodeUpdater() {}

    static final class Release {
        final int code;
        final String version, notes, apk, sha;
        Release(int code, String version, String notes, String apk, String sha) {
            this.code=code;this.version=version;this.notes=notes;this.apk=apk;this.sha=sha;
        }
    }

    static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static boolean enabled(Context c){return prefs(c).getBoolean("auto_check",true);}
    public static void enabled(Context c,boolean value){
        prefs(c).edit().putBoolean("auto_check",value).apply();
        schedule(c);
    }
    private static String installedVersion(Context c){
        try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}catch(Exception ignored){return "desconocida";}
    }
    private static long currentCode(Context c){
        try {
            PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
            return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
        }catch(Exception ignored){return 0L;}
    }
    static void channel(Context c){
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(nm!=null){
            NotificationChannel nc=new NotificationChannel(CHANNEL,"Actualizaciones de NODO",NotificationManager.IMPORTANCE_DEFAULT);
            nc.setDescription("Avisos sobre nuevas versiones de NODO Beta");
            nm.createNotificationChannel(nc);
        }
    }
    public static void schedule(Context ctx){
        channel(ctx);
        JobScheduler jobs=ctx.getSystemService(JobScheduler.class);
        if(jobs==null)return;
        if(!enabled(ctx)){jobs.cancel(JOB_ID);return;}
        JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(ctx,NodoUpdateJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            .build();
        jobs.schedule(info);
    }

    private static Release fetchLatest() throws Exception{
        HttpURLConnection con=(HttpURLConnection)new URL(MANIFEST_URL).openConnection();
        con.setConnectTimeout(9000);con.setReadTimeout(10000);con.setInstanceFollowRedirects(false);
        con.setRequestProperty("Accept","application/json");
        try{
            if(con.getResponseCode()!=200)throw new Exception("El servidor de actualizaciones no respondió");
            if(con.getContentLengthLong()>32768)throw new Exception("Metadatos demasiado grandes");
            byte[] bytes;
            try(InputStream in=con.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] buf=new byte[2048];int n;
                while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>32768)throw new Exception("Metadatos demasiado grandes");}
                bytes=out.toByteArray();
            }
            JSONObject j=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            int code=j.getInt("versionCode");
            String version=j.getString("versionName");
            String notes=j.optString("notes","Mejoras y correcciones para NODO Beta.");
            String apk=j.getString("apkUrl");
            String sha=j.getString("sha256").toLowerCase(Locale.ROOT);
            if(code<1||code>999999||version.length()>80||notes.length()>3500)
                throw new Exception("Datos de versión inválidos");
            if(!apk.startsWith(URL_PREFIX)||!apk.contains("/downloads/")||!apk.endsWith(".apk")||apk.length()>600)
                throw new Exception("Origen del instalador no permitido");
            if(!sha.matches("[0-9a-f]{64}"))throw new Exception("Huella de descarga no válida");
            return new Release(code,version,notes,apk,sha);
        }finally{con.disconnect();}
    }

    public static void check(Activity activity,boolean manual){
        if(!manual&&!enabled(activity))return;
        SharedPreferences p=prefs(activity);
        long now=System.currentTimeMillis();
        if(!manual && now-p.getLong("last_check",0)<CHECK_COOLDOWN_MS)return;
        Context app=activity.getApplicationContext();
        POOL.execute(()->{
            try{
                Release r=fetchLatest();
                p.edit().putLong("last_check",System.currentTimeMillis()).apply();
                if(r.code>currentCode(app)){
                    if(manual || p.getInt("last_prompt_code",0)<r.code){
                        p.edit().putInt("last_prompt_code",r.code).apply();
                        MAIN.post(()->{
                            if(!activity.isFinishing()&&!activity.isDestroyed())showPrompt(activity,r);
                            else notifyAvailable(app,r);
                        });
                    }
                }else if(manual)MAIN.post(()->toast(activity,"NODO ya está actualizado"));
            }catch(Exception e){
                if(manual)MAIN.post(()->{
                    if(!activity.isFinishing())new AlertDialog.Builder(activity)
                        .setTitle("No se pudo buscar actualizaciones")
                        .setMessage("Para comprobar nuevas versiones necesitas acceso a internet. Los mensajes por Wi-Fi local siguen funcionando sin internet.\n\n"+e.getMessage())
                        .setPositiveButton("Entendido",null).show();
                });
            }
        });
    }

    static void checkBackground(Context context,Runnable completion){
        Context app=context.getApplicationContext();
        POOL.execute(()->{
            try{
                Release r=fetchLatest();
                prefs(app).edit().putLong("last_check",System.currentTimeMillis()).apply();
                if(r.code>currentCode(app))notifyAvailable(app,r);
            }catch(Exception ignored){}finally{completion.run();}
        });
    }

    private static PendingIntent openApp(Context ctx){
        Intent i=new Intent(ctx,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("nodo_show_updates",true);
        return PendingIntent.getActivity(ctx,72612,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static void notifyAvailable(Context ctx,Release r){
        channel(ctx);
        NotificationManager nm=ctx.getSystemService(NotificationManager.class);
        if(nm==null)return;
        Notification note=new Notification.Builder(ctx,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("NODO "+r.version+" disponible")
            .setContentText("Toca para descargar e instalar la actualización")
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build();
        nm.notify(UPDATE_NOTIFICATION,note);
    }
    public static void showSettings(Activity a){
        boolean on=enabled(a);
        new AlertDialog.Builder(a).setTitle("Actualizaciones de NODO")
            .setMessage("Versión instalada: "+installedVersion(a)+"\n\n"+
                "Búsqueda automática: "+(on?"activada":"desactivada")+"\n\n"+
                "NODO avisará cuando publiquemos una nueva versión en GitHub. La descarga y la instalación se harán únicamente con tu autorización. Se necesita internet para buscar y descargar actualizaciones.")
            .setPositiveButton("Buscar ahora",(d,w)->check(a,true))
            .setNeutralButton(on?"Desactivar avisos":"Activar avisos",(d,w)->{
                enabled(a,!on);toast(a,!on?"Avisos automáticos activados":"Avisos automáticos desactivados");
            })
            .setNegativeButton("Cerrar",null).show();
    }
    private static void showPrompt(Activity a,Release r){
        new AlertDialog.Builder(a).setTitle("Nueva actualización: NODO "+r.version)
            .setMessage(r.notes+"\n\nSe descargará desde el GitHub oficial de NODO y Android te pedirá confirmar la instalación.")
            .setPositiveButton("Descargar",(d,w)->download(a,r))
            .setNegativeButton("Más tarde",null).show();
    }

    private static void download(Activity a,Release r){
        Context ctx=a.getApplicationContext();
        channel(ctx);
        toast(a,"Descargando NODO "+r.version+"…");
        POOL.execute(()->{
            File dir=new File(ctx.getFilesDir(),"updates");
            if(!dir.exists()&&!dir.mkdirs()){MAIN.post(()->toast(a,"No se pudo preparar la descarga"));return;}
            File part=new File(dir,"nodo-"+r.code+".tmp.apk");
            File target=new File(dir,"nodo-"+r.code+".apk");
            try{
                HttpURLConnection con=(HttpURLConnection)new URL(r.apk).openConnection();
                con.setConnectTimeout(15000);con.setReadTimeout(25000);con.setInstanceFollowRedirects(false);
                try{
                    if(con.getResponseCode()!=200)throw new Exception("No se encontró el APK publicado");
                    long total=con.getContentLengthLong();
                    if(total>150L*1024*1024)throw new Exception("El APK excede el límite permitido");
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");
                    long size=0;byte[] buf=new byte[8192];int n;
                    try(InputStream in=con.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                        while((n=in.read(buf))!=-1){
                            size+=n;if(size>150L*1024*1024)throw new Exception("Archivo demasiado grande");
                            digest.update(buf,0,n);out.write(buf,0,n);
                        }
                    }
                    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                    if(!hex.toString().equals(r.sha))throw new Exception("La descarga no coincide con la firma de integridad publicada");
                    PackageInfo pkg=ctx.getPackageManager().getPackageArchiveInfo(part.getAbsolutePath(),0);
                    if(pkg==null || !ctx.getPackageName().equals(pkg.packageName))throw new Exception("El APK no pertenece a NODO Beta");
                    long apkCode=Build.VERSION.SDK_INT>=28?pkg.getLongVersionCode():pkg.versionCode;
                    if(apkCode!=r.code)throw new Exception("La versión del APK no coincide con el anuncio");
                    if(target.exists()&&!target.delete())throw new Exception("No se pudo reemplazar descarga previa");
                    if(!part.renameTo(target))throw new Exception("No se pudo guardar el APK");
                    prefs(ctx).edit().putString("ready_apk",target.getName()).apply();
                    MAIN.post(()->{
                        if(!a.isFinishing())new AlertDialog.Builder(a)
                            .setTitle("Descarga verificada")
                            .setMessage("NODO "+r.version+" está listo. Android pedirá confirmar la actualización.")
                            .setPositiveButton("Instalar",(d,w)->install(a,target))
                            .setNegativeButton("Después",null).show();
                        else notifyDownloaded(ctx,r);
                    });
                }finally{con.disconnect();}
            }catch(Exception e){
                part.delete();MAIN.post(()->{
                    if(!a.isFinishing())new AlertDialog.Builder(a)
                        .setTitle("Descarga cancelada")
                        .setMessage("No fue posible descargar la actualización de forma segura: "+e.getMessage())
                        .setPositiveButton("Aceptar",null).show();
                });
            }
        });
    }
    private static void notifyDownloaded(Context ctx,Release r){
        NotificationManager nm=ctx.getSystemService(NotificationManager.class);if(nm==null)return;
        nm.notify(PROGRESS_NOTIFICATION,new Notification.Builder(ctx,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("NODO "+r.version+" descargado")
            .setContentText("Abre NODO para instalar la actualización")
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build());
    }
    private static void install(Activity activity,File apk){
        if(!apk.isFile()){toast(activity,"No se encontró el APK descargado");return;}
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
            prefs(activity).edit().putBoolean("install_waiting",true).apply();
            new AlertDialog.Builder(activity).setTitle("Permitir instalar actualizaciones")
                .setMessage("Android requiere que autorices a NODO Beta para instalar APK. Activa 'Permitir desde esta fuente', vuelve a NODO y confirma la instalación.")
                .setPositiveButton("Abrir configuración",(d,w)->{
                    try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:"+activity.getPackageName())));}catch(Exception e){toast(activity,"Abre los permisos de instalación de NODO en Android");}
                }).setNegativeButton("Cancelar",null).show();
            return;
        }
        prefs(activity).edit().putBoolean("install_waiting",false).apply();
        try{
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(NodeFileProvider.uri("updates",apk.getName()),"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        }catch(Exception e){toast(activity,"Android no pudo abrir el instalador: "+e.getMessage());}
    }
    public static void onResume(Activity a){
        SharedPreferences p=prefs(a);
        if(!p.getBoolean("install_waiting",false))return;
        if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls())return;
        p.edit().putBoolean("install_waiting",false).apply();
        String name=p.getString("ready_apk","");
        File file=new File(new File(a.getFilesDir(),"updates"),name);
        if(name.matches("nodo-[0-9]+\\.apk")&&file.isFile()){
            new AlertDialog.Builder(a).setTitle("Instalar actualización")
                .setMessage("La instalación de NODO Beta está preparada. ¿Abrir el instalador de Android?")
                .setPositiveButton("Instalar",(d,w)->install(a,file))
                .setNegativeButton("Después",null).show();
        }
    }
    private static void toast(Context c,String m){Toast.makeText(c,m,Toast.LENGTH_LONG).show();}
}
