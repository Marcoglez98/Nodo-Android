package com.nodo.messenger;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.media.PlaybackParams;
import android.text.InputType;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import com.nodo.messenger.ui.NodoTheme;
import com.nodo.messenger.ui.NodoBottomBar;
import com.nodo.messenger.data.ChatRepository;
import com.nodo.messenger.domain.MessageRecord;
import com.nodo.messenger.transport.NearbyTransport;
import com.nodo.messenger.transport.LanTransport;
import com.nodo.messenger.transport.PlannedTransport;

/** First alpha: a native UI, LAN discovery, user-approved pairing, chat, files, notes and voice calls. */
public final class MainActivity extends Activity implements LocalNode.Listener {
    private static final int GREEN=NodoTheme.EMERALD, DARK=NodoTheme.INK, MUTED=NodoTheme.SECONDARY;
    private static final int PALE=NodoTheme.MINT, BG=NodoTheme.BACKGROUND, BLUE=NodoTheme.BLUE;
    private static final int REQUEST_DOC=41,REQUEST_PHOTO=42,REQUEST_MIC=43,REQUEST_CAMERA=44;
    private Store store; private ChatRepository chats; private LocalNode node; private NodoService nodoService;
    private String chatFilter="all";
    private boolean bound=false,bindRequested=false;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            nodoService=((NodoService.LocalBinder)binder).service();
            node=nodoService.network();bound=true;
            nodoService.attach(MainActivity.this);
            render();handleLaunch(getIntent());
            String caller=nodoService.pendingCaller();
            if(caller!=null&&!caller.isEmpty()&&node.isCalling()){
                if(!nodoService.pendingGroup().isEmpty())showIncomingGroupCallDialog(caller,nodoService.pendingGroup(),nodoService.pendingGroupName());
                else showIncomingCallDialog(caller);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name){
            bound=false;node=null;nodoService=null;
            renderDisconnected();
        }
    };
    private LinearLayout root,chatList;private ScrollView chatScroll;private EditText composer;
    private TextView chatStatus;
    private int chatCount=0;
    private String audioPath="";
    private SeekBar audioSeek;
    private TextView audioToggle,audioTime;
    private Runnable audioTick;
    private float playbackSpeed=1.0f;
    private static final String[] STICKERS={"hola","genial","wow","gracias","jaja","bien","ok","amor"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String screen="discover",currentPeer="",callPeer="",networkStatus="Iniciando red local…",micAction="";
    private boolean recording=false,recordingPaused=false,muted=false,loud=false,foreground=false,callDialogOpen=false;
    private long recordingStart=0L,recordedMs=0L;
    private TextView callTimerLabel;
    private Runnable callTimer;
    private String groupCaller="",groupCallerId="";
    private String pendingCaller="";
    private MediaRecorder recorder;private File voiceFile;private MediaPlayer player;
    
    private final SimpleDateFormat clock=new SimpleDateFormat("HH:mm",new Locale("es","MX"));

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(249,252,253));getWindow().setNavigationBarColor(Color.rgb(249,252,253));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        NodeAlerts.setup(this);
        store=new Store(this);
        chats=new ChatRepository(store);
        renderDisconnected();
        if(store.backgroundEnabled()){
            try{NodoService.start(this);}catch(Exception ex){
                Toast.makeText(this,"No se pudo activar segundo plano: "+ex.getMessage(),Toast.LENGTH_LONG).show();
            }
        }
        bindRequested=bindService(new Intent(this,NodoService.class),connection,Context.BIND_AUTO_CREATE);
        if(store.name().equals("Usuario NODO")) handler.postDelayed(this::intro,600);
        handler.postDelayed(()->{if(!isFinishing())NodeAlerts.requestPermission(this);},2100);
        // Notification intents are handled after the service binder is connected.
    }
    private void renderDisconnected(){
        LinearLayout box=v();box.setGravity(Gravity.CENTER);box.setBackgroundColor(BG);
        TextView title=txt("NODO",28,DARK,true);box.addView(title);
        TextView label=txt("Preparando la red Wi-Fi local…",15,MUTED,false);box.addView(label);
        setContentView(box);
    }
    @Override protected void onResume(){
        super.onResume();foreground=true;
        if(nodoService!=null){
            nodoService.attach(this);
            String pending=nodoService.pendingCaller();
            if(pending!=null&&!pending.isEmpty()&&node!=null&&node.isCalling())pendingCaller=pending;
        }
        if(!pendingCaller.isEmpty()&&node!=null&&node.isCalling()){
            handler.post(()->{
                if(nodoService!=null && !nodoService.pendingGroup().isEmpty())
                    showIncomingGroupCallDialog(pendingCaller,nodoService.pendingGroup(),nodoService.pendingGroupName());
                else showIncomingCallDialog(pendingCaller);
            });
        }
    }
    @Override protected void onPause(){
        foreground=false;
        if(nodoService!=null)nodoService.detach(this);
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleLaunch(intent);}
    private void handleLaunch(Intent intent){
        if(intent==null)return;
        String peer=intent.getStringExtra("nodo_peer");
        if(peer==null||peer.isEmpty())return;
        intent.removeExtra("nodo_peer");
        if(node!=null && nodoService!=null && node.isCalling() && peer.equals(nodoService.pendingCaller())){
            if(foreground){
                if(!nodoService.pendingGroup().isEmpty())showIncomingGroupCallDialog(peer,nodoService.pendingGroup(),nodoService.pendingGroupName());
                else showIncomingCallDialog(peer);
            }
        }else if(store!=null&&node!=null){currentPeer=peer;screen="chat";render();}
    }
    private void intro(){new AlertDialog.Builder(this).setTitle("Bienvenido a NODO")
        .setMessage("Crea tu perfil para aparecer con tu nombre cuando otro teléfono NODO esté en la misma red Wi-Fi local.")
        .setPositiveButton("Configurar",(a,b)->editProfile()).setNegativeButton("Después",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void ui(Runnable r){runOnUiThread(r);}
    @Override public void changed(){ui(()->{
        if(node==null)return;
        flushPending();
        // Never recreate the EditText while the user is typing: it would lose IME focus.
        if(screen.equals("chat"))refreshChatMessages();
        else if(!screen.equals("call"))render();
    });}
    @Override public void delivered(LocalNode.Peer p,String mid){ui(()->{
        if(screen.equals("chat")&&currentPeer.equals(p.id))refreshChatMessages();
    });}
    @Override public void groupIncoming(LocalNode.Peer p,String gid,String name,List<String> members,String mid,String body){
        ui(()->{
            if(screen.equals("chat")&&currentPeer.equals("group:"+gid)&&foreground)refreshChatMessages();
            else if(foreground)NodeAlerts.message(this,store,"group:"+gid,name,p.name+": "+body);
        });
    }
    @Override public void groupKnown(LocalNode.Peer p,String gid,String name,List<String> members){ui(()->{
        if(screen.equals("chats"))render();
    });}
    @Override public void groupCallOffer(LocalNode.Peer p,String gid,String name){ui(()->{
        pendingCaller=p.id;groupCaller=p.id;groupCallerId=gid;
        if(foreground)showIncomingGroupCallDialog(p.id,gid,name);
    });}
    @Override public void groupCallChanged(){ui(()->{if(screen.equals("call"))updateCallTime();});}
    @Override public void status(String s){ui(()->{networkStatus=s;if(screen.equals("discover"))render();});}
    @Override public void pairing(String name,java.util.function.Consumer<Boolean> answer){
        ui(()->new AlertDialog.Builder(this).setTitle("Solicitud de conexión")
            .setMessage(name+" quiere enlazarse con tu NODO. ¿Lo conoces y autorizas el intercambio de mensajes y archivos?")
            .setCancelable(false)
            .setPositiveButton("Autorizar",(a,b)->answer.accept(true))
            .setNegativeButton("Rechazar",(a,b)->answer.accept(false)).show());
    }
    @Override public void incoming(LocalNode.Peer p,String kind,String body,String path,String filename){
        // NodoService always persists received messages, including when no Activity exists.
        ui(()->{
            if(screen.equals("chat")&&currentPeer.equals(p.id)&&foreground)refreshChatMessages();
            else {
                String preview=kind.equals("text")?body:kind.equals("voice")?"🎙 Nota de voz":"📎 "+filename;
                NodeAlerts.message(this,store,p.id,p.name,preview);
                if(foreground)toast("Nuevo mensaje de "+p.name);
            }
        });
    }
    @Override public void callOffer(LocalNode.Peer p){
        ui(()->{
            if(isFinishing()){node.rejectCall(p.id);return;}
            pendingCaller=p.id;
            if(foreground)showIncomingCallDialog(p.id);
        });
    }
    private void showIncomingCallDialog(String id){
        if(callDialogOpen || id==null || id.isEmpty() || node==null || !node.isCalling())return;
        callDialogOpen=true;
        new AlertDialog.Builder(this).setTitle("☎ Llamada entrante de NODO")
            .setMessage(store.peerName(id)+" te está llamando por Wi-Fi local.")
            .setCancelable(false)
            .setPositiveButton("Contestar",(a,b)->{
                NodeAlerts.clearCall(this);pendingCaller="";
                callPeer=id;screen="call";render();micAction="answer:"+id;ensureMicrophone();
            }).setNegativeButton("Rechazar",(a,b)->{
                NodeAlerts.clearCall(this);pendingCaller="";node.rejectCall(id);
            }).setOnDismissListener(d->callDialogOpen=false).show();
    }
    private void showIncomingGroupCallDialog(String host,String gid,String groupName){
        if(callDialogOpen || node==null || !node.groupCallActive())return;
        callDialogOpen=true;
        new AlertDialog.Builder(this).setTitle("☎ Llamada del grupo "+groupName)
            .setMessage(store.peerName(host)+" te invita a hablar con el grupo por Wi-Fi local. ¿Unirte?")
            .setCancelable(false)
            .setPositiveButton("Unirme",(d,x)->{
                NodeAlerts.clearCall(this);pendingCaller="";
                callPeer="group:"+gid;screen="call";render();
                micAction="groupAnswer:"+host;ensureMicrophone();
            }).setNegativeButton("Rechazar",(d,x)->{
                NodeAlerts.clearCall(this);pendingCaller="";
                if(nodoService!=null)nodoService.rejectGroup(host);else node.rejectGroupCall(host);
            }).setOnDismissListener(d->callDialogOpen=false).show();
    }
    @Override public void callAccepted(LocalNode.Peer p){
        ui(()->{
            NodeAlerts.clearCall(this);pendingCaller="";
            callPeer=p.id;screen="call";render();micAction="startcall:"+p.id;ensureMicrophone();
            toast("Llamada conectada");
        });
    }
    @Override public void callEnded(LocalNode.Peer p){
        ui(()->{
            NodeAlerts.clearCall(this);pendingCaller="";
            if(screen.equals("call")){screen="chats";render();toast("Llamada finalizada");}
        });
    }
    private void flushPending(){
        // Only NodoService may retry pending messages; avoid racing/repeating sends here.
    }
    private int dp(float val){return (int)(getResources().getDisplayMetrics().density*val+0.5f);}
    private GradientDrawable shape(int fill,int radius){
        GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));return g;
    }
    private GradientDrawable border(int fill,int radius,int stroke){
        GradientDrawable g=shape(fill,radius);g.setStroke(dp(1),stroke);return g;
    }
    private GradientDrawable gradient(){
        GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{GREEN,BLUE});g.setCornerRadius(dp(20));return g;
    }
    private LinearLayout v(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout h(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView txt(String s,int sz,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sz);t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);
        t.setFontFeatureSettings("kern");
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        return t;
    }
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void pad(View v,int a,int b,int c,int d){v.setPadding(dp(a),dp(b),dp(c),dp(d));}
    private void gap(LinearLayout l,int px){l.addView(new View(this),lp(1,px));}
    private TextView pill(String title,boolean selected){
        TextView t=txt(title,13,selected?Color.rgb(4,110,78):DARK,selected);
        pad(t,13,8,13,8);t.setGravity(Gravity.CENTER);t.setBackground(shape(selected?PALE:Color.rgb(238,243,246),16));return t;
    }
    private TextView button(String label,Runnable action){
        TextView t=txt(label,15,Color.WHITE,true);t.setGravity(Gravity.CENTER);pad(t,15,12,15,12);
        t.setBackground(NodoTheme.primary(this));t.setElevation(dp(2));t.setOnClickListener(w->action.run());return t;
    }
    private void title(LinearLayout l,String text,String subtitle){
        TextView t=txt(text,25,DARK,true);l.addView(t);
        if(subtitle!=null&&!subtitle.isEmpty()){
            TextView s=txt(subtitle,13,MUTED,false);LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(5);l.addView(s,p);
        }
    }
    private LinearLayout card(){
        return NodoTheme.card(this);
    }
    private void ripple(View target,int backgroundColor,int radius){
        target.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(45,32,202,145)),shape(backgroundColor,radius),null));
        target.setClickable(true);
    }
    private TextView glyph(String symbol,int color,int size){
        TextView item=txt(symbol,size,color,true);item.setGravity(Gravity.CENTER);
        return item;
    }
    private ImageView imageLogo(int width){ImageView iv=new ImageView(this);iv.setImageResource(R.drawable.nodo_logo);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);iv.setLayoutParams(lp(width,width));return iv;}
    private ImageView avatar(String id,int size){
        ImageView iv=new ImageView(this);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        File f=id.equals(store.id())?new File(store.avatar()):new File(new File(getFilesDir(),"avatars"),id+".jpg");
        if(f.isFile())iv.setImageBitmap(decode(f.getAbsolutePath(),size*2));else iv.setImageResource(R.drawable.nodo_logo);
        iv.setBackground(shape(PALE,size/2));iv.setClipToOutline(true);iv.setLayoutParams(lp(size,size));return iv;
    }
    private Bitmap decode(String file,int bound){
        BitmapFactory.Options a=new BitmapFactory.Options();a.inJustDecodeBounds=true;BitmapFactory.decodeFile(file,a);
        int scale=1;while(a.outWidth/scale>bound*2||a.outHeight/scale>bound*2)scale*=2;
        BitmapFactory.Options b=new BitmapFactory.Options();b.inSampleSize=scale;return BitmapFactory.decodeFile(file,b);
    }
    private void render(){
        root=v();root.setBackgroundColor(BG);root.setFocusableInTouchMode(true);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(root);
        // Android 15+ edge-to-edge: apply system and IME insets to the content root.
        // Keeping the composer inside a weighted vertical layout lifts it above the IME.
        if(Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                int top=bars.top+dp(5), bottom=Math.max(bars.bottom,keyboard.bottom);
                if(view.getPaddingTop()!=top||view.getPaddingBottom()!=bottom){
                    view.setPadding(0,top,0,bottom);
                    if(screen.equals("chat")&&chatScroll!=null)chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
                }
                return insets;
            });
            root.requestApplyInsets();
        }
        if(screen.equals("chat")){showChat();return;}
        if(screen.equals("call")){showCall();return;}
        LinearLayout header=h();pad(header,18,12,18,9);header.setBackgroundColor(Color.WHITE);header.setElevation(dp(3));
        header.addView(imageLogo(38));TextView brand=txt("NODO",23,DARK,true);header.addView(brand);
        View space=new View(this);header.addView(space,new LinearLayout.LayoutParams(0,1,1));
        TextView mini=txt("● Wi-Fi local",11,NodoTheme.EMERALD_DARK,true);
        mini.setBackground(shape(PALE,14));pad(mini,10,7,10,7);header.addView(mini);
        root.addView(header,lp(-1,62));
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setClipToPadding(false);
        LinearLayout body=v();pad(body,16,16,16,20);sv.addView(body);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        switch(screen){case "discover":showDiscover(body);break;case "chats":showChats(body);break;
            case "calls":showCalls(body);break;case "files":showFiles(body);break;
            case "settings":showSettings(body);break;default:showDiscover(body);}
        bottomNav();
    }
    private void bottomNav(){
        LinearLayout nav=NodoBottomBar.create(this,screen,key->{
            screen=key;render();
        });
        root.addView(nav,lp(-1,67));
    }
    private void showDiscover(LinearLayout body){
        title(body,"Descubrir","Personas y dispositivos que usan NODO cerca de ti");gap(body,13);
        Radar r=new Radar(this);body.addView(r,lp(-1,180));gap(body,10);
        TextView banner=pill("●  "+networkStatus,false);body.addView(banner);gap(body,15);
        LinearLayout row=h();row.addView(txt("Encontrados cerca",16,DARK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView hint=pill("↻ Buscar",true);hint.setOnClickListener(w->{toast("Búsqueda automática activa. Comprueba que ambos estén en la misma red Wi-Fi.");});row.addView(hint);body.addView(row);gap(body,10);
        List<LocalNode.Discovered> ds=node.nearby();ds.sort(Comparator.comparing(a->a.serviceName));
        if(ds.isEmpty()){
            LinearLayout blank=card();blank.addView(txt("Aún no hay otros teléfonos NODO detectados.",15,DARK,true));gap(blank,5);
            blank.addView(txt("Abre NODO en otro Android conectado al mismo Wi-Fi o punto de acceso, aunque no tenga internet.",13,MUTED,false));body.addView(blank);
        }
        for(LocalNode.Discovered p:ds){
            String match=store.matchShort(p.shortId());String name=match!=null?store.peerName(match):p.serviceName;
            LinearLayout c=card();LinearLayout line=h();
            line.addView(match!=null?avatar(match,44):imageLogo(44));
            LinearLayout text=v();pad(text,11,0,6,0);text.addView(txt(name,15,DARK,true));
            text.addView(txt(match!=null&&node.isConnected(match)?"● Conectado":"Cerca · Wi-Fi local · sin distancia exacta",12,match!=null&&node.isConnected(match)?GREEN:MUTED,false));
            line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            TextView btn=pill(match!=null&&node.isConnected(match)?"Abrir":"Enlazar",true);
            btn.setOnClickListener(w->{if(match!=null&&node.isConnected(match)){currentPeer=match;screen="chat";render();}
                else {node.connect(p);toast("Solicitando enlace. La otra persona debe autorizarlo.");}});
            line.addView(btn);c.addView(line);body.addView(c);gap(body,9);
        }
        gap(body,10);body.addView(button("Crear enlace NODO",()->{
            new AlertDialog.Builder(this).setTitle("Tu enlace NODO")
                .setMessage("Tu dispositivo ya está anunciándose como NODO-"+store.id().substring(0,8)+". Para enlazar, abre NODO en otro teléfono en la misma red Wi-Fi, entra en Cercanos y pulsa Enlazar. No necesitas acceso a internet.")
                .setPositiveButton("Entendido",null).show();
        }),lp(-1,48));
        gap(body,12);body.addView(txt("Reconexión automática con contactos autorizados. Para recibir mensajes al cerrar la pantalla, activa Segundo plano en Ajustes.",12,MUTED,false));
    }
    private void showChats(LinearLayout body){
        title(body,"Chats","Tus conversaciones y grupos sin depender de internet");gap(body,15);
        LinearLayout shortcuts=h();
        String[] actions={"Mensajes","Llamadas","Archivos"};
        for(int i=0;i<actions.length;i++){
            final int selected=i;
            TextView option=pill((i==0?"▤ ":i==1?"☎ ":"▣ ")+actions[i],i==0);
            option.setOnClickListener(view->{
                if(selected==0){chatFilter="all";render();}
                else {screen=selected==1?"calls":"files";render();}
            });
            shortcuts.addView(option,new LinearLayout.LayoutParams(0,-2,1));
            if(i<2)gapHorizontal(shortcuts,7);
        }
        body.addView(shortcuts);gap(body,15);
        LinearLayout filters=h();
        String[] keys={"all","near","favorites"};
        String[] labels={"Todos","Cercanos","Favoritos"};
        for(int i=0;i<3;i++){
            final String key=keys[i];TextView chip=pill(labels[i],chatFilter.equals(key));
            chip.setOnClickListener(view->{chatFilter=key;render();});
            filters.addView(chip);if(i<2)gapHorizontal(filters,8);
        }
        body.addView(filters);gap(body,16);
        TextView create=pill("＋ Nuevo grupo",true);
        create.setOnClickListener(w->createGroupDialog());body.addView(create);gap(body,15);
        int listed=0;
        if(chatFilter.equals("all"))for(String gid:store.groups()){
            String id="group:"+gid;LinearLayout row=contactRow(id);
            row.setOnClickListener(w->openChat(id));body.addView(row);gap(body,9);listed++;
        }
        for(String id:store.contacts()){
            if(chatFilter.equals("near")&&!node.isConnected(id))continue;
            if(chatFilter.equals("favorites")&&!chats.favorite(id))continue;
            LinearLayout row=contactRow(id);
            row.setOnClickListener(w->openChat(id));body.addView(row);gap(body,9);listed++;
        }
        if(listed==0){
            LinearLayout empty=card();
            empty.addView(txt(chatFilter.equals("favorites")?"Todavía no tienes favoritos":
                chatFilter.equals("near")?"No hay contactos conectados":"Empieza tu primera conversación",17,DARK,true));
            gap(empty,8);
            empty.addView(txt(chatFilter.equals("favorites")?"Marca ☆ en la cabecera de un chat para guardarlo aquí.":
                "Busca otro teléfono NODO en Descubrir y autoriza el enlace.",13,MUTED,false));
            gap(empty,14);
            if(!chatFilter.equals("favorites"))empty.addView(button("Descubrir personas",()->{screen="discover";render();}));
            body.addView(empty);
        }
    }
    private void createGroupDialog(){
        List<String> ids=store.contacts();
        if(ids.isEmpty()){toast("Primero enlaza al menos un contacto en Cercanos");return;}
        String[] names=new String[ids.size()];boolean[] selected=new boolean[ids.size()];
        for(int i=0;i<ids.size();i++)names[i]=store.peerName(ids.get(i))+(node.isConnected(ids.get(i))?" · conectado":" · sin conexión");
        EditText groupName=new EditText(this);groupName.setSingleLine(true);
        groupName.setHint("Nombre del grupo");groupName.setText("Mi grupo NODO");
        LinearLayout layout=v();pad(layout,20,7,20,5);
        layout.addView(txt("Elige las personas que quieres agregar. Se enlazan automáticamente cuando ya estén autorizadas y cerca.",13,MUTED,false));gap(layout,7);
        layout.addView(groupName);
        new AlertDialog.Builder(this).setTitle("Nuevo grupo")
            .setView(layout).setMultiChoiceItems(names,selected,(dialog,index,on)->selected[index]=on)
            .setPositiveButton("Crear",(dialog,which)->{
                List<String> members=new ArrayList<>();members.add(store.id());
                for(int i=0;i<ids.size();i++)if(selected[i])members.add(ids.get(i));
                if(members.size()<2){toast("Selecciona al menos una persona");return;}
                String title=groupName.getText().toString().trim();if(title.isEmpty())title="Grupo NODO";
                String gid=store.createGroup(title,members);
                if(nodoService!=null)nodoService.announceGroup(gid);
                currentPeer="group:"+gid;screen="chat";render();
            }).setNegativeButton("Cancelar",null).show();
    }
    private void showOnlineOnly(){
        List<String> connected=new ArrayList<>();for(String id:store.contacts())if(node.isConnected(id))connected.add(store.peerName(id));
        new AlertDialog.Builder(this).setTitle("Conectados ahora")
            .setMessage(connected.isEmpty()?"No tienes contactos conectados actualmente.":android.text.TextUtils.join("\n",connected))
            .setPositiveButton("Cerrar",null).show();
    }
    private void gapHorizontal(LinearLayout l,int width){l.addView(new View(this),lp(width,1));}
    private LinearLayout contactRow(String id){
        LinearLayout outer=card();LinearLayout row=h();row.addView(avatar(id,52));
        LinearLayout desc=v();pad(desc,13,0,7,0);
        LinearLayout heading=h();heading.addView(txt(store.peerName(id),16,DARK,true),new LinearLayout.LayoutParams(0,-2,1));
        MessageRecord last=chats.latest(id);
        if(last!=null){
            String when=clock.format(new Date(last.timestamp));
            TextView time=txt(when,11,MUTED,false);heading.addView(time);
        }
        desc.addView(heading);gap(desc,7);
        String preview=last==null?"Toca para iniciar conversación":last.preview();
        if(store.isGroup(id))preview="Grupo · "+preview;
        if(preview.length()>45)preview=preview.substring(0,45)+"…";
        desc.addView(txt(preview,13,MUTED,false));
        row.addView(desc,new LinearLayout.LayoutParams(0,-2,1));
        boolean connected=node.isConnected(id);
        if(store.isGroup(id))for(String member:store.groupMembers(id.substring(6)))if(node.isConnected(member))connected=true;
        int unread=chats.unread(id);
        LinearLayout state=v();state.setGravity(Gravity.CENTER);
        if(unread>0){
            TextView count=txt(String.valueOf(Math.min(99,unread)),12,Color.WHITE,true);
            count.setGravity(Gravity.CENTER);count.setBackground(shape(GREEN,15));
            state.addView(count,lp(28,26));gap(state,5);
        }
        state.addView(txt(connected?"●":"○",18,connected?GREEN:MUTED,true));
        row.addView(state);outer.addView(row);return outer;
    }
    private void showCalls(LinearLayout body){
        title(body,"Llamadas","Voz por Wi-Fi local con personas enlazadas");gap(body,13);
        LinearLayout c=card();c.addView(txt("Para llamar, abre un chat y toca ☎.",15,DARK,true));gap(c,5);
        c.addView(txt("Las llamadas de voz funcionan si ambos teléfonos están enlazados por Wi-Fi local y NODO tiene permiso para ejecutarse en segundo plano. La videollamada se incorporará después.",13,MUTED,false));
        body.addView(c);gap(body,12);
        for(String id:store.contacts())if(node.isConnected(id)){
            LinearLayout row=contactRow(id);row.setOnClickListener(w->call(id));body.addView(row);gap(body,8);
        }
        gap(body,8);
        body.addView(txt("Llamadas grupales (beta): crea un grupo en Chats y pulsa ☎. Hasta cuatro personas conectadas al mismo Wi-Fi; todos deben aceptar la invitación.",13,MUTED,false));
        gap(body,9);
        body.addView(button("Videollamadas — ver estado",()->featurePending("Videollamadas por Wi-Fi local")),lp(-1,48));
    }
    private void showFiles(LinearLayout body){
        title(body,"Archivos","Fotos, documentos y notas de voz transferidos");gap(body,12);
        int total=0;
        for(String id:store.contacts()){
            JSONArray a=store.messages(id);
            for(int i=0;i<a.length();i++){
                JSONObject m=a.optJSONObject(i);if(m==null||m.optString("kind").equals("text"))continue;
                total++;final String path=m.optString("path"),name=m.optString("name");
                LinearLayout row=card();row.addView(txt((m.optString("kind").equals("voice")?"🎙  ":"📎  ")+name,14,DARK,true));
                row.addView(txt("Chat: "+store.peerName(id),12,MUTED,false));
                row.setOnClickListener(w->openLocal(path,name));body.addView(row);gap(body,8);
            }
        }
        if(total==0){LinearLayout none=card();none.addView(txt("No tienes archivos transferidos todavía.",14,DARK,false));body.addView(none);}
    }
    private void showSettings(LinearLayout body){
        title(body,"Mi perfil y ajustes","Personaliza tu experiencia en NODO");gap(body,14);
        LinearLayout profile=card();LinearLayout p=h();p.addView(avatar(store.id(),66));
        LinearLayout info=v();pad(info,14,0,5,0);info.addView(txt(store.name(),19,DARK,true));
        info.addView(txt(store.status(),13,MUTED,false));p.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        p.addView(txt("✎",23,GREEN,true));profile.addView(p);profile.setOnClickListener(w->editProfile());body.addView(profile);gap(body,14);
        settingsRow(body,"◉","Cuenta y perfil","Foto, nombre y estado",this::editProfile);
        settingsRow(body,"♧","Privacidad","Enlaces autorizados y contactos",this::privacySettings);
        settingsRow(body,"☷","Chats","Exportar conversación o borrar mensajes",this::chatSettings);
        settingsRow(body,"◈","Notificaciones y sonidos","Tonos de mensajes y llamadas",this::notificationSettings);
        settingsRow(body,"◎","Conectividad","Reconexión y tecnología de red",this::connectivitySettings);
        settingsRow(body,"◌","Segundo plano","Mantener NODO conectado al cerrar la pantalla",this::backgroundSettings);
        settingsRow(body,"▣","Almacenamiento y datos","Archivos de hasta 30 MB",this::storageSettings);
        settingsRow(body,"♧","Dispositivos autorizados","Consultar y revocar confianza",this::trustedContactsDialog);
        settingsRow(body,"◐","Apariencia","Estilo original blanco y menta",()->info("Apariencia","NODO utiliza el diseño blanco, verde menta y azul elegido. Temas adicionales vendrán después."));
        settingsRow(body,"?","Ayuda","Cómo conectar dos Android",this::help);
        settingsRow(body,"↗","Invitar amigos","Compartir información sobre NODO",this::shareInvite);
        gap(body,14);body.addView(txt("NODO Beta v0.6.0 · Versión experimental · Comunicaciones sin cifrado de extremo a extremo. Solo para pruebas.",12,MUTED,false));
    }
    private void settingsRow(LinearLayout body,String icon,String name,String sub,Runnable action){
        LinearLayout c=card();LinearLayout row=h();
        TextView glyph=txt(icon,23,GREEN,true);glyph.setGravity(Gravity.CENTER);row.addView(glyph,lp(32,40));
        LinearLayout content=v();pad(content,10,0,0,0);content.addView(txt(name,15,DARK,true));content.addView(txt(sub,12,MUTED,false));
        row.addView(content,new LinearLayout.LayoutParams(0,-2,1));row.addView(txt("›",23,MUTED,false));c.addView(row);
        c.setOnClickListener(w->action.run());body.addView(c);gap(body,8);
    }
    private void openChat(String id){currentPeer=id;screen="chat";chats.markRead(id);render();}
    private void showChat(){
        LinearLayout bar=h();pad(bar,10,10,14,10);bar.setBackgroundColor(Color.WHITE);bar.setElevation(dp(4));
        TextView back=glyph("‹",DARK,32);pad(back,4,0,10,0);back.setOnClickListener(w->{screen="chats";render();});bar.addView(back);
        bar.addView(avatar(currentPeer,44));
        LinearLayout who=v();pad(who,10,0,2,0);who.addView(txt(store.peerName(currentPeer),17,DARK,true));
        chatStatus=txt("",11,MUTED,false);who.addView(chatStatus);
        bar.addView(who,new LinearLayout.LayoutParams(0,-2,1));
        if(!store.isGroup(currentPeer)){
            TextView favorite=glyph(chats.favorite(currentPeer)?"★":"☆",GREEN,24);
            pad(favorite,7,5,7,5);favorite.setContentDescription("Agregar o quitar de favoritos");
            favorite.setOnClickListener(w->{
                boolean status=!chats.favorite(currentPeer);chats.favorite(currentPeer,status);
                favorite.setText(status?"★":"☆");toast(status?"Añadido a Favoritos":"Eliminado de Favoritos");
            });bar.addView(favorite);
        }
        TextView call=glyph("☎",GREEN,24);pad(call,7,5,7,5);
        call.setOnClickListener(w->{if(store.isGroup(currentPeer))startGroupCall(currentPeer.substring(6));else call(currentPeer);});bar.addView(call);
        TextView video=glyph("▣",GREEN,23);pad(video,7,5,3,5);video.setOnClickListener(w->featurePending("Videollamadas"));bar.addView(video);
        root.addView(bar,lp(-1,70));
        TextView tag=txt("◈  Comunicación mediante Wi-Fi local",11,Color.rgb(7,121,87),true);
        tag.setGravity(Gravity.CENTER);tag.setBackground(shape(PALE,14));pad(tag,10,9,10,9);
        LinearLayout.LayoutParams tp=lp(-1,-2);tp.setMargins(dp(16),dp(10),dp(16),dp(7));root.addView(tag,tp);
        chatScroll=new ScrollView(this);chatScroll.setFillViewport(true);chatScroll.setClipToPadding(false);
        chatScroll.setVerticalScrollBarEnabled(false);chatScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        chatList=v();pad(chatList,14,12,14,16);chatScroll.addView(chatList);
        root.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        refreshChatMessages();
        if(recording){
            LinearLayout recorderBar=h();pad(recorderBar,13,10,13,10);
            recorderBar.setBackground(shape(Color.rgb(255,238,241),18));
            TextView status=txt(recordingPaused?"Ⅱ Pausado":"● Grabando…",12,Color.rgb(173,45,66),true);
            recorderBar.addView(status,new LinearLayout.LayoutParams(0,-2,1));
            TextView pause=pill(recordingPaused?"▶ Seguir":"Ⅱ Pausar",false);
            pause.setOnClickListener(v->toggleRecordingPause());recorderBar.addView(pause);
            TextView done=pill("✓ Revisar",true);done.setOnClickListener(v->stopRecorder(true));recorderBar.addView(done);
            TextView discard=pill("✕",false);discard.setOnClickListener(v->stopRecorder(false));recorderBar.addView(discard);
            root.addView(recorderBar);
        }
        LinearLayout input=h();pad(input,11,9,11,9);input.setBackgroundColor(Color.WHITE);input.setElevation(dp(6));
        TextView attach=glyph("+",GREEN,27);ripple(attach,PALE,28);input.addView(attach,lp(39,40));
        attach.setOnClickListener(w->showAttachments());
        composer=new EditText(this);composer.setSingleLine(false);composer.setMaxLines(5);
        composer.setMinHeight(dp(43));composer.setTextSize(15);composer.setTextColor(DARK);
        composer.setHintTextColor(MUTED);composer.setHint("Mensaje…");
        composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        composer.setBackground(border(Color.rgb(245,249,250),23,Color.rgb(225,237,237)));
        pad(composer,14,7,10,7);
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,-2,1);ep.setMargins(dp(8),0,dp(5),0);input.addView(composer,ep);
        composer.setOnFocusChangeListener((view,focus)->{if(focus&&chatScroll!=null)chatScroll.postDelayed(()->chatScroll.fullScroll(View.FOCUS_DOWN),120);});
        TextView emoji=glyph("☺",Color.rgb(44,154,130),23);pad(emoji,3,5,3,5);emoji.setOnClickListener(w->showExpressionPicker(false));input.addView(emoji);
        TextView send=glyph("➤",Color.WHITE,20);send.setBackground(gradient());input.addView(send,lp(40,42));send.setOnClickListener(w->sendText());
        TextView mic=glyph(recording?"■":"🎙",recording?Color.rgb(206,58,74):GREEN,22);
        pad(mic,5,6,1,6);mic.setOnClickListener(w->toggleRecorder());input.addView(mic);
        root.addView(input,lp(-1,-2));
        // Focus is requested only by user interaction, not when opening a conversation.
        root.requestFocus();
        chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void refreshChatMessages(){
        if(chatList==null||node==null||!screen.equals("chat"))return;
        boolean connected=node.isConnected(currentPeer);
        if(store.isGroup(currentPeer)){
            int online=0;for(String member:store.groupMembers(currentPeer.substring(6)))if(node.isConnected(member))online++;
            connected=online>0;
            if(chatStatus!=null)chatStatus.setText("👥 "+online+" miembros conectados · Wi-Fi local");
        }else if(chatStatus!=null){
            long lastSeen=store.peerLastSeen(currentPeer);
            String offline=lastSeen>0?"Última conexión compartida: "+clock.format(new Date(lastSeen)):
                "Sin conexión · textos en espera";
            chatStatus.setText(connected?"●  Conectado por Wi-Fi local":offline);
            chatStatus.setTextColor(connected?GREEN:MUTED);
        }
        if(foreground)chats.markRead(currentPeer);
        JSONArray items=store.messages(currentPeer);
        if(items.length()==chatCount && chatList.getChildCount()>0)return;
        chatList.removeAllViews();chatCount=items.length();
        if(items.length()==0){
            LinearLayout welcome=card();welcome.setGravity(Gravity.CENTER);gap(welcome,14);
            TextView a=txt("🌿",36,GREEN,true);a.setGravity(Gravity.CENTER);welcome.addView(a);
            TextView t=txt("¡Empieza la conversación!",17,DARK,true);t.setGravity(Gravity.CENTER);welcome.addView(t);
            TextView desc=txt("Escribe, comparte fotos o envía una nota de voz.",13,MUTED,false);desc.setGravity(Gravity.CENTER);welcome.addView(desc);
            chatList.addView(welcome);return;
        }
        for(int i=0;i<items.length();i++){
            JSONObject msg=items.optJSONObject(i);if(msg!=null)addBubble(chatList,msg);
        }
        chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void addBubble(LinearLayout list,JSONObject msg){
        boolean out=msg.optBoolean("out");String kind=msg.optString("kind"),body=msg.optString("body"),path=msg.optString("path"),name=msg.optString("name");
        boolean sticker=kind.equals("file")&&isSticker(name);
        LinearLayout container=v();pad(container,sticker?2:13,sticker?2:12,sticker?2:13,sticker?2:9);
        if(!sticker){
            container.setBackground(border(out?Color.rgb(218,248,237):Color.WHITE,20,out?Color.rgb(193,236,219):Color.rgb(229,237,241)));
            container.setElevation(dp(1));
        }
        if(kind.equals("text")){
            TextView t=txt(body,15,DARK,false);t.setMaxWidth(dp(284));t.setLineSpacing(dp(3),1.03f);
            container.addView(t);
        }else if(kind.equals("voice")){
            addAudioBubble(container,path,out);
        }else if(kind.equals("file")){
            File file=new File(path);
            if(sticker && file.isFile()){
                ImageView stickerImage=new ImageView(this);stickerImage.setImageBitmap(decode(path,300));
                stickerImage.setScaleType(ImageView.ScaleType.FIT_CENTER);container.addView(stickerImage,lp(164,164));
                stickerImage.setOnClickListener(w->openLocal(path,name));
            }else{
                if(isImage(name)&&file.isFile()){
                    ImageView preview=new ImageView(this);preview.setImageBitmap(decode(path,550));
                    preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setBackground(shape(PALE,12));preview.setClipToOutline(true);
                    container.addView(preview,lp(225,155));preview.setOnClickListener(w->openLocal(path,name));gap(container,7);
                }
                String icon=isPdf(name)?"▤":isVideo(name)?"▶":isAudio(name)?"♫":"▣";
                LinearLayout link=h();TextView document=glyph(icon,GREEN,24);
                link.addView(document,lp(34,38));
                LinearLayout desc=v();pad(desc,7,0,0,0);
                TextView fname=txt(name,13,DARK,true);fname.setMaxWidth(dp(200));fname.setMaxLines(2);desc.addView(fname);
                desc.addView(txt("Ver en NODO · "+humanSize(file.length()),11,MUTED,false));link.addView(desc);
                container.addView(link);link.setOnClickListener(w->openLocal(path,name));
            }
        }
        String state=out?(msg.optBoolean("sent")?"  ✓✓":"  ◷") : "";
        TextView time=txt(clock.format(new Date(msg.optLong("when",System.currentTimeMillis())))+state,10,MUTED,false);
        time.setGravity(Gravity.RIGHT);pad(time,0,6,0,0);container.addView(time);
        LinearLayout frame=h();frame.setGravity(out?Gravity.RIGHT:Gravity.LEFT);
        frame.addView(container,new LinearLayout.LayoutParams(-2,-2));
        LinearLayout.LayoutParams mar=lp(-1,-2);mar.bottomMargin=dp(10);list.addView(frame,mar);
    }
    private boolean isSticker(String filename){return filename!=null&&filename.startsWith("nodo_sticker_")&&filename.endsWith(".png");}
    private boolean isPdf(String name){return name.toLowerCase(Locale.ROOT).endsWith(".pdf");}
    private boolean isVideo(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|webm|3gp|mkv)$");}
    private boolean isAudio(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(m4a|mp3|wav|aac|ogg)$");}
    private String humanSize(long bytes){return bytes>=1048576?String.format(Locale.getDefault(),"%.1f MB",bytes/1048576.0):Math.max(1,bytes/1024)+" KB";}
    private void addAudioBubble(LinearLayout parent,String path,boolean out){
        LinearLayout row=h();
        TextView play=glyph("▶",Color.WHITE,20);play.setBackground(shape(out?GREEN:BLUE,23));row.addView(play,lp(45,45));
        LinearLayout details=v();pad(details,10,0,0,0);
        details.addView(txt("Nota de voz",12,DARK,true));
        LinearLayout progress=h();
        SeekBar seek=new SeekBar(this);seek.setMax(1000);seek.setProgressTintList(ColorStateList.valueOf(out?GREEN:BLUE));
        progress.addView(seek,new LinearLayout.LayoutParams(0,dp(32),1));
        TextView duration=txt(voiceDuration(path),11,MUTED,false);progress.addView(duration);
        details.addView(progress);row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        parent.addView(row,lp(247,-2));
        play.setOnClickListener(w->toggleVoicePlayback(path,play,seek,duration));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){if(player!=null&&path.equals(audioPath)&&player.getDuration()>0)player.seekTo(player.getDuration()*b.getProgress()/1000);}
            @Override public void onProgressChanged(SeekBar b,int p,boolean from){ }
        });
    }
    private String voiceDuration(String path){
        MediaMetadataRetriever mm=new MediaMetadataRetriever();
        try{mm.setDataSource(path);String s=mm.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(s!=null){int secs=(int)(Long.parseLong(s)/1000);return String.format(Locale.getDefault(),"%d:%02d",secs/60,secs%60);}}
        catch(Exception ignored){}finally{try{mm.release();}catch(Exception ignored){}}
        return "0:00";
    }
    private void toggleVoicePlayback(String path,TextView button,SeekBar seek,TextView duration){
        if(path.isEmpty()||!new File(path).isFile()){toast("No se encuentra la nota de voz");return;}
        try{
            if(player!=null && path.equals(audioPath)){
                if(player.isPlaying()){player.pause();button.setText("▶");return;}
                player.start();button.setText("Ⅱ");startAudioProgress(button,seek,duration);return;
            }
            stopVoicePlayback();
            player=new MediaPlayer();player.setDataSource(path);player.prepare();player.start();audioPath=path;button.setText("Ⅱ");
            player.setOnCompletionListener(mp->{stopVoicePlayback();button.setText("▶");seek.setProgress(0);});
            startAudioProgress(button,seek,duration);
        }catch(Exception ex){stopVoicePlayback();toast("No se pudo reproducir el audio");}
    }
    private void startAudioProgress(TextView button,SeekBar seek,TextView duration){
        if(audioTick!=null)handler.removeCallbacks(audioTick);
        audioTick=new Runnable(){@Override public void run(){
            try{if(player==null||!player.isPlaying())return;
                int length=player.getDuration(),position=player.getCurrentPosition();
                if(length>0)seek.setProgress(position*1000/length);
                int secs=position/1000;duration.setText(String.format(Locale.getDefault(),"%d:%02d",secs/60,secs%60));
                handler.postDelayed(this,180);
            }catch(Exception ignored){}
        }};handler.post(audioTick);
    }
    private void stopVoicePlayback(){
        if(audioTick!=null){handler.removeCallbacks(audioTick);audioTick=null;}
        if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
        audioPath="";
    }
    private void showAttachments(){
        final String[] choices={"Galería","Cámara","Archivo / PDF","Contacto","Ubicación (escribir)","Emojis","Stickers"};
        new AlertDialog.Builder(this).setTitle("Compartir en NODO")
            .setItems(choices,(d,which)->{
                if(which==0)pickImage();
                else if(which==1)openCamera();
                else if(which==2)pickFile();
                else if(which==3)shareContact();
                else if(which==4)sharePlace();
                else showExpressionPicker(which==6);
            }).show();
    }
    private void pickImage(){
        if(!node.isConnected(currentPeer)){toast("Conecta primero con tu contacto");return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent,REQUEST_DOC);
    }
    private void openCamera(){
        if(!node.isConnected(currentPeer)){toast("Conecta primero con tu contacto");return;}
        Intent intent=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        if(intent.resolveActivity(getPackageManager())!=null)startActivityForResult(intent,REQUEST_CAMERA);
        else toast("No hay una cámara compatible disponible");
    }
    private void shareContact(){
        List<String> ids=store.contacts();if(ids.isEmpty()){toast("Aún no tienes contactos enlazados");return;}
        String[] names=new String[ids.size()];for(int i=0;i<ids.size();i++)names[i]=store.peerName(ids.get(i));
        new AlertDialog.Builder(this).setTitle("Compartir contacto NODO")
            .setItems(names,(d,which)->sendComposedText("Contacto NODO: "+names[which]+" · ID "+ids.get(which)))
            .setNegativeButton("Cancelar",null).show();
    }
    private void sharePlace(){
        EditText place=new EditText(this);place.setSingleLine(false);place.setHint("Escribe una dirección, lugar o coordenadas");
        new AlertDialog.Builder(this).setTitle("Compartir ubicación")
            .setMessage("NODO no consulta tu GPS automáticamente. Puedes compartir un lugar por texto.")
            .setView(place).setPositiveButton("Enviar",(d,w)->{
                String value=place.getText().toString().trim();
                if(value.isEmpty()){toast("Indica un lugar para compartir");return;}
                sendComposedText("📍 Ubicación: "+value);
            }).setNegativeButton("Cancelar",null).show();
    }
    private void sendComposedText(String value){
        if(nodoService==null){toast("Inicia la red NODO primero");return;}
        if(store.isGroup(currentPeer))nodoService.queueGroupText(currentPeer.substring(6),value);
        else nodoService.queueText(currentPeer,value);
        refreshChatMessages();
    }
    private void showExpressionPicker(boolean startStickers){
        LinearLayout sheet=v();pad(sheet,14,12,14,8);sheet.setBackgroundColor(Color.WHITE);
        LinearLayout tabs=h();TextView emojis=pill("☺ Emojis",!startStickers),stickers=pill("▧ Stickers",startStickers);
        tabs.addView(emojis);gapHorizontal(tabs,8);tabs.addView(stickers);sheet.addView(tabs);gap(sheet,10);
        ScrollView scroll=new ScrollView(this);LinearLayout panel=v();scroll.addView(panel);sheet.addView(scroll,lp(-1,360));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Exprésate en NODO")
            .setView(sheet).setNegativeButton("Cerrar",null).create();
        Runnable emojiTab=()->fillEmojis(panel,dialog);
        Runnable stickerTab=()->fillStickers(panel,dialog);
        emojis.setOnClickListener(w->emojiTab.run());stickers.setOnClickListener(w->stickerTab.run());
        if(startStickers)stickerTab.run();else emojiTab.run();dialog.show();
    }
    private void fillEmojis(LinearLayout panel,AlertDialog dialog){
        panel.removeAllViews();
        String[][] sections={
            {"Caritas","😀","😃","😄","😁","😆","😂","🤣","😊","😍","🥰","😘","😎","😢","😭","😡","🥹","🤩","🙃","😴","😉"},
            {"Gestos","👍","👎","👏","🙌","👌","✌️","🤝","🙏","💪","👋","🤗","🤞","👀","💯","🔥","✨"},
            {"Corazones y naturaleza","❤️","💚","💙","💜","💛","🩷","💕","💖","💘","🌹","🌸","🌿","🌞","🌈","🌙","⭐"},
            {"Fiesta y objetos","🎉","🎂","🎁","🎵","🎧","☕","🍕","⚽","🚀","📷","📍","🎈","💡","✅","❗","🥳"}
        };
        for(String[] section:sections){
            TextView heading=txt(section[0],14,DARK,true);pad(heading,5,11,3,9);panel.addView(heading);
            GridLayout grid=new GridLayout(this);grid.setColumnCount(5);
            for(int i=1;i<section.length;i++){
                final String emoji=section[i];TextView icon=glyph(emoji,DARK,25);
                GridLayout.LayoutParams cell=new GridLayout.LayoutParams();cell.width=dp(57);cell.height=dp(53);
                cell.setMargins(dp(2),dp(2),dp(2),dp(2));grid.addView(icon,cell);
                ripple(icon,Color.rgb(247,251,252),13);
                icon.setOnClickListener(w->{if(composer!=null){int at=Math.max(0,composer.getSelectionStart());composer.getText().insert(at,emoji);composer.requestFocus();}dialog.dismiss();});
            }
            panel.addView(grid);
        }
    }
    private void fillStickers(LinearLayout panel,AlertDialog dialog){
        panel.removeAllViews();
        TextView hint=txt("Stickers incluidos en NODO · Se envían como imágenes a tus contactos.",13,MUTED,false);
        pad(hint,4,7,2,10);panel.addView(hint);
        GridLayout grid=new GridLayout(this);grid.setColumnCount(2);
        for(String id:STICKERS){
            ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try(InputStream in=getAssets().open("stickers/"+id+".png")){image.setImageBitmap(BitmapFactory.decodeStream(in));}
            catch(IOException ignored){}
            GridLayout.LayoutParams cell=new GridLayout.LayoutParams();cell.width=dp(142);cell.height=dp(132);
            cell.setMargins(dp(4),dp(4),dp(4),dp(4));grid.addView(image,cell);
            ripple(image,Color.rgb(248,252,250),19);
            image.setOnClickListener(w->{dialog.dismiss();sendSticker(id);});
        }
        panel.addView(grid);
    }
    private void sendSticker(String sticker){
        if(node==null||!node.isConnected(currentPeer)){toast("Conecta con tu contacto para enviar stickers");return;}
        final String peer=currentPeer;
        new Thread(()->{
            try{
                File folder=new File(getFilesDir(),"sent");folder.mkdirs();
                File file=new File(folder,UUID.randomUUID()+"_nodo_sticker_"+sticker+".png");
                try(InputStream in=getAssets().open("stickers/"+sticker+".png");OutputStream out=new FileOutputStream(file)){
                    byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)out.write(block,0,n);
                }
                transmitFile(peer,file,"nodo_sticker_"+sticker+".png",false);
            }catch(Exception ex){ui(()->toast("No se pudo enviar el sticker"));}
        },"nodo-sticker").start();
    }
    private boolean isImage(String name){String s=name.toLowerCase(Locale.ROOT);return s.endsWith(".jpg")||s.endsWith(".jpeg")||s.endsWith(".png")||s.endsWith(".webp")||s.endsWith(".gif");}
    private void sendText(){
        String text=composer.getText().toString().trim();if(text.isEmpty())return;
        if(nodoService==null){toast("Iniciando NODO…");return;}
        if(store.isGroup(currentPeer)){
            nodoService.queueGroupText(currentPeer.substring(6),text);
        }else nodoService.queueText(currentPeer,text);
        composer.setText("");refreshChatMessages();
    }
    private void pickFile(){
        if(!node.isConnected(currentPeer)){toast("Conecta primero con tu contacto para enviar archivos");return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,REQUEST_DOC);
    }
    private void sendPicked(Uri uri){
        if(uri==null)return;
        String name="archivo";
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
            if(c!=null&&c.moveToFirst()){int at=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(at>=0)name=c.getString(at);}
        }catch(Exception ignored){}
        if(name==null||name.isEmpty())name="archivo";
        final String targetName=name.replaceAll("[^a-zA-Z0-9._-]","_");
        final String peer=currentPeer;
        toast("Preparando archivo…");
        new Thread(()->{
            try{
                File dir=new File(getFilesDir(),"sent");dir.mkdirs();
                File file=new File(dir,UUID.randomUUID()+"_"+targetName);
                try(InputStream source=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){
                    if(source==null)throw new IOException("No se puede leer el archivo");
                    byte[] data=new byte[8192];int n;long bytes=0;
                    while((n=source.read(data))!=-1){bytes+=n;if(bytes>LocalNode.MAX_FILE)throw new IOException("Máximo 30 MB");out.write(data,0,n);}
                }catch(Exception e){file.delete();throw e;}
                transmitFile(peer,file,targetName,false);
            }catch(Exception e){ui(()->toast("No se pudo adjuntar: "+e.getMessage()));}
        },"nodo-file").start();
    }
    private void transmitFile(String peer,File file,String name,boolean voice){
        node.sendFile(peer,file,name,voice,ok->ui(()->{
            if(ok){store.saveMessage(peer,store.newMessage(true,voice?"voice":"file","",file.getAbsolutePath(),name,true));
                if(screen.equals("chat")&&currentPeer.equals(peer))refreshChatMessages();toast("Archivo enviado");
            }else toast("No enviado. Comprueba el enlace e inténtalo de nuevo.");
        }));
    }
    private void toggleRecorder(){
        if(recording){stopRecorder(true);return;}
        if(store.isGroup(currentPeer)){toast("Las notas de voz grupales se incorporarán después");return;}
        if(!node.isConnected(currentPeer)){toast("Se necesita conexión local para enviar audio");return;}
        if(node.isCalling()){toast("Finaliza la llamada antes de grabar una nota");return;}
        micAction="record";ensureMicrophone();
    }
    private void ensureMicrophone(){
        if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);
        }else runMicAction();
    }
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] results){
        super.onRequestPermissionsResult(req,perms,results);
        if(req==REQUEST_MIC){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)runMicAction();
            else {if(micAction.startsWith("answer:"))node.rejectCall(micAction.substring(7));if(micAction.startsWith("startcall:"))node.endCall();micAction="";toast("Permiso de micrófono necesario");}}
    }
    private void runMicAction(){
        String a=micAction;micAction="";
        if(a.equals("record"))startRecorder();
        else if(a.startsWith("groupAnswer:")){
            if(nodoService!=null)nodoService.captureMicrophone(true);
            if(nodoService!=null)nodoService.acceptGroup(a.substring(12));else node.acceptGroupCall(a.substring(12));
        }else if(a.startsWith("groupOutgoing:"))makeGroupOutgoingCall(a.substring(14));
        else if(a.startsWith("answer:")){
            if(nodoService!=null)nodoService.captureMicrophone(true);
            node.acceptCall(a.substring(7));
            if(nodoService!=null && !node.isCalling())nodoService.captureMicrophone(false);
        } else if(a.startsWith("startcall:")){
            if(nodoService!=null)nodoService.captureMicrophone(true);
            node.startAudio(a.substring(10));
            if(nodoService!=null && !node.isCalling())nodoService.captureMicrophone(false);
        }
        else if(a.startsWith("outgoing:"))makeOutgoingCall(a.substring(9));
    }
    private void startRecorder(){
        try{
            File dir=new File(getFilesDir(),"voice");dir.mkdirs();voiceFile=new File(dir,UUID.randomUUID()+".m4a");
            recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(48000);recorder.setAudioSamplingRate(22050);
            recorder.setOutputFile(voiceFile.getAbsolutePath());recorder.prepare();recorder.start();recording=true;recordingPaused=false;
            recordedMs=0;recordingStart=android.os.SystemClock.elapsedRealtime();render();
        }catch(Exception ex){recording=false;if(recorder!=null)recorder.release();recorder=null;toast("Error al grabar: "+ex.getMessage());}
    }
    private void toggleRecordingPause(){
        if(!recording||recorder==null)return;
        try{
            if(recordingPaused){recorder.resume();recordingStart=android.os.SystemClock.elapsedRealtime();recordingPaused=false;}
            else{recorder.pause();recordedMs+=android.os.SystemClock.elapsedRealtime()-recordingStart;recordingPaused=true;}
            render();
        }catch(Exception e){toast("No se pudo pausar: "+e.getMessage());}
    }
    private void stopRecorder(boolean preview){
        if(!recording)return;
        recording=false;
        boolean success=true;
        try{recorder.stop();}catch(Exception ex){success=false;}
        try{recorder.release();}catch(Exception ignored){}recorder=null;
        recordingPaused=false;recordingStart=0;
        final File draft=voiceFile;voiceFile=null;
        if(!preview||!success||draft==null||draft.length()==0){
            if(draft!=null)draft.delete();render();return;
        }
        render();previewRecording(draft,currentPeer);
    }
    private void previewRecording(File draft,String peer){
        LinearLayout body=v();pad(body,20,8,20,10);
        body.addView(txt("Escucha tu nota antes de enviarla. Puedes reproducirla, descartarla o enviarla.",14,MUTED,false));
        gap(body,12);
        TextView time=txt("🎙 Nota de voz · "+voiceDuration(draft.getAbsolutePath()),16,DARK,true);
        body.addView(time);gap(body,12);
        TextView play=button("▶ Escuchar grabación",()->{
            if(player!=null && draft.getAbsolutePath().equals(audioPath) && player.isPlaying()){
                player.pause();return;
            }
            playNote(draft.getAbsolutePath());
        });body.addView(play,lp(-1,50));
        final boolean[] sent={false};
        new AlertDialog.Builder(this).setTitle("Vista previa del audio")
            .setView(body).setPositiveButton("➤ Enviar",(d,x)->{
                sent[0]=true;stopVoicePlayback();
                transmitFile(peer,draft,"Nota_de_voz.m4a",true);
            }).setNegativeButton("Descartar",(d,x)->{})
            .setOnDismissListener(d->{stopVoicePlayback();if(!sent[0])draft.delete();})
            .show();
    }
    private void playNote(String path){
        if(path.isEmpty()||!new File(path).isFile()){toast("Audio no disponible");return;}
        try{
            stopVoicePlayback();
            player=new MediaPlayer();player.setDataSource(path);player.prepare();player.start();audioPath=path;
            player.setOnCompletionListener(m->{stopVoicePlayback();});
            toast("Reproduciendo nota de voz");
        }catch(Exception ex){toast("No se puede reproducir este audio");}
    }
    private void call(String peer){
        if(!node.isConnected(peer)){toast("Este contacto no está conectado a tu red local");return;}
        if(node.isCalling()){toast("Ya hay una llamada activa");return;}
        micAction="outgoing:"+peer;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_MIC);return;
        }
        makeOutgoingCall(peer);
    }
    private void makeOutgoingCall(String peer){callPeer=peer;node.offerCall(peer);screen="call";render();}
    private void startGroupCall(String gid){
        if(node.isCalling()){toast("Ya tienes una llamada activa");return;}
        int online=0;for(String member:store.groupMembers(gid))if(node.isConnected(member))online++;
        if(online==0){toast("Conecta primero a los integrantes del grupo");return;}
        micAction="groupOutgoing:"+gid;ensureMicrophone();
    }
    private void makeGroupOutgoingCall(String gid){
        if(nodoService!=null)nodoService.captureMicrophone(true);
        node.offerGroupCall(gid,store.groupName(gid),store.groupMembers(gid));
        if(node.groupCallActive()){
            node.startAudio("group:"+gid);
            callPeer="group:"+gid;screen="call";render();
        }else{
            if(nodoService!=null)nodoService.captureMicrophone(false);
            toast("No se pudo crear la llamada grupal");
        }
    }
    private void updateCallTime(){
        if(callTimerLabel==null||node==null||!screen.equals("call"))return;
        long sec=node.callDuration();
        callTimerLabel.setText(sec<0?"Esperando respuesta…":String.format(Locale.getDefault(),"%02d:%02d:%02d",sec/3600,(sec/60)%60,sec%60));
    }

    private void showCall(){
        LinearLayout top=h();pad(top,17,20,17,14);TextView back=txt("‹",29,DARK,true);back.setOnClickListener(w->{node.endCall();if(nodoService!=null)nodoService.captureMicrophone(false);screen="chats";render();});top.addView(back);
        TextView title=txt(node.groupCallActive()?"Llamada grupal · Beta":"Llamada de voz",21,DARK,true);
        title.setGravity(Gravity.CENTER);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(top);
        LinearLayout center=v();center.setGravity(Gravity.CENTER);pad(center,20,25,20,20);
        TextView status=pill("◉ Conexión por Wi-Fi local",true);center.addView(status);
        gap(center,35);center.addView(avatar(callPeer,150));gap(center,18);
        TextView person=txt(store.peerName(callPeer),25,DARK,true);person.setGravity(Gravity.CENTER);center.addView(person);
        gap(center,8);
        TextView desc=txt(node.groupCallActive()?"👥 "+node.groupCallParticipants()+" participantes · Audio de prueba":"Llamada local · Wi-Fi",13,MUTED,false);
        desc.setGravity(Gravity.CENTER);center.addView(desc);
        gap(center,11);
        callTimerLabel=txt("Conectando…",25,GREEN,true);callTimerLabel.setGravity(Gravity.CENTER);center.addView(callTimerLabel);
        updateCallTime();
        if(callTimer!=null)handler.removeCallbacks(callTimer);
        callTimer=new Runnable(){@Override public void run(){
            if(screen.equals("call")&&node!=null){updateCallTime();handler.postDelayed(this,1000);}
        }};
        handler.postDelayed(callTimer,1000);
        root.addView(center,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout buttons=h();buttons.setGravity(Gravity.CENTER);pad(buttons,12,10,12,20);
        TextView mute=pill(muted?"Micrófono apagado":"🎙 Silenciar",false);
        mute.setOnClickListener(w->{muted=!muted;node.mute(muted);mute.setText(muted?"Micrófono apagado":"🎙 Silenciar");});buttons.addView(mute);
        gapHorizontal(buttons,12);
        TextView speaker=pill("🔊 Altavoz",false);speaker.setOnClickListener(w->{loud=!loud;node.speaker(loud);speaker.setText(loud?"Altavoz activo":"🔊 Altavoz");});buttons.addView(speaker);
        root.addView(buttons);
        TextView end=txt("☎  Finalizar llamada",18,Color.WHITE,true);end.setGravity(Gravity.CENTER);end.setBackground(shape(Color.rgb(234,67,83),24));
        LinearLayout.LayoutParams e=lp(-1,54);e.setMargins(dp(30),dp(6),dp(30),dp(30));root.addView(end,e);
        end.setOnClickListener(w->{node.endCall();if(nodoService!=null)nodoService.captureMicrophone(false);screen="chats";render();});
    }
    private void openLocal(String path){openLocal(path,new File(path).getName());}
    private void openLocal(String path,String name){
        File file=new File(path);if(!file.isFile()){toast("No se encontró el archivo");return;}
        NodeViewer.open(this,file,name);
    }
    private void editProfile(){
        LinearLayout form=v();pad(form,22,10,22,5);
        form.addView(txt("Nombre visible",13,MUTED,true));
        EditText name=new EditText(this);name.setSingleLine(true);name.setText(store.name());form.addView(name);
        form.addView(txt("Estado",13,MUTED,true));
        EditText status=new EditText(this);status.setSingleLine(true);status.setText(store.status());form.addView(status);
        TextView photo=pill("📷 Cambiar foto de perfil",true);photo.setOnClickListener(w->{
            Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("image/*");startActivityForResult(i,REQUEST_PHOTO);
        });form.addView(photo);
        new AlertDialog.Builder(this).setTitle("Mi perfil NODO").setView(form)
            .setPositiveButton("Guardar",(a,b)->{
                String n=name.getText().toString().trim();if(n.isEmpty())n="Usuario NODO";
                store.name(n.length()>32?n.substring(0,32):n);store.status(status.getText().toString().trim());render();
                toast("Perfil guardado");
            }).setNegativeButton("Cancelar",null).show();
    }
    private void saveProfilePhoto(Uri uri){
        try(InputStream in=getContentResolver().openInputStream(uri)){
            Bitmap bm=BitmapFactory.decodeStream(in);if(bm==null)throw new IOException("Imagen inválida");
            int scale=Math.max(bm.getWidth(),bm.getHeight());
            int w=Math.max(1,bm.getWidth()*512/scale),h=Math.max(1,bm.getHeight()*512/scale);
            Bitmap small=Bitmap.createScaledBitmap(bm,w,h,true);
            File f=new File(getFilesDir(),"profile.jpg");try(FileOutputStream o=new FileOutputStream(f)){small.compress(Bitmap.CompressFormat.JPEG,85,o);}
            store.avatar(f.getAbsolutePath());render();toast("Foto guardada");
        }catch(Exception e){toast("No se pudo guardar la foto: "+e.getMessage());}
    }
    private void privacySettings(){
        LinearLayout content=v();pad(content,19,9,19,9);
        Switch visibility=new Switch(this);visibility.setText("Visible para usuarios cercanos");
        visibility.setTextColor(DARK);visibility.setChecked(store.discoverable());
        visibility.setOnCheckedChangeListener((v,on)->{
            if(node!=null)node.setDiscoverable(on);else store.discoverable(on);
            toast(on?"NODO visible en Wi-Fi local":"NODO dejó de anunciarse");
        });content.addView(visibility);gap(content,11);
        Switch unknown=new Switch(this);unknown.setText("Permitir solicitudes de desconocidos");
        unknown.setTextColor(DARK);unknown.setChecked(store.allowNewPairing());
        unknown.setOnCheckedChangeListener((v,on)->store.allowNewPairing(on));
        content.addView(unknown);gap(content,11);
        Switch lastSeen=new Switch(this);lastSeen.setText("Mostrar última conexión");
        lastSeen.setTextColor(DARK);lastSeen.setChecked(store.shareLastSeen());
        lastSeen.setOnCheckedChangeListener((v,on)->store.shareLastSeen(on));
        content.addView(lastSeen);gap(content,12);
        content.addView(txt("Los contactos ya autorizados pueden seguir conectándose aunque no anuncies tu NODO. "
            +"La última conexión compartida dependerá del protocolo que usen ambos dispositivos.",12,MUTED,false));
        gap(content,12);
        content.addView(button("Administrar dispositivos autorizados",this::trustedContactsDialog),lp(-1,48));
        new AlertDialog.Builder(this).setTitle("Privacidad")
            .setView(content).setPositiveButton("Guardar",null).show();
    }
    private void trustedContactsDialog(){
        List<String> ids=store.contacts();String[] options=new String[ids.size()];
        for(int i=0;i<ids.size();i++)options[i]=store.peerName(ids.get(i))+
            (store.trusted(ids.get(i))?" · autorizado":" · sin autorización");
        new AlertDialog.Builder(this).setTitle("Dispositivos autorizados")
            .setMessage(ids.isEmpty()?"Todavía no hay dispositivos enlazados.":
                "Elige un contacto para retirar su autorización de reconexión.")
            .setItems(options,(a,index)->{
                String id=ids.get(index);
                new AlertDialog.Builder(this).setTitle("Revocar enlace")
                    .setMessage("¿Retirar la autorización de "+store.peerName(id)+"?")
                    .setPositiveButton("Revocar",(d,n)->{store.revoke(id);toast("Autorización retirada");render();})
                    .setNegativeButton("Cancelar",null).show();
            }).setPositiveButton("Cerrar",null).show();
    }
    private void notificationSettings(){
        LinearLayout content=v();pad(content,18,9,18,6);
        Switch messages=new Switch(this);messages.setText("Notificar mensajes recibidos");
        messages.setChecked(store.messageNotifications());messages.setTextColor(DARK);
        messages.setOnCheckedChangeListener((v,on)->store.messageNotifications(on));
        content.addView(messages);gap(content,8);
        Switch msgSounds=new Switch(this);msgSounds.setText("Sonido de mensajes");
        msgSounds.setChecked(store.messageSounds());msgSounds.setTextColor(DARK);
        msgSounds.setOnCheckedChangeListener((v,on)->store.messageSounds(on));
        content.addView(msgSounds);gap(content,8);
        Switch calls=new Switch(this);calls.setText("Notificar llamadas entrantes");
        calls.setChecked(store.callNotifications());calls.setTextColor(DARK);
        calls.setOnCheckedChangeListener((v,on)->store.callNotifications(on));
        content.addView(calls);gap(content,8);
        Switch callSounds=new Switch(this);callSounds.setText("Tono de llamada");
        callSounds.setChecked(store.callSounds());callSounds.setTextColor(DARK);
        callSounds.setOnCheckedChangeListener((v,on)->store.callSounds(on));
        content.addView(callSounds);gap(content,12);
        TextView note=txt("Usa los sonidos del teléfono. Puedes cambiar el tono de los mensajes desde las opciones de notificaciones de Android. Los avisos funcionan mientras NODO permanezca en ejecución.",13,MUTED,false);
        content.addView(note);gap(content,12);
        TextView system=button("Configurar sonidos en Android",()->NodeAlerts.openSystemSoundSettings(this));
        content.addView(system,lp(-1,48));
        new AlertDialog.Builder(this).setTitle("Notificaciones de NODO")
            .setView(content).setPositiveButton("Listo",null).show();
    }
    private void backgroundSettings(){
        LinearLayout layout=v();pad(layout,22,9,22,9);
        Switch enabled=new Switch(this);
        enabled.setText("Mantener NODO activo en segundo plano");
        enabled.setTextColor(DARK);enabled.setChecked(store.backgroundEnabled());
        enabled.setOnCheckedChangeListener((button,active)->{
            store.backgroundEnabled(active);
            if(active){
                try{NodoService.start(this);toast("NODO permanecerá conectado");}
                catch(Exception e){toast("No se pudo iniciar el servicio: "+e.getMessage());}
            }else{
                NodoService.stopBackground(this);
                toast("Segundo plano desactivado. NODO funcionará mientras esté abierto.");
            }
        });
        layout.addView(enabled);gap(layout,12);
        layout.addView(txt("Con la opción activada se mostrará una notificación permanente de NODO conectado. "
            +"Podrás recibir mensajes y llamadas mientras uses otras apps o apagues la pantalla, si Android lo permite.",13,MUTED,false));
        gap(layout,10);
        layout.addView(txt("Importante: ambos teléfonos deben seguir en la misma red Wi-Fi o punto de acceso. "
            +"Si desconectas Wi-Fi, fuerzas el cierre de NODO o Android restringe su batería, el enlace puede interrumpirse.",13,MUTED,false));
        gap(layout,13);
        TextView battery=button("Abrir ajustes de batería",()->{
            Intent intent=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+getPackageName()));
            try{startActivity(intent);}catch(Exception e){toast("Abre Ajustes > Apps > NODO > Batería");}
        });
        layout.addView(battery,lp(-1,48));
        new AlertDialog.Builder(this).setTitle("NODO en segundo plano")
            .setView(layout).setPositiveButton("Listo",null).show();
    }
    private void connectivitySettings(){
        LinearLayout layout=v();pad(layout,21,10,21,10);
        Switch lan=new Switch(this);lan.setText("Usar Wi-Fi local");lan.setTextColor(DARK);
        lan.setChecked(true);lan.setEnabled(false);layout.addView(lan);gap(layout,5);
        layout.addView(txt("Transporte activo de esta versión. Funciona en una red o hotspot compartido, incluso sin internet.",12,MUTED,false));gap(layout,13);
        Switch bluetooth=new Switch(this);bluetooth.setText("Usar Bluetooth");
        bluetooth.setTextColor(MUTED);bluetooth.setChecked(false);bluetooth.setEnabled(false);
        layout.addView(bluetooth);gap(layout,5);
        layout.addView(txt("En desarrollo · Aún no hay canal Bluetooth funcional.",12,MUTED,false));gap(layout,13);
        Switch mobile=new Switch(this);mobile.setText("Usar datos móviles (respaldo)");
        mobile.setTextColor(MUTED);mobile.setChecked(false);mobile.setEnabled(false);
        layout.addView(mobile);gap(layout,5);
        layout.addView(txt("En desarrollo · Requiere servidor de señalización y conexión segura.",12,MUTED,false));gap(layout,13);
        Switch auto=new Switch(this);auto.setText("Reconexión automática con conocidos");
        auto.setTextColor(DARK);auto.setChecked(store.autoConnect());
        auto.setOnCheckedChangeListener((b,on)->store.autoConnect(on));layout.addView(auto);gap(layout,14);
        layout.addView(txt("Wi-Fi Direct (sin router) se añadirá como transporte independiente. NODO nunca indica conexiones inexistentes.",12,MUTED,false));
        new AlertDialog.Builder(this).setTitle("Conectividad NODO").setView(layout)
            .setPositiveButton("Guardar",null).show();
    }
    private void chatSettings(){
        List<String> ids=store.contacts();String[] opts={"Exportar una conversación como texto","Borrar mensajes de un contacto"};
        new AlertDialog.Builder(this).setTitle("Chats").setItems(opts,(x,choice)->{
            if(ids.isEmpty()){toast("Aún no tienes contactos");return;}
            String[] names=new String[ids.size()];for(int i=0;i<ids.size();i++)names[i]=store.peerName(ids.get(i));
            new AlertDialog.Builder(this).setTitle("Seleccionar contacto").setItems(names,(y,k)->{
                String id=ids.get(k);
                if(choice==0){
                    StringBuilder sb=new StringBuilder("Conversación NODO con "+store.peerName(id)+"\n\n");JSONArray arr=store.messages(id);
                    for(int j=0;j<arr.length();j++){
                        JSONObject m=arr.optJSONObject(j);if(m!=null){sb.append(m.optBoolean("out")?"Yo: ":store.peerName(id)+": ")
                            .append(m.optString("kind").equals("text")?m.optString("body"):"["+m.optString("name")+"]").append("\n");}
                    }
                    Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,sb.toString());
                    startActivity(Intent.createChooser(share,"Exportar conversación"));
                }else new AlertDialog.Builder(this).setTitle("Eliminar mensajes")
                    .setMessage("Se borrará el historial local de "+store.peerName(id)+". Los archivos físicos permanecen almacenados.")
                    .setPositiveButton("Borrar",(z,t)->{store.clearMessages(id);render();toast("Historial borrado");})
                    .setNegativeButton("Cancelar",null).show();
            }).show();
        }).show();
    }
    private void storageSettings(){
        long size=0;int files=0;
        for(String type:new String[]{"received","sent","voice"}){
            File[] f=new File(getFilesDir(),type).listFiles();if(f!=null)for(File x:f){size+=x.length();files++;}
        }
        info("Almacenamiento","Archivos guardados: "+files+"\nEspacio aproximado: "+(size/1024)+" KB\n\nTamaño máximo por transferencia: 30 MB. Los archivos están en el almacenamiento privado de NODO; puedes abrirlos desde Archivos.");
    }
    private void help(){info("Cómo conectar NODO",
        "1. Instala y abre NODO en dos teléfonos Android.\n\n2. Conéctalos al mismo Wi-Fi. Puede ser el hotspot de un teléfono sin internet.\n\n3. Abre Cercanos y toca Enlazar. El otro usuario debe aceptar.\n\n4. Abre Chats para enviar texto, notas de voz y archivos, o iniciar llamada de voz.\n\n5. La reconexión automática solo se aplica a usuarios autorizados. Para seguir activo con pantalla apagada, usa Ajustes > Segundo plano.\n\nNODO alfa usa Wi-Fi local. Bluetooth, Wi-Fi Direct, videollamadas, internet remoto y cifrado de extremo a extremo siguen pendientes. Android puede detener la app por ahorro de batería.");}
    private void shareInvite(){
        Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,"Te invito a probar NODO, una app de mensajes y llamadas por Wi-Fi local sin depender de internet. Necesitamos instalar NODO en ambos Android y usar la misma red Wi-Fi.");
        startActivity(Intent.createChooser(share,"Invitar a NODO"));
    }
    private void info(String title,String content){new AlertDialog.Builder(this).setTitle(title).setMessage(content).setPositiveButton("Entendido",null).show();}
    private void featurePending(String what){info(what,"Esta función es parte del diseño de NODO, pero aún no está implementada en la versión alfa. El botón no inicia una conexión simulada.");}
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(res!=RESULT_OK||data==null)return;
        if(req==REQUEST_CAMERA){
            Bitmap photo=data.getParcelableExtra("data");
            if(photo==null){toast("La cámara no devolvió ninguna imagen");return;}
            try{
                File dir=new File(getFilesDir(),"sent");dir.mkdirs();
                File file=new File(dir,"foto_"+UUID.randomUUID()+".jpg");
                try(FileOutputStream out=new FileOutputStream(file)){photo.compress(Bitmap.CompressFormat.JPEG,88,out);}
                transmitFile(currentPeer,file,file.getName(),false);
            }catch(Exception e){toast("No se pudo compartir la foto");}
            return;
        }
        if(data.getData()==null)return;
        if(req==REQUEST_DOC)sendPicked(data.getData());
        else if(req==REQUEST_PHOTO)saveProfilePhoto(data.getData());
    }
    @Override public void onBackPressed(){
        if(screen.equals("call")){node.endCall();if(nodoService!=null)nodoService.captureMicrophone(false);screen="chats";render();}
        else if(screen.equals("chat")){if(recording)stopRecorder(false);screen="chats";render();}
        else if(!screen.equals("discover")){screen="discover";render();}
        else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(callTimer!=null)handler.removeCallbacks(callTimer);
        stopVoicePlayback();
        if(recording)stopRecorder(false);
        if(player!=null){player.release();player=null;}
        if(nodoService!=null)nodoService.detach(this);
        if(bindRequested){unbindService(connection);bindRequested=false;bound=false;}
        // The service—not this screen—keeps listening for peers and calls.
        super.onDestroy();
    }
    /** A decorative abstract radar; the UI never pretends to measure distance. */
    private final class Radar extends View{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Radar(Context c){super(c);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);int w=getWidth(),h=getHeight();float cx=w/2f,cy=h/2f,r=Math.min(w,h)*0.43f;
            p.setStyle(Paint.Style.FILL);for(int i=4;i>=1;i--){
                p.setColor(Color.argb(28+(4-i)*8,35,211,151));c.drawCircle(cx,cy,r*i/4f,p);
            }
            p.setColor(GREEN);c.drawCircle(cx,cy,dp(29),p);
            p.setColor(Color.WHITE);p.setTextSize(dp(30));p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.CENTER);
            c.drawText("∞",cx,cy+dp(11),p);
            p.setColor(BLUE);for(int i=0;i<4;i++){
                double a=(i*90+33)*Math.PI/180;
                float x=cx+(float)Math.cos(a)*r*.77f,y=cy+(float)Math.sin(a)*r*.77f;
                c.drawCircle(x,y,dp(12),p);
                p.setColor(Color.WHITE);p.setTextSize(dp(14));c.drawText("●",x,y+dp(5),p);p.setColor(BLUE);
            }
        }
    }
}
