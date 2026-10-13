package com.heathen.persephone;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

// Programmatic-only view: its injected tile cache and selection callback cannot be inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class SignalMapView extends View {
    interface Selection {void select(String key);}
    private final TileCache tiles; private final Selection selection;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),tilePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector drag; private final ScaleGestureDetector pinch;
    private List<SignalMapModel.Point> points=Collections.emptyList();
    private String target=""; private SignalMapModel.Estimate estimate;
    private double lat,lon; private int zoom=17; private boolean centered,follow=true;
    private final float density;
    private final RectF tileRect=new RectF();
    private final DashPathEffect dashed;
    SignalMapView(Context context,TileCache tiles,Selection selection) {
        super(context);this.tiles=tiles;this.selection=selection;density=getResources().getDisplayMetrics().density; dashed=new DashPathEffect(new float[]{8*density,5*density},0);
        setContentDescription("Interactive signal map. Drag to pan, pinch or use zoom buttons. Points are receiver observations; dashed area is a model estimate.");
        ColorMatrix matrix=new ColorMatrix(new float[]{.16f,.16f,.16f,0,0,.18f,.18f,.18f,0,8,.22f,.22f,.22f,0,14,0,0,0,1,0});tilePaint.setColorFilter(new ColorMatrixColorFilter(matrix));
        drag=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent e){return true;}
            public boolean onScroll(MotionEvent a,MotionEvent b,float dx,float dy){double[] c=world(lat,lon);fromWorld(c[0]+dx/density,c[1]+dy/density);follow=false;invalidate();return true;}
            public boolean onSingleTapUp(MotionEvent e){SignalMapModel.Point best=null;double distance=30*density;for(SignalMapModel.Point p:points){float[] xy=screen(p.lat,p.lon);double d=Math.hypot(xy[0]-e.getX(),xy[1]-e.getY());if(d<distance){distance=d;best=p;}}if(best!=null)selection.select(best.key);return true;}
        });
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){float factor=1;public boolean onScaleBegin(ScaleGestureDetector detector){factor=1;return true;}public boolean onScale(ScaleGestureDetector d){factor*=d.getScaleFactor();if(factor>1.35){zoom(1);factor=1;}else if(factor<.74){zoom(-1);factor=1;}return true;}});
    }
    void setData(List<SignalMapModel.Point> source,String selected){points=new ArrayList<>(source);if(points.isEmpty())centered=false;target=selected;estimate=SignalMapModel.estimate(points,target);if(!points.isEmpty()&&(!centered||follow)){SignalMapModel.Point p=points.get(points.size()-1);lat=p.lat;lon=p.lon;centered=true;}invalidate();}
    SignalMapModel.Estimate estimate(){return estimate;}
    void zoom(int delta){zoom=Math.max(3,Math.min(19,zoom+delta));invalidate();}
    void center(){follow=true;if(!points.isEmpty()){SignalMapModel.Point p=points.get(points.size()-1);lat=p.lat;lon=p.lon;centered=true;}invalidate();}
    @Override public boolean onTouchEvent(MotionEvent e){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(e.getAction()!=MotionEvent.ACTION_UP&&e.getAction()!=MotionEvent.ACTION_CANCEL);pinch.onTouchEvent(e);boolean handled=drag.onTouchEvent(e);if(handled&&e.getAction()==MotionEvent.ACTION_UP)performClick();return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    private double[] world(double latitude,double longitude){double size=256*(1<<zoom),phi=Math.toRadians(Math.max(-85.0511,Math.min(85.0511,latitude)));return new double[]{(longitude+180)/360*size,(1-Math.log(Math.tan(phi)+1/Math.cos(phi))/Math.PI)/2*size};}
    private void fromWorld(double x,double y){double size=256*(1<<zoom);lon=(x/size*360-180+540)%360-180;lat=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*y/size))));lat=Math.max(-85.0511,Math.min(85.0511,lat));}
    private float[] screen(double latitude,double longitude){double[] p=world(latitude,longitude),c=world(lat,lon);double dx=p[0]-c[0],size=256*(1<<zoom);if(dx>size/2)dx-=size;if(dx< -size/2)dx+=size;return new float[]{(float)(getWidth()/2+dx*density),(float)(getHeight()/2+(p[1]-c[1])*density)};}
    private void stroke(int color,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width*density);paint.setPathEffect(null);}
    private void label(Canvas canvas,String text,float x,float y,int color,float size){paint.setColor(color);paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(size*density);paint.setPathEffect(null);canvas.drawText(text,x,y,paint);}
    static int color(String tag){if(tag.contains("RAVEN"))return Color.rgb(222,105,255);if(tag.contains("FALCON")||tag.contains("LPR"))return Color.rgb(255,222,67);if(tag.contains("FLOCK"))return Color.rgb(65,244,231);return Color.rgb(116,159,186);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(Color.rgb(5,13,20));stroke(Color.rgb(20,46,58),.5f);
        for(float x=0;x<getWidth();x+=32*density)canvas.drawLine(x,0,x,getHeight(),paint);for(float y=0;y<getHeight();y+=32*density)canvas.drawLine(0,y,getWidth(),y,paint);
        if(centered){double[] center=world(lat,lon);double left=center[0]-getWidth()/density/2,top=center[1]-getHeight()/density/2;
            int startX=(int)Math.floor(left/256),startY=(int)Math.floor(top/256),endX=(int)Math.floor((left+getWidth()/density)/256),endY=(int)Math.floor((top+getHeight()/density)/256);
            for(int x=startX;x<=endX;x++)for(int y=startY;y<=endY;y++){Bitmap tile=tiles.get(zoom,x,y,this::invalidate);if(tile!=null){tileRect.set((float)((x*256-left)*density),(float)((y*256-top)*density),(float)(((x+1)*256-left)*density),(float)(((y+1)*256-top)*density));canvas.drawBitmap(tile,null,tileRect,tilePaint);}}
        }
        // The receiver route is drawn only between time-near samples: no invented jumps.
        SignalMapModel.Point previous=null;stroke(Color.rgb(42,117,119),1.5f);
        for(SignalMapModel.Point p:points){if(previous!=null&&Math.abs(p.time-previous.time)<30000&&SignalMapModel.meters(p.lat,p.lon,previous.lat,previous.lon)<500){float[] a=screen(previous.lat,previous.lon),b=screen(p.lat,p.lon);canvas.drawLine(a[0],a[1],b[0],b[1],paint);}previous=p;}
        SignalMapModel.Point strongest=null;int begin=Math.max(0,points.size()-600);
        for(int i=begin;i<points.size();i++){SignalMapModel.Point p=points.get(i);if(!target.isEmpty()&&!target.equals(p.key))continue;float[] xy=screen(p.lat,p.lon);paint.setColor(color(p.tag)==Color.rgb(116,159,186)&&p.key.contains("BLE/")?0xff79a6ff:color(p.tag));paint.setStyle(Paint.Style.FILL);paint.setAlpha(180);canvas.drawCircle(xy[0],xy[1],(p.key.equals(target)?5:3)*density,paint);paint.setAlpha(255);if(strongest==null||p.rssi>strongest.rssi)strongest=p;}
        if(strongest!=null&&!target.isEmpty()){float[] s=screen(strongest.lat,strongest.lon);stroke(Color.WHITE,1.5f);canvas.drawCircle(s[0],s[1],10*density,paint);label(canvas,String.format(Locale.ROOT,"STRONGEST RX ±%.0f m",strongest.accuracy),s[0]+12*density,s[1]-10*density,Color.WHITE,10);}
        if(estimate!=null){float[] e=screen(estimate.lat,estimate.lon);double metersPerPixel=Math.cos(Math.toRadians(estimate.lat))*40075016.686/(256*(1<<zoom))/density;
            stroke(Color.rgb(255,162,66),2);paint.setPathEffect(dashed);canvas.drawCircle(e[0],e[1],(float)(estimate.radius/metersPerPixel),paint);paint.setPathEffect(null);canvas.drawLine(e[0]-8*density,e[1],e[0]+8*density,e[1],paint);canvas.drawLine(e[0],e[1]-8*density,e[0],e[1]+8*density,paint);label(canvas,(estimate.demo?"DEMO ":"")+"MODEL SEARCH AREA",e[0]+12*density,e[1]+18*density,Color.rgb(255,162,66),10);
        }
        stroke(Color.rgb(58,244,222),1);canvas.drawRect(1,1,getWidth()-1,getHeight()-1,paint);
        label(canvas,centered?String.format(Locale.ROOT,"%.5f  %.5f   Z%02d",lat,lon,zoom):"AWAITING GPS OBSERVATIONS",10*density,20*density,Color.rgb(58,244,222),11);
        label(canvas,"POINTS = RECEIVER POSITIONS",10*density,getHeight()-26*density,Color.rgb(173,198,208),10);
        label(canvas,"© OpenStreetMap contributors",10*density,getHeight()-10*density,Color.rgb(173,198,208),10);
    }
}
