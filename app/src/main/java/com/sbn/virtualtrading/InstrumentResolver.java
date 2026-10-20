package com.sbn.virtualtrading;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * NSE_EQ symbol resolver with schema-specific Dhan CSV parsing.
 *
 * Detailed/segment-wise schema:
 *   EXCH_ID, SEGMENT, SECURITY_ID, SYMBOL_NAME, DISPLAY_NAME, ...
 * Compact schema:
 *   SEM_EXM_EXCH_ID, SEM_SEGMENT, SEM_SMST_SECURITY_ID,
 *   SM_SYMBOL_NAME, SEM_TRADING_SYMBOL, SEM_CUSTOM_SYMBOL, ...
 *
 * Important: detailed SYMBOL_NAME and compact SM_SYMBOL_NAME are the canonical
 * symbol fields. DISPLAY_NAME / custom symbol are used only for display/search.
 */
public final class InstrumentResolver {
    private static final String SEGMENT_URL="https://api.dhan.co/v2/instrument/NSE_EQ";
    private static final String DETAILED_URL="https://images.dhan.co/api-data/api-scrip-master-detailed.csv";
    private static final String COMPACT_URL="https://images.dhan.co/api-data/api-scrip-master.csv";
    private static final String CACHE_FILE="nse_eq_symbols_v4.tsv";
    private static final long CACHE_MAX_AGE_MS=24L*60L*60L*1000L;

    private static volatile Context app;
    private static volatile List<Instrument> memory;
    private static volatile String lastSource="none";
    private static final Object LOCK=new Object();

    private InstrumentResolver(){}
    public static void init(Context c){if(c!=null)app=c.getApplicationContext();}
    public static void warmCache(){try{ensureLoaded();}catch(Exception ignored){}}
    public static int cachedCount(){List<Instrument> m=memory;return m==null?0:m.size();}
    public static String cacheSource(){return lastSource;}

    public static final class Instrument {
        public final String symbol,securityId,displayName,exchangeSegment,instrumentType;
        Instrument(String s,String id,String d){this(s,id,d,"NSE_EQ","EQUITY");}
        Instrument(String s,String id,String d,String seg,String type){
            symbol=canonicalSymbol(s);
            securityId=id==null?"":id.trim();
            displayName=d==null?"":d.trim();
            exchangeSegment=(seg==null||seg.trim().isEmpty())?"NSE_EQ":seg.trim().toUpperCase(Locale.US);
            instrumentType=(type==null||type.trim().isEmpty())?"EQUITY":type.trim().toUpperCase(Locale.US);
        }
        @Override public String toString(){
            String base=symbol+(displayName.isEmpty()||displayName.equalsIgnoreCase(symbol)?"":"  •  "+displayName);
            return "IDX_I".equals(exchangeSegment)?base+"  •  INDEX":base;
        }
    }

    private static final Instrument NIFTY50=new Instrument("NIFTY50","13","NIFTY 50","IDX_I","INDEX");
    private static final Instrument BANKNIFTY=new Instrument("BANKNIFTY","25","NIFTY BANK","IDX_I","INDEX");

    private static Instrument exactPinnedIndex(String query){
        String q=norm(query);
        if(q.equals("NIFTY50")||q.equals("NIFTY")||q.equals("NIFTYINDEX"))return NIFTY50;
        if(q.equals("BANKNIFTY")||q.equals("NIFTYBANK")||q.equals("BANKNIFTYINDEX"))return BANKNIFTY;
        return null;
    }

    private static List<Instrument> matchingPinnedIndices(String query){
        String q=norm(query);
        ArrayList<Instrument> out=new ArrayList<>();
        if(q.isEmpty())return out;
        Instrument exact=exactPinnedIndex(q);
        if(exact!=null){out.add(exact);return out;}
        String n50="NIFTY50 NIFTY INDEX";
        String bn="BANKNIFTY NIFTYBANK NIFTY BANK BANK NIFTY";
        if(norm(n50).contains(q)||q.contains("NIFTY50"))out.add(NIFTY50);
        if(norm(bn).contains(q)||q.contains("BANKNIFTY")||q.contains("NIFTYBANK"))out.add(BANKNIFTY);
        return out;
    }

