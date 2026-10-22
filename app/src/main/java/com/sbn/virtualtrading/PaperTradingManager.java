package com.sbn.virtualtrading;

import android.content.*;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Symbol-wise paper portfolio with global cash + persistent stop-loss/target. */
public final class PaperTradingManager {
    private static final String P="paper_trade_v3";
    private final SharedPreferences sp;

    public static final class RiskEvent {
        public final String type;
        public final double exitPrice;
        public final int closedQty;
        public final double realizedAfter;
        RiskEvent(String t,double p,int q,double r){type=t;exitPrice=p;closedQty=q;realizedAfter=r;}
    }

    public PaperTradingManager(Context c){
        sp=c.getSharedPreferences(P,Context.MODE_PRIVATE);
        if(!sp.contains("cash")){
            SharedPreferences v2=c.getSharedPreferences("paper_trade_v2",Context.MODE_PRIVATE);
            SharedPreferences v1=c.getSharedPreferences("paper_trade_v1",Context.MODE_PRIVATE);
            double bal=0;
            if(v2.contains("cash"))bal=Double.longBitsToDouble(v2.getLong("cash",Double.doubleToLongBits(0.0)));
            else if(v1.contains("bal"))bal=Double.longBitsToDouble(v1.getLong("bal",Double.doubleToLongBits(0.0)));
            sp.edit().putLong("cash",Double.doubleToLongBits(bal)).apply();
        }
    }

    private String s(String symbol){
        String x=symbol==null?"UNKNOWN":symbol.trim().toUpperCase(Locale.US);
        return x.replaceAll("[^A-Z0-9_\\-]","_");
    }
    private String k(String symbol,String field){return "p_"+s(symbol)+"_"+field;}
    private double d(String key,double def){return Double.longBitsToDouble(sp.getLong(key,Double.doubleToLongBits(def)));}
    private void putD(SharedPreferences.Editor e,String key,double value){e.putLong(key,Double.doubleToLongBits(value));}

    public double balance(){return d("cash",0.0);}
    public int qty(String symbol){return sp.getInt(k(symbol,"qty"),0);}
    public double avg(String symbol){return d(k(symbol,"avg"),0.0);}
    public double realized(String symbol){return d(k(symbol,"real"),0.0);}
    public double stopLoss(String symbol){return d(k(symbol,"sl"),0.0);}
    public double target(String symbol){return d(k(symbol,"target"),0.0);}

    public void reset(){sp.edit().clear().apply();}
    public void resetTo(double amount){sp.edit().clear().putLong("cash",Double.doubleToLongBits(Math.max(0,amount))).apply();}

    private void registerSymbol(String symbol){
        String clean=s(symbol);
        java.util.Set<String> old=sp.getStringSet("portfolio_symbols", Collections.<String>emptySet());
        java.util.Set<String> next=new java.util.HashSet<>(old);
        next.add(clean);
        sp.edit().putStringSet("portfolio_symbols",next).apply();
    }
    public List<String> symbols(){
        java.util.Set<String> raw=sp.getStringSet("portfolio_symbols", Collections.<String>emptySet());
        List<String> out=new ArrayList<>();
        for(String x:raw) if(x!=null&&!x.trim().isEmpty()) out.add(x);
        Collections.sort(out);
        return out;
    }
    public double totalRealized(){double total=0;for(String x:symbols())total+=realized(x);return total;}
    public int openPositionCount(){int n=0;for(String x:symbols())if(qty(x)!=0)n++;return n;}
    public void addFunds(double amount){if(amount>0)setBalance(balance()+amount);}
    public void setBalance(double amount){sp.edit().putLong("cash",Double.doubleToLongBits(Math.max(0,amount))).apply();}

    /** Edit average entry of an open position without changing cash or P&L. */
    public String setAverageEntry(String symbol, double newAverage){
        int q = qty(symbol);
        if(q == 0) return "No open position to edit";
        if(newAverage <= 0 || Double.isNaN(newAverage) || Double.isInfinite(newAverage))
            return "Entry price must be a valid positive number";
        sp.edit().putLong(k(symbol,"avg"), Double.doubleToLongBits(newAverage)).apply();
        return null;
    }

