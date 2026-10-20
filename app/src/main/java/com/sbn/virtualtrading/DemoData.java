package com.sbn.virtualtrading;
import java.util.*;

public final class DemoData {
    public static List<Candle> make(){
        List<Candle> a=new ArrayList<>(); long t=System.currentTimeMillis()-180L*60_000L; double p=265.0; Random r=new Random(7);
        for(int i=0;i<180;i++){
            double o=p, drift=(r.nextDouble()-.48)*2.2; double cl=Math.max(10,o+drift); double h=Math.max(o,cl)+r.nextDouble()*1.4; double l=Math.min(o,cl)-r.nextDouble()*1.4; long v=20000+r.nextInt(120000);
            a.add(new Candle(t+i*60_000L,o,h,l,cl,v)); p=cl;
        }
        return a;
    }
}