    public static boolean queryMatchesInstrument(String query,Instrument x){
        if(x==null)return false;
        Instrument idx=exactPinnedIndex(query);
        if(idx!=null)return idx.securityId.equals(x.securityId)&&idx.exchangeSegment.equals(x.exchangeSegment);
        return norm(x.symbol).equals(norm(query));
    }

    public static Instrument resolveInstrument(String symbol)throws Exception{
        Instrument idx=exactPinnedIndex(symbol);
        if(idx!=null)return idx;
        String q=norm(symbol);
        Instrument exact=findExact(ensureLoaded(),q);
        if(exact!=null)return exact;
        Instrument freshExact=findExact(forceRefresh(),q);
        if(freshExact!=null)return freshExact;
        throw new IOException("Exact NSE_EQ/index symbol not found after fresh refresh: "+q);
    }

    public static String resolveNseEquitySecurityId(String symbol)throws Exception{
        return resolveInstrument(symbol).securityId;
    }

    public static List<Instrument> searchNseEquities(String query,int limit)throws Exception{
        int cap=Math.max(1,limit);
        LinkedHashMap<String,Instrument> out=new LinkedHashMap<>();
        for(Instrument x:matchingPinnedIndices(query))out.put(x.exchangeSegment+":"+x.securityId,x);
        for(Instrument x:rankedSearch(ensureLoaded(),query,cap)) {
            out.put(x.exchangeSegment+":"+x.securityId,x);
            if(out.size()>=cap)break;
        }
        return new ArrayList<>(out.values());
    }

    /** SEARCH button behavior: exact miss always refreshes once, then returns ranked results. */
    public static List<Instrument> searchFreshOnMiss(String query,int limit)throws Exception{
        String q=norm(query);
        Instrument pinned=exactPinnedIndex(q);
        if(pinned!=null)return Collections.singletonList(pinned);
        List<Instrument> localAll=ensureLoaded();
        Instrument localExact=findExact(localAll,q);
        if(localExact!=null)return searchNseEquities(q,limit);

        List<Instrument> fresh=forceRefresh();
        LinkedHashMap<String,Instrument> out=new LinkedHashMap<>();
        for(Instrument x:matchingPinnedIndices(q))out.put(x.exchangeSegment+":"+x.securityId,x);
        for(Instrument x:rankedSearch(fresh,q,limit)){
            out.put(x.exchangeSegment+":"+x.securityId,x);
            if(out.size()>=Math.max(1,limit))break;
        }
        return new ArrayList<>(out.values());
    }

    public static List<Instrument> forceRefresh()throws Exception{
        synchronized(LOCK){
            DownloadResult dr=downloadMaster();
            if(dr.rows.isEmpty())throw new IOException("NSE_EQ refresh returned no instruments");
            saveDisk(dr.rows);
            memory=Collections.unmodifiableList(dr.rows);
            lastSource=dr.source;
            return memory;
        }
    }

    private static List<Instrument> ensureLoaded()throws Exception{
        List<Instrument> m=memory;
        if(m!=null&&!m.isEmpty())return m;
        synchronized(LOCK){
            if(memory!=null&&!memory.isEmpty())return memory;

            List<Instrument> disk=loadDisk();
            if(!disk.isEmpty()){
                memory=Collections.unmodifiableList(disk);
                lastSource="disk-cache";
                File f=cacheFile();
                if(f!=null && System.currentTimeMillis()-f.lastModified()<=CACHE_MAX_AGE_MS)return memory;
                try{
                    DownloadResult dr=downloadMaster();
                    if(!dr.rows.isEmpty()){
                        saveDisk(dr.rows);memory=Collections.unmodifiableList(dr.rows);lastSource=dr.source;
                    }
                }catch(Exception ignored){}
                return memory;
            }

            DownloadResult dr=downloadMaster();
            if(dr.rows.isEmpty())throw new IOException("NSE_EQ instrument list is empty");
            saveDisk(dr.rows);
            memory=Collections.unmodifiableList(dr.rows);
            lastSource=dr.source;
            return memory;
        }
    }

    private static final class DownloadResult{
        final List<Instrument> rows; final String source;
        DownloadResult(List<Instrument> r,String s){rows=r;source=s;}
    }

