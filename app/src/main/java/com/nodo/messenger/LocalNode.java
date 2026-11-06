package com.nodo.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Wi-Fi LAN / hotspot peer-to-peer transport. It does NOT implement Wi-Fi Direct or Bluetooth. */
public final class LocalNode {
    private static final String SERVICE="_nodo._tcp.";
    public static final int MAX_FILE=30*1024*1024;
    public interface Listener {
        void changed();
        void status(String text);
        void pairing(String name, java.util.function.Consumer<Boolean> answer);
        void incoming(Peer peer,String kind,String body,String path,String filename);
        void delivered(Peer peer,String messageId);
        void groupIncoming(Peer peer,String gid,String groupName,List<String> members,String mid,String text);
        void groupKnown(Peer peer,String gid,String groupName,List<String> members);
        void groupCallOffer(Peer peer,String gid,String name);
        void groupCallChanged();
        void callOffer(Peer peer);
        void callAccepted(Peer peer);
        void callEnded(Peer peer);
    }
    public static final class Peer {
        public final String id, name;
        Peer(String id,String name){this.id=id;this.name=name;}
    }
    public static final class Discovered {
        public final String serviceName,host;public final int port;
        Discovered(String a,String b,int c){serviceName=a;host=b;port=c;}
        public String shortId(){return serviceName.startsWith("NODO-")?serviceName.substring(5).split("[-.]",2)[0]:"";}
    }
    private final Context ctx;
    private final Store store;
    private final Listener listener;
    private final ExecutorService pool=Executors.newCachedThreadPool();
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,Discovered> nearby=new ConcurrentHashMap<>();
    private final Map<String,Connection> sockets=new ConcurrentHashMap<>();
    private final Set<String> connecting=ConcurrentHashMap.newKeySet();
    private volatile boolean running=false;
    private ServerSocket server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registrar;
    private NsdManager.DiscoveryListener discoverer;
    private WifiManager.MulticastLock multicast;
    private AudioEngine audio;
    private volatile String activeCall="";
    private volatile long connectedCallAt=0L;
    private volatile boolean groupCall=false;
    private volatile String currentGroup="";
    private final Set<String> groupCallMembers=ConcurrentHashMap.newKeySet();
    private final Set<String> groupCallInvited=ConcurrentHashMap.newKeySet();
    private WifiManager.WifiLock wifiLock;
    private volatile long lastReconnect=0;

