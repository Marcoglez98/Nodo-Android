package com.nodo.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Grant-only provider for files created by NODO; no access to arbitrary app paths. */
public final class NodeFileProvider extends ContentProvider {
    public static Uri uri(String folder,String name){return new Uri.Builder().scheme("content").authority("com.nodo.messenger.beta.files").appendPath(folder).appendPath(name).build();}
    private File file(Uri u)throws FileNotFoundException {
        List<String> p=u.getPathSegments();
        if(p.size()!=2 || !(p.get(0).equals("received")||p.get(0).equals("sent")||p.get(0).equals("voice")||p.get(0).equals("updates")))throw new FileNotFoundException("Ruta no permitida");
        if(p.get(1).contains("/")||p.get(1).contains(".."))throw new FileNotFoundException("Nombre no permitido");
        File f=new File(new File(getContext().getFilesDir(),p.get(0)),p.get(1));
        if(!f.isFile())throw new FileNotFoundException("Archivo no encontrado");
        return f;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){try{
        String name=file(uri).getName();int dot=name.lastIndexOf('.');
        String ext=dot<0?"":name.substring(dot+1).toLowerCase(java.util.Locale.ROOT);
        String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime==null?"application/octet-stream":mime;
    }catch(Exception ignored){return "application/octet-stream";}}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Solo lectura");
        return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try{File f=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor c=new MatrixCursor(cols);Object[] v=new Object[cols.length];
            for(int i=0;i<cols.length;i++){
                if(cols[i].equals(OpenableColumns.DISPLAY_NAME))v[i]=f.getName();
                if(cols[i].equals(OpenableColumns.SIZE))v[i]=f.length();
            }c.addRow(v);return c;
        }catch(Exception ex){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
}
