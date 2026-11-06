package com.nodo.messenger;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Alerts for LAN messages and calls while NODO's process is alive. */
final class NodeAlerts {
    static final int PERMISSION_REQUEST = 72;
    static final String MESSAGES = "nodo_message_sound_v2";
    static final String MESSAGES_SILENT = "nodo_message_silent_v2";
    static final String CALLS = "nodo_calls_v2";
    private static final int CALL_ID = 7491;
    private static Ringtone activeRingtone;

    private NodeAlerts() { }

    static void setup(Context ctx) {
        NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        NotificationChannel messages=new NotificationChannel(MESSAGES,"Mensajes NODO",NotificationManager.IMPORTANCE_DEFAULT);
        messages.setDescription("Mensajes y archivos recibidos de personas conectadas a NODO");
        Uri uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if(uri!=null)messages.setSound(uri,new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        messages.enableVibration(true);
        manager.createNotificationChannel(messages);
        NotificationChannel silent=new NotificationChannel(MESSAGES_SILENT,"Mensajes sin sonido",NotificationManager.IMPORTANCE_LOW);
        silent.setSound(null,null);
        manager.createNotificationChannel(silent);
        NotificationChannel calls=new NotificationChannel(CALLS,"Llamadas NODO",NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Llamadas entrantes por Wi-Fi local");
        calls.setSound(null,null);
        calls.enableVibration(true);
        manager.createNotificationChannel(calls);
    }

    static void requestPermission(Activity activity) {
        if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},PERMISSION_REQUEST);
        }
    }

    private static boolean permitted(Context ctx) {
        return Build.VERSION.SDK_INT<33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            ==PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent open(Context ctx,String peerId) {
        Intent i=new Intent(ctx,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("nodo_peer",peerId);
        return PendingIntent.getActivity(ctx,peerId.hashCode(),i,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static void message(Context ctx,Store store,String id,String name,String preview) {
        if(!store.messageNotifications()||!permitted(ctx))return;
        NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        String channel=store.messageSounds()?MESSAGES:MESSAGES_SILENT;
        Notification item=new Notification.Builder(ctx,channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(name).setContentText(preview)
            .setStyle(new Notification.BigTextStyle().bigText(preview))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open(ctx,id)).setAutoCancel(true).build();
        manager.notify(11000+(id.hashCode() & 0x7fff),item);
    }

    static void incomingCall(Context ctx,Store store,String id,String name) {
        if(!store.callNotifications())return;
        if(permitted(ctx)) {
            NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if(manager!=null) {
                Notification item=new Notification.Builder(ctx,CALLS)
                    .setSmallIcon(android.R.drawable.sym_action_call)
                    .setContentTitle("Llamada de NODO: "+name)
                    .setContentText("Llamada por red local. Toca para contestar.")
                    .setCategory(Notification.CATEGORY_CALL).setPriority(Notification.PRIORITY_HIGH)
                    .setContentIntent(open(ctx,id)).setOngoing(true).build();
                manager.notify(CALL_ID,item);
            }
        }
        if(store.callSounds())startRinging(ctx);
    }

    private static synchronized void startRinging(Context ctx) {
        stopRinging();
        try {
            Uri uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            activeRingtone=RingtoneManager.getRingtone(ctx.getApplicationContext(),uri);
            if(activeRingtone!=null){
                if(Build.VERSION.SDK_INT>=28)activeRingtone.setLooping(true);
                activeRingtone.play();
            }
        } catch(Exception ignored){activeRingtone=null;}
    }

    static synchronized void stopRinging(){
        if(activeRingtone!=null){
            try{activeRingtone.stop();}catch(Exception ignored){}
            activeRingtone=null;
        }
    }

    static void clearCall(Context ctx){
        stopRinging();
        NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager!=null)manager.cancel(CALL_ID);
    }

    static void openSystemSoundSettings(Context ctx){
        Intent intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE,ctx.getPackageName());
        try{ctx.startActivity(intent);}catch(Exception ex){
            Intent fallback=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+ctx.getPackageName()));ctx.startActivity(fallback);
        }
    }
}
