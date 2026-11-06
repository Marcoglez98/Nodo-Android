package com.nodo.messenger.data;

import com.nodo.messenger.Store;
import com.nodo.messenger.domain.MessageRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Stable facade for messages/read indicators and conversation previews. */
public final class ChatRepository {
    private final Store store;
    public ChatRepository(Store store){this.store=store;}
    public List<MessageRecord> messages(String peer){
        JSONArray stored=store.messages(peer);List<MessageRecord> output=new ArrayList<>();
        for(int i=0;i<stored.length();i++){
            JSONObject row=stored.optJSONObject(i);if(row!=null)output.add(MessageRecord.from(row));
        }
        return output;
    }
    public MessageRecord latest(String peer){
        List<MessageRecord> records=messages(peer);
        return records.isEmpty()?null:records.get(records.size()-1);
    }
    public int unread(String peer){return store.unreadCount(peer);}
    public void markRead(String peer){store.markRead(peer);}
    public boolean favorite(String peer){return store.favorite(peer);}
    public void favorite(String peer,boolean enabled){store.favorite(peer,enabled);}
}
