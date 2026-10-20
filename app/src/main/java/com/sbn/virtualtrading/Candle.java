package com.sbn.virtualtrading;

public class Candle {
    public final long time;
    public final double open, high, low, close;
    public final long volume;
    public double rsi = Double.NaN;
    public Signal signal = Signal.NONE;

    public Candle(long time, double open, double high, double low, double close, long volume) {
        this.time = time; this.open = open; this.high = high; this.low = low; this.close = close; this.volume = volume;
    }

    public enum Signal { NONE, BULLISH, BEARISH }
}
