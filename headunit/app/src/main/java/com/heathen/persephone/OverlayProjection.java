package com.heathen.persephone;

/** Two user-aligned landmarks for a flat Mercator map snapshot. No other-app viewport access. */
final class OverlayProjection {
    private final double u,v,x,y,a,b;
    private OverlayProjection(double u,double v,double x,double y,double a,double b){this.u=u;this.v=v;this.x=x;this.y=y;this.a=a;this.b=b;}
    static OverlayProjection calibrate(double latA,double lonA,double xA,double yA,double latB,double lonB,double xB,double yB){
        if(!validGeo(latA,lonA)||!validGeo(latB,lonB)||!Double.isFinite(xA)||!Double.isFinite(yA)||!Double.isFinite(xB)||!Double.isFinite(yB))throw new IllegalArgumentException("Invalid calibration coordinates");
        double[] first=mercator(latA,lonA),second=mercator(latB,lonB);double du=second[0]-first[0],dv=second[1]-first[1],dx=xB-xA,dy=yB-yA,norm=du*du+dv*dv;
        if(norm<900||norm>250000000000.0||Math.hypot(dx,dy)<80)throw new IllegalArgumentException("Landmarks must be ≥30 m apart and ≥80 screen pixels apart");
        return new OverlayProjection(first[0],first[1],xA,yA,(dx*du+dy*dv)/norm,(dy*du-dx*dv)/norm);
    }
    double[] screen(double lat,double lon){if(!validGeo(lat,lon))throw new IllegalArgumentException("Invalid ping coordinates");double[] p=mercator(lat,lon);return new double[]{x+a*(p[0]-u)-b*(p[1]-v),y+b*(p[0]-u)+a*(p[1]-v)};}
    static boolean validGeo(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=85.0511&&Math.abs(lon)<=180;}
    private static double[] mercator(double lat,double lon){return new double[]{6378137*Math.toRadians(lon),-6378137*Math.log(Math.tan(Math.PI/4+Math.toRadians(lat)/2))};}
}
