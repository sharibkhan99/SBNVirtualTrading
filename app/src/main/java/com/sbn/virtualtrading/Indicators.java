package com.sbn.virtualtrading;

import java.util.List;

public final class Indicators {
    private Indicators() {}

    public static void rsi14(List<Candle> c) {
        if (c.size() < 15) return;
        double gain = 0, loss = 0;
        for (int i=1;i<=14;i++) {
            double d = c.get(i).close-c.get(i-1).close;
            if (d>=0) gain += d; else loss -= d;
        }
        double avgGain=gain/14.0, avgLoss=loss/14.0;
        c.get(14).rsi = toRsi(avgGain, avgLoss);
        for (int i=15;i<c.size();i++) {
            double d=c.get(i).close-c.get(i-1).close;
            double g=Math.max(d,0), l=Math.max(-d,0);
            avgGain=(avgGain*13+g)/14.0;
            avgLoss=(avgLoss*13+l)/14.0;
            c.get(i).rsi=toRsi(avgGain,avgLoss);
        }
    }

    private static double toRsi(double g,double l) {
        if (l==0) return 100;
        double rs=g/l;
        return 100-(100/(1+rs));
    }

    // Sample non-repainting reversal detector. Uses current and previous closed candle only.
    public static void detectReversals(List<Candle> c) {
        rsi14(c);
        for (int i=1;i<c.size();i++) {
            Candle p=c.get(i-1), x=c.get(i);
            if (Double.isNaN(x.rsi)) continue;
            boolean bullEngulf=x.close>x.open && p.close<p.open && x.open<=p.close && x.close>=p.open;
            boolean bearEngulf=x.close<x.open && p.close>p.open && x.open>=p.close && x.close<=p.open;
            if (bullEngulf && x.rsi<=45) x.signal=Candle.Signal.BULLISH;
            else if (bearEngulf && x.rsi>=55) x.signal=Candle.Signal.BEARISH;
        }
    }
}
