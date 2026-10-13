package com.heathen.persephone;
import android.content.*;
import android.graphics.*;
import android.view.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import java.time.Instant;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w420dp-h900dp-port-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnnotationPreviewTest {
    @Test public void fieldNoteEditorRendersWithSyntheticPing()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();new PinGate(context).verify("9818");
        JSONObject row=new JSONObject().put("source","DEMO").put("radio","Wi-Fi").put("address","02:00:00:00:00:AB").put("name","Falcon workshop demo").put("time",Instant.now().toString()).put("rssi",-60);DetectionAnnotator.annotate(row);
        PingStore store=PingStore.get(context);String id=store.observe(row);store.nickname(id,"Demo checkpoint","Synthetic presentation sample");
        try(ActivityController<PingEditorActivity> controller=Robolectric.buildActivity(PingEditorActivity.class,new Intent(context,PingEditorActivity.class).putExtra("ping_id",id)).setup()){
            View decor=controller.get().getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));decor.layout(0,0,420,900);Bitmap image=Bitmap.createBitmap(420,900,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image));File directory=new File("build/previews");directory.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(directory,"phone-notes.png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}assertFalse(controller.get().isFinishing());
        }
    }
}
