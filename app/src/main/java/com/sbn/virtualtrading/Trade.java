package com.sbn.virtualtrading;

public class Trade {
    public enum Side { LONG, SHORT }
    public final Side side;
    public final int entryIndex, exitIndex;
    public final long entryTime, exitTime;
    public final double entryPrice, exitPrice;
    public final int qty;
    public final double pnl;

    public Trade(Side side,int entryIndex,int exitIndex,long entryTime,long exitTime,double entryPrice,double exitPrice,int qty){
        this.side=side; this.entryIndex=entryIndex; this.exitIndex=exitIndex; this.entryTime=entryTime; this.exitTime=exitTime;
        this.entryPrice=entryPrice; this.exitPrice=exitPrice; this.qty=qty;
        this.pnl=(side==Side.LONG ? (exitPrice-entryPrice) : (entryPrice-exitPrice))*qty;
    }
}
