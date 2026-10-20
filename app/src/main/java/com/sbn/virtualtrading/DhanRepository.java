package com.sbn.virtualtrading;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class DhanRepository {
    private DhanRepository() {}

    public interface Progress {
        void onProgress(int done, int total, int candles, String range);
    }

    public static double ltp(String token, String clientId, String securityId) throws Exception {
        return ltp(token,clientId,securityId,"NSE_EQ");
    }

    public static double ltp(String token, String clientId, String securityId, String exchangeSegment) throws Exception {
        URL url = new URL("https://api.dhan.co/v2/marketfeed/ltp");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setDoOutput(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("access-token", token);
        c.setRequestProperty("client-id", clientId);
        JSONObject body = new JSONObject();
        JSONArray ids = new JSONArray(); ids.put(Long.parseLong(securityId));
        body.put(exchangeSegment, ids);
        try(OutputStream os=c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        String text = responseText(c);
        int code = c.getResponseCode();
        if(code<200||code>=300) throw new IOException("LTP HTTP "+code+": "+text);
        JSONObject root = new JSONObject(text);
        if(!"success".equalsIgnoreCase(root.optString("status"))) throw new IOException("LTP: "+text);
        JSONObject row = root.getJSONObject("data").getJSONObject(exchangeSegment).getJSONObject(securityId);
        return row.getDouble("last_price");
    }

    public static List<Candle> historical(String token,String securityId,String interval,String from,String to) throws Exception {
        return historical(token,securityId,"NSE_EQ","EQUITY",interval,from,to);
    }

    public static List<Candle> historical(String token,String securityId,String exchangeSegment,String instrumentType,String interval,String from,String to) throws Exception {
        boolean daily="1D".equals(interval);
        URL url=new URL(daily ? "https://api.dhan.co/v2/charts/historical" : "https://api.dhan.co/v2/charts/intraday");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setDoOutput(true);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("Content-Type","application/json");
        c.setRequestProperty("access-token",token);
        JSONObject body=new JSONObject();
        body.put("securityId",securityId); body.put("exchangeSegment",exchangeSegment); body.put("instrument",instrumentType); body.put("oi",false);
        if (daily) { if(!"INDEX".equalsIgnoreCase(instrumentType)) body.put("expiryCode",0); body.put("fromDate",from.substring(0,10)); body.put("toDate",to.substring(0,10)); }
        else { body.put("interval",interval); body.put("fromDate",from); body.put("toDate",to); }
        try(OutputStream os=c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        String text=responseText(c);
        int code=c.getResponseCode();
        if(code<200||code>=300) throw new IOException("History HTTP "+code+": "+text);
        JSONObject json=new JSONObject(text);
        if(json.has("errorCode") || json.has("errorMessage")) throw new IOException("History: "+text);
        List<Candle> parsed=parse(json);
        return daily ? parsed : filterNseSession(parsed);
    }

    public static List<Candle> historicalMax(String token, String securityId, String interval, Progress progress) throws Exception {
        return historicalMax(token,securityId,"NSE_EQ","EQUITY",interval,progress);
    }

    public static List<Candle> historicalMax(String token, String securityId, String exchangeSegment, String instrumentType, String interval, Progress progress) throws Exception {
        final boolean daily="1D".equals(interval);
        Calendar end=Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        end.add(Calendar.DAY_OF_YEAR,1);
        Calendar start=(Calendar)end.clone();
        if(daily){
            start.set(Calendar.YEAR,1990); start.set(Calendar.MONTH,Calendar.JANUARY); start.set(Calendar.DAY_OF_MONTH,1);
        } else start.add(Calendar.YEAR,-5);

        int chunkDays=chunkDays(interval);
        List<long[]> ranges=new ArrayList<>();
        Calendar cur=(Calendar)start.clone();
        while(cur.before(end)){
            Calendar nxt=(Calendar)cur.clone(); nxt.add(Calendar.DAY_OF_YEAR,chunkDays);
            if(nxt.after(end)) nxt=(Calendar)end.clone();
            ranges.add(new long[]{cur.getTimeInMillis(),nxt.getTimeInMillis()}); cur=nxt;
        }

        TreeMap<Long,Candle> merged=new TreeMap<>();
        SimpleDateFormat f=new SimpleDateFormat(daily?"yyyy-MM-dd":"yyyy-MM-dd HH:mm:ss",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        for(int i=0;i<ranges.size();i++){
            long[] r=ranges.get(i); String from=f.format(new Date(r[0])), to=f.format(new Date(r[1]));
            List<Candle> part;
            try { part=historical(token,securityId,exchangeSegment,instrumentType,interval,from,to); }
            catch(IOException ex) {
                if(ex.getMessage()!=null && ex.getMessage().contains("DH-907")){part=Collections.emptyList();}
                else {Thread.sleep(1200); part=historical(token,securityId,exchangeSegment,instrumentType,interval,from,to);}
            }
            for(Candle candle:part) merged.put(candle.time,candle);
            if(progress!=null) progress.onProgress(i+1,ranges.size(),merged.size(),from+" → "+to);
            if(i<ranges.size()-1) Thread.sleep(300);
        }
        return new ArrayList<>(merged.values());
    }

    private static int chunkDays(String interval){
        if("1D".equals(interval)) return 3650;
        return 89; // Dhan v2 allows max 90 days per intraday request; stay safely inside the boundary.
    }

    static List<Candle> parse(JSONObject o) throws Exception {
        JSONArray op=o.optJSONArray("open"), hi=o.optJSONArray("high"), lo=o.optJSONArray("low"), cl=o.optJSONArray("close"), vo=o.optJSONArray("volume"), ts=o.optJSONArray("timestamp");
        if(op==null||hi==null||lo==null||cl==null||vo==null||ts==null) throw new IOException("No candle arrays in Dhan response: "+o.toString());
        int n=Math.min(op.length(),Math.min(hi.length(),Math.min(lo.length(),Math.min(cl.length(),Math.min(vo.length(),ts.length())))));
        List<Candle> out=new ArrayList<>(n);
        for(int i=0;i<n;i++) out.add(new Candle(ts.getLong(i)*1000L,op.getDouble(i),hi.getDouble(i),lo.getDouble(i),cl.getDouble(i),vo.getLong(i)));
        return out;
    }


    static List<Candle> filterNseSession(List<Candle> src){
        ArrayList<Candle> out=new ArrayList<>(); TimeZone tz=TimeZone.getTimeZone("Asia/Kolkata");
        Calendar cal=Calendar.getInstance(tz);
        for(Candle x:src){cal.setTimeInMillis(x.time);int m=cal.get(Calendar.HOUR_OF_DAY)*60+cal.get(Calendar.MINUTE);if(m>=555&&m<=930)out.add(x);}
        return out;
    }

    private static String responseText(HttpURLConnection c)throws Exception {
        int code=c.getResponseCode(); InputStream in=(code>=200&&code<300)?c.getInputStream():c.getErrorStream(); return readAll(in);
    }
    static String readAll(InputStream in)throws Exception { if(in==null)return""; BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8)); StringBuilder sb=new StringBuilder(); String s; while((s=br.readLine())!=null)sb.append(s); return sb.toString(); }
}