    private static DownloadResult downloadMaster()throws Exception{
        ArrayList<String> errors=new ArrayList<>();

        // Segment-wise endpoint is documented as a detailed instrument list.
        try{
            List<Instrument> rows=parseDetailedLike(SEGMENT_URL,true);
            if(validUniverse(rows))return new DownloadResult(rows,"Dhan NSE_EQ segment");
            errors.add("segment count="+rows.size());
        }catch(Exception e){errors.add("segment: "+safeMessage(e));}

        try{
            List<Instrument> rows=parseDetailedLike(DETAILED_URL,false);
            if(validUniverse(rows))return new DownloadResult(rows,"Dhan detailed master");
            errors.add("detailed count="+rows.size());
        }catch(Exception e){errors.add("detailed: "+safeMessage(e));}

        try{
            List<Instrument> rows=parseCompact(COMPACT_URL);
            if(validUniverse(rows))return new DownloadResult(rows,"Dhan compact master");
            errors.add("compact count="+rows.size());
        }catch(Exception e){errors.add("compact: "+safeMessage(e));}

        throw new IOException("Unable to build NSE_EQ master • "+join(errors," • "));
    }

    private static boolean validUniverse(List<Instrument> rows){return rows!=null&&rows.size()>=500;}

    /** Detailed and segment-wise Dhan schema parser. */
    private static List<Instrument> parseDetailedLike(String url,boolean alreadyNseEq)throws Exception{
        HttpURLConnection c=open(url);
        ArrayList<Instrument> out=new ArrayList<>();
        LinkedHashSet<String> seenSid=new LinkedHashSet<>();
        try(BufferedReader br=reader(c)){
            String header=br.readLine();
            if(header==null)throw new IOException("Empty CSV");
            List<String> h=parseCsv(header);

            int exch=indexOf(h,"EXCH_ID");
            int seg=indexOf(h,"SEGMENT");
            int sid=firstIndex(h,"SECURITY_ID","SEM_SMST_SECURITY_ID");
            int sym=firstIndex(h,"SYMBOL_NAME","SM_SYMBOL_NAME");
            int disp=firstIndex(h,"DISPLAY_NAME","SEM_CUSTOM_SYMBOL");

            if(sid<0||sym<0)
                throw new IOException("Detailed schema missing SECURITY_ID/SYMBOL_NAME; headers="+headerSummary(h));

            String line;
            while((line=br.readLine())!=null){
                List<String>a=parseCsv(line);
                if(!alreadyNseEq){
                    String ex=get(a,exch).toUpperCase(Locale.US);
                    String sg=get(a,seg).toUpperCase(Locale.US);
                    if(exch>=0&&!"NSE".equals(ex))continue;
                    if(seg>=0&&!("E".equals(sg)||"EQ".equals(sg)||"NSE_EQ".equals(sg)))continue;
                }

                String id=get(a,sid).trim();
                String symbol=canonicalSymbol(get(a,sym));
                if(id.isEmpty()||symbol.isEmpty()||!seenSid.add(id))continue;
                String display=get(a,disp).trim();
                out.add(new Instrument(symbol,id,display));
            }
        }finally{c.disconnect();}
        sort(out);return out;
    }

