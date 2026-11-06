package com.nodo.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Small, local-only persistence layer for the early alpha. */
public final class Store {
    private final SharedPreferences p;
    public Store(Context c) { p = c.getSharedPreferences("nodo_v1", Context.MODE_PRIVATE); }
    public String id() {
        String s=p.getString("id","");
        if(s.isEmpty()){ s=UUID.randomUUID().toString().replace("-", ""); p.edit().putString("id",s).apply(); }
        return s;
    }
    public String name(){return p.getString("name","Usuario NODO");}
    public void name(String s){p.edit().putString("name",s).apply();}
    public String status(){return p.getString("status","Disponible en NODO");}
    public void status(String s){p.edit().putString("status",s).apply();}
    public boolean backgroundEnabled(){return p.getBoolean("background_enabled",true);}
    public void backgroundEnabled(boolean enable){p.edit().putBoolean("background_enabled",enable).apply();}
    public boolean autoConnect(){return p.getBoolean("autoconnect",true);}
    public void autoConnect(boolean b){p.edit().putBoolean("autoconnect",b).apply();}
    public boolean messageNotifications(){return p.getBoolean("message_notifications",true);}
    public void messageNotifications(boolean on){p.edit().putBoolean("message_notifications",on).apply();}
    public boolean messageSounds(){return p.getBoolean("message_sounds",true);}
    public void messageSounds(boolean on){p.edit().putBoolean("message_sounds",on).apply();}
    public boolean callNotifications(){return p.getBoolean("call_notifications",true);}
    public void callNotifications(boolean on){p.edit().putBoolean("call_notifications",on).apply();}
    public boolean callSounds(){return p.getBoolean("call_sounds",true);}
    public void callSounds(boolean on){p.edit().putBoolean("call_sounds",on).apply();}
    public String avatar(){return p.getString("avatar","");}
    public void avatar(String a){p.edit().putString("avatar",a).apply();}
    public boolean trusted(String id){return p.getBoolean("trust_"+id,false);}
    public void trust(String id,String name){
        if(id==null||id.isEmpty()) return;
        p.edit().putBoolean("trust_"+id,true).putString("peer_"+id,name).apply();
    }
    public void revoke(String id){p.edit().remove("trust_"+id).apply();}
    public String peerName(String id){return p.getString("peer_"+id,id.length()>6?id.substring(0,6):id);}
    public void peerName(String id,String name){p.edit().putString("peer_"+id,name).apply();}
    public List<String> contacts(){
        Set<String> all =p.getAll().keySet(); List<String> ids=new ArrayList<>();
        for(String k:all)if(k.startsWith("peer_"))ids.add(k.substring(5));
        Collections.sort(ids,(a,b)->peerName(a).compareToIgnoreCase(peerName(b)));
        return ids;
    }
    public String matchShort(String shortId){
        for(String id:contacts()) if(id.startsWith(shortId) && trusted(id))return id;
        return null;
    }
    public synchronized JSONArray messages(String peer){
        try{return new JSONArray(p.getString("messages_"+peer,"[]"));}catch(Exception ex){return new JSONArray();}
    }
    public synchronized void saveMessage(String peer,JSONObject msg){
        JSONArray a=messages(peer); a.put(msg);
        p.edit().putString("messages_"+peer,a.toString()).apply();
    }
    public synchronized void markSent(String peer,String mid){
        JSONArray a=messages(peer);
        for(int i=0;i<a.length();i++){
            JSONObject j=a.optJSONObject(i);if(j!=null && mid.equals(j.optString("mid"))){try{j.put("sent",true);}catch(Exception ignored){}}
        }
        p.edit().putString("messages_"+peer,a.toString()).apply();
    }
    public synchronized void clearMessages(String peer){p.edit().remove("messages_"+peer).apply();}
    public JSONObject newMessage(boolean out,String kind,String body,String path,String name,boolean sent){
        JSONObject m=new JSONObject();try{
            m.put("mid",UUID.randomUUID().toString());m.put("out",out);
            m.put("kind",kind);m.put("body",body);m.put("path",path);
            m.put("name",name);m.put("sent",sent);m.put("when",System.currentTimeMillis());
        }catch(Exception ignored){}return m;
    }
}
