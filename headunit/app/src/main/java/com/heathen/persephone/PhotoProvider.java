package com.heathen.persephone;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the chosen camera access to one private UUID JPEG; no general file access. */
public final class PhotoProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    static Uri uri(Context context,String name){return new Uri.Builder().scheme("content").authority(context.getPackageName()+".photos").appendPath("photo").appendPath(name).build();}
    private File file(Uri uri)throws FileNotFoundException{String name=uri.getLastPathSegment();if(!uri.getAuthority().equals(getContext().getPackageName()+".photos")||uri.getPathSegments().size()!=2||!uri.getPathSegments().get(0).equals("photo")||name==null||!name.matches("[0-9a-f-]{36}\\.jpg"))throw new FileNotFoundException("Invalid photo path");File folder=new File(getContext().getFilesDir(),"ping-photos");if(!folder.isDirectory()&&!folder.mkdirs())throw new FileNotFoundException("Photo directory unavailable");return new File(folder,name);}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode));}
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File file=file(uri);MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});cursor.addRow(new Object[]{file.getName(),file.length()});return cursor;}catch(IOException e){throw new IllegalArgumentException(e);}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
