package com.nodo.messenger;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/**
 * Owns the local Wi-Fi discovery, socket server, active calls and notifications.
 * A bound Activity may come and go without closing the network connection.
 * Android can still stop a foreground service (battery limits, force-stop, no Wi-Fi).
 */
public final class NodoService extends Service implements LocalNode.Listener {
    public static final String ACTION_START="com.nodo.messenger.START_BACKGROUND";
    public static final String ACTION_STOP="com.nodo.messenger.STOP_BACKGROUND";
    public static final String ACTION_HANGUP="com.nodo.messenger.HANGUP";
    public static final int ONGOING_ID=8013;
    private static final String CHANNEL="nodo_background_link_v1";
    private static final int PAIR_ID=8098;

    private final IBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Store store;
    private LocalNode node;
    private volatile LocalNode.Listener uiListener;
    private boolean promoted=false;
    private boolean capturingAudio=false;
    private String incomingPeer="";
    private PendingPair pendingPair;

    public final class LocalBinder extends Binder {
        public NodoService service(){return NodoService.this;}
    }

    @Override public void onCreate(){
        super.onCreate();
        store=new Store(this);
        NodeAlerts.setup(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"NODO conectado",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantiene la comunicación Wi-Fi local en segundo plano");
            channel.setSound(null,null);
            nm.createNotificationChannel(channel);
        }
        node=new LocalNode(getApplicationContext(),store,this);
        node.start();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(action)){
            store.backgroundEnabled(false);
            disableForeground();
            stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_HANGUP.equals(action)){
            node.endCall();captureMicrophone(false);
            updateNotification();
            return store.backgroundEnabled()?START_STICKY:START_NOT_STICKY;
        }
        store.backgroundEnabled(true);
        promote();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){uiListener=null;return true;}
    @Override public void onRebind(Intent intent){super.onRebind(intent);}

    LocalNode network(){return node;}
    boolean enabled(){return store.backgroundEnabled();}
    String pendingCaller(){return incomingPeer;}

    void attach(LocalNode.Listener listener){
        uiListener=listener;
        if(pendingPair!=null) deliverPendingPair(listener);
        if(incomingPeer.length()>0 && listener!=null && node.isCalling()) {
            listener.callOffer(new LocalNode.Peer(incomingPeer,store.peerName(incomingPeer)));
        }
    }
    void detach(LocalNode.Listener listener){if(uiListener==listener)uiListener=null;}

    public static void start(Context context){
        Intent i=new Intent(context,NodoService.class).setAction(ACTION_START);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i);
        else context.startService(i);
    }
    public static void stopBackground(Context context){
        context.startService(new Intent(context,NodoService.class).setAction(ACTION_STOP));
    }

    void captureMicrophone(boolean enable){
        if(!promoted) return;
        capturingAudio=enable;
        promote();
    }

    private void promote(){
        Notification n=ongoingNotification();
        try {
            if(Build.VERSION.SDK_INT>=29){
                int mask=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                if(capturingAudio && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                    mask |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(ONGOING_ID,n,mask);
            } else startForeground(ONGOING_ID,n);
            promoted=true;
        }catch(RuntimeException ex){
            // Never claim background operation if the OS denied required permissions.
            promoted=false;
            stopSelf();
        }
    }

    private Notification ongoingNotification(){
        int count=node==null?0:node.connections();
        String text=capturingAudio?"Llamada de voz activa":
            (count>0?count+" contacto(s) conectados · Wi-Fi local":"Buscando contactos · Wi-Fi local");
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap=PendingIntent.getActivity(this,15,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent exit=new Intent(this,NodoService.class).setAction(ACTION_STOP);
        PendingIntent stop=PendingIntent.getService(this,16,exit,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NODO · Red local activa")
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Detener",stop).build());
        if(capturingAudio){
            Intent end=new Intent(this,NodoService.class).setAction(ACTION_HANGUP);
            PendingIntent hang=PendingIntent.getService(this,17,end,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(android.R.drawable.sym_action_call,"Colgar",hang).build());
        }
        return b.build();
    }

    private void updateNotification(){
        if(!promoted)return;
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null)nm.notify(ONGOING_ID,ongoingNotification());
    }
    private void disableForeground(){
        promoted=false;capturingAudio=false;
        if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override public void onDestroy(){
        pendingPair=null;
        NodeAlerts.clearCall(this);
        if(node!=null)node.stop();
        disableForeground();
        super.onDestroy();
    }

    @Override public void changed(){
        main.post(()->{
            flushPending();
            updateNotification();
            LocalNode.Listener listener=uiListener;
            if(listener!=null)listener.changed();
        });
    }
    private void flushPending(){
        for(String id:store.contacts())if(node.isConnected(id)){
            JSONArray messages=store.messages(id);
            for(int i=0;i<messages.length();i++){
                JSONObject m=messages.optJSONObject(i);
                if(m!=null && m.optBoolean("out") && "text".equals(m.optString("kind")) && !m.optBoolean("sent")){
                    if(node.send(id,"text",m.optString("body"))) store.markSent(id,m.optString("mid"));
                }
            }
        }
    }
    @Override public void status(String text){
        LocalNode.Listener listener=uiListener;
        if(listener!=null) main.post(()->{if(uiListener==listener)listener.status(text);});
    }

    private static final class PendingPair {
        final String name;final Consumer<Boolean> answer;
        PendingPair(String n,Consumer<Boolean> a){name=n;answer=a;}
    }
    @Override public void pairing(String name,Consumer<Boolean> answer){
        main.post(()->{
            if(pendingPair!=null) {answer.accept(false);return;}
            PendingPair p=new PendingPair(name,answer);
            pendingPair=p;
            main.postDelayed(()->{
                if(pendingPair==p){pendingPair=null;answer.accept(false);cancelPairNotice();}
            },40000);
            if(uiListener!=null)deliverPendingPair(uiListener);
            else showPairNotice(name);
        });
    }
    private void deliverPendingPair(LocalNode.Listener listener){
        PendingPair p=pendingPair;
        if(p==null)return;
        cancelPairNotice();
        listener.pairing(p.name,approved->{
            main.post(()->{
                if(pendingPair!=p)return;
                pendingPair=null;
                p.answer.accept(approved);
            });
        });
    }
    private void showPairNotice(String name){
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm==null)return;
        Intent launch=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap=PendingIntent.getActivity(this,21,launch,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,NodeAlerts.CALLS)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle("Solicitud de enlace NODO")
            .setContentText(name+" quiere conectarse. Abre NODO para autorizar.")
            .setContentIntent(tap).setAutoCancel(true).build();
        nm.notify(PAIR_ID,n);
    }
    private void cancelPairNotice(){
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null)nm.cancel(PAIR_ID);
    }

    @Override public void incoming(LocalNode.Peer p,String kind,String body,String path,String filename){
        store.peerName(p.id,p.name);
        store.saveMessage(p.id,store.newMessage(false,kind,body,path,filename,true));
        LocalNode.Listener listener=uiListener;
        if(listener!=null)listener.incoming(p,kind,body,path,filename);
        else {
            String preview="text".equals(kind)?body:("voice".equals(kind)?"🎙 Nota de voz":"📎 "+filename);
            NodeAlerts.message(this,store,p.id,p.name,preview);
        }
    }
    @Override public void callOffer(LocalNode.Peer p){
        incomingPeer=p.id;
        NodeAlerts.incomingCall(this,store,p.id,p.name);
        LocalNode.Listener listener=uiListener;
        if(listener!=null)listener.callOffer(p);
    }
    @Override public void callAccepted(LocalNode.Peer p){
        incomingPeer="";
        NodeAlerts.clearCall(this);
        LocalNode.Listener listener=uiListener;
        if(listener!=null)listener.callAccepted(p);
        main.post(this::updateNotification);
    }
    @Override public void callEnded(LocalNode.Peer p){
        incomingPeer="";
        NodeAlerts.clearCall(this);
        main.post(()->{captureMicrophone(false);updateNotification();});
        LocalNode.Listener listener=uiListener;
        if(listener!=null)listener.callEnded(p);
    }
}
