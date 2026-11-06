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

/** First alpha: a native UI, LAN discovery, user-approved pairing, chat, files, notes and voice calls. */
public final class MainActivity extends Activity implements LocalNode.Listener {
    private static final int GREEN=Color.rgb(32,202,145), DARK=Color.rgb(19,36,51), MUTED=Color.rgb(107,123,134);
    private static final int PALE=Color.rgb(225,252,239), BG=Color.rgb(248,251,253), BLUE=Color.rgb(65,174,231);
    private static final int REQUEST_DOC=41,REQUEST_PHOTO=42,REQUEST_MIC=43;
    private Store store; private LocalNode node; private NodoService nodoService;
    private boolean bound=false,bindRequested=false;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            nodoService=((NodoService.LocalBinder)binder).service();
            node=nodoService.network();bound=true;
            nodoService.attach(MainActivity.this);
            render();handleLaunch(getIntent());
            String caller=nodoService.pendingCaller();
            if(caller!=null&&!caller.isEmpty()&&node.isCalling())showIncomingCallDialog(caller);
        }
        @Override public void onServiceDisconnected(ComponentName name){
            bound=false;node=null;nodoService=null;
            renderDisconnected();
        }
    };
    private LinearLayout root;private ScrollView chatScroll;private EditText composer;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String screen="discover",currentPeer="",callPeer="",networkStatus="Iniciando red local…",micAction="";
    private boolean recording=false,muted=false,loud=false,foreground=false,callDialogOpen=false;
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
        renderDisconnected();
        if(store.backgroundEnabled()){
            try{NodoService.start(this);}catch(Exception ex){
                Toast.makeText(this,"No se pudo activar segundo plano: "+ex.getMessage(),Toast.LENGTH_LONG).show();
            }
        }
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
    @Override protected void onStart(){
        super.onStart();
        if(!bindRequested)
            bindRequested=bindService(new Intent(this,NodoService.class),connection,Context.BIND_AUTO_CREATE);
    }
    @Override protected void onStop(){
        if(nodoService!=null)nodoService.detach(this);
        if(bindRequested){unbindService(connection);bindRequested=false;bound=false;}
        node=null;nodoService=null;
        super.onStop();
    }
    @Override protected void onResume(){
        super.onResume();foreground=true;
        if(nodoService!=null){
            nodoService.attach(this);
            String pending=nodoService.pendingCaller();
            if(pending!=null&&!pending.isEmpty()&&node!=null&&node.isCalling())pendingCaller=pending;
        }
        if(!pendingCaller.isEmpty()&&node!=null&&node.isCalling()){
            handler.post(()->showIncomingCallDialog(pendingCaller));
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
            if(foreground)showIncomingCallDialog(peer);
        }else if(store!=null&&node!=null){currentPeer=peer;screen="chat";render();}
    }
    private void intro(){new AlertDialog.Builder(this).setTitle("Bienvenido a NODO")
        .setMessage("Crea tu perfil para aparecer con tu nombre cuando otro teléfono NODO esté en la misma red Wi-Fi local.")
        .setPositiveButton("Configurar",(a,b)->editProfile()).setNegativeButton("Después",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void ui(Runnable r){runOnUiThread(r);}
    @Override public void changed(){ui(()->{
        String draft=screen.equals("chat")&&composer!=null?composer.getText().toString():"";
        flushPending();if(!screen.equals("call")){render();
            if(screen.equals("chat")&&composer!=null&&!draft.isEmpty()){composer.setText(draft);composer.setSelection(draft.length());}
        }
    });}
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
            if(screen.equals("chat")&&currentPeer.equals(p.id)&&foreground)render();
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
        for(String id:store.contacts())if(node.isConnected(id)){
            JSONArray a=store.messages(id);
            for(int i=0;i<a.length();i++){
                JSONObject m=a.optJSONObject(i);
                if(m!=null&&m.optBoolean("out")&&m.optString("kind").equals("text")&&!m.optBoolean("sent")){
                    if(node.send(id,"text",m.optString("body")))store.markSent(id,m.optString("mid"));
                }
            }
        }
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
        t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(true);
        if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
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
        t.setBackground(gradient());t.setOnClickListener(w->action.run());return t;
    }
    private void title(LinearLayout l,String text,String subtitle){
        TextView t=txt(text,24,DARK,true);l.addView(t);
        if(subtitle!=null&&!subtitle.isEmpty()){
            TextView s=txt(subtitle,13,MUTED,false);LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(5);l.addView(s,p);
        }
    }
    private LinearLayout card(){LinearLayout l=v();pad(l,15,14,15,14);l.setBackground(border(Color.WHITE,18,Color.rgb(235,242,245)));return l;}
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
        root=v();root.setBackgroundColor(BG);setContentView(root);
        // Android 15+ draws behind system bars. Respect actual bar AND keyboard insets
        // instead of using a fixed top margin that would fail on different devices.
        if(Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                android.graphics.Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
                int top=bars.top+dp(5), bottom=Math.max(bars.bottom,keyboard.bottom);
                if(view.getPaddingTop()!=top||view.getPaddingBottom()!=bottom)
                    view.setPadding(0,top,0,bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
        if(screen.equals("chat")){showChat();return;}
        if(screen.equals("call")){showCall();return;}
        LinearLayout header=h();pad(header,16,12,16,8);header.setBackgroundColor(Color.WHITE);
        header.addView(imageLogo(38));TextView brand=txt("NODO",23,DARK,true);header.addView(brand);
        View space=new View(this);header.addView(space,new LinearLayout.LayoutParams(0,1,1));
        TextView mini=txt("●  Red local",11,GREEN,true);header.addView(mini);
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
        LinearLayout nav=h();nav.setBackgroundColor(Color.WHITE);pad(nav,4,7,4,8);
        String[] keys={"discover","chats","calls","files","settings"};
        String[] icons={"◎","▤","☎","▣","⚙"};String[] names={"Cercanos","Chats","Llamadas","Archivos","Ajustes"};
        for(int i=0;i<keys.length;i++){
            final String k=keys[i];LinearLayout b=v();b.setGravity(Gravity.CENTER);pad(b,0,3,0,3);
            TextView icon=txt(icons[i],23,k.equals(screen)?GREEN:MUTED,false);icon.setGravity(Gravity.CENTER);
            TextView label=txt(names[i],10,k.equals(screen)?GREEN:MUTED,k.equals(screen));label.setGravity(Gravity.CENTER);
            b.addView(icon);b.addView(label);nav.addView(b,new LinearLayout.LayoutParams(0,dp(53),1));
            b.setOnClickListener(w->{screen=k;render();});
        }
        root.addView(nav,lp(-1,66));
    }
    private void showDiscover(LinearLayout body){
        title(body,"Descubrir","Personas y dispositivos que usan NODO cerca de ti");gap(body,13);
        Radar r=new Radar(this);body.addView(r,lp(-1,180));gap(body,10);
        TextView banner=pill("●  "+networkStatus,false);body.addView(banner);gap(body,15);
        LinearLayout row=h();row.addView(txt("Encontrados en Wi-Fi local",16,DARK,true),new LinearLayout.LayoutParams(0,-2,1));
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
            text.addView(txt(match!=null&&node.isConnected(match)?"● Conectado":"Disponible por Wi-Fi local",12,match!=null&&node.isConnected(match)?GREEN:MUTED,false));
            line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            TextView btn=pill(match!=null&&node.isConnected(match)?"Abrir":"Enlazar",true);
            btn.setOnClickListener(w->{if(match!=null&&node.isConnected(match)){currentPeer=match;screen="chat";render();}
                else {node.connect(p);toast("Solicitando enlace. La otra persona debe autorizarlo.");}});
            line.addView(btn);c.addView(line);body.addView(c);gap(body,9);
        }
        gap(body,10);body.addView(button("🔗  Crear enlace / compartir mi NODO",()->{
            new AlertDialog.Builder(this).setTitle("Tu enlace NODO")
                .setMessage("Tu dispositivo ya está anunciándose como NODO-"+store.id().substring(0,8)+". Para enlazar, abre NODO en otro teléfono en la misma red Wi-Fi, entra en Cercanos y pulsa Enlazar. No necesitas acceso a internet.")
                .setPositiveButton("Entendido",null).show();
        }),lp(-1,48));
        gap(body,12);body.addView(txt("Reconexión automática con contactos autorizados. Para recibir mensajes al cerrar la pantalla, activa Segundo plano en Ajustes.",12,MUTED,false));
    }
    private void showChats(LinearLayout body){
        title(body,"Chats","Mensajes, fotos, archivos y notas de voz");gap(body,14);
        LinearLayout tabs=h();TextView all=pill("Todos",true), near=pill("Conectados",false), groups=pill("Grupos (próximamente)",false);
        all.setOnClickListener(w->toast("Mostrando todas las conversaciones"));
        near.setOnClickListener(w->showOnlineOnly());
        groups.setOnClickListener(w->featurePending("Grupos"));
        tabs.addView(all);gapHorizontal(tabs,6);tabs.addView(near);body.addView(tabs);gap(body,12);
        List<String> ids=store.contacts();
        if(ids.isEmpty()){
            LinearLayout c=card();c.addView(txt("Empieza tu primera conversación",17,DARK,true));gap(c,6);
            c.addView(txt("Ve a Cercanos, encuentra otro teléfono NODO y solicita el enlace.",13,MUTED,false));gap(c,12);
            c.addView(button("Buscar personas",()->{screen="discover";render();}));body.addView(c);
        }
        for(String id:ids){
            LinearLayout c=contactRow(id);c.setOnClickListener(w->openChat(id));body.addView(c);gap(body,8);
        }
    }
    private void showOnlineOnly(){
        List<String> connected=new ArrayList<>();for(String id:store.contacts())if(node.isConnected(id))connected.add(store.peerName(id));
        new AlertDialog.Builder(this).setTitle("Conectados ahora")
            .setMessage(connected.isEmpty()?"No tienes contactos conectados actualmente.":android.text.TextUtils.join("\n",connected))
            .setPositiveButton("Cerrar",null).show();
    }
    private void gapHorizontal(LinearLayout l,int width){l.addView(new View(this),lp(width,1));}
    private LinearLayout contactRow(String id){
        LinearLayout outer=card();LinearLayout row=h();row.addView(avatar(id,48));
        LinearLayout desc=v();pad(desc,12,0,0,0);desc.addView(txt(store.peerName(id),16,DARK,true));
        JSONArray a=store.messages(id);JSONObject last=a.optJSONObject(a.length()-1);
        String preview=last==null?"Toca para iniciar chat":last.optString("kind").equals("text")?last.optString("body"):last.optString("kind").equals("voice")?"🎙 Nota de voz":"📎 "+last.optString("name");
        if(preview.length()>48)preview=preview.substring(0,48)+"…";
        desc.addView(txt(preview,13,MUTED,false));
        row.addView(desc,new LinearLayout.LayoutParams(0,-2,1));
        TextView connection=txt(node.isConnected(id)?"●":"○",21,node.isConnected(id)?GREEN:MUTED,true);row.addView(connection);
        outer.addView(row);return outer;
    }
    private void showCalls(LinearLayout body){
        title(body,"Llamadas","Voz por Wi-Fi local con personas enlazadas");gap(body,13);
        LinearLayout c=card();c.addView(txt("Para llamar, abre un chat y toca ☎.",15,DARK,true));gap(c,5);
        c.addView(txt("Las llamadas de voz funcionan mientras ambos teléfonos están conectados en la red local y la app está abierta. La videollamada se incorporará después.",13,MUTED,false));
        body.addView(c);gap(body,12);
        for(String id:store.contacts())if(node.isConnected(id)){
            LinearLayout row=contactRow(id);row.setOnClickListener(w->call(id));body.addView(row);gap(body,8);
        }
        gap(body,8);
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
                row.setOnClickListener(w->openLocal(path));body.addView(row);gap(body,8);
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
        settingsRow(body,"♧","Dispositivos autorizados","Consultar y revocar confianza",this::privacySettings);
        settingsRow(body,"◐","Apariencia","Estilo original blanco y menta",()->info("Apariencia","NODO utiliza el diseño blanco, verde menta y azul elegido. Temas adicionales vendrán después."));
        settingsRow(body,"?","Ayuda","Cómo conectar dos Android",this::help);
        settingsRow(body,"↗","Invitar amigos","Compartir información sobre NODO",this::shareInvite);
        gap(body,14);body.addView(txt("NODO v0.3.0 · Prototipo alfa · Solo para pruebas. Los chats de esta versión aún NO tienen cifrado de extremo a extremo.",12,MUTED,false));
    }
    private void settingsRow(LinearLayout body,String icon,String name,String sub,Runnable action){
        LinearLayout c=card();LinearLayout row=h();
        TextView glyph=txt(icon,23,GREEN,true);glyph.setGravity(Gravity.CENTER);row.addView(glyph,lp(32,40));
        LinearLayout content=v();pad(content,10,0,0,0);content.addView(txt(name,15,DARK,true));content.addView(txt(sub,12,MUTED,false));
        row.addView(content,new LinearLayout.LayoutParams(0,-2,1));row.addView(txt("›",23,MUTED,false));c.addView(row);
        c.setOnClickListener(w->action.run());body.addView(c);gap(body,8);
    }
    private void openChat(String id){currentPeer=id;screen="chat";render();}
    private void showChat(){
        LinearLayout bar=h();pad(bar,9,9,12,9);bar.setBackgroundColor(Color.WHITE);
        TextView back=txt("‹",32,DARK,true);pad(back,5,0,9,0);back.setOnClickListener(w->{screen="chats";render();});bar.addView(back);
        bar.addView(avatar(currentPeer,42));
        LinearLayout who=v();pad(who,9,0,4,0);who.addView(txt(store.peerName(currentPeer),17,DARK,true));
        who.addView(txt(node.isConnected(currentPeer)?"● Conectado por Wi-Fi local":"○ Sin conexión — textos en espera",11,node.isConnected(currentPeer)?GREEN:MUTED,false));
        bar.addView(who,new LinearLayout.LayoutParams(0,-2,1));
        TextView call=txt("☎",25,GREEN,true);pad(call,6,2,6,2);call.setOnClickListener(w->call(currentPeer));bar.addView(call);
        TextView video=txt("▣",23,GREEN,true);pad(video,5,2,5,2);video.setOnClickListener(w->featurePending("Videollamadas"));bar.addView(video);
        root.addView(bar,lp(-1,68));
        TextView tag=txt(node.isConnected(currentPeer)?"◉  Conectado sin internet · Red Wi-Fi local":"○  Se enviarán los textos al volver a conectar",12,node.isConnected(currentPeer)?Color.rgb(8,122,78):MUTED,true);
        tag.setGravity(Gravity.CENTER);tag.setBackground(shape(node.isConnected(currentPeer)?PALE:Color.rgb(236,241,244),12));pad(tag,7,10,7,10);
        LinearLayout.LayoutParams tp=lp(-1,-2);tp.setMargins(dp(13),dp(5),dp(13),dp(6));root.addView(tag,tp);
        chatScroll=new ScrollView(this);chatScroll.setFillViewport(true);
        LinearLayout list=v();pad(list,12,8,12,12);chatScroll.addView(list);
        root.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        JSONArray items=store.messages(currentPeer);
        if(items.length()==0){TextView empty=txt("¡Saluda! 👋\nAquí aparecerán tus conversaciones.",15,MUTED,false);empty.setGravity(Gravity.CENTER);pad(empty,10,55,10,10);list.addView(empty);}
        for(int i=0;i<items.length();i++){
            JSONObject msg=items.optJSONObject(i);if(msg!=null)addBubble(list,msg);
        }
        if(recording){TextView live=txt("● Grabando nota de voz… Toca el micrófono para enviar.",13,Color.rgb(193,55,55),true);pad(live,16,6,12,6);root.addView(live);}
        LinearLayout input=h();pad(input,8,8,8,10);input.setBackgroundColor(Color.WHITE);
        TextView plus=pill("＋",false);plus.setOnClickListener(w->pickFile());input.addView(plus);
        composer=new EditText(this);composer.setSingleLine(false);composer.setMaxLines(3);
        composer.setTextSize(15);composer.setTextColor(DARK);composer.setHint("Escribe un mensaje…");
        composer.setBackground(shape(Color.rgb(241,246,248),23));pad(composer,12,7,12,7);
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,-2,1);ep.setMargins(dp(6),0,dp(6),0);input.addView(composer,ep);
        TextView send=txt("➤",22,GREEN,true);send.setGravity(Gravity.CENTER);pad(send,5,5,5,5);send.setOnClickListener(w->sendText());input.addView(send);
        TextView mic=txt(recording?"■":"🎙",22,recording?Color.RED:GREEN,true);mic.setGravity(Gravity.CENTER);pad(mic,6,5,6,5);
        mic.setOnClickListener(w->toggleRecorder());input.addView(mic);
        root.addView(input,lp(-1,-2));
        chatScroll.post(()->chatScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void addBubble(LinearLayout list,JSONObject msg){
        boolean out=msg.optBoolean("out");String kind=msg.optString("kind"),body=msg.optString("body"),path=msg.optString("path"),name=msg.optString("name");
        LinearLayout container=v();pad(container,11,9,11,8);container.setBackground(shape(out?PALE:Color.WHITE,17));
        if(kind.equals("text"))container.addView(txt(body,15,DARK,false));
        else if(kind.equals("voice")){
            TextView a=txt("▶  ▰▱▰▱▰▱▰▱  Nota de voz",14,GREEN,true);pad(a,5,5,5,5);a.setOnClickListener(w->playNote(path));container.addView(a);
        }else if(kind.equals("file")){
            if(isImage(name)&&new File(path).isFile()){
                ImageView preview=new ImageView(this);preview.setImageBitmap(decode(path,220));preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                container.addView(preview,lp(230,145));
            }
            TextView f=txt("📎 "+name,14,DARK,true);pad(f,2,8,2,4);f.setOnClickListener(w->openLocal(path));container.addView(f);
        }
        String state=out?(msg.optBoolean("sent")?"  ✓ enviado":"  ◷ pendiente"):"";
        TextView time=txt(clock.format(new Date(msg.optLong("when",System.currentTimeMillis())))+state,10,MUTED,false);
        time.setGravity(Gravity.RIGHT);container.addView(time);
        LinearLayout frame=h();frame.setGravity(out?Gravity.RIGHT:Gravity.LEFT);frame.addView(container);
        LinearLayout.LayoutParams mar=lp(-1,-2);mar.bottomMargin=dp(8);list.addView(frame,mar);
    }
    private boolean isImage(String name){String s=name.toLowerCase(Locale.ROOT);return s.endsWith(".jpg")||s.endsWith(".jpeg")||s.endsWith(".png")||s.endsWith(".webp")||s.endsWith(".gif");}
    private void sendText(){
        String text=composer.getText().toString().trim();if(text.isEmpty())return;
        boolean sent=node.send(currentPeer,"text",text);
        store.saveMessage(currentPeer,store.newMessage(true,"text",text,"","",sent));
        render();if(!sent)toast("Mensaje guardado; se enviará cuando vuelvan a conectarse");
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
                if(screen.equals("chat")&&currentPeer.equals(peer))render();toast("Archivo enviado");
            }else toast("No enviado. Comprueba el enlace e inténtalo de nuevo.");
        }));
    }
    private void toggleRecorder(){
        if(recording){stopRecorder(true);return;}
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
            recorder.setOutputFile(voiceFile.getAbsolutePath());recorder.prepare();recorder.start();recording=true;render();
        }catch(Exception ex){recording=false;if(recorder!=null)recorder.release();recorder=null;toast("Error al grabar: "+ex.getMessage());}
    }
    private void stopRecorder(boolean send){
        if(!recording)return;recording=false;boolean success=true;
        try{recorder.stop();}catch(Exception ex){success=false;}
        try{recorder.release();}catch(Exception ignored){}recorder=null;
        if(send&&success&&voiceFile!=null&&voiceFile.length()>0)transmitFile(currentPeer,voiceFile,"Nota_de_voz.m4a",true);
        else if(voiceFile!=null)voiceFile.delete();render();
    }
    private void playNote(String path){
        if(path.isEmpty()||!new File(path).isFile()){toast("Audio no disponible");return;}
        try{
            if(player!=null){player.stop();player.release();player=null;}
            player=new MediaPlayer();player.setDataSource(path);player.prepare();player.start();
            player.setOnCompletionListener(m->{m.release();if(player==m)player=null;});
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
    private void showCall(){
        LinearLayout top=h();pad(top,17,20,17,14);TextView back=txt("‹",29,DARK,true);back.setOnClickListener(w->{node.endCall();if(nodoService!=null)nodoService.captureMicrophone(false);screen="chats";render();});top.addView(back);
        TextView title=txt("Llamada de voz",21,DARK,true);title.setGravity(Gravity.CENTER);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(top);
        LinearLayout center=v();center.setGravity(Gravity.CENTER);pad(center,20,25,20,20);
        TextView status=pill("◉ Conexión por Wi-Fi local",true);center.addView(status);
        gap(center,35);center.addView(avatar(callPeer,150));gap(center,18);
        TextView person=txt(store.peerName(callPeer),25,DARK,true);person.setGravity(Gravity.CENTER);center.addView(person);
        gap(center,8);TextView desc=txt("Llamada local · audio de prueba",13,MUTED,false);desc.setGravity(Gravity.CENTER);center.addView(desc);
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
    private void openLocal(String path){
        File file=new File(path);if(!file.isFile()){toast("No se encontró el archivo");return;}
        String folder=file.getParentFile().getName();
        Uri uri=NodeFileProvider.uri(folder,file.getName());
        Intent view=new Intent(Intent.ACTION_VIEW);view.setDataAndType(uri,getContentResolver().getType(uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(view);}catch(Exception e){
            Intent share=new Intent(Intent.ACTION_SEND);share.setType(getContentResolver().getType(uri));
            share.putExtra(Intent.EXTRA_STREAM,uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share,"Compartir archivo NODO"));
        }
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
        List<String> ids=store.contacts();String[] options=new String[ids.size()];
        for(int i=0;i<ids.size();i++) options[i]=store.peerName(ids.get(i))+(store.trusted(ids.get(i))?" · autorizado":" · sin autorización");
        new AlertDialog.Builder(this).setTitle("Dispositivos y privacidad")
            .setMessage(ids.isEmpty()?"Todavía no has enlazado teléfonos. Cada nuevo dispositivo necesitará tu autorización.":"Toca un dispositivo para retirar el permiso de reconexión automática.")
            .setItems(options,(a,i)->{
                final String id=ids.get(i);new AlertDialog.Builder(this).setTitle("Revocar autorización")
                    .setMessage("¿Quitar la confianza automática para "+store.peerName(id)+"? La próxima conexión necesitará tu autorización.")
                    .setPositiveButton("Revocar",(d,x)->{store.revoke(id);toast("Autorización revocada");render();})
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
        LinearLayout layout=v();pad(layout,22,10,22,10);
        TextView a=txt("Wi-Fi local (activo)",15,DARK,true);layout.addView(a);gap(layout,6);
        layout.addView(txt("NODO detecta y enlaza dispositivos conectados al mismo Wi-Fi o hotspot, sin necesidad de internet.",13,MUTED,false));gap(layout,12);
        Switch auto=new Switch(this);auto.setText("Reconectar usuarios autorizados");auto.setTextColor(DARK);auto.setChecked(store.autoConnect());
        auto.setOnCheckedChangeListener((b,c)->{store.autoConnect(c);toast(c?"Reconexión automática activada":"Reconexión automática desactivada");});layout.addView(auto);gap(layout,12);
        layout.addView(txt("Bluetooth, Wi-Fi Direct y datos móviles: previstos para siguientes versiones. No están activos en este prototipo.",13,MUTED,false));
        new AlertDialog.Builder(this).setTitle("Conectividad NODO").setView(layout).setPositiveButton("Cerrar",null).show();
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
        if(res!=RESULT_OK||data==null||data.getData()==null)return;
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
