package com.heathen.persephone;
import android.graphics.*;
import android.view.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import java.lang.reflect.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w420dp-h900dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PortraitPreviewTest {
    @Test public void renderPhoneHudAndMap() throws Exception {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity app=controller.get();new PinGate(app).verify("9818");Method start=MainActivity.class.getDeclaredMethod("startSession",boolean.class);start.setAccessible(true);start.invoke(app,true);
            Method sample=MainActivity.class.getDeclaredMethod("generateDemo");sample.setAccessible(true);for(int i=0;i<96;i++)sample.invoke(app);
            Field tab=MainActivity.class.getDeclaredField("tab");tab.setAccessible(true);Field target=MainActivity.class.getDeclaredField("track");target.setAccessible(true);target.set(app,"Wi-Fi/02:00:00:00:00:02");
            Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);File directory=new File("build/previews");directory.mkdirs();
            for(int index:new int[]{0,1,2}){tab.set(app,index);render.invoke(app);View decor=app.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));decor.layout(0,0,420,900);Bitmap image=Bitmap.createBitmap(420,900,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(directory,"phone-"+index+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}assertEquals(420,decor.getWidth());}
        }
    }
}
