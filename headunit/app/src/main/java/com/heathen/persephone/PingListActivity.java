package com.heathen.persephone;
import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONObject;

@android.annotation.SuppressLint("SetTextI18n")
public final class PingListActivity extends Activity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);if(!PinGate.require(this))return;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);OverlayService.hideForEditor(this);}
    @Override protected void onResume(){super.onResume();if(!PinGate.require(this))return;LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackground(new HudBackground());root.setPadding(24,100,24,24);TextView title=label("PINGS // FIELD NOTES",22,0xffffde43);root.addView(title);root.addView(label("Choose a ping to capture phone GPS, attach photos, or set a nickname. Unlocated pings can be annotated here.",14,0xffafc4ce));
        for(JSONObject ping:PingStore.get(this).list()){String name=ping.optString("nickname");if(name.isEmpty())name=ping.optString("name");Button button=new Button(this);button.setText(name+"\n"+ping.optString("radio")+" / "+ping.optString("address")+"\n"+(PingStore.point(ping)==null?"NO GPS NOTE":ping.optString("tags")));button.setTextColor(SignalMapView.color(ping.optString("tags")));button.setOnClickListener(v->{if(PinGate.require(this))startActivity(new Intent(this,PingEditorActivity.class).putExtra("ping_id",ping.optString("id")));});HudBackground.style(button);button.setTextColor(SignalMapView.color(ping.optString("tags")));root.addView(button);}
        Button done=new Button(this);done.setText("Return to overlay / map");done.setOnClickListener(v->{OverlayService.showAfterEditor(this);finish();});HudBackground.style(done);root.addView(done);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
    }
    private TextView label(String text,int size,int color){TextView label=new TextView(this);label.setText(text);label.setTextSize(size);label.setTextColor(color);label.setTypeface(Typeface.MONOSPACE);return label;}
}