    /** Compact Dhan schema parser. */
    private static List<Instrument> parseCompact(String url)throws Exception{
        HttpURLConnection c=open(url);
        ArrayList<Instrument> out=new ArrayList<>();
        LinkedHashSet<String> seenSid=new LinkedHashSet<>();
        try(BufferedReader br=reader(c)){
            String header=br.readLine();
            if(header==null)throw new IOException("Empty CSV");
            List<String> h=parseCsv(header);

            int exch=indexOf(h,"SEM_EXM_EXCH_ID");
            int seg=indexOf(h,"SEM_SEGMENT");
            int sid=indexOf(h,"SEM_SMST_SECURITY_ID");
            int symbolName=indexOf(h,"SM_SYMBOL_NAME");
            int trading=indexOf(h,"SEM_TRADING_SYMBOL");
            int custom=indexOf(h,"SEM_CUSTOM_SYMBOL");

            if(sid<0||symbolName<0)
                throw new IOException("Compact schema missing SEM_SMST_SECURITY_ID/SM_SYMBOL_NAME; headers="+headerSummary(h));

            String line;
            while((line=br.readLine())!=null){
                List<String>a=parseCsv(line);
                String ex=get(a,exch).toUpperCase(Locale.US);
                String sg=get(a,seg).toUpperCase(Locale.US);
                if(exch>=0&&!("NSE".equals(ex)||"N".equals(ex)))continue;
                if(seg>=0&&!("E".equals(sg)||"EQ".equals(sg)||"NSE_EQ".equals(sg)))continue;

                String id=get(a,sid).trim();
                String symbol=canonicalSymbol(get(a,symbolName));
                if(symbol.isEmpty())symbol=canonicalSymbol(get(a,trading));
                if(id.isEmpty()||symbol.isEmpty()||!seenSid.add(id))continue;
                String display=get(a,custom).trim();
                out.add(new Instrument(symbol,id,display));
            }
        }finally{c.disconnect();}
        sort(out);return out;
    }

