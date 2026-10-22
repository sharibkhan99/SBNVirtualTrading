package com.sbn.virtualtrading;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class TradingChartView extends View {
    private final List<Candle> data=new ArrayList<>();
    private final List<Trade> trades=new ArrayList<>();
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm=new Paint.FontMetrics();
    private final ScaleGestureDetector scaleDetector;
    private float candlesPerScreen=75f,rightOffset=0f,downX,lastX;
    private boolean dragging=false,replayMode=false;
    private int selectedIndex=-1,replayIndex=-1,replayWindowStart=-1;
    private double livePrice=Double.NaN;
    // Paper-trading overlay state. Entry is fixed; SL/Target can be placed or dragged on chart.
    private String paperSymbol="";
    private int paperQty=0;
    private double paperAvg=0,paperSL=0,paperTarget=0,paperRealized=0,paperMark=0;
    private double lastMin=0,lastMax=1;
    private float lastPriceH=0,lastPlotW=0;
    private int riskDrag=0, placementMode=0; // 1=SL, 2=TARGET
    private final RectF slButton=new RectF(), targetButton=new RectF();
    public interface RiskLineListener{void onRiskLineChanged(String type,double price);}
    private RiskLineListener riskLineListener;
    public interface CandleSelectionListener{void onCandleSelected(Candle candle);}
    private CandleSelectionListener candleSelectionListener;
    public void setCandleSelectionListener(CandleSelectionListener l){candleSelectionListener=l;}
    private final int bg=Color.rgb(15,18,23),grid=Color.rgb(43,47,54),text=Color.rgb(190,194,202),up=Color.rgb(38,166,154),down=Color.rgb(239,83,80),accent=Color.rgb(41,121,255);
    private static final TimeZone IST=TimeZone.getTimeZone("Asia/Kolkata");

    public TradingChartView(Context c){super(c);setBackgroundColor(bg);scaleDetector=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){candlesPerScreen=clamp(candlesPerScreen/d.getScaleFactor(),15f,240f);clampOffset();invalidate();return true;}});}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private int effectiveSize(){return replayMode&&replayIndex>=0?Math.min(data.size(),replayIndex+1):data.size();}
    private void clampOffset(){rightOffset=Math.max(0f,Math.min(rightOffset,Math.max(0f,effectiveSize()-10)));}

    public void setData(List<Candle>d){data.clear();if(d!=null)data.addAll(d);Indicators.detectReversals(data);candlesPerScreen=Math.min(90f,Math.max(25f,data.size()));rightOffset=0;selectedIndex=-1;replayMode=false;replayIndex=-1;replayWindowStart=-1;invalidate();}
    public void setLivePrice(double v){livePrice=v;invalidate();}
    public void setRiskLineListener(RiskLineListener l){riskLineListener=l;}
    public void setPaperPosition(String symbol,int qty,double avg,double sl,double target,double realized,double mark){
        paperSymbol=symbol==null?"":symbol;paperQty=qty;paperAvg=avg;paperSL=sl;paperTarget=target;paperRealized=realized;paperMark=mark;invalidate();
    }
    public void clearPaperOverlay(){paperSymbol="";paperQty=0;paperAvg=paperSL=paperTarget=paperRealized=paperMark=0;placementMode=0;riskDrag=0;invalidate();}
    public void setTrades(List<Trade>t){trades.clear();if(t!=null)trades.addAll(t);invalidate();}
    public Candle getCandle(int i){return i>=0&&i<data.size()?data.get(i):null;}
    public int getSelectedIndex(){return selectedIndex;}
    public double selectedCandlePrice(){
        Candle c = getCandle(selectedIndex);
        return c != null ? c.close : 0;
    }

    public boolean isReplayMode(){return replayMode;}
    public int getReplayIndex(){return replayIndex;}
    public Candle getReplayCandle(){return replayMode?getCandle(replayIndex):null;}
    public double executionPrice(){Candle c=getReplayCandle();if(c!=null)return c.close;return data.isEmpty()?0:data.get(data.size()-1).close;}
    public int startReplayAtSelected(){if(data.isEmpty())return-1;return startReplay(selectedIndex>=0?selectedIndex:Math.max(0,data.size()-Math.min(100,data.size())));}
    public int startReplay(int idx){if(data.isEmpty())return-1;replayMode=true;replayIndex=Math.max(0,Math.min(data.size()-1,idx));selectedIndex=replayIndex;rightOffset=0;int visible=Math.max(15,(int)candlesPerScreen);replayWindowStart=Math.max(0,replayIndex-visible/2);invalidate();return replayIndex;}
    public int stepReplay(){if(!replayMode)return-1;if(replayIndex<data.size()-1)replayIndex++;selectedIndex=replayIndex;rightOffset=0;int visible=Math.max(15,(int)candlesPerScreen);if(replayWindowStart<0)replayWindowStart=Math.max(0,replayIndex-visible/2);int maxPos=Math.max(visible/2+3,visible-6);if(replayIndex-replayWindowStart>=maxPos)replayWindowStart=Math.max(0,replayIndex-maxPos);invalidate();return replayIndex;}
    public void stopReplay(){replayMode=false;replayIndex=-1;replayWindowStart=-1;rightOffset=0;invalidate();}
    public boolean replayAtEnd(){return replayMode&&replayIndex>=data.size()-1;}
    public int jumpToTime(long target){if(data.isEmpty())return-1;int lo=0,hi=data.size()-1;while(lo<hi){int m=(lo+hi)/2;if(data.get(m).time<target)lo=m+1;else hi=m;}int idx=lo;if(idx>0&&Math.abs(data.get(idx-1).time-target)<Math.abs(data.get(idx).time-target))idx--;selectedIndex=idx;if(replayMode){replayIndex=idx;int visible=Math.max(15,(int)candlesPerScreen);replayWindowStart=Math.max(0,idx-visible/2);rightOffset=0;}else rightOffset=Math.max(0,effectiveSize()-idx-(int)candlesPerScreen/2);invalidate();return idx;}

    private static final class Window{int start,end,slots;float step;Window(int s,int e,int n,float st){start=s;end=e;slots=n;step=st;}}
    private Window window(float plotW){int size=effectiveSize(),visible=Math.max(15,(int)candlesPerScreen);if(size<=0)return new Window(0,0,visible,plotW/visible);float step=plotW/visible;
        if(replayMode&&rightOffset<1f){
            int start=replayWindowStart>=0?Math.min(replayWindowStart,Math.max(0,size-1)):Math.max(0,size-visible/2);
            int end=Math.min(size,start+visible);
            return new Window(start,end,visible,step);
        }
        int end=Math.min(size,Math.max(Math.min(visible,size),size-(int)rightOffset));int start=Math.max(0,end-visible);return new Window(start,end,visible,step);}

    @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();if(w<=0||h<=0)return;float priceScaleW=dp(72),timeAxisH=dp(42),plotW=Math.max(dp(120),w-priceScaleW),priceH=Math.max(dp(100),h-timeAxisH);drawBg(c,plotW,priceScaleW,h,priceH);Window win=window(plotW);if(win.end<=win.start){empty(c,plotW,priceH);return;}
        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;for(int i=win.start;i<win.end;i++){Candle x=data.get(i);min=Math.min(min,x.low);max=Math.max(max,x.high);}
        if(paperQty!=0){if(paperAvg>0){min=Math.min(min,paperAvg);max=Math.max(max,paperAvg);}if(paperSL>0){min=Math.min(min,paperSL);max=Math.max(max,paperSL);}if(paperTarget>0){min=Math.min(min,paperTarget);max=Math.max(max,paperTarget);}}
        double pad=Math.max((max-min)*.08,Math.max(Math.abs(max)*.002,.02));min-=pad;max+=pad;if(max<=min)max=min+1;
        lastMin=min;lastMax=max;lastPriceH=priceH;lastPlotW=plotW;
        gridScale(c,plotW,priceScaleW,priceH,min,max);float body=Math.max(dp(2),win.step*.62f);
        for(int i=win.start;i<win.end;i++){Candle x=data.get(i);float cx=(i-win.start+.5f)*win.step;float yo=y(x.open,min,max,priceH),yc=y(x.close,min,max,priceH),yh=y(x.high,min,max,priceH),yl=y(x.low,min,max,priceH);boolean bull=x.close>=x.open;p.setColor(bull?up:down);p.setStrokeWidth(dp(1));p.setStyle(Paint.Style.STROKE);c.drawLine(cx,yh,cx,yl,p);float top=Math.min(yo,yc),bot=Math.max(yo,yc);if(bot-top<dp(1))bot=top+dp(1);p.setStyle(Paint.Style.FILL);c.drawRect(cx-body/2,top,cx+body/2,bot,p);if(x.signal!=Candle.Signal.NONE){p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(12));p.setTypeface(Typeface.DEFAULT_BOLD);String s=x.signal==Candle.Signal.BULLISH?"▲":"▼";float sy=x.signal==Candle.Signal.BULLISH?Math.min(priceH-dp(4),yl+dp(15)):Math.max(dp(14),yh-dp(4));c.drawText(s,cx,sy,p);p.setTypeface(Typeface.DEFAULT);}}
        drawTrades(c,priceH,min,max,win);drawPrice(c,plotW,priceScaleW,priceH,min,max,win);drawPaperOverlay(c,plotW,priceScaleW,priceH,min,max);drawTimeAxis(c,plotW,priceH,timeAxisH,win);drawCrosshair(c,plotW,priceScaleW,priceH,min,max,win,body);if(replayMode)badge(c,plotW);
    }
    private void drawBg(Canvas c,float plotW,float scaleW,float h,float priceH){p.setStyle(Paint.Style.FILL);p.setColor(bg);c.drawRect(0,0,plotW,h,p);p.setColor(Color.rgb(19,22,28));c.drawRect(plotW,0,plotW+scaleW,h,p);p.setColor(grid);c.drawLine(plotW,0,plotW,h,p);c.drawLine(0,priceH,plotW+scaleW,priceH,p);}
    private void empty(Canvas c,float plotW,float priceH){p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(14));p.setColor(text);c.drawText("Search a symbol and load history",plotW/2,priceH/2,p);}
    private void gridScale(Canvas c,float plotW,float scaleW,float priceH,double min,double max){p.setStrokeWidth(dp(.6f));p.setTextSize(dp(10));p.setTextAlign(Paint.Align.LEFT);for(int i=0;i<7;i++){float yy=dp(8)+(priceH-dp(16))*i/6f;double v=max-(max-min)*i/6d;p.setColor(grid);c.drawLine(0,yy,plotW,yy,p);p.setColor(text);c.drawText(fmt(v),plotW+dp(5),yy+dp(4),p);}for(int i=1;i<7;i++){float x=plotW*i/7f;p.setColor(grid);c.drawLine(x,0,x,priceH,p);}}
    private float y(double v,double min,double max,float h){return(float)(dp(8)+(max-v)/(max-min)*(h-dp(16)));}
    private void drawTrades(Canvas c,float priceH,double min,double max,Window w){if(trades.isEmpty())return;p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(9));p.setTextAlign(Paint.Align.CENTER);for(Trade t:trades){if(t.entryIndex>=w.start&&t.entryIndex<w.end){float x=(t.entryIndex-w.start+.5f)*w.step,yy=y(t.entryPrice,min,max,priceH);p.setColor(t.side==Trade.Side.LONG?up:down);c.drawCircle(x,yy,dp(7),p);p.setColor(Color.WHITE);c.drawText(t.side==Trade.Side.LONG?"L":"S",x,yy+dp(3),p);}if(t.exitIndex>=w.start&&t.exitIndex<w.end){float x=(t.exitIndex-w.start+.5f)*w.step,yy=y(t.exitPrice,min,max,priceH);p.setColor(Color.rgb(255,193,7));c.drawCircle(x,yy,dp(6),p);p.setColor(Color.BLACK);c.drawText("X",x,yy+dp(3),p);}}p.setTypeface(Typeface.DEFAULT);}
    private void drawPrice(Canvas c,float plotW,float scaleW,float priceH,double min,double max,Window w){
        double v = data.get(w.end-1).close;
        if (replayMode) {
            Candle replayCandle = getReplayCandle();
            if (replayCandle != null) {
                v = replayCandle.close;
            }
        } else {
            Candle selectedCandle = getCandle(selectedIndex);
            if (selectedCandle != null) {
                v = selectedCandle.close;
            } else if (!Double.isNaN(livePrice) && !Double.isInfinite(livePrice)) {
                v = livePrice;
            }
        }
        float yy=y(v,min,max,priceH);
        p.setColor(accent);
        p.setPathEffect(new DashPathEffect(new float[]{dp(5),dp(4)},0));
        c.drawLine(0,yy,plotW,yy,p);
        p.setPathEffect(null);
        p.setTextSize(dp(10));
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.getFontMetrics(fm);
        float bh=Math.max(dp(18),fm.bottom-fm.top+dp(5));
        p.setColor(accent);
        c.drawRect(plotW,yy-bh/2,plotW+scaleW,yy+bh/2,p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(fmt(v),plotW+scaleW/2,yy-(fm.ascent+fm.descent)/2,p);
        p.setTypeface(Typeface.DEFAULT);
    }
    private void drawTimeAxis(Canvas c,float plotW,float priceH,float axisH,Window w){p.setColor(text);p.setTextSize(dp(9));p.setTextAlign(Paint.Align.CENTER);int labels=Math.min(5,Math.max(2,(int)(plotW/dp(85))));SimpleDateFormat day=new SimpleDateFormat("dd MMM yyyy",Locale.US),intr=new SimpleDateFormat("dd MMM HH:mm",Locale.US);day.setTimeZone(IST);intr.setTimeZone(IST);boolean daily=w.end-w.start>1&&data.get(Math.min(w.end-1,w.start+1)).time-data.get(w.start).time>20L*60*60*1000;for(int k=0;k<labels;k++){int idx=w.start+Math.round((w.end-w.start-1)*k/(labels-1f));float x=(idx-w.start+.5f)*w.step;c.drawText((daily?day:intr).format(new Date(data.get(idx).time)),x,priceH+dp(17),p);}p.setTextAlign(Paint.Align.LEFT);p.setTextSize(dp(8));p.setColor(Color.rgb(130,136,146));c.drawText("IST • NSE 09:15–15:30",dp(4),priceH+axisH-dp(4),p);}
    private void drawCrosshair(Canvas c,float plotW,float scaleW,float priceH,double min,double max,Window w,float body){if(selectedIndex<w.start||selectedIndex>=w.end)return;Candle x=data.get(selectedIndex);float cx=(selectedIndex-w.start+.5f)*w.step,cy=y(x.close,min,max,priceH);p.setColor(Color.rgb(145,150,160));p.setPathEffect(new DashPathEffect(new float[]{dp(4),dp(3)},0));c.drawLine(cx,0,cx,priceH,p);c.drawLine(0,cy,plotW,cy,p);p.setPathEffect(null);
        SimpleDateFormat f=new SimpleDateFormat("dd MMM yyyy HH:mm:ss 'IST'",Locale.US);f.setTimeZone(IST);String dt=f.format(new Date(x.time));String o="O "+fmt(x.open)+"   H "+fmt(x.high)+"   L "+fmt(x.low)+"   C "+fmt(x.close);
        p.setTextSize(dp(10));float bw=Math.min(plotW-dp(8),Math.max(dp(210),p.measureText(dt)+dp(20)));float left=Math.max(dp(4),Math.min(cx-bw/2,plotW-bw-dp(4)));p.setColor(Color.argb(225,20,23,29));c.drawRoundRect(left,dp(5),left+bw,dp(49),dp(5),dp(5),p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.LEFT);c.drawText(dt,left+dp(8),dp(21),p);c.drawText(o,left+dp(8),dp(39),p);
        float labelW=Math.min(dp(190),Math.max(dp(128),p.measureText(dt)+dp(14)));float lx=Math.max(0,Math.min(cx-labelW/2,plotW-labelW));p.setColor(Color.rgb(88,94,104));c.drawRect(lx,priceH,lx+labelW,priceH+dp(21),p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);c.drawText(dt,lx+labelW/2,priceH+dp(14),p);
    }
    private void drawPaperOverlay(Canvas c,float plotW,float scaleW,float priceH,double min,double max){
        if(paperQty==0){slButton.setEmpty();targetButton.setEmpty();return;}
        // TradingView-like on-chart controls. Tap SET SL / SET TARGET then tap chart; existing lines are draggable.
        float bw=dp(68),bh=dp(25),gap=dp(5),top=dp(56);
        slButton.set(dp(6),top,dp(6)+bw,top+bh);
        targetButton.set(dp(6)+bw+gap,top,dp(6)+bw*2+gap,top+bh);
        drawRiskButton(c,slButton,placementMode==1?"TAP SL":"SET SL",Color.rgb(239,83,80),placementMode==1);
        drawRiskButton(c,targetButton,placementMode==2?"TAP TP":"TARGET",Color.rgb(38,166,154),placementMode==2);

        drawPaperLine(c,plotW,scaleW,priceH,min,max,paperAvg,Color.rgb(64,136,255),"ENTRY",false);
        if(paperSL>0)drawPaperLine(c,plotW,scaleW,priceH,min,max,paperSL,Color.rgb(239,83,80),"SL",riskDrag==1);
        if(paperTarget>0)drawPaperLine(c,plotW,scaleW,priceH,min,max,paperTarget,Color.rgb(38,166,154),"TP",riskDrag==2);

        double mark=paperMark>0?paperMark:paperAvg;
        double openPnl=(paperQty>0?(mark-paperAvg):(paperAvg-mark))*Math.abs(paperQty);
        String side=paperQty>0?"LONG":"SHORT";
        String pnl="P&L "+money(openPnl)+"   R "+money(paperRealized);
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(10));
        float tw=Math.max(dp(165),Math.max(p.measureText(paperSymbol+"  "+side+"  x"+Math.abs(paperQty)),p.measureText(pnl))+dp(18));
        float l=dp(6),t=dp(87),r=Math.min(plotW-dp(6),l+tw),b=t+dp(43);
        p.setColor(Color.argb(225,24,28,35));p.setStyle(Paint.Style.FILL);c.drawRoundRect(l,t,r,b,dp(6),dp(6),p);
        p.setTextAlign(Paint.Align.LEFT);p.setColor(Color.WHITE);c.drawText(paperSymbol+"  "+side+"  x"+Math.abs(paperQty),l+dp(8),t+dp(16),p);
        p.setColor(openPnl>=0?Color.rgb(66,200,120):Color.rgb(239,83,80));c.drawText(pnl,l+dp(8),t+dp(34),p);
        p.setTypeface(Typeface.DEFAULT);

        if(placementMode!=0){
            String msg=placementMode==1?"Tap chart at Stop Loss price":"Tap chart at Target price";
            p.setTextSize(dp(9));p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.argb(230,40,44,52));
            float mw=Math.min(plotW-dp(12),Math.max(dp(180),p.measureText(msg)+dp(18)));float ml=(plotW-mw)/2;
            c.drawRoundRect(ml,priceH-dp(34),ml+mw,priceH-dp(8),dp(5),dp(5),p);p.setColor(Color.WHITE);c.drawText(msg,plotW/2,priceH-dp(17),p);
        }
    }
    private void drawRiskButton(Canvas c,RectF r,String text,int color,boolean active){
        p.setStyle(Paint.Style.FILL);p.setColor(active?color:Color.argb(235,36,40,48));c.drawRoundRect(r,dp(5),dp(5),p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(color);c.drawRoundRect(r,dp(5),dp(5),p);p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(9));p.setTextAlign(Paint.Align.CENTER);p.setColor(Color.WHITE);c.drawText(text,r.centerX(),r.centerY()+dp(3),p);p.setTypeface(Typeface.DEFAULT);
    }
    private void drawPaperLine(Canvas c,float plotW,float scaleW,float priceH,double min,double max,double value,int color,String name,boolean draggingNow){
        if(value<=0)return;float yy=y(value,min,max,priceH);p.setColor(color);p.setStrokeWidth(draggingNow?dp(2.4f):dp(1.5f));p.setPathEffect(new DashPathEffect(new float[]{dp(7),dp(4)},0));c.drawLine(0,yy,plotW,yy,p);p.setPathEffect(null);
        String lab=name+" "+fmt(value);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(9));float lw=Math.min(scaleW,Math.max(dp(52),p.measureText(lab)+dp(8)));p.setColor(color);c.drawRect(plotW,yy-dp(10),plotW+lw,yy+dp(10),p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);c.drawText(lab,plotW+lw/2,yy+dp(3),p);p.setTypeface(Typeface.DEFAULT);
    }
    private String money(double v){return (v>=0?"+₹":"-₹")+fmt(Math.abs(v));}
    private double priceAtY(float yy){if(lastPriceH<=0||lastMax<=lastMin)return 0;float py=Math.max(dp(8),Math.min(lastPriceH-dp(8),yy));double frac=(py-dp(8))/Math.max(1f,lastPriceH-dp(16));return lastMax-frac*(lastMax-lastMin);}
    private boolean validRiskPrice(int kind,double value){if(paperQty==0||paperAvg<=0||value<=0)return false;if(paperQty>0)return kind==1?value<paperAvg:value>paperAvg;return kind==1?value>paperAvg:value<paperAvg;}
    private int hitRiskLine(float yy){if(paperQty==0||lastPriceH<=0)return 0;float tol=dp(12);if(paperSL>0&&Math.abs(yy-y(paperSL,lastMin,lastMax,lastPriceH))<=tol)return 1;if(paperTarget>0&&Math.abs(yy-y(paperTarget,lastMin,lastMax,lastPriceH))<=tol)return 2;return 0;}

    private void badge(Canvas c,float plotW){p.setColor(Color.rgb(255,152,0));c.drawRoundRect(plotW-dp(78),dp(56),plotW-dp(7),dp(80),dp(5),dp(5),p);p.setColor(Color.BLACK);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(dp(10));p.setTextAlign(Paint.Align.CENTER);c.drawText("REPLAY",plotW-dp(42),dp(72),p);p.setTypeface(Typeface.DEFAULT);}
    private String fmt(double v){return String.format(Locale.US,"%.2f",v);}
    @Override public boolean onTouchEvent(MotionEvent e){
        scaleDetector.onTouchEvent(e);if(scaleDetector.isInProgress())return true;
        float x=e.getX(),yy=e.getY();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(paperQty!=0&&slButton.contains(x,yy)){placementMode=placementMode==1?0:1;invalidate();return true;}
                if(paperQty!=0&&targetButton.contains(x,yy)){placementMode=placementMode==2?0:2;invalidate();return true;}
                if(placementMode==0&&x<=lastPlotW){int hit=hitRiskLine(yy);if(hit!=0){riskDrag=hit;dragging=false;return true;}}
                downX=lastX=x;dragging=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(riskDrag!=0){double v=priceAtY(yy);if(validRiskPrice(riskDrag,v)){if(riskDrag==1)paperSL=v;else paperTarget=v;invalidate();}return true;}
                if(dragging){float plotW=Math.max(dp(120),getWidth()-dp(72));float step=plotW/Math.max(15f,candlesPerScreen);rightOffset+=-(x-lastX)/Math.max(dp(2),step);clampOffset();lastX=x;invalidate();}return true;
            case MotionEvent.ACTION_UP:
                if(riskDrag!=0){int kind=riskDrag;riskDrag=0;double v=kind==1?paperSL:paperTarget;if(riskLineListener!=null&&validRiskPrice(kind,v))riskLineListener.onRiskLineChanged(kind==1?"SL":"TARGET",v);invalidate();return true;}
                if(placementMode!=0&&x<=lastPlotW&&yy<lastPriceH){int kind=placementMode;double v=priceAtY(yy);if(validRiskPrice(kind,v)){if(kind==1)paperSL=v;else paperTarget=v;placementMode=0;if(riskLineListener!=null)riskLineListener.onRiskLineChanged(kind==1?"SL":"TARGET",v);}invalidate();return true;}
                float dist=Math.abs(x-downX);dragging=false;if(dist<dp(8))selectAt(x);return true;
            case MotionEvent.ACTION_CANCEL:riskDrag=0;dragging=false;return true;
        }
        return true;
    }
    private void selectAt(float x){float plotW=Math.max(dp(120),getWidth()-dp(72));if(x>plotW)return;Window w=window(plotW);int idx=w.start+(int)(x/Math.max(1,w.step));if(idx>=w.start&&idx<w.end){selectedIndex=idx;invalidate();if(candleSelectionListener!=null)candleSelectionListener.onCandleSelected(data.get(idx));}}
}
