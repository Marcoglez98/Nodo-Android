package com.nodo.messenger;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * In-app viewer for shared files. It does NOT route supported files to an external app.
 * Office formats use a deliberately simplified text preview, not an Office renderer.
 */
public final class NodeViewer {
    private static final int INK=Color.rgb(21,46,53);
    private static final int GREEN=Color.rgb(26,175,134);
    private static final int BG=Color.rgb(246,250,251);
    private NodeViewer() {}

    private static int dp(Context context,float px){return (int)(context.getResources().getDisplayMetrics().density*px+.5f);}
    private static TextView label(Context c,String text,int size,boolean bold){
        TextView t=new TextView(c);t.setText(text);t.setTextColor(INK);t.setTextSize(size);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;
    }
    private static GradientDrawable rounded(int color,int radius,Context c){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(c,radius));return g;
    }
    private static boolean is(String name,String...extensions){
        String lower=name.toLowerCase(Locale.ROOT);for(String x:extensions)if(lower.endsWith(x))return true;return false;
    }
    public static void open(Activity activity,File file,String displayName){
        if(!file.isFile()){
            new AlertDialog.Builder(activity).setMessage("No se encuentra el archivo en NODO.").setPositiveButton("Cerrar",null).show();return;
        }
        String name=displayName==null||displayName.isEmpty()?file.getName():displayName;
        Dialog dialog=new Dialog(activity);
        LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity,15),dp(activity,10),dp(activity,15),dp(activity,10));header.setBackgroundColor(Color.WHITE);
        TextView close=label(activity,"✕",23,true);close.setGravity(Gravity.CENTER);
        header.addView(close,new LinearLayout.LayoutParams(dp(activity,44),dp(activity,48)));
        close.setOnClickListener(v->dialog.dismiss());
        LinearLayout info=new LinearLayout(activity);info.setOrientation(LinearLayout.VERTICAL);
        TextView title=label(activity,name,16,true);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(title);TextView subtitle=label(activity,"Vista previa dentro de NODO",12,false);
        subtitle.setTextColor(Color.rgb(115,136,142));info.addView(subtitle);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(activity,9);header.addView(info,ip);
        layout.addView(header,new LinearLayout.LayoutParams(-1,dp(activity,73)));
        FrameLayout content=new FrameLayout(activity);layout.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(layout);
        Window win=dialog.getWindow();if(win!=null){win.setBackgroundDrawableResource(android.R.color.transparent);win.setLayout(-1,-1);}
        dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setLayout(-1,-1);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        if(is(name,".jpg",".jpeg",".png",".webp",".gif",".bmp"))showImage(activity,file,content);
        else if(is(name,".pdf"))showPdf(activity,file,content,dialog);
        else if(is(name,".mp4",".3gp",".webm",".mkv"))showVideo(activity,file,content,dialog);
        else if(is(name,".mp3",".m4a",".aac",".ogg",".wav"))showAudio(activity,file,content,dialog);
        else if(is(name,".txt",".md",".csv",".json",".xml",".log",".html",".java",".kt",".yaml",".yml",".rtf"))showText(activity,content,readUtf8(file),null);
        else if(is(name,".docx",".pptx",".xlsx"))showText(activity,content,previewOffice(file,name),"Vista previa de texto, sin formato original");
        else showUnsupported(activity,file,content,name);
    }

    private static void showImage(Context ctx,File f,FrameLayout frame){
        ZoomImage view=new ZoomImage(ctx);view.setBackgroundColor(Color.rgb(26,35,38));
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(f.getAbsolutePath(),opts);
        int s=1;while(opts.outWidth/s>2600||opts.outHeight/s>2600)s*=2;
        opts.inJustDecodeBounds=false;opts.inSampleSize=s;
        Bitmap bitmap=BitmapFactory.decodeFile(f.getAbsolutePath(),opts);
        if(bitmap!=null){view.setImageBitmap(bitmap);frame.addView(view,new FrameLayout.LayoutParams(-1,-1));}
        else showText(ctx,frame,"No se pudo interpretar esta imagen.",null);
    }
    private static final class ZoomImage extends androidxNotUsedImageView {
        // Uses only Android SDK APIs: gestures and matrix for pinch-to-zoom.
        final Matrix m=new Matrix();final ScaleGestureDetector zoom;
        float scale=1f,lastX,lastY;boolean moving;
        ZoomImage(Context c){super(c);setScaleType(ScaleType.MATRIX);
            zoom=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){
                    float next=Math.max(1f,Math.min(5f,scale*d.getScaleFactor()));
                    float factor=next/scale;scale=next;m.postScale(factor,factor,d.getFocusX(),d.getFocusY());setImageMatrix(m);return true;
                }
            });
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);fit();}
        @Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);post(this::fit);}
        private void fit(){
            if(getDrawable()==null||getWidth()==0||getHeight()==0)return;
            float dw=getDrawable().getIntrinsicWidth(),dh=getDrawable().getIntrinsicHeight();
            if(dw==0||dh==0)return;
            float k=Math.min((float)getWidth()/dw,(float)getHeight()/dh);
            m.reset();m.postScale(k,k);m.postTranslate((getWidth()-dw*k)/2f,(getHeight()-dh*k)/2f);
            scale=1f;setImageMatrix(m);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent event){
            zoom.onTouchEvent(event);
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN: lastX=event.getX();lastY=event.getY();moving=true;return true;
                case MotionEvent.ACTION_MOVE: if(!zoom.isInProgress()&&moving && scale>1){
                    m.postTranslate(event.getX()-lastX,event.getY()-lastY);setImageMatrix(m);
                }lastX=event.getX();lastY=event.getY();return true;
                case MotionEvent.ACTION_UP: moving=false;return true;
                case MotionEvent.ACTION_CANCEL: moving=false;return true;
            }
            return true;
        }
    }
    /** Named base avoids a third party dependency for the image widget. */
    private static class androidxNotUsedImageView extends ImageView {
        androidxNotUsedImageView(Context c){super(c);}
    }

    private static void showPdf(Context ctx,File f,FrameLayout content,Dialog dialog){
        try{
            ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);
            PdfRenderer pdf=new PdfRenderer(descriptor);
            LinearLayout wrapper=new LinearLayout(ctx);wrapper.setOrientation(LinearLayout.VERTICAL);
            ImageView page=new ImageView(ctx);page.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ScrollView scroller=new ScrollView(ctx);scroller.setFillViewport(true);scroller.addView(page);
            wrapper.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout bottom=new LinearLayout(ctx);bottom.setGravity(Gravity.CENTER);bottom.setPadding(8,12,8,12);
            TextView previous=label(ctx,"‹  Anterior",14,true),next=label(ctx,"Siguiente  ›",14,true),index=label(ctx,"",13,false);
            previous.setGravity(Gravity.CENTER);next.setGravity(Gravity.CENTER);index.setGravity(Gravity.CENTER);
            bottom.addView(previous,new LinearLayout.LayoutParams(0,dp(ctx,42),1));
            bottom.addView(index,new LinearLayout.LayoutParams(0,dp(ctx,42),1));
            bottom.addView(next,new LinearLayout.LayoutParams(0,dp(ctx,42),1));
            wrapper.addView(bottom);content.addView(wrapper,new FrameLayout.LayoutParams(-1,-1));
            final int[] current={0};final Bitmap[] last={null};
            Runnable render=()->{
                try(PdfRenderer.Page p=pdf.openPage(current[0])){
                    int width=Math.min(1400,Math.max(600,ctx.getResources().getDisplayMetrics().widthPixels*2));
                    int height=Math.max(1,(int)((float)p.getHeight()/p.getWidth()*width));
                    if(height>2600){width=width*2600/height;height=2600;}
                    Bitmap b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
                    p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.setImageBitmap(b);if(last[0]!=null)last[0].recycle();last[0]=b;
                    index.setText((current[0]+1)+" / "+pdf.getPageCount());
                    previous.setAlpha(current[0]==0?.35f:1f);next.setAlpha(current[0]==pdf.getPageCount()-1?.35f:1f);
                    scroller.scrollTo(0,0);
                }catch(Exception e){index.setText("Error de lectura");}
            };
            previous.setOnClickListener(v->{if(current[0]>0){current[0]--;render.run();}});
            next.setOnClickListener(v->{if(current[0]<pdf.getPageCount()-1){current[0]++;render.run();}});
            dialog.setOnDismissListener(d->{page.setImageDrawable(null);if(last[0]!=null)last[0].recycle();pdf.close();try{descriptor.close();}catch(IOException ignored){}});
            if(pdf.getPageCount()>0)render.run();
        }catch(Exception e){showText(ctx,content,"No se pudo abrir este PDF dentro de NODO.\n\n"+e.getMessage(),null);}
    }
    private static void showVideo(Context ctx,File f,FrameLayout frame,Dialog dialog){
        VideoView player=new VideoView(ctx);player.setVideoPath(f.getAbsolutePath());
        player.setBackgroundColor(Color.BLACK);
        frame.addView(player,new FrameLayout.LayoutParams(-1,-1));
        MediaController controls=new MediaController(ctx);controls.setAnchorView(player);player.setMediaController(controls);
        player.setOnPreparedListener(mp->{mp.setLooping(false);player.start();controls.show(3000);});
        player.setOnErrorListener((mp,what,extra)->{
            frame.removeAllViews();showText(ctx,frame,"El video no se pudo reproducir con los códecs de este teléfono.",null);return true;
        });
        dialog.setOnDismissListener(d->player.stopPlayback());
    }
    private static void showAudio(Context ctx,File f,FrameLayout frame,Dialog dialog){
        LinearLayout layout=new LinearLayout(ctx);layout.setOrientation(LinearLayout.VERTICAL);layout.setGravity(Gravity.CENTER);
        layout.setPadding(26,24,26,24);
        TextView icon=label(ctx,"♫",62,true);icon.setTextColor(GREEN);icon.setGravity(Gravity.CENTER);layout.addView(icon);
        TextView name=label(ctx,f.getName(),15,true);name.setGravity(Gravity.CENTER);layout.addView(name);
        SeekBar progress=new SeekBar(ctx);layout.addView(progress,new LinearLayout.LayoutParams(-1,dp(ctx,55)));
        Button toggle=new Button(ctx);toggle.setText("▶ Reproducir");layout.addView(toggle);
        frame.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        try{
            android.media.MediaPlayer mp=new android.media.MediaPlayer();mp.setDataSource(f.getAbsolutePath());mp.prepare();
            progress.setMax(Math.max(1,mp.getDuration()));
            android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable[] tick={null};tick[0]=()->{try{if(mp.isPlaying())progress.setProgress(mp.getCurrentPosition());h.postDelayed(tick[0],250);}catch(Exception ignored){}};
            toggle.setOnClickListener(v->{if(mp.isPlaying()){mp.pause();toggle.setText("▶ Reproducir");}
                else{mp.start();toggle.setText("Ⅱ Pausar");h.post(tick[0]);}});
            progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar b){} public void onProgressChanged(SeekBar b,int p,boolean from){if(from)mp.seekTo(p);}
                public void onStopTrackingTouch(SeekBar b){}
            });
            mp.setOnCompletionListener(m->{toggle.setText("▶ Reproducir");progress.setProgress(0);});
            dialog.setOnDismissListener(d->{h.removeCallbacks(tick[0]);mp.release();});
        }catch(Exception e){toggle.setEnabled(false);toggle.setText("No disponible");}
    }
    private static String readUtf8(File file){
        if(file.length()>2_000_000)return "Este archivo es demasiado grande para la vista previa de texto (máximo 2 MB).";
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }catch(IOException e){return "No se pudo leer el archivo: "+e.getMessage();}
    }
    private static void showText(Context c,FrameLayout frame,String text,String note){
        ScrollView scroll=new ScrollView(c);scroll.setFillViewport(true);
        LinearLayout body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(c,22),dp(c,20),dp(c,22),dp(c,30));
        if(note!=null){TextView h=label(c,note,12,true);h.setTextColor(GREEN);body.addView(h);}
        TextView view=label(c,text,15,false);view.setTextIsSelectable(true);view.setLineSpacing(dp(c,5),1f);body.addView(view);
        scroll.addView(body);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
    }
    private static String previewOffice(File file,String name){
        StringBuilder out=new StringBuilder();
        try(ZipFile zip=new ZipFile(file)){
            ArrayList<? extends ZipEntry> entries=Collections.list(zip.entries());
            entries.sort(Comparator.comparing(ZipEntry::getName));
            int found=0;
            for(ZipEntry entry:entries){
                String key=entry.getName();
                boolean matches=name.toLowerCase(Locale.ROOT).endsWith(".docx")?key.equals("word/document.xml"):
                    name.toLowerCase(Locale.ROOT).endsWith(".pptx")?key.matches("ppt/slides/slide[0-9]+.xml"):
                    key.equals("xl/sharedStrings.xml")||key.matches("xl/worksheets/sheet[0-9]+.xml");
                if(!matches)continue;
                if(entry.getSize()>2_000_000)continue;
                String raw;
                try(InputStream in=zip.getInputStream(entry);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;
                    while((n=in.read(buf))!=-1&&bytes.size()<2_000_000)bytes.write(buf,0,n);
                    raw=bytes.toString("UTF-8");
                }
                raw=raw.replaceAll("</(?:w:p|a:p|row|si|c|sld)>","\\n").replaceAll("<[^>]+>","");
                String clean=Html.fromHtml(raw,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\n{3,}","\\n\\n").trim();
                if(clean.length()>0){out.append(clean).append("\n\n");found++;}
                if(found>=22||out.length()>100_000)break;
            }
        }catch(Exception e){return "No se pudo crear la vista previa del documento.";}
        return out.length()>0?out.toString():"No se encontró texto para mostrar. Este formato puede contener gráficos o elementos que la vista previa no interpreta.";
    }
    private static void showUnsupported(Activity ctx,File file,FrameLayout frame,String name){
        LinearLayout body=new LinearLayout(ctx);body.setOrientation(LinearLayout.VERTICAL);body.setGravity(Gravity.CENTER);
        body.setPadding(dp(ctx,26),dp(ctx,25),dp(ctx,26),dp(ctx,25));
        TextView icon=label(ctx,"▣",58,true);icon.setTextColor(GREEN);icon.setGravity(Gravity.CENTER);body.addView(icon);
        TextView heading=label(ctx,"Vista previa no disponible",20,true);heading.setGravity(Gravity.CENTER);body.addView(heading);
        TextView detail=label(ctx,"NODO conserva este archivo ("+name+") pero todavía no puede representar su formato en pantalla.",14,false);
        detail.setGravity(Gravity.CENTER);detail.setPadding(0,dp(ctx,12),0,dp(ctx,18));body.addView(detail);
        Button share=new Button(ctx);share.setText("Compartir o abrir con otra app (opcional)");
        share.setOnClickListener(v->{
            try{
                String folder=file.getParentFile().getName();Uri uri=NodeFileProvider.uri(folder,file.getName());
                Intent intent=new Intent(Intent.ACTION_SEND).setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_STREAM,uri);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(Intent.createChooser(intent,"Compartir archivo de NODO"));
            }catch(Exception e){Toast.makeText(ctx,"Este archivo no se puede compartir",Toast.LENGTH_SHORT).show();}
        });body.addView(share);frame.addView(body,new FrameLayout.LayoutParams(-1,-1));
    }
}
