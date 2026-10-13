package com.heathen.persephone;
import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

@android.annotation.SuppressLint("SetTextI18n")
public final class OverlayCalibrationActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);if(!PinGate.require(this))return;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);OverlayService.hideForEditor(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(new HudBackground());root.setPadding(24,90,24,24);
        root.addView(text("FLAT MAP // CALIBRATION",22,0xffffde43));root.addView(text("Enter coordinates of two known visible landmarks. Use a flat map (no 3D tilt), disable auto-follow, and leave the viewport unchanged after locking. This does not read Google Maps' camera state.",15,0xffb0c6d0));
        EditText latA=input("Landmark A latitude"),lonA=input("Landmark A longitude"),latB=input("Landmark B latitude"),lonB=input("Landmark B longitude");for(EditText edit:new EditText[]{latA,lonA,latB,lonB})root.addView(edit);
        TextView error=text("Anchors must be ≥30 m apart. Next, place A/B over the matching landmarks and press LOCK in the floating legend. Dots pause after outside touches and expire after 30 seconds; automatic map movement cannot be detected.",14,0xffb0c6d0);root.addView(error);
        Button place=new Button(this);place.setText("Place anchors over my map");place.setOnClickListener(v->{if(!PinGate.require(this))return;try{double a=Double.parseDouble(latA.getText().toString()),b=Double.parseDouble(lonA.getText().toString()),c=Double.parseDouble(latB.getText().toString()),d=Double.parseDouble(lonB.getText().toString());if(!OverlayProjection.validGeo(a,b)||!OverlayProjection.validGeo(c,d)||SignalMapModel.meters(a,b,c,d)<30)throw new IllegalArgumentException("Enter two valid landmarks ≥30 m apart");startService(new Intent(this,OverlayService.class).setAction(OverlayService.PLACE).putExtra("lat_a",a).putExtra("lon_a",b).putExtra("lat_b",c).putExtra("lon_b",d));finish();}catch(Exception e){error.setText(e.getMessage());}});HudBackground.style(place);root.addView(place);
        Button cancel=new Button(this);cancel.setText("Cancel / return to overlay");cancel.setOnClickListener(v->{OverlayService.showAfterEditor(this);finish();});HudBackground.style(cancel);root.addView(cancel);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
    }
    private EditText input(String hint){EditText edit=new EditText(this);edit.setHint(hint);edit.setHintTextColor(0xffb0c6d0);edit.setTextColor(Color.WHITE);edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);return edit;}
    private TextView text(String value,int size,int color){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setTypeface(Typeface.MONOSPACE);text.setPadding(0,10,0,10);return text;}
}