    private static HttpURLConnection open(String url)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET");c.setConnectTimeout(15000);c.setReadTimeout(60000);c.setUseCaches(false);
        c.setRequestProperty("Accept","text/csv,*/*");
        c.setRequestProperty("User-Agent","SBNVirtualTrading/1.22");
        int code=c.getResponseCode();
        if(code<200||code>=300){c.disconnect();throw new IOException("HTTP "+code);}
        return c;
    }

    private static BufferedReader reader(HttpURLConnection c)throws Exception{
        return new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8),256*1024);
    }

    private static File cacheFile(){return app==null?null:new File(app.getFilesDir(),CACHE_FILE);}

    private static List<Instrument> loadDisk(){
        File f=cacheFile();
        if(f==null||!f.exists()||f.length()<100)return Collections.emptyList();
        ArrayList<Instrument> out=new ArrayList<>();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8),128*1024)){
            String line;
            while((line=br.readLine())!=null){
                String[] a=line.split("\\t",-1);
                if(a.length>=2&&!a[0].isEmpty()&&!a[1].isEmpty())out.add(new Instrument(a[0],a[1],a.length>2?a[2]:""));
            }
        }catch(Exception ignored){return Collections.emptyList();}
        return out;
    }

    private static void saveDisk(List<Instrument> rows){
        File f=cacheFile();if(f==null)return;
        File tmp=new File(f.getParentFile(),f.getName()+".tmp");
        try(BufferedWriter bw=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),StandardCharsets.UTF_8),128*1024)){
            for(Instrument x:rows){
                bw.write(clean(x.symbol));bw.write('\t');bw.write(clean(x.securityId));bw.write('\t');bw.write(clean(x.displayName));bw.newLine();
            }
            bw.flush();
            if(f.exists())f.delete();
            if(!tmp.renameTo(f)){
                try(InputStream in=new FileInputStream(tmp);OutputStream out=new FileOutputStream(f)){
                    byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);
                }
                tmp.delete();
            }
        }catch(Exception ignored){tmp.delete();}
    }

    private static List<Instrument> rankedSearch(List<Instrument> all,String query,int limit){
        String q=norm(query);
        if(q.isEmpty()||all==null)return Collections.emptyList();
        ArrayList<Instrument> exact=new ArrayList<>(),prefix=new ArrayList<>(),company=new ArrayList<>(),contains=new ArrayList<>(),fuzzy=new ArrayList<>();
        for(Instrument x:all){
            String ns=norm(x.symbol),nd=normKeepSpaces(x.displayName);
            if(ns.equals(q))exact.add(x);
            else if(ns.startsWith(q))prefix.add(x);
            else if(companyWordStarts(nd,q))company.add(x);
            else if(ns.contains(q)||nd.replace(" ","").contains(q))contains.add(x);
            else if(q.length()>=5&&isCloseSymbol(ns,q))fuzzy.add(x);
        }
        Comparator<Instrument> cmp=(a,b)->{
            int c=Integer.compare(a.symbol.length(),b.symbol.length());
            return c!=0?c:a.symbol.compareToIgnoreCase(b.symbol);
        };
        exact.sort(cmp);prefix.sort(cmp);company.sort(cmp);contains.sort(cmp);
        fuzzy.sort((a,b)->{
            int da=editDistance(norm(a.symbol),q),db=editDistance(norm(b.symbol),q);
            return da!=db?Integer.compare(da,db):cmp.compare(a,b);
        });
        LinkedHashMap<String,Instrument> out=new LinkedHashMap<>();
        int cap=Math.max(1,limit);
        for(List<Instrument> bucket:Arrays.asList(exact,prefix,company,contains,fuzzy)){
            for(Instrument x:bucket){out.put(x.securityId,x);if(out.size()>=cap)break;}
            if(out.size()>=cap)break;
        }
        return new ArrayList<>(out.values());
    }

    private static Instrument findExact(List<Instrument> rows,String q){
        if(rows!=null)for(Instrument x:rows)if(norm(x.symbol).equals(q))return x;
        return null;
    }
    private static boolean companyWordStarts(String text,String q){
        if(text==null)return false;for(String w:text.split("\\s+"))if(norm(w).startsWith(q))return true;return false;
    }
    private static boolean isCloseSymbol(String a,String b){
        int allowed=Math.max(a.length(),b.length())<=7?1:2;
        return Math.abs(a.length()-b.length())<=allowed&&editDistance(a,b)<=allowed;
    }
    static int editDistance(String a,String b){
        int[] p=new int[b.length()+1],c=new int[b.length()+1];for(int j=0;j<=b.length();j++)p[j]=j;
        for(int i=1;i<=a.length();i++){c[0]=i;for(int j=1;j<=b.length();j++){int cost=a.charAt(i-1)==b.charAt(j-1)?0:1;c[j]=Math.min(Math.min(c[j-1]+1,p[j]+1),p[j-1]+cost);}int[]t=p;p=c;c=t;}return p[b.length()];
    }

    private static String canonicalSymbol(String s){
        if(s==null)return "";String x=s.trim().toUpperCase(Locale.US);if(x.isEmpty())return "";
        int colon=x.lastIndexOf(':');if(colon>=0)x=x.substring(colon+1).trim();
        for(String suf:new String[]{"-EQ","-BE","-BZ"," EQ"," BE"," BZ"})if(x.endsWith(suf)){x=x.substring(0,x.length()-suf.length()).trim();break;}
        return x.matches("[A-Z0-9&._\\-]+")?x:"";
    }
    static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replaceAll("\\s+","");}
    static String normKeepSpaces(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replaceAll("\\s+"," ");}
    private static String clean(String s){return s==null?"":s.replace('\t',' ').replace('\n',' ').replace('\r',' ').trim();}
    private static String get(List<String>a,int i){return i>=0&&i<a.size()?a.get(i).trim():"";}
    private static int firstIndex(List<String>h,String...names){for(String n:names){int i=indexOf(h,n);if(i>=0)return i;}return -1;}
    private static int indexOf(List<String>h,String name){for(int i=0;i<h.size();i++)if(name.equalsIgnoreCase(h.get(i).trim().replace("\uFEFF","")))return i;return -1;}
    private static void sort(List<Instrument>x){x.sort((a,b)->a.symbol.compareToIgnoreCase(b.symbol));}
    private static String headerSummary(List<String> h){StringBuilder b=new StringBuilder();for(int i=0;i<h.size()&&i<16;i++){if(i>0)b.append('|');b.append(h.get(i));}return b.toString();}
    private static String safeMessage(Throwable t){String m=t.getMessage();return m==null?t.getClass().getSimpleName():m;}
    private static String join(List<String>x,String sep){StringBuilder b=new StringBuilder();for(String s:x){if(b.length()>0)b.append(sep);b.append(s);}return b.toString();}
    static List<String> parseCsv(String s){
        ArrayList<String>o=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean q=false;
        for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(ch=='\"'){if(q&&i+1<s.length()&&s.charAt(i+1)=='\"'){cur.append('\"');i++;}else q=!q;}else if(ch==','&&!q){o.add(cur.toString());cur.setLength(0);}else cur.append(ch);}o.add(cur.toString());return o;
    }
}