    public void clearPositionAndPnl(String symbol){
        sp.edit().remove(k(symbol,"qty")).remove(k(symbol,"avg")).remove(k(symbol,"real"))
                .remove(k(symbol,"sl")).remove(k(symbol,"target")).apply();
    }

    public void setRisk(String symbol,double sl,double tgt){
        SharedPreferences.Editor e=sp.edit();
        if(sl>0)putD(e,k(symbol,"sl"),sl); else e.remove(k(symbol,"sl"));
        if(tgt>0)putD(e,k(symbol,"target"),tgt); else e.remove(k(symbol,"target"));
        e.apply();
    }
    public void clearRisk(String symbol){sp.edit().remove(k(symbol,"sl")).remove(k(symbol,"target")).apply();}

    /**
     * Manual paper order. BUY consumes cash; SELL credits cash.
     * Returns null on success.
     */
    public String trade(String symbol,boolean buy,int q,double price){
        if(q<=0)return "Quantity must be greater than 0";
        if(price<=0||Double.isNaN(price)||Double.isInfinite(price))
            return "Invalid execution price";

        registerSymbol(symbol);

        int newQty = buy ? q : -q;
        double cash = balance();
        double orderValue = q * price;

        // Each order replaces the existing position for this symbol.
        cash += buy ? -orderValue : orderValue;

        SharedPreferences.Editor e = sp.edit();
        e.putInt(k(symbol,"qty"), newQty);
        putD(e,k(symbol,"avg"),price);
        putD(e,k(symbol,"real"),0.0);
        putD(e,"cash",cash);
        e.remove(k(symbol,"sl"));
        e.remove(k(symbol,"target"));
        e.apply();

        return null;
    }

    /** Forced SL/target exit. It cannot be rejected for insufficient paper cash. */
    public RiskEvent closeAt(String symbol,double price,String reason){
        int q=qty(symbol);
        if(q==0||price<=0)return null;
        double oldAvg=avg(symbol), real=realized(symbol), cash=balance();
        int n=Math.abs(q);
        real+=(q>0?(price-oldAvg):(oldAvg-price))*n;
        cash += q>0 ? n*price : -n*price;
        SharedPreferences.Editor e=sp.edit();
        e.putInt(k(symbol,"qty"),0);
        putD(e,k(symbol,"avg"),0);
        putD(e,k(symbol,"real"),real);
        putD(e,"cash",cash);
        e.remove(k(symbol,"sl")); e.remove(k(symbol,"target"));
        e.apply();
        return new RiskEvent(reason,price,n,real);
    }

    /**
     * Replay/backtest check. Conservative priority: if SL and target are both
     * touched by the same candle, SL is executed first.
     */
    public RiskEvent checkCandle(String symbol,double high,double low){
        int q=qty(symbol); if(q==0)return null;
        double sl=stopLoss(symbol), tg=target(symbol);
        if(q>0){
            if(sl>0 && low<=sl)return closeAt(symbol,sl,"STOP LOSS");
            if(tg>0 && high>=tg)return closeAt(symbol,tg,"TARGET");
        }else{
            if(sl>0 && high>=sl)return closeAt(symbol,sl,"STOP LOSS");
            if(tg>0 && low<=tg)return closeAt(symbol,tg,"TARGET");
        }
        return null;
    }

    /** Current-price check for non-replay GET PRICE refreshes. */
    public RiskEvent checkPrice(String symbol,double price){
        int q=qty(symbol); if(q==0||price<=0)return null;
        double sl=stopLoss(symbol), tg=target(symbol);
        if(q>0){
            if(sl>0 && price<=sl)return closeAt(symbol,price,"STOP LOSS");
            if(tg>0 && price>=tg)return closeAt(symbol,price,"TARGET");
        }else{
            if(sl>0 && price>=sl)return closeAt(symbol,price,"STOP LOSS");
            if(tg>0 && price<=tg)return closeAt(symbol,price,"TARGET");
        }
        return null;
    }

    public double unrealized(String symbol,double price){
        int q=qty(symbol); if(q==0||price<=0||Double.isNaN(price))return 0;
        return (q>0?(price-avg(symbol)):(avg(symbol)-price))*Math.abs(q);
    }

    public double equity(String symbol,double price){
        int q=qty(symbol); if(price<=0||Double.isNaN(price))return balance();
        return balance()+q*price;
    }
}
