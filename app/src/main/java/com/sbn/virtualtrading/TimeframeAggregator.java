package com.sbn.virtualtrading;

import java.util.*;

public final class TimeframeAggregator {
    private TimeframeAggregator() {}
    public static List<Candle> aggregateMinutes(List<Candle> src,int minutes){
        if(minutes<=1)return new ArrayList<>(src);
        List<Candle> out=new ArrayList<>(); TimeZone tz=TimeZone.getTimeZone("Asia/Kolkata");
        long activeKey=Long.MIN_VALUE,t=0; double o=0,h=0,l=0,cl=0; long v=0;
        for(Candle c:src){
            Calendar cal=Calendar.getInstance(tz);cal.setTimeInMillis(c.time);
            int hh=cal.get(Calendar.HOUR_OF_DAY),mm=cal.get(Calendar.MINUTE);
            int minOfDay=hh*60+mm; if(minOfDay<555||minOfDay>930)continue; // 09:15–15:30 IST
            int day=cal.get(Calendar.YEAR)*10000+(cal.get(Calendar.MONTH)+1)*100+cal.get(Calendar.DAY_OF_MONTH);
            int fromOpen=minOfDay-555; int bucket=fromOpen/minutes;
            long key=((long)day)*10000L+bucket;
            if(key!=activeKey){
                if(activeKey!=Long.MIN_VALUE)out.add(new Candle(t,o,h,l,cl,v));
                activeKey=key;t=c.time;o=c.open;h=c.high;l=c.low;cl=c.close;v=c.volume;
            }else{h=Math.max(h,c.high);l=Math.min(l,c.low);cl=c.close;v+=c.volume;}
        }
        if(activeKey!=Long.MIN_VALUE)out.add(new Candle(t,o,h,l,cl,v));
        return out;
    }
}
