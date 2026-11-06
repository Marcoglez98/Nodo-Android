package com.nodo.messenger.domain;

import org.json.JSONObject;

/** Immutable projection of one persisted message. No Android UI dependency. */
public final class MessageRecord {
    public enum Kind { TEXT, VOICE, FILE, UNKNOWN }
    public final String id,body,fileName,path;
    public final boolean outgoing,delivered;
    public final long timestamp;
    public final Kind kind;
    private MessageRecord(String id,String body,String fileName,String path,boolean outgoing,
                          boolean delivered,long timestamp,Kind kind){
        this.id=id;this.body=body;this.fileName=fileName;this.path=path;this.outgoing=outgoing;
        this.delivered=delivered;this.timestamp=timestamp;this.kind=kind;
    }
    public static MessageRecord from(JSONObject json){
        String raw=json.optString("kind","text");Kind type;
        if("text".equals(raw))type=Kind.TEXT;
        else if("voice".equals(raw))type=Kind.VOICE;
        else if("file".equals(raw))type=Kind.FILE;
        else type=Kind.UNKNOWN;
        return new MessageRecord(json.optString("mid"),json.optString("body"),
            json.optString("name"),json.optString("path"),json.optBoolean("out"),
            json.optBoolean("sent"),json.optLong("when"),type);
    }
    public String preview(){
        if(kind==Kind.TEXT)return body;
        if(kind==Kind.VOICE)return "Nota de voz";
        if(kind==Kind.FILE)return "Archivo: "+fileName;
        return "Mensaje";
    }
}
