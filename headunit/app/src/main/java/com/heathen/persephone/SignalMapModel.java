package com.heathen.persephone;
import java.util.*;

/** Relative RSSI path-loss fit. A model-derived search area, not measured transmitter coordinates. */
final class SignalMapModel {
    static final class Point {
        final double lat, lon, accuracy; final int rssi; final String key, tag; final long time; final boolean demo;
        Point(double lat, double lon, double accuracy, int rssi, String key, String tag, long time, boolean demo) {
            this.lat=lat; this.lon=lon; this.accuracy=accuracy; this.rssi=rssi; this.key=key; this.tag=tag; this.time=time; this.demo=demo;
        }
        boolean valid() { return Double.isFinite(lat) && Double.isFinite(lon) && Double.isFinite(accuracy) && Math.abs(lat)<=85.0511 && Math.abs(lon)<=180 && accuracy>0 && accuracy<=1000 && rssi>=-127 && rssi<=0; }
    }
    static final class Estimate {
        final double lat,lon,radius,rms; final boolean demo;
        Estimate(double lat,double lon,double radius,double rms,boolean demo) {this.lat=lat;this.lon=lon;this.radius=radius;this.rms=rms;this.demo=demo;}
    }
    static double meters(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat2-lat1), b=Math.toRadians(lon2-lon1);
        double v=Math.sin(a/2)*Math.sin(a/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(b/2)*Math.sin(b/2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,v)));
    }
    static Estimate estimate(List<Point> observations, String key) {
        if (key.isEmpty()) return null;
        long newest=0; for(Point p:observations) if(p.key.equals(key)) newest=Math.max(newest,p.time);
        List<Point> points=new ArrayList<>();
        for(Point p:observations) {
            if(!p.key.equals(key)||!p.valid()||p.accuracy>25||p.rssi> -15||p.rssi< -110||newest-p.time>900000) continue;
            boolean distinct=true; for(Point old:points) if(meters(p.lat,p.lon,old.lat,old.lon)<6) {distinct=false;break;}
            if(distinct) points.add(p); if(points.size()>=80) break;
        }
        if(points.size()<8) return null;
        boolean demo=points.get(0).demo; for(Point p:points) if(p.demo!=demo) return null;
        double lat=0,lon=0; int minRssi=0,maxRssi=-127; for(Point p:points){lat+=p.lat;lon+=p.lon;minRssi=Math.min(minRssi,p.rssi);maxRssi=Math.max(maxRssi,p.rssi);} lat/=points.size();lon/=points.size();
        if(maxRssi-minRssi<10) return null;
        double longitudeScale=111320*Math.cos(Math.toRadians(lat)), latitudeScale=111320;
        double[] x=new double[points.size()],y=new double[points.size()]; double minX=1e9,maxX=-1e9,minY=1e9,maxY=-1e9,xx=0,yy=0,xy=0;
        for(int i=0;i<x.length;i++){Point p=points.get(i);x[i]=(p.lon-lon)*longitudeScale;y[i]=(p.lat-lat)*latitudeScale;minX=Math.min(minX,x[i]);maxX=Math.max(maxX,x[i]);minY=Math.min(minY,y[i]);maxY=Math.max(maxY,y[i]);xx+=x[i]*x[i];yy+=y[i]*y[i];xy+=x[i]*y[i];}
        double width=Math.max(maxX-minX,maxY-minY); if(width<60||width>2000) return null;
        double discriminant=Math.sqrt((xx-yy)*(xx-yy)+4*xy*xy), major=(xx+yy+discriminant)/2,minor=(xx+yy-discriminant)/2;
        if(major<=0||minor/major<.10) return null; // a straight driving route cannot constrain both axes
        double centerX=(maxX+minX)/2,centerY=(maxY+minY)/2,span=width*2,step=span/40;
        double best=1e9,bestX=0,bestY=0; int bestI=0,bestJ=0; double[][] errors=new double[41][41];
        for(int i=0;i<=40;i++)for(int j=0;j<=40;j++){
            double cx=centerX-span/2+i*step,cy=centerY-span/2+j*step,error=fit(points,x,y,cx,cy);errors[i][j]=error;
            if(error<best){best=error;bestX=cx;bestY=cy;bestI=i;bestJ=j;}
        }
        if(best>3.5||bestI<2||bestI>38||bestJ<2||bestJ>38) return null;
        double radius=25; boolean edge=false;
        for(int i=0;i<=40;i++)for(int j=0;j<=40;j++)if(errors[i][j]<=best+1){
            if(i==0||j==0||i==40||j==40) edge=true;
            radius=Math.max(radius,Math.hypot(centerX-span/2+i*step-bestX,centerY-span/2+j*step-bestY)+25);
        }
        if(edge||radius>width) return null; // ambiguous/unbounded fit: show observations only
        return new Estimate(lat+bestY/latitudeScale,lon+bestX/longitudeScale,radius,best,demo);
    }
    private static double fit(List<Point> points,double[] x,double[] y,double cx,double cy) {
        double[] u=new double[x.length]; double um=0,rm=0;
        for(int i=0;i<u.length;i++){u[i]=-10*Math.log10(Math.max(3,Math.hypot(x[i]-cx,y[i]-cy)));um+=u[i];rm+=points.get(i).rssi;}um/=u.length;rm/=u.length;
        double covariance=0,variance=0;for(int i=0;i<u.length;i++){covariance+=(u[i]-um)*(points.get(i).rssi-rm);variance+=(u[i]-um)*(u[i]-um);}
        if(variance<1e-9) return 1e9;
        double exponent=covariance/variance;if(exponent<1.3||exponent>4) return 1e9;
        double sum=0;for(int i=0;i<u.length;i++){double residual=points.get(i).rssi-(rm+exponent*(u[i]-um));sum+=residual*residual;}return Math.sqrt(sum/u.length);
    }
}