    public LocalNode(Context c,Store s,Listener l){ctx=c.getApplicationContext();store=s;listener=l;}
    public boolean isConnected(String id){ Connection c=sockets.get(id);return c!=null&&!c.socket.isClosed(); }
    public boolean supportsAck(String id){Connection c=sockets.get(id);return c!=null && c.modern;}
    public List<Discovered> nearby(){return new ArrayList<>(nearby.values());}
    public int connections(){return sockets.size();}
    public boolean isCalling(){return !activeCall.isEmpty();}
    public boolean groupCallActive(){return groupCall;}
    public String activeGroupId(){return currentGroup;}
    public long callDuration(){return connectedCallAt==0 ? -1 : (System.currentTimeMillis()-connectedCallAt)/1000;}
    public int groupCallParticipants(){return groupCallMembers.size()+1;}
    public synchronized void start(){
        if(running)return;running=true;
        try{
            server=new ServerSocket(0);server.setReuseAddress(true);
            nsd=(NsdManager)ctx.getSystemService(Context.NSD_SERVICE);
            WifiManager wifi=(WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wifi!=null){
                multicast=wifi.createMulticastLock("nodo-mdns");multicast.setReferenceCounted(false);multicast.acquire();
                wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"nodo-network-active");
                wifiLock.setReferenceCounted(false);wifiLock.acquire();
            }
            pool.execute(()->{while(running){try{Socket s=server.accept();pool.execute(()->handleSocket(s,false));}catch(IOException ex){if(running)listener.status("Red local: "+ex.getMessage());}}});
            NsdServiceInfo info=new NsdServiceInfo();
            info.setServiceName("NODO-"+store.id().substring(0,8));info.setServiceType(SERVICE);info.setPort(server.getLocalPort());
            registrar=new NsdManager.RegistrationListener(){
                @Override public void onServiceRegistered(NsdServiceInfo s){listener.status("Visible en la red Wi-Fi local");}
                @Override public void onRegistrationFailed(NsdServiceInfo s,int error){listener.status("No se pudo anunciar NODO: "+error);}
                @Override public void onServiceUnregistered(NsdServiceInfo s){}
                @Override public void onUnregistrationFailed(NsdServiceInfo s,int error){}
            };
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registrar);
            discoverer=new NsdManager.DiscoveryListener(){
                @Override public void onDiscoveryStarted(String t){listener.status("Buscando teléfonos NODO en Wi-Fi local…");}
                @Override public void onServiceFound(NsdServiceInfo s){
                    String name=s.getServiceName();
                    if(!name.startsWith("NODO-")||name.startsWith("NODO-"+store.id().substring(0,8)))return;
                    try{nsd.resolveService(s,new NsdManager.ResolveListener(){
                        @Override public void onResolveFailed(NsdServiceInfo r,int err){listener.status("No se pudo resolver "+name);}
                        @Override public void onServiceResolved(NsdServiceInfo r){
                            InetAddress host=r.getHost();if(host==null||r.getPort()<1)return;
                            Discovered d=new Discovered(name,host.getHostAddress(),r.getPort());
                            nearby.put(name,d);listener.changed();
                            String peerId=store.matchShort(d.shortId());
                            // Only one side initiates reconnect to avoid two competing sockets.
                            if(peerId!=null && store.autoConnect() && store.id().compareTo(peerId)<0 && !isConnected(peerId))connect(d);
                        }
                    });}catch(Exception ex){listener.status("Descubrimiento: "+ex.getMessage());}
                }
                @Override public void onServiceLost(NsdServiceInfo s){nearby.remove(s.getServiceName());listener.changed();}
                @Override public void onDiscoveryStopped(String t){}
                @Override public void onStartDiscoveryFailed(String t,int e){listener.status("Búsqueda fallida: "+e);}
                @Override public void onStopDiscoveryFailed(String t,int e){}
            };
            nsd.discoverServices(SERVICE,NsdManager.PROTOCOL_DNS_SD,discoverer);
            monitor.scheduleAtFixedRate(()->{
                if(!running)return;
                long now=System.currentTimeMillis();
                for(Connection cn:sockets.values()){
                    if(now-cn.lastRx>45000){try{cn.socket.close();}catch(Exception ignored){}}
                    else send(cn.peer.id,"ping","");
                }
                // Android NSD may not emit another onServiceFound after a socket drops.
                // Try cached mDNS endpoints periodically; only one side initiates.
                if(now-lastReconnect>6000){
                    lastReconnect=now;
                    for(Discovered d:nearby.values()){
                        String id=store.matchShort(d.shortId());
                        if(id!=null && store.autoConnect() && store.id().compareTo(id)<0
                            && !isConnected(id))connect(d);
                    }
                }

            },5,5,TimeUnit.SECONDS);
        }catch(Exception ex){listener.status("Activa Wi-Fi local: "+ex.getMessage());stop();}
    }
    public void connect(Discovered d){
        if(d==null||!running||!connecting.add(d.serviceName))return;
        pool.execute(()->{
            try{
                Socket s=new Socket();s.connect(new InetSocketAddress(d.host,d.port),5000);s.setTcpNoDelay(true);
                handleSocket(s,true);
            }catch(Exception ex){listener.status("No se pudo enlazar con "+d.serviceName+": "+ex.getMessage());}
            finally{connecting.remove(d.serviceName);listener.changed();}
        });
    }
    private void handleSocket(Socket s,boolean outgoing){
        String remoteId=null;Connection cn=null;
        try{
            s.setSoTimeout(60000);
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            JSONObject hello=new JSONObject(); hello.put("type","hello");hello.put("id",store.id());hello.put("name",store.name());hello.put("avatar",avatarBase64());hello.put("proto",2);
            if(outgoing)write(out,hello,null);
            JSONObject other=read(in);
            if(!"hello".equals(other.optString("type")))throw new IOException("Saludo no válido");
            remoteId=other.optString("id");String remoteName=other.optString("name","Usuario NODO");
            int remoteProto=other.optInt("proto",1);
            if(!remoteId.matches("[a-fA-F0-9]{32}") || remoteId.equals(store.id()))throw new IOException("Identidad no válida");
            saveAvatar(remoteId,other.optString("avatar",""));
            if(!outgoing)write(out,hello,null);
            boolean accepted=outgoing || store.trusted(remoteId);
            if(!accepted){
                CountDownLatch latch=new CountDownLatch(1);final boolean[] ans={false};
                listener.pairing(remoteName,ok->{ans[0]=ok;latch.countDown();});
                if(!latch.await(45,TimeUnit.SECONDS))throw new IOException("Solicitud caducada");
                accepted=ans[0];
            }
            JSONObject response=new JSONObject();response.put("type","pair");response.put("ok",accepted);
            write(out,response,null);
            JSONObject peerResponse=read(in);
            if(!"pair".equals(peerResponse.optString("type")) || !peerResponse.optBoolean("ok") || !accepted) {
                listener.status("Enlace rechazado o sin confirmar");return;
            }
            store.trust(remoteId,remoteName);
            // Keep the already established socket if simultaneous handshakes occur.
            Connection old=sockets.get(remoteId);
            if(old!=null&&!old.socket.isClosed() && System.currentTimeMillis()-old.lastRx<40000)return;
            if(old!=null){try{old.socket.close();}catch(Exception ignored){}}
            cn=new Connection(s,in,out,new Peer(remoteId,remoteName),remoteProto>=2);
            sockets.put(remoteId,cn);listener.changed();listener.status("Conectado a "+remoteName);
            s.setSoTimeout(0);
            while(running&&!s.isClosed()){
                JSONObject m=read(in);cn.lastRx=System.currentTimeMillis();String type=m.optString("type");
                if(type.equals("text")){
                    String messageId=m.optString("mid","");
                    listener.incoming(cn.peer,"text",m.optString("body"),messageId,"");
                    if(!messageId.isEmpty() && cn.modern){
                        JSONObject ack=new JSONObject();ack.put("type","ack");ack.put("mid",messageId);
                        sendJson(cn.peer.id,ack);
                    }
                }else if(type.equals("ack")){
                    listener.delivered(cn.peer,m.optString("mid"));
                }else if(type.equals("groupInfo")){
                    listener.groupKnown(cn.peer,m.optString("gid"),m.optString("gname"),idsFrom(m.optJSONArray("members")));
                }else if(type.equals("groupText")){
                    String mid=m.optString("mid","");
                    listener.groupIncoming(cn.peer,m.optString("gid"),m.optString("gname"),idsFrom(m.optJSONArray("members")),mid,m.optString("body"));
                }else if(type.equals("groupOffer")){
                    if(activeCall.isEmpty()){
                        groupCall=true;currentGroup=m.optString("gid");activeCall=cn.peer.id;
                        connectedCallAt=0;groupCallMembers.clear();groupCallInvited.clear();
                        groupCallMembers.add(cn.peer.id);
                        listener.groupCallOffer(cn.peer,currentGroup,m.optString("gname","Grupo NODO"));
                    }else send(cn.peer.id,"groupEnd","");
                }else if(type.equals("groupAccept")){
                    if(groupCall && activeCall.startsWith("group:") && currentGroup.equals(m.optString("gid"))){
                        groupCallMembers.add(cn.peer.id);
                        groupCallInvited.remove(cn.peer.id);
                        if(connectedCallAt==0)connectedCallAt=System.currentTimeMillis();
                        listener.groupCallChanged();
                    }
                }else if(type.equals("groupLeave")){
                    groupCallMembers.remove(cn.peer.id);groupCallInvited.remove(cn.peer.id);
                    if(groupCall && activeCall.equals(cn.peer.id)){
                        stopAudio();listener.callEnded(cn.peer);
                    }else listener.groupCallChanged();
                }else if(type.equals("groupEnd")){
                    if(groupCall){stopAudio();listener.callEnded(cn.peer);}
                }else if(type.equals("groupPcm")){
                    int len=m.optInt("size",0);if(len<1||len>4096)throw new IOException("Audio inválido");
                    byte[] pcm=new byte[len];in.readFully(pcm);
                    if(groupCall && audio!=null && groupCallMembers.contains(cn.peer.id)){
                        audio.play(pcm);
                        if(activeCall.startsWith("group:")){
                            // Simple star relay, maximum four listeners: no central infrastructure.
                            for(String member:groupCallMembers)if(!member.equals(cn.peer.id))sendPcm(member,"groupPcm",pcm);
                        }
                    }
                }else if(type.equals("file")){
                    long size=m.optLong("size",-1);if(size<0||size>MAX_FILE)throw new IOException("Archivo demasiado grande");
                    String name=m.optString("name","archivo").replaceAll("[^A-Za-z0-9._-]","_");
                    if(name.length()>80)name=name.substring(name.length()-80);
                    File folder=new File(ctx.getFilesDir(),"received");folder.mkdirs();
                    File target=new File(folder,UUID.randomUUID()+"_"+name);
                    try(FileOutputStream fos=new FileOutputStream(target)){
                        byte[] block=new byte[8192];long left=size;
                        while(left>0){int n=in.read(block,0,(int)Math.min(left,block.length));if(n<0)throw new EOFException();fos.write(block,0,n);left-=n;}
                    }
                    listener.incoming(cn.peer,m.optBoolean("voice")?"voice":"file","",target.getAbsolutePath(),name);
                }else if(type.equals("ping")){
                    send(cn.peer.id,"pong","");
                }else if(type.equals("pong")){
                    // Heartbeat reply.
                }else if(type.equals("callOffer")){
                    if(activeCall.isEmpty()){activeCall=cn.peer.id;listener.callOffer(cn.peer);}
                    else send(cn.peer.id,"callEnd","");
                }else if(type.equals("callAccept")){
                    if(activeCall.equals(cn.peer.id)){
                        connectedCallAt=System.currentTimeMillis();listener.callAccepted(cn.peer);
                    }
                }else if(type.equals("callEnd")){
                    if(activeCall.equals(cn.peer.id)){stopAudio();listener.callEnded(cn.peer);}
                }else if(type.equals("pcm")){
                    if(audio!=null&&activeCall.equals(cn.peer.id)){
                        int len=m.optInt("size",0);if(len<1||len>4096)throw new IOException("Audio inválido");
                        byte[] pcm=new byte[len];in.readFully(pcm);audio.play(pcm);
                    }else{
                        int len=m.optInt("size",0);if(len<1||len>4096)throw new IOException("Audio inválido");
                        byte[] discard=new byte[len];in.readFully(discard);
                    }
                }else throw new IOException("Tipo no permitido");
            }
        }catch(Exception e){if(running && remoteId!=null)listener.status("Enlace cerrado: "+e.getMessage());}
        finally{
            if(cn!=null){
                sockets.remove(cn.peer.id,cn);
                if(groupCall){
                    groupCallMembers.remove(cn.peer.id);groupCallInvited.remove(cn.peer.id);
                    if(activeCall.equals(cn.peer.id)){stopAudio();listener.callEnded(cn.peer);}
                    else listener.groupCallChanged();
                }else if(activeCall.equals(cn.peer.id)){stopAudio();listener.callEnded(cn.peer);}
                listener.changed();
            }
            try{s.close();}catch(IOException ignored){}
        }
    }
    private String avatarBase64(){
        try{File f=new File(store.avatar());if(!f.isFile())return "";
            Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath());if(b==null)return "";
            Bitmap scaled=Bitmap.createScaledBitmap(b,100,100,true);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,65,bytes);
            if(bytes.size()>40000)return "";return Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
        }catch(Exception ignored){return "";}
    }
    private void saveAvatar(String peerId,String data){
        if(data.isEmpty()||data.length()>60000)return;
        try{File dir=new File(ctx.getFilesDir(),"avatars");dir.mkdirs();
            byte[] bytes=Base64.decode(data,Base64.DEFAULT);
            if(bytes.length>40000)return;
            Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(b==null)return;
            try(FileOutputStream fos=new FileOutputStream(new File(dir,peerId+".jpg"))){fos.write(bytes);}
        }catch(Exception ignored){}
    }
    private static JSONObject read(DataInputStream in)throws Exception{
        int n=in.readInt();if(n<1||n>131072)throw new IOException("Encabezado demasiado grande");
        byte[] b=new byte[n];in.readFully(b);return new JSONObject(new String(b,StandardCharsets.UTF_8));
    }
    private static void write(DataOutputStream out,JSONObject j,byte[] payload)throws Exception{
        byte[] header=j.toString().getBytes(StandardCharsets.UTF_8);
        out.writeInt(header.length);out.write(header);
        if(payload!=null)out.write(payload);
        out.flush();
    }
    private static final class Connection {
        final Socket socket;final DataInputStream in;final DataOutputStream out;final Peer peer;
        final Object writeLock=new Object();final boolean modern;volatile long lastRx=System.currentTimeMillis();
        Connection(Socket s,DataInputStream i,DataOutputStream o,Peer p,boolean modern){socket=s;in=i;out=o;peer=p;this.modern=modern;}
    }
    private static List<String> idsFrom(org.json.JSONArray a){
        List<String> ids=new ArrayList<>();if(a==null)return ids;
        for(int i=0;i<a.length();i++){String id=a.optString(i,"");if(id.matches("[a-f0-9]{32}"))ids.add(id);}
        return ids;
    }
    private static org.json.JSONArray idsArray(List<String> ids){
        org.json.JSONArray a=new org.json.JSONArray();for(String s:ids)a.put(s);return a;
    }
    private boolean sendJson(String id,JSONObject obj){
        Connection cn=sockets.get(id);if(cn==null || cn.socket.isClosed())return false;
        if(("groupInfo".equals(obj.optString("type")) || "groupText".equals(obj.optString("type")) || "groupOffer".equals(obj.optString("type"))) && !cn.modern)return false;
        try{synchronized(cn.writeLock){write(cn.out,obj,null);}return true;}
        catch(Exception e){try{cn.socket.close();}catch(Exception ignored){}return false;}
    }
    public boolean sendText(String id,String mid,String text){
        try{JSONObject j=new JSONObject();j.put("type","text");j.put("mid",mid);j.put("body",text);return sendJson(id,j);}
        catch(Exception e){return false;}
    }
    public void sendGroupInfo(String gid,String name,List<String> members){
        try{JSONObject j=new JSONObject();j.put("type","groupInfo");j.put("gid",gid);
            j.put("gname",name);j.put("members",idsArray(members));
            for(String id:members)if(!id.equals(store.id()))sendJson(id,j);
        }catch(Exception ignored){}
    }
    public int sendGroupText(String gid,String name,List<String> members,String mid,String text){
        int count=0;try{JSONObject j=new JSONObject();j.put("type","groupText");j.put("gid",gid);
            j.put("gname",name);j.put("members",idsArray(members));j.put("mid",mid);j.put("body",text);
            for(String id:members)if(!id.equals(store.id()) && sendJson(id,j))count++;
        }catch(Exception ignored){}return count;
    }
    private boolean sendPcm(String id,String type,byte[] pcm){
        Connection c=sockets.get(id);if(c==null)return false;
        try{JSONObject j=new JSONObject();j.put("type",type);j.put("size",pcm.length);
            synchronized(c.writeLock){write(c.out,j,pcm);}return true;
        }catch(Exception e){try{c.socket.close();}catch(Exception ignored){}return false;}
    }
    public boolean send(String id,String kind,String body){
        Connection cn=sockets.get(id);if(cn==null)return false;
        try{JSONObject j=new JSONObject();j.put("type",kind);if(kind.equals("text"))j.put("body",body);
            synchronized(cn.writeLock){write(cn.out,j,null);}return true;
        }catch(Exception ex){listener.status("No se pudo enviar: "+ex.getMessage());return false;}
    }
    public void sendFile(String id,File f,String filename,boolean voice,java.util.function.Consumer<Boolean> done){
        pool.execute(()->{
            Connection cn=sockets.get(id);boolean success=false;
            if(cn!=null&&f.exists()&&f.length()<=MAX_FILE){
                try{
                    JSONObject h=new JSONObject();h.put("type","file");h.put("name",filename);h.put("size",f.length());h.put("voice",voice);
                    synchronized(cn.writeLock){
                        byte[] header=h.toString().getBytes(StandardCharsets.UTF_8);
                        cn.out.writeInt(header.length);cn.out.write(header);
                        try(InputStream fs=new FileInputStream(f)){
                            byte[] bytes=new byte[8192];int n;
                            while((n=fs.read(bytes))!=-1)cn.out.write(bytes,0,n);
                        }
                        cn.out.flush();
                    }
                    success=true;
                }catch(Exception e){listener.status("Archivo no enviado: "+e.getMessage());}
            }
            done.accept(success);
        });
    }
    public void offerCall(String id){if(isConnected(id)&&activeCall.isEmpty()){activeCall=id;send(id,"callOffer","");}}
    public void acceptCall(String id){if(id.equals(activeCall)){
        connectedCallAt=System.currentTimeMillis();send(id,"callAccept","");startAudio(id);
    }}
    public void offerGroupCall(String gid,String name,List<String> members){
        if(isCalling())return;
        List<String> connected=new ArrayList<>();
        for(String id:members)if(!id.equals(store.id())&&isConnected(id)&&connected.size()<3)connected.add(id);
        if(connected.isEmpty())return;
        groupCall=true;currentGroup=gid;activeCall="group:"+gid;connectedCallAt=0;
        groupCallMembers.clear();groupCallInvited.clear();
        for(String id:connected){
            try{JSONObject msg=new JSONObject();msg.put("type","groupOffer");msg.put("gid",gid);msg.put("gname",name);
                if(sendJson(id,msg))groupCallInvited.add(id);
            }catch(Exception ignored){}
        }
        listener.groupCallChanged();
    }
    public void acceptGroupCall(String host){
        if(!groupCall||!activeCall.equals(host))return;
        try{JSONObject j=new JSONObject();j.put("type","groupAccept");j.put("gid",currentGroup);
            if(sendJson(host,j)){
                connectedCallAt=System.currentTimeMillis();startAudio(host);listener.groupCallChanged();
            }
        }catch(Exception ignored){}
    }
    public void rejectGroupCall(String host){
        if(!groupCall||!activeCall.equals(host))return;
        send(host,"groupLeave","");stopAudio();
    }
    public void rejectCall(String id){send(id,"callEnd","");if(id.equals(activeCall))stopAudio();}
    public synchronized void startAudio(String id){
        if(!id.equals(activeCall)||audio!=null)return;
        try{audio=new AudioEngine(ctx,pcm->{
            if(groupCall){
                for(String member:groupCallMembers)sendPcm(member,"groupPcm",pcm);
            }else if(!sendPcm(id,"pcm",pcm)){
                listener.status("Audio temporalmente interrumpido");
            }
        });audio.start();}catch(Exception e){audio=null;listener.status("No se pudo abrir micrófono: "+e.getMessage());endCall();}
    }
    public synchronized void stopAudio(){
        if(audio!=null){audio.stop();audio=null;}
        activeCall="";connectedCallAt=0;groupCall=false;currentGroup="";
        groupCallMembers.clear();groupCallInvited.clear();
    }
    public void mute(boolean yes){if(audio!=null)audio.mute(yes);}
    public void speaker(boolean yes){if(audio!=null)audio.speaker(yes);}
    public void endCall(){
        String id=activeCall;
        if(groupCall){
            if(id.startsWith("group:")){
                for(String member:groupCallMembers)send(member,"groupEnd","");
                for(String member:groupCallInvited)send(member,"groupEnd","");
            }else if(!id.isEmpty())send(id,"groupLeave","");
        }else if(!id.isEmpty())send(id,"callEnd","");
        stopAudio();
    }
    public synchronized void stop(){
        if(!running)return;running=false;endCall();
        if(nsd!=null){try{if(discoverer!=null)nsd.stopServiceDiscovery(discoverer);}catch(Exception ignored){}
            try{if(registrar!=null)nsd.unregisterService(registrar);}catch(Exception ignored){}}
        for(Connection cn:sockets.values())try{cn.socket.close();}catch(Exception ignored){}
        sockets.clear();nearby.clear();
        try{if(server!=null)server.close();}catch(IOException ignored){}
        if(multicast!=null&&multicast.isHeld())multicast.release();
        if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();
        monitor.shutdownNow();pool.shutdownNow();
    }
}
