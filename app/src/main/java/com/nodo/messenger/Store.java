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
    // UI and chat preferences kept independently of the transport implementation.
    public boolean favorite(String id){return p.getBoolean("favorite_"+id,false);}
    public void favorite(String id,boolean enabled){p.edit().putBoolean("favorite_"+id,enabled).apply();}
    public boolean discoverable(){return p.getBoolean("discoverable",true);}
    public void discoverable(boolean enabled){p.edit().putBoolean("discoverable",enabled).apply();}
    public boolean allowNewPairing(){return p.getBoolean("allow_new_pairing",true);}
    public void allowNewPairing(boolean enabled){p.edit().putBoolean("allow_new_pairing",enabled).apply();}
    public boolean shareLastSeen(){return p.getBoolean("share_last_seen",false);}
    public void shareLastSeen(boolean enabled){p.edit().putBoolean("share_last_seen",enabled).apply();}
    public synchronized void markRead(String peer){
        JSONArray a=messages(peer);
        long last=System.currentTimeMillis();
        for(int i=0;i<a.length();i++){
            JSONObject row=a.optJSONObject(i);
            if(row!=null&&!row.optBoolean("out"))last=Math.max(last,row.optLong("when"));
        }
        p.edit().putLong("lastread_"+peer,last).apply();
    }
    public synchronized int unreadCount(String peer){
        long last=p.getLong("lastread_"+peer,0L);int unread=0;
        JSONArray a=messages(peer);
        for(int i=0;i<a.length();i++){
            JSONObject row=a.optJSONObject(i);
            if(row!=null&&!row.optBoolean("out")&&row.optLong("when")>last)unread++;
        }
        return unread;
    }
    public void peerLastSeen(String id,long time){
        if(id!=null&&!id.isEmpty()&&time>0)p.edit().putLong("remote_seen_"+id,time).apply();
    }
    public long peerLastSeen(String id){return p.getLong("remote_seen_"+id,0L);}
    public boolean trusted(String id){return p.getBoolean("trust_"+id,false);}
    public void trust(String id,String name){
        if(id==null||id.isEmpty()) return;
        p.edit().putBoolean("trust_"+id,true).putString("peer_"+id,name).apply();
    }
    public void revoke(String id){p.edit().remove("trust_"+id).apply();}
    public String peerName(String id){
        if(isGroup(id))return groupName(id.substring(6));
        return p.getString("peer_"+id,id.length()>6?id.substring(0,6):id);
    }
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
    public boolean isGroup(String peer){return peer!=null && peer.startsWith("group:");}
    public synchronized void ensureGroup(String groupId,String name,List<String> members){
        if(groupId==null || !groupId.matches("[a-f0-9]{32}"))return;
        List<String> ids=groups();
        if(!ids.contains(groupId))ids.add(groupId);
        JSONArray arr=new JSONArray();for(String id:ids)arr.put(id);
        JSONArray m=new JSONArray();for(String id:members)if(id!=null && id.matches("[a-f0-9]{32}"))m.put(id);
        p.edit().putString("group_ids",arr.toString()).putString("group_name_"+groupId,name)
            .putString("group_members_"+groupId,m.toString()).apply();
    }
    public synchronized String createGroup(String name,List<String> members){
        String id=UUID.randomUUID().toString().replace("-","");
        ensureGroup(id,name,members);return id;
    }
    public synchronized List<String> groups(){
        List<String> ids=new ArrayList<>();
        try{JSONArray a=new JSONArray(p.getString("group_ids","[]"));for(int i=0;i<a.length();i++)ids.add(a.getString(i));}
        catch(Exception ignored){}return ids;
    }
    public String groupName(String id){return p.getString("group_name_"+id,"Grupo NODO");}
    public List<String> groupMembers(String id){
        List<String> ids=new ArrayList<>();
        try{JSONArray a=new JSONArray(p.getString("group_members_"+id,"[]"));for(int i=0;i<a.length();i++)ids.add(a.getString(i));}
        catch(Exception ignored){}return ids;
    }
    public synchronized boolean saveIncomingOnce(String peer,String mid,String kind,String body,String path,String name){
        if(mid==null || mid.isEmpty())mid=UUID.randomUUID().toString();
        JSONArray a=messages(peer);
        for(int i=Math.max(0,a.length()-1200);i<a.length();i++){
            JSONObject old=a.optJSONObject(i);
            if(old!=null && !old.optBoolean("out") && mid.equals(old.optString("mid")))return false;
        }
        JSONObject m=newMessage(false,kind,body,path,name,true);
        try{m.put("mid",mid);}catch(Exception ignored){}
        a.put(m);p.edit().putString("messages_"+peer,a.toString()).commit();return true;
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
