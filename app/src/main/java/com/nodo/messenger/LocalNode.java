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
        void groupInvitation(Peer peer,String gid,String groupName,List<String> members);
        void groupJoined(Peer peer,String gid);
        void groupMemberKnown(Peer peer,String gid,String joinedId);
        void callConnectionChanged();
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
    private final Map<String,Long> inviteLastSent=new ConcurrentHashMap<>();
    private final Map<String,ArrayBlockingQueue<byte[]>> groupAudioFrames=new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> groupMixTask;
    private final Map<String,Connection> sockets=new ConcurrentHashMap<>();
    private final Set<String> connecting=ConcurrentHashMap.newKeySet();
    private volatile boolean running=false;
    private volatile boolean advertising=false;
    private NsdServiceInfo announcement;
    private ServerSocket server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registrar;
    private NsdManager.DiscoveryListener discoverer;
    private WifiManager.MulticastLock multicast;
    private AudioEngine audio;
    private volatile String activeCall="";
    private volatile String callSessionId="";
    // A session remains active through temporary Wi-Fi disconnections until someone hangs up.
    private volatile boolean callRecovered=false;
    private final Map<String,Long> lastContactLost=new ConcurrentHashMap<>();
    private volatile long connectedCallAt=0L;
    private volatile boolean groupCall=false;
    private volatile String currentGroup="";
    private final Set<String> groupCallMembers=ConcurrentHashMap.newKeySet();
    private final Set<String> groupCallInvited=ConcurrentHashMap.newKeySet();
    private WifiManager.WifiLock wifiLock;
    private volatile long lastReconnect=0,lastManualRetry=0;

    public LocalNode(Context c,Store s,Listener l){ctx=c.getApplicationContext();store=s;listener=l;}
    public boolean isConnected(String id){ Connection c=sockets.get(id);return c!=null&&!c.socket.isClosed(); }
    public boolean supportsAck(String id){Connection c=sockets.get(id);return c!=null && c.modern;}
    public List<Discovered> nearby(){return new ArrayList<>(nearby.values());}
    public int connections(){return sockets.size();}
    /** A manually provided hotspot/server address. Saved for reconnection when NSD is blocked. */
    public void connectLocalAddress(String host,int port){
        if(host==null || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}") || port<1 || port>65535){
            listener.status("Dirección de servidor no válida");return;
        }
        try{
            InetAddress address=InetAddress.getByName(host);
            if(!(address instanceof Inet4Address) || (!address.isSiteLocalAddress() && !address.isLinkLocalAddress())){
                listener.status("Utiliza una dirección IP privada del punto de acceso");return;
            }
        }catch(Exception e){listener.status("Dirección no válida");return;}
        String key=host+":"+port;
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("nodo_local_endpoints",Context.MODE_PRIVATE);
        Set<String> saved=new java.util.HashSet<>(prefs.getStringSet("addresses",java.util.Collections.emptySet()));
        if(saved.size()>=4&&!saved.contains(key))saved.clear();
        saved.add(key);prefs.edit().putStringSet("addresses",saved).apply();
        connect(new Discovered("MANUAL-"+key,host,port));
    }
    /** Listening on all local interfaces: regular WLAN or an Android local-only hotspot. */
    public int listeningPort(){return server==null?0:server.getLocalPort();}
    public static String localHotspotIpv4(){
        try{
            List<String> preferred=new ArrayList<>(),other=new ArrayList<>();
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null&&interfaces.hasMoreElements()){
                NetworkInterface net=interfaces.nextElement();
                if(!net.isUp()||net.isLoopback()||net.isVirtual())continue;
                String name=net.getName().toLowerCase(Locale.ROOT);
                if(name.startsWith("tun")||name.startsWith("rmnet")||name.startsWith("ccmni"))continue;
                Enumeration<InetAddress> ips=net.getInetAddresses();
                while(ips.hasMoreElements()){
                    InetAddress a=ips.nextElement();
                    if(a instanceof Inet4Address && a.isSiteLocalAddress()){
                        if(name.contains("ap")||name.contains("wlan")||name.contains("swlan")||name.contains("wifi")) preferred.add(a.getHostAddress());
                        else other.add(a.getHostAddress());
                    }
                }
            }
            preferred.addAll(other);return String.join(" / ",preferred);
        }catch(Exception ex){return "";}
    }
    public boolean isCalling(){return !activeCall.isEmpty();}
    public boolean isCallReconnecting(){
        if(activeCall.isEmpty()||connectedCallAt<=0)return false;
        if(groupCall){
            if(activeCall.startsWith("group:")){
                for(String id:groupCallMembers)if(isConnected(id))return false;
                return true;
            }
            return !isConnected(activeCall);
        }
        return !isConnected(activeCall);
    }
    public boolean groupCallActive(){return groupCall;}
    public String activeGroupId(){return currentGroup;}
    public long callDuration(){return connectedCallAt==0 ? -1 : (System.currentTimeMillis()-connectedCallAt)/1000;}
    public int groupCallParticipants(){return groupCallMembers.size()+1;}
    public synchronized void start(){
        if(running)return;running=true;
        try{
            try{server=new ServerSocket(48617);}catch(BindException inUse){server=new ServerSocket(0);}
            server.setReuseAddress(true);
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
            announcement=info;
            registrar=new NsdManager.RegistrationListener(){
                @Override public void onServiceRegistered(NsdServiceInfo s){listener.status("Visible en la red Wi-Fi local");}
                @Override public void onRegistrationFailed(NsdServiceInfo s,int error){listener.status("No se pudo anunciar NODO: "+error);}
                @Override public void onServiceUnregistered(NsdServiceInfo s){}
                @Override public void onUnregistrationFailed(NsdServiceInfo s,int error){}
            };
            if(store.discoverable())setDiscoverable(true);
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
                            if(peerId!=null && store.autoConnect() && !isConnected(peerId)
                                && (store.id().compareTo(peerId)<0 || System.currentTimeMillis()-lastContactLost.getOrDefault(peerId,0L)>18000))connect(d);
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
                // Manual local-IP fallback for hotspot devices whose mDNS discovery is filtered.
                if(now-lastManualRetry>13000){
                    lastManualRetry=now;
                    android.content.SharedPreferences pref=ctx.getSharedPreferences("nodo_local_endpoints",Context.MODE_PRIVATE);
                    for(String target:pref.getStringSet("addresses",java.util.Collections.emptySet())){
                        int cut=target.lastIndexOf(':');if(cut<1)continue;
                        try{
                            String host=target.substring(0,cut);int port=Integer.parseInt(target.substring(cut+1));
                            // Never initiate another connection while the same peer's socket is alive.
                            boolean alive=false;
                            for(Connection c:sockets.values())if(c.socket!=null && !c.socket.isClosed()
                                    && host.equals(c.socket.getInetAddress().getHostAddress())){alive=true;break;}
                            if(!alive) connect(new Discovered("MANUAL-"+target,host,port));
                        }catch(Exception ignored){}
                    }
                }
                // Android NSD may not emit another onServiceFound after a socket drops.
                // Try cached mDNS endpoints periodically; only one side initiates.
                if(now-lastReconnect>6000){
                    lastReconnect=now;
                    for(Discovered d:nearby.values()){
                        String id=store.matchShort(d.shortId());
                        if(id!=null && store.autoConnect() && !isConnected(id)
                            && (store.id().compareTo(id)<0 || now-lastContactLost.getOrDefault(id,0L)>18000))connect(d);
                    }
                }

            },5,5,TimeUnit.SECONDS);
        }catch(Exception ex){listener.status("Activa Wi-Fi local: "+ex.getMessage());stop();}
    }
    /** Toggle mDNS advertisement without destroying active authorized sockets. */
    public synchronized void setDiscoverable(boolean enabled){
        store.discoverable(enabled);
        if(!running||nsd==null||registrar==null||announcement==null)return;
        if(enabled&&!advertising){
            try{nsd.registerService(announcement,NsdManager.PROTOCOL_DNS_SD,registrar);advertising=true;}
            catch(Exception e){listener.status("No se pudo anunciar NODO: "+e.getMessage());}
        }else if(!enabled&&advertising){
            try{nsd.unregisterService(registrar);}catch(Exception ignored){}
            advertising=false;
        }
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
            JSONObject hello=new JSONObject(); hello.put("type","hello");hello.put("id",store.id());hello.put("name",store.name());hello.put("avatar",avatarBase64());hello.put("proto",3);hello.put("lastSeen",store.shareLastSeen()?System.currentTimeMillis():0L);
            if(outgoing)write(out,hello,null);
            JSONObject other=read(in);
            if(!"hello".equals(other.optString("type")))throw new IOException("Saludo no válido");
            remoteId=other.optString("id");String remoteName=other.optString("name","Usuario NODO");
            int remoteProto=other.optInt("proto",1);
            if(!remoteId.matches("[a-fA-F0-9]{32}") || remoteId.equals(store.id()))throw new IOException("Identidad no válida");
            saveAvatar(remoteId,other.optString("avatar",""));
            store.peerLastSeen(remoteId,other.optLong("lastSeen",0L));
            if(!outgoing)write(out,hello,null);
            if(!outgoing&&!store.trusted(remoteId)&&!store.allowNewPairing())
                throw new IOException("El usuario no acepta nuevas solicitudes de enlace");
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
            cn=new Connection(s,in,out,new Peer(remoteId,remoteName),remoteProto);
            sockets.put(remoteId,cn);lastContactLost.remove(remoteId);
            listener.changed();listener.status("Conectado a "+remoteName);
            // Recover an accepted audio session; do not call again or reset its timer.
            if(remoteProto>=3 && connectedCallAt>0 && !callSessionId.isEmpty()){
                if(!groupCall && activeCall.equals(remoteId))sendCallResume(remoteId);
                else if(groupCall && groupCallMembers.contains(remoteId))sendGroupResume(remoteId);
            }
            listener.callConnectionChanged();
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
                    String gid=m.optString("gid");
                    if(store.groups().contains(gid) && store.trusted(cn.peer.id))
                        listener.groupKnown(cn.peer,gid,m.optString("gname"),idsFrom(m.optJSONArray("members")));
                }else if(type.equals("groupInvite")){
                    String gid=m.optString("gid");List<String> members=idsFrom(m.optJSONArray("members"));
                    if(store.trusted(cn.peer.id)&&gid.matches("[a-f0-9]{32}")&&members.contains(store.id())&&members.contains(cn.peer.id)){
                        if(store.groups().contains(gid))sendGroupJoin(cn.peer.id,gid);
                        else listener.groupInvitation(cn.peer,gid,m.optString("gname","Grupo NODO"),members);
                    }
                }else if(type.equals("groupDecline")){
                    String gid=m.optString("gid","");
                    if(gid.matches("[a-f0-9]{32}")&&store.groups().contains(gid))
                        store.groupDeclined(gid,cn.peer.id);
                }else if(type.equals("groupMemberJoined")){
                    String gid=m.optString("gid","");String member=m.optString("id","");
                    if(gid.matches("[a-f0-9]{32}")&&member.matches("[a-f0-9]{32}")
                        && store.groups().contains(gid)&&store.groupOwner(gid).equals(cn.peer.id)
                        && store.groupMembers(gid).contains(member))
                        listener.groupMemberKnown(cn.peer,gid,member);
                }else if(type.equals("groupJoin")){
                    String gid=m.optString("gid","");
                    if(store.trusted(cn.peer.id) && gid.matches("[a-f0-9]{32}") && store.groups().contains(gid)
                      && store.groupMembers(gid).contains(cn.peer.id))listener.groupJoined(cn.peer,gid);
                }else if(type.equals("groupText")){
                    String mid=m.optString("mid","");String gid=m.optString("gid","");
                    if(store.groups().contains(gid)&&store.groupApproved(gid,cn.peer.id))
                        listener.groupIncoming(cn.peer,gid,m.optString("gname"),idsFrom(m.optJSONArray("members")),mid,m.optString("body"));
                }else if(type.equals("groupOffer")){
                    if(activeCall.isEmpty()){
                        String gid=m.optString("gid","");
                        if(!store.groups().contains(gid)||!store.groupApproved(gid,store.id())){
                            send(cn.peer.id,"groupEnd","");continue;
                        }
                        groupCall=true;currentGroup=gid;activeCall=cn.peer.id;
                        callSessionId=m.optString("cid","");connectedCallAt=0;groupCallMembers.clear();groupCallInvited.clear();
                        groupCallMembers.add(cn.peer.id);
                        listener.groupCallOffer(cn.peer,currentGroup,m.optString("gname","Grupo NODO"));
                    }else send(cn.peer.id,"groupEnd","");
                }else if(type.equals("groupAccept")){
                    if(groupCall && activeCall.startsWith("group:") && currentGroup.equals(m.optString("gid"))){
                        if(store.groupApproved(currentGroup,cn.peer.id)){
                            groupCallMembers.add(cn.peer.id);
                            groupCallInvited.remove(cn.peer.id);
                            if(connectedCallAt==0)connectedCallAt=System.currentTimeMillis();
                        }
                        listener.groupCallChanged();
                    }
                }else if(type.equals("groupLeave")){
                    groupCallMembers.remove(cn.peer.id);groupCallInvited.remove(cn.peer.id);
                    if(groupCall && activeCall.equals(cn.peer.id)){
                        stopAudio();listener.callEnded(cn.peer);
                    }else listener.groupCallChanged();
                }else if(type.equals("groupEnd")){
                    if(groupCall && (m.optString("cid","").isEmpty()
                        || callSessionId.equals(m.optString("cid")))){
                        stopAudio();listener.callEnded(cn.peer);
                    }
                }else if(type.equals("callResume")){
                    String cid=m.optString("cid","");
                    if(!cid.isEmpty() && cid.equals(callSessionId)&&!groupCall
                        && activeCall.equals(cn.peer.id)&&connectedCallAt>0){
                        JSONObject reply=new JSONObject();reply.put("type","callResumeAck");reply.put("cid",cid);
                        sendJson(cn.peer.id,reply);listener.callConnectionChanged();
                    }else send(cn.peer.id,"callEnd","");
                }else if(type.equals("callResumeAck")){
                    if(!groupCall && activeCall.equals(cn.peer.id)
                        && callSessionId.equals(m.optString("cid")))listener.callConnectionChanged();
                }else if(type.equals("groupResume")){
                    String cid=m.optString("cid","");
                    if(groupCall && !cid.isEmpty() && cid.equals(callSessionId)
                        && currentGroup.equals(m.optString("gid")) && groupCallMembers.contains(cn.peer.id)){
                        JSONObject reply=new JSONObject();reply.put("type","groupResumeAck");reply.put("cid",cid);
                        sendJson(cn.peer.id,reply);listener.callConnectionChanged();
                    }else if(!cid.isEmpty()){
                        JSONObject ending=new JSONObject();ending.put("type","groupEnd");
                        ending.put("cid",cid);sendJson(cn.peer.id,ending);
                    }
                }else if(type.equals("groupResumeAck")){
                    if(groupCall && callSessionId.equals(m.optString("cid")))listener.callConnectionChanged();
                }else if(type.equals("groupPcm")){
                    int len=m.optInt("size",0);if(len<1||len>4096)throw new IOException("Audio inválido");
                    byte[] pcm=new byte[len];in.readFully(pcm);
                    if(groupCall && audio!=null && groupCallMembers.contains(cn.peer.id)){
                        if(activeCall.startsWith("group:"))queueGroupFrame(cn.peer.id,pcm);
                        else audio.play(pcm); // Participants receive the host's mixed stream.
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
                    if(activeCall.isEmpty()){
                        activeCall=cn.peer.id;callSessionId=m.optString("cid","");
                        listener.callOffer(cn.peer);
                    }
                    else send(cn.peer.id,"callEnd","");
                }else if(type.equals("callAccept")){
                    if(activeCall.equals(cn.peer.id) && (callSessionId.isEmpty()
                        || callSessionId.equals(m.optString("cid","")))){
                        if(connectedCallAt==0)connectedCallAt=System.currentTimeMillis();
                        listener.callAccepted(cn.peer);
                    }
                }else if(type.equals("callEnd")){
                    if(activeCall.equals(cn.peer.id) && (m.optString("cid","").isEmpty()
                        || m.optString("cid","").equals(callSessionId))){stopAudio();listener.callEnded(cn.peer);}
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
                lastContactLost.put(cn.peer.id,System.currentTimeMillis());
                // A disconnected socket is not an intentional hang-up.
                // Preserve the accepted session/timer until a participant explicitly ends it.
                if(groupCall)listener.groupCallChanged();
                if(activeCall.equals(cn.peer.id)||groupCallMembers.contains(cn.peer.id))
                    listener.callConnectionChanged();
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
        final Object writeLock=new Object();final boolean modern;final int protocol;volatile long lastRx=System.currentTimeMillis();
        Connection(Socket s,DataInputStream i,DataOutputStream o,Peer p,int protocol){socket=s;in=i;out=o;peer=p;this.protocol=protocol;modern=protocol>=2;}
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
    public void sendGroupInvitations(String gid,String name,List<String> members){
        if(!store.groupOwner(gid).equals(store.id()))return;
        try{
            JSONObject j=new JSONObject();j.put("type","groupInvite");j.put("gid",gid);
            j.put("gname",name);j.put("members",idsArray(members));
            for(String id:members)if(!id.equals(store.id())&&isConnected(id)
                && !store.groupApproved(gid,id)&&!store.groupDeclined(gid,id)){
                String key=gid+":"+id;long now=System.currentTimeMillis();
                if(now-inviteLastSent.getOrDefault(key,0L)>60000 && sendJson(id,j))
                    inviteLastSent.put(key,now);
            }
        }catch(Exception ignored){}
    }
    public void sendGroupDecline(String inviter,String gid){
        try{JSONObject j=new JSONObject();j.put("type","groupDecline");j.put("gid",gid);sendJson(inviter,j);}
        catch(Exception ignored){}
    }
    public void announceGroupMember(String gid,String joinedId){
        try{JSONObject m=new JSONObject();m.put("type","groupMemberJoined");m.put("gid",gid);m.put("id",joinedId);
            for(String member:store.groupMembers(gid))if(!member.equals(store.id())&&store.groupApproved(gid,member))
                sendJson(member,m);
        }catch(Exception ignored){}
    }
    public void sendGroupJoin(String inviter,String gid){
        try{JSONObject j=new JSONObject();j.put("type","groupJoin");j.put("gid",gid);sendJson(inviter,j);}
        catch(Exception ignored){}
    }
    public void sendGroupInfo(String gid,String name,List<String> members){
        try{JSONObject j=new JSONObject();j.put("type","groupInfo");j.put("gid",gid);
            j.put("gname",name);j.put("members",idsArray(members));
            for(String id:members)if(!id.equals(store.id())&&store.groupApproved(gid,id))sendJson(id,j);
        }catch(Exception ignored){}
    }
    public int sendGroupText(String gid,String name,List<String> members,String mid,String text){
        int count=0;try{JSONObject j=new JSONObject();j.put("type","groupText");j.put("gid",gid);
            j.put("gname",name);j.put("members",idsArray(members));j.put("mid",mid);j.put("body",text);
            for(String id:members)if(!id.equals(store.id()) && store.groupApproved(gid,id) && sendJson(id,j))count++;
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
    private void sendCallResume(String id){
        try{JSONObject j=new JSONObject();j.put("type","callResume");j.put("cid",callSessionId);sendJson(id,j);}
        catch(Exception ignored){}
    }
    private void sendGroupResume(String id){
        try{JSONObject j=new JSONObject();j.put("type","groupResume");j.put("gid",currentGroup);
            j.put("cid",callSessionId);sendJson(id,j);}
        catch(Exception ignored){}
    }
    public void offerCall(String id){
        if(isConnected(id)&&activeCall.isEmpty()){
            activeCall=id;callSessionId=UUID.randomUUID().toString();
            try{JSONObject j=new JSONObject();j.put("type","callOffer");j.put("cid",callSessionId);
                if(!sendJson(id,j))stopAudio();}
            catch(Exception ignored){stopAudio();}
        }
    }
    public void acceptCall(String id){if(id.equals(activeCall)){
        if(connectedCallAt==0)connectedCallAt=System.currentTimeMillis();
        try{JSONObject j=new JSONObject();j.put("type","callAccept");j.put("cid",callSessionId);
            sendJson(id,j);}catch(Exception ignored){}
        startAudio(id);
    }}
    public void offerGroupCall(String gid,String name,List<String> members){
        if(isCalling())return;
        List<String> connected=new ArrayList<>();
        for(String id:members)if(!id.equals(store.id())&&isConnected(id)
            && store.groupApproved(gid,id)&&connected.size()<3)connected.add(id);
        if(connected.isEmpty())return;
        groupCall=true;currentGroup=gid;activeCall="group:"+gid;connectedCallAt=0;
        callSessionId=UUID.randomUUID().toString();
        groupCallMembers.clear();groupCallInvited.clear();
        for(String id:connected){
            try{JSONObject msg=new JSONObject();msg.put("type","groupOffer");msg.put("gid",gid);msg.put("gname",name);msg.put("cid",callSessionId);
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
    private void queueGroupFrame(String id,byte[] pcm){
        if(pcm==null||pcm.length!=640)return;
        ArrayBlockingQueue<byte[]> frames=groupAudioFrames.computeIfAbsent(id,k->new ArrayBlockingQueue<>(8));
        if(!frames.offer(pcm)){
            frames.poll();frames.offer(pcm); // Drop oldest frame to keep latency bounded.
        }
    }
    private static byte[] mixFrames(Collection<byte[]> frames){
        byte[] out=new byte[640];if(frames.isEmpty())return out;
        for(int i=0;i<640;i+=2){
            int total=0,count=0;
            for(byte[] frame:frames){
                if(frame==null||frame.length<640)continue;
                total+=(short)((frame[i]&0xff)|(frame[i+1]<<8));count++;
            }
            int sample=count==0?0:total/Math.max(1,(int)Math.ceil(Math.sqrt(count)));
            sample=Math.max(-32768,Math.min(32767,sample));
            out[i]=(byte)sample;out[i+1]=(byte)(sample>>>8);
        }
        return out;
    }
    private void mixGroupAudio(){
        if(!groupCall || !activeCall.startsWith("group:") || audio==null)return;
        Map<String,byte[]> slice=new HashMap<>();
        for(Map.Entry<String,ArrayBlockingQueue<byte[]>> e:groupAudioFrames.entrySet()){
            byte[] data=e.getValue().poll();if(data!=null)slice.put(e.getKey(),data);
        }
        // Host hears only the other participants. Each guest hears everyone except themselves.
        List<byte[]> local=new ArrayList<>();
        for(Map.Entry<String,byte[]> e:slice.entrySet())if(!e.getKey().equals("self"))local.add(e.getValue());
        if(!local.isEmpty())audio.play(mixFrames(local));
        for(String participant:groupCallMembers){
            if(!isConnected(participant))continue;
            List<byte[]> remote=new ArrayList<>();
            for(Map.Entry<String,byte[]> e:slice.entrySet())if(!e.getKey().equals(participant))remote.add(e.getValue());
            if(!remote.isEmpty())sendPcm(participant,"groupPcm",mixFrames(remote));
        }
    }
    public synchronized void startAudio(String id){
        if(!id.equals(activeCall)||audio!=null)return;
        try{audio=new AudioEngine(ctx,pcm->{
            if(groupCall){
                if(activeCall.startsWith("group:"))queueGroupFrame("self",pcm);
                else if(isConnected(activeCall))sendPcm(activeCall,"groupPcm",pcm);
            }else if(isConnected(id))sendPcm(id,"pcm",pcm);
        });audio.start();
            if(groupCall && activeCall.startsWith("group:") && groupMixTask==null)
                groupMixTask=monitor.scheduleAtFixedRate(this::mixGroupAudio,20,20,TimeUnit.MILLISECONDS);
        }catch(Exception e){audio=null;listener.status("No se pudo abrir micrófono: "+e.getMessage());endCall();}
    }
    public synchronized void stopAudio(){
        if(groupMixTask!=null){groupMixTask.cancel(false);groupMixTask=null;}
        groupAudioFrames.clear();
        if(audio!=null){audio.stop();audio=null;}
        activeCall="";callSessionId="";connectedCallAt=0;groupCall=false;currentGroup="";
        groupCallMembers.clear();groupCallInvited.clear();
    }
    public void mute(boolean yes){if(audio!=null)audio.mute(yes);}
    public void speaker(boolean yes){if(audio!=null)audio.speaker(yes);}
    private void sendGroupEnd(String id){
        try{JSONObject msg=new JSONObject();msg.put("type","groupEnd");msg.put("cid",callSessionId);
            sendJson(id,msg);}catch(Exception ignored){}
    }
    public void endCall(){
        String id=activeCall;
        if(groupCall){
            if(id.startsWith("group:")){
                for(String member:groupCallMembers)sendGroupEnd(member);
                for(String member:groupCallInvited)sendGroupEnd(member);
            }else if(!id.isEmpty())send(id,"groupLeave","");
        }else if(!id.isEmpty()){
            try{JSONObject m=new JSONObject();m.put("type","callEnd");m.put("cid",callSessionId);sendJson(id,m);}
            catch(Exception ignored){}
        }
        stopAudio();
    }
    public synchronized void stop(){
        if(!running)return;running=false;endCall();
        if(nsd!=null){try{if(discoverer!=null)nsd.stopServiceDiscovery(discoverer);}catch(Exception ignored){}
            try{if(advertising&&registrar!=null)nsd.unregisterService(registrar);}catch(Exception ignored){}
            advertising=false;}
        for(Connection cn:sockets.values())try{cn.socket.close();}catch(Exception ignored){}
        sockets.clear();nearby.clear();
        try{if(server!=null)server.close();}catch(IOException ignored){}
        if(multicast!=null&&multicast.isHeld())multicast.release();
        if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();
        monitor.shutdownNow();pool.shutdownNow();
    }
}
