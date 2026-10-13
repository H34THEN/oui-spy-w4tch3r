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
@Config(sdk = 35, qualifiers = "w960dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PreviewTest {
    @Test public void renderLandscapeScreensOnAndroid35() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity app = controller.get(); new PinGate(app).verify("9818");
            Method start = MainActivity.class.getDeclaredMethod("startSession", boolean.class); start.setAccessible(true); start.invoke(app, true);
            Method sample = MainActivity.class.getDeclaredMethod("generateDemo"); sample.setAccessible(true); for (int i = 0; i < 96; i++) sample.invoke(app);
            Field tab = MainActivity.class.getDeclaredField("tab"); tab.setAccessible(true);
            Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
            File dir = new File("build/previews"); dir.mkdirs();
            for (int index = 0; index < 5; index++) {
                tab.set(app, index);
                if(index==2){Field target=MainActivity.class.getDeclaredField("track");target.setAccessible(true);target.set(app,"Wi-Fi/02:00:00:00:00:02");}
                render.invoke(app);
                View decor = app.getWindow().getDecorView();
                decor.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 960, 480);
                Bitmap bitmap = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888); decor.draw(new Canvas(bitmap));
                try (FileOutputStream out = new FileOutputStream(new File(dir, "landscape-" + index + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
                assertEquals(960, decor.getWidth()); assertEquals(480, decor.getHeight());
            }
        }
    }
}
