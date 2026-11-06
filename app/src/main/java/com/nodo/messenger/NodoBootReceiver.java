package com.nodo.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opt-in network restart after reboot or upgrade; subject to Android restrictions. */
public final class NodoBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null || !new Store(context).backgroundEnabled())return;
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        try{NodoService.start(context);}catch(RuntimeException ignored){
            // Android may disallow foreground service start from the background.
            // The user can start it again by opening NODO.
        }
    }
}
