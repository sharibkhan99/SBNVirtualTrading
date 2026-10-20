package com.sbn.virtualtrading;

import java.util.*;

public final class BacktestEngine {
    private BacktestEngine(){}
    public static final class Result {
        public final List<Trade> trades=new ArrayList<>();
        public double netPnl=0, grossProfit=0, grossLoss=0, maxDrawdown=0;
        public int wins=0, losses=0;
        public double winRate(){return trades.isEmpty()?0:(wins*100.0/trades.size());}
    }

    public static Result run(List<Candle> candles,int qty,boolean reverse){
        Result r=new Result();
        if(candles==null||candles.size()<3)return r;
        Indicators.detectReversals(candles);
        Trade.Side openSide=null; int entryIdx=-1; double entry=0; double equity=0, peak=0;
        for(int signalIdx=0;signalIdx<candles.size()-1;signalIdx++){
            Candle.Signal s=candles.get(signalIdx).signal;
            if(s==Candle.Signal.NONE)continue;
            Trade.Side desired=(s==Candle.Signal.BULLISH)?Trade.Side.LONG:Trade.Side.SHORT;
            if(reverse)desired=(desired==Trade.Side.LONG)?Trade.Side.SHORT:Trade.Side.LONG;
            int execIdx=signalIdx+1; double execPrice=candles.get(execIdx).open;
            if(openSide==null){openSide=desired;entryIdx=execIdx;entry=execPrice;continue;}
            if(openSide!=desired){
                Trade t=new Trade(openSide,entryIdx,execIdx,candles.get(entryIdx).time,candles.get(execIdx).time,entry,execPrice,Math.max(1,qty));
                r.trades.add(t); r.netPnl+=t.pnl; if(t.pnl>0){r.wins++;r.grossProfit+=t.pnl;} else if(t.pnl<0){r.losses++;r.grossLoss+=-t.pnl;}
                equity+=t.pnl; peak=Math.max(peak,equity); r.maxDrawdown=Math.max(r.maxDrawdown,peak-equity);
                openSide=desired;entryIdx=execIdx;entry=execPrice;
            }
        }
        if(openSide!=null && entryIdx>=0 && entryIdx<candles.size()-1){
            int exitIdx=candles.size()-1; double exit=candles.get(exitIdx).close;
            Trade t=new Trade(openSide,entryIdx,exitIdx,candles.get(entryIdx).time,candles.get(exitIdx).time,entry,exit,Math.max(1,qty));
            r.trades.add(t); r.netPnl+=t.pnl; if(t.pnl>0){r.wins++;r.grossProfit+=t.pnl;} else if(t.pnl<0){r.losses++;r.grossLoss+=-t.pnl;}
            equity+=t.pnl; peak=Math.max(peak,equity); r.maxDrawdown=Math.max(r.maxDrawdown,peak-equity);
        }
        return r;
    }
}
