package com.sbn.virtualtrading;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    TradingChartView chart; TextView status, price, resolved, paperPnl;
    EditText token,clientId; AutoCompleteTextView symbol;
    Button lockBtn;
    String interval="1", selectedTf="1m", currentSid="", currentResolvedSymbol="", currentExchangeSegment="NSE_EQ", currentInstrumentType="EQUITY";
    boolean suppressSymbolSuggestions=false;
    // Latest successful live LTP for order-price validation.
    double lastLtpPrice=Double.NaN;
    int symbolRequestVersion=0; final android.os.Handler symbolHandler=new android.os.Handler(android.os.Looper.getMainLooper()); Runnable pendingSuggest; java.util.List<InstrumentResolver.Instrument> liveSuggestions=new java.util.ArrayList<>();
    int selectedMinutes=1;
    List<Candle> base1m=new ArrayList<>();
    List<Candle> currentData=new ArrayList<>();
    PaperTradingManager paper;
    boolean reverseMode=false;
    final Handler replayHandler=new Handler(Looper.getMainLooper());
    boolean replayPlaying=false;
    int replayDelayMs=700;
    Button replayPlayBtn, intervalButton;
    final Map<String,TextView> tfChipMap=new HashMap<>();
    FrameLayout screenHost;
    LinearLayout floatingReplayPanel, floatingReplayControls;

    int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);} 
    TextView label(String s,int sp){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);t.setPadding(dp(8),dp(5),dp(8),dp(5));return t;}
    @Override public void onCreate(Bundle b){super.onCreate(b);
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(true);
        getWindow().setNavigationBarColor(Color.rgb(16,19,24));getWindow().setStatusBarColor(Color.rgb(16,19,24));paper=new PaperTradingManager(this);InstrumentResolver.init(getApplicationContext());new Thread(()->{InstrumentResolver.warmCache();runOnUiThread(()->{if(status!=null&&InstrumentResolver.cachedCount()>0)status.setText("NSE_EQ cache ready • "+InstrumentResolver.cachedCount()+" • "+InstrumentResolver.cacheSource());});}).start();buildUi();restoreCredentials();currentData.clear();chart.setData(currentData);status.setText("Search NSE stock or NIFTY50 / BANKNIFTY • then GET PRICE / LOAD MAX HISTORY • IST");}

    void buildUi(){
        screenHost=new FrameLayout(this);
        screenHost.setBackgroundColor(Color.rgb(16,19,24));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(16,19,24));root.setPadding(dp(10),dp(8),dp(10),dp(8));
        screenHost.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label("SBN • VIRTUAL TRADING  v1.29",17);title.setTypeface(null,1);top.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        Button portfolioBtn=new Button(this);portfolioBtn.setText("ACCOUNT");portfolioBtn.setTextSize(10);portfolioBtn.setOnClickListener(v->showPortfolioDialog());top.addView(portfolioBtn,new LinearLayout.LayoutParams(dp(78),dp(48)));
        Button go=new Button(this);go.setText("GO TO");go.setTextSize(10);go.setOnClickListener(v->showGoToDialog());top.addView(go,new LinearLayout.LayoutParams(dp(70),dp(48)));root.addView(top);

        LinearLayout symRow=new LinearLayout(this); symRow.setOrientation(LinearLayout.HORIZONTAL);
        symbol=new AutoCompleteTextView(this);symbol.setHint("Symbol / Index e.g. CROMPTON, NIFTY50");symbol.setText("CROMPTON");symbol.setTextColor(Color.WHITE);symbol.setHintTextColor(Color.GRAY);symbol.setSingleLine(true);symbol.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);symbol.setThreshold(1);
        symbol.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence cs,int st,int b,int c){
                String now=cs.toString().trim().toUpperCase(Locale.US);
                if(pendingSuggest!=null)symbolHandler.removeCallbacks(pendingSuggest);
                if(suppressSymbolSuggestions){symbol.dismissDropDown();return;}
                if(!now.equals(currentResolvedSymbol)){currentSid="";lastLtpPrice=Double.NaN;resolved.setText("Symbol → Security ID auto");price.setText("LTP: --");chart.setLivePrice(Double.NaN);}
                // Do not reopen the search dropdown after a symbol has already been selected.
                if(now.isEmpty() || now.equals(currentResolvedSymbol)){symbol.dismissDropDown();return;}
                final int req=++symbolRequestVersion;
                pendingSuggest=()->new Thread(()->{
                    try{
                        java.util.List<InstrumentResolver.Instrument> hits=InstrumentResolver.searchNseEquities(now,80);
                        if(req!=symbolRequestVersion)return;
                        runOnUiThread(()->showLiveSuggestions(now,hits,req));
                    }catch(Exception ignored){}
                }).start();
                symbolHandler.postDelayed(pendingSuggest,260);
            }
            public void afterTextChanged(android.text.Editable e){}
        });
        symbol.setOnItemClickListener((p,v,pos,id)->{
            if(pos>=0&&pos<liveSuggestions.size())selectResolvedInstrument(liveSuggestions.get(pos),symbolRequestVersion);
        });
        Button resolveBtn=new Button(this);resolveBtn.setText("SEARCH");resolveBtn.setOnClickListener(v->resolveSymbolOnly());
        symRow.addView(symbol,new LinearLayout.LayoutParams(0,dp(54),1));symRow.addView(resolveBtn,new LinearLayout.LayoutParams(dp(105),dp(54)));root.addView(symRow);
        resolved=label("Symbol → Security ID auto",12); root.addView(resolved,new LinearLayout.LayoutParams(-1,dp(32)));

        price=label("LTP: --",20); price.setTypeface(null,1); root.addView(price,new LinearLayout.LayoutParams(-1,dp(46)));
        paperPnl=label("PAPER P&L: No open position",12);paperPnl.setTypeface(null,1);paperPnl.setTextColor(Color.LTGRAY);root.addView(paperPnl,new LinearLayout.LayoutParams(-1,dp(32)));
        TextView market=label("NSE CASH / INDEX • IST • Regular market 09:15 AM – 03:30 PM",11); market.setTextColor(Color.LTGRAY); root.addView(market,new LinearLayout.LayoutParams(-1,dp(30)));
        // TradingView-style compact interval toolbar: favorites + one interval menu.
        LinearLayout tfBar=new LinearLayout(this);tfBar.setOrientation(LinearLayout.HORIZONTAL);tfBar.setGravity(Gravity.CENTER_VERTICAL);
        String[] fav={"1m","5m","15m","1H","1D"};
        for(String x:fav){TextView chip=makeTfChip(x);tfChipMap.put(x,chip);chip.setOnClickListener(v->selectTf(((TextView)v).getText().toString()));tfBar.addView(chip,new LinearLayout.LayoutParams(0,dp(42),1));}
        intervalButton=new Button(this);intervalButton.setAllCaps(false);intervalButton.setText("1m ▼");intervalButton.setTextSize(12);intervalButton.setMinWidth(0);intervalButton.setPadding(dp(4),0,dp(4),0);intervalButton.setOnClickListener(v->showIntervalMenu());
        tfBar.addView(intervalButton,new LinearLayout.LayoutParams(dp(92),dp(42)));
        root.addView(tfBar,new LinearLayout.LayoutParams(-1,dp(44)));
        String savedTf=getPreferences(MODE_PRIVATE).getString("selected_tf","1m");
        if(savedTf!=null && java.util.Arrays.asList("1m","2m","3m","5m","6m","7m","8m","9m","10m","15m","25m","1H","1D").contains(savedTf)){
            selectedTf=savedTf;
            int sm=1;
            if(savedTf.endsWith("m"))sm=Integer.parseInt(savedTf.substring(0,savedTf.length()-1)); else if(savedTf.equals("1H"))sm=60;
            selectedMinutes=sm;
            interval=savedTf.equals("1D")?"1D":((sm==1||sm==5||sm==15||sm==25||sm==60)?String.valueOf(sm):"1");
            intervalButton.setText(savedTf+" ▼");
        }
        updateTfHighlight();
        chart=new TradingChartView(this);
        chart.setRiskLineListener((type,value)->applyChartRisk(type,value));
        chart.setCandleSelectionListener(candle -> {
            price.setText("HIST CLOSE ₹"+fmt(candle.close));
            status.setText("HISTORICAL CANDLE • "+formatCandleTime(candle.time)
                +" • O ₹"+fmt(candle.open)+" H ₹"+fmt(candle.high)
                +" L ₹"+fmt(candle.low)+" C ₹"+fmt(candle.close)
                +" • LIVE LTP ₹"+fmt(lastLtpPrice));
        });
        root.addView(chart,new LinearLayout.LayoutParams(-1,0,1));

        status=label("",13);root.addView(status);

        LinearLayout cfg1=new LinearLayout(this);cfg1.setOrientation(LinearLayout.HORIZONTAL);
        token=new EditText(this);token.setHint("Dhan access token");token.setTextColor(Color.WHITE);token.setHintTextColor(Color.GRAY);token.setSingleLine(true);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clientId=new EditText(this);clientId.setHint("Client ID");clientId.setTextColor(Color.WHITE);clientId.setHintTextColor(Color.GRAY);clientId.setSingleLine(true);clientId.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        cfg1.addView(token,new LinearLayout.LayoutParams(0,dp(52),2));cfg1.addView(clientId,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(cfg1);

        LinearLayout authRow=new LinearLayout(this);authRow.setOrientation(LinearLayout.HORIZONTAL);
        Button save=new Button(this);save.setText("SAVE CREDENTIALS");save.setOnClickListener(v->saveCredentials());
        lockBtn=new Button(this);lockBtn.setText("LOCK");lockBtn.setOnClickListener(v->toggleLock());
        authRow.addView(save,new LinearLayout.LayoutParams(0,dp(50),1));authRow.addView(lockBtn,new LinearLayout.LayoutParams(dp(110),dp(50)));root.addView(authRow);

        LinearLayout actRow=new LinearLayout(this);actRow.setOrientation(LinearLayout.HORIZONTAL);
        Button ltp=new Button(this);ltp.setText("GET PRICE");ltp.setOnClickListener(v->loadLtp());
        Button load=new Button(this);load.setText("LOAD MAX HISTORY");load.setOnClickListener(v->loadDhanMax());
        actRow.addView(ltp,new LinearLayout.LayoutParams(0,dp(52),1));actRow.addView(load,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(actRow);

        LinearLayout tradeRow=new LinearLayout(this);tradeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button paperBtn=new Button(this);paperBtn.setText("PAPER TRADE");paperBtn.setOnClickListener(v->showPaperTradeDialog());
        Button moneyBtn=new Button(this);moneyBtn.setText("PAPER MONEY");moneyBtn.setOnClickListener(v->showPaperMoneyDialog());
        Button reverseBtn=new Button(this);reverseBtn.setText("REVERSE OFF");reverseBtn.setOnClickListener(v->{reverseMode=!reverseMode;reverseBtn.setText(reverseMode?"REVERSE ON":"REVERSE OFF");status.setText("Reverse Engine "+(reverseMode?"ON • Bull↔Bear replay direction swapped":"OFF • Normal replay direction"));});
        tradeRow.addView(paperBtn,new LinearLayout.LayoutParams(0,dp(52),1));tradeRow.addView(moneyBtn,new LinearLayout.LayoutParams(0,dp(52),1));tradeRow.addView(reverseBtn,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(tradeRow);

        LinearLayout riskRow=new LinearLayout(this);riskRow.setOrientation(LinearLayout.HORIZONTAL);
        Button riskBtn=new Button(this);riskBtn.setText("SL / TARGET ORDERS");riskBtn.setTextSize(11);riskBtn.setOnClickListener(v->showRiskOrderDialog());
        riskRow.addView(riskBtn,new LinearLayout.LayoutParams(-1,dp(48)));root.addView(riskRow);
        addFixedReplayControls(root);
        setContentView(screenHost);
    }

    void addFixedReplayControls(LinearLayout root){
        LinearLayout replayRow=new LinearLayout(this);
        replayRow.setOrientation(LinearLayout.HORIZONTAL);
        replayRow.setGravity(Gravity.CENTER_VERTICAL);
        Button replayStart=new Button(this);replayStart.setText("REPLAY");replayStart.setTextSize(10);replayStart.setOnClickListener(v->startReplay());
        replayPlayBtn=new Button(this);replayPlayBtn.setText("▶");replayPlayBtn.setTextSize(12);replayPlayBtn.setOnClickListener(v->toggleReplayPlay());
        Button replayNext=new Button(this);replayNext.setText("NEXT");replayNext.setTextSize(10);replayNext.setOnClickListener(v->replayNext());
        Button replaySpeed=new Button(this);replaySpeed.setText("1x");replaySpeed.setTextSize(10);replaySpeed.setOnClickListener(v->showReplaySpeed(replaySpeed));
        Button replayReset=new Button(this);replayReset.setText("RESET");replayReset.setTextSize(10);replayReset.setOnClickListener(v->stopReplay());
        replayRow.addView(replayStart,new LinearLayout.LayoutParams(0,dp(44),1));
        replayRow.addView(replayPlayBtn,new LinearLayout.LayoutParams(0,dp(44),1));
        replayRow.addView(replayNext,new LinearLayout.LayoutParams(0,dp(44),1));
        replayRow.addView(replaySpeed,new LinearLayout.LayoutParams(0,dp(44),1));
        replayRow.addView(replayReset,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(replayRow,new LinearLayout.LayoutParams(-1,dp(46)));
    }

    void addFloatingReplayPanel(){
        floatingReplayPanel=new LinearLayout(this);
        floatingReplayPanel.setOrientation(LinearLayout.VERTICAL);
        floatingReplayPanel.setPadding(dp(5),dp(4),dp(5),dp(5));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(238,28,32,39)); bg.setCornerRadius(dp(9)); bg.setStroke(dp(1),Color.rgb(85,92,104));
        floatingReplayPanel.setBackground(bg);
        floatingReplayPanel.setElevation(dp(10));

        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView drag=label("☰  REPLAY / MANUAL BACKTEST",11);drag.setTypeface(null,1);drag.setTextColor(Color.WHITE);
        Button collapse=new Button(this);collapse.setText("—");collapse.setTextSize(12);collapse.setMinWidth(0);collapse.setPadding(0,0,0,0);
        header.addView(drag,new LinearLayout.LayoutParams(0,dp(34),1));
        header.addView(collapse,new LinearLayout.LayoutParams(dp(42),dp(34)));
        floatingReplayPanel.addView(header,new LinearLayout.LayoutParams(dp(310),dp(34)));

        floatingReplayControls=new LinearLayout(this);floatingReplayControls.setOrientation(LinearLayout.HORIZONTAL);
        Button replayStart=new Button(this);replayStart.setText("REPLAY");replayStart.setTextSize(10);replayStart.setOnClickListener(v->startReplay());
        replayPlayBtn=new Button(this);replayPlayBtn.setText("▶");replayPlayBtn.setTextSize(12);replayPlayBtn.setOnClickListener(v->toggleReplayPlay());
        Button replayNext=new Button(this);replayNext.setText("NEXT");replayNext.setTextSize(10);replayNext.setOnClickListener(v->replayNext());
        Button replaySpeed=new Button(this);replaySpeed.setText("1x");replaySpeed.setTextSize(10);replaySpeed.setOnClickListener(v->showReplaySpeed(replaySpeed));
        Button replayReset=new Button(this);replayReset.setText("RESET");replayReset.setTextSize(10);replayReset.setOnClickListener(v->stopReplay());
        floatingReplayControls.addView(replayStart,new LinearLayout.LayoutParams(dp(72),dp(42)));
        floatingReplayControls.addView(replayPlayBtn,new LinearLayout.LayoutParams(dp(52),dp(42)));
        floatingReplayControls.addView(replayNext,new LinearLayout.LayoutParams(dp(60),dp(42)));
        floatingReplayControls.addView(replaySpeed,new LinearLayout.LayoutParams(dp(52),dp(42)));
        floatingReplayControls.addView(replayReset,new LinearLayout.LayoutParams(dp(68),dp(42)));
        floatingReplayPanel.addView(floatingReplayControls,new LinearLayout.LayoutParams(dp(310),dp(44)));
        collapse.setOnClickListener(v->{
            boolean open=floatingReplayControls.getVisibility()==View.VISIBLE;
            floatingReplayControls.setVisibility(open?View.GONE:View.VISIBLE);
            collapse.setText(open?"+":"—");
        });

        final float[] touch=new float[4];
        drag.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    touch[0]=e.getRawX();touch[1]=e.getRawY();touch[2]=floatingReplayPanel.getX();touch[3]=floatingReplayPanel.getY();return true;
                case MotionEvent.ACTION_MOVE:
                    float nx=touch[2]+e.getRawX()-touch[0], ny=touch[3]+e.getRawY()-touch[1];
                    float maxX=Math.max(0,screenHost.getWidth()-floatingReplayPanel.getWidth());
                    float maxY=Math.max(0,screenHost.getHeight()-floatingReplayPanel.getHeight());
                    floatingReplayPanel.setX(Math.max(0,Math.min(nx,maxX)));
                    floatingReplayPanel.setY(Math.max(0,Math.min(ny,maxY)));return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:return true;
            }return false;
        });
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(320),FrameLayout.LayoutParams.WRAP_CONTENT);
        fp.gravity=Gravity.TOP|Gravity.START;
        screenHost.addView(floatingReplayPanel,fp);
    }

    void restoreCredentials(){
        token.setText(CredentialStore.load(this,"token")); clientId.setText(CredentialStore.load(this,"client"));
        applyLock(CredentialStore.isLocked(this));
    }
    void saveCredentials(){
        try{
            String t=token.getText().toString().trim(), c=clientId.getText().toString().trim();
            if(t.isEmpty()||c.isEmpty()){Toast.makeText(this,"Token and Client ID required",Toast.LENGTH_SHORT).show();return;}
            CredentialStore.save(this,"token",t); CredentialStore.save(this,"client",c); CredentialStore.setLocked(this,true); applyLock(true);
            Toast.makeText(this,"Credentials saved & locked",Toast.LENGTH_SHORT).show();
        }catch(Exception e){status.setText("Credential save error: "+e.getMessage());}
    }
    void toggleLock(){
        boolean locked=CredentialStore.isLocked(this);
        CredentialStore.setLocked(this,!locked); applyLock(!locked);
    }
    void applyLock(boolean locked){
        token.setEnabled(!locked); clientId.setEnabled(!locked);
        token.setAlpha(locked ? .65f : 1f); clientId.setAlpha(locked ? .65f : 1f);
        lockBtn.setText(locked?"UNLOCK":"LOCK");
    }

    boolean validate(boolean needClient){
        if(token.getText().toString().trim().isEmpty()){Toast.makeText(this,"Dhan access token required",Toast.LENGTH_SHORT).show();return false;}
        if(symbol.getText().toString().trim().isEmpty()){Toast.makeText(this,"Symbol / index required",Toast.LENGTH_SHORT).show();return false;}
        if(needClient && clientId.getText().toString().trim().isEmpty()){Toast.makeText(this,"Dhan Client ID required for live price",Toast.LENGTH_LONG).show();return false;}
        return true;
    }

    interface SidAction{void run(String sid,String exchangeSegment,String instrumentType)throws Exception;}

    String errorText(Throwable e){
        if(e==null)return "Unknown error";
        String m=e.getMessage();
        if(m!=null&&!m.trim().isEmpty())return m;
        return e.getClass().getSimpleName();
    }

    // IMPORTANT: every Dhan/network action runs on a worker thread.
    // v1.14 could call action.run() on the Android main thread when the SID was
    // already cached, causing NetworkOnMainThreadException (often message=null).
    void withResolvedSid(SidAction action){
        final String sym=symbol.getText().toString().trim().toUpperCase(Locale.US);
        symbol.setText(sym);
        if(!currentSid.isEmpty() && sym.equals(currentResolvedSymbol)){
            final String sid=currentSid;
            new Thread(()->{
                try{action.run(sid,currentExchangeSegment,currentInstrumentType);}
                catch(Exception e){runOnUiThread(()->status.setText("Symbol/API error: "+errorText(e)));}
            },"SBN-Dhan-"+sym).start();
            return;
        }
        final int request=++symbolRequestVersion;
        status.setText("Resolving "+sym+" • pinned index / local NSE cache, then refresh if needed …");
        new Thread(()->{
            try{
                InstrumentResolver.Instrument inst=InstrumentResolver.resolveInstrument(sym);
                if(request!=symbolRequestVersion)return;
                String sid=inst.securityId; currentSid=sid; currentResolvedSymbol=inst.symbol; currentExchangeSegment=inst.exchangeSegment; currentInstrumentType=inst.instrumentType;
                runOnUiThread(()->{symbol.setText(inst.symbol,false);resolved.setText(inst.symbol+" • "+inst.exchangeSegment+" • "+inst.instrumentType+" • Security ID "+sid);});
                try{action.run(sid,inst.exchangeSegment,inst.instrumentType);}
                catch(Exception ex){runOnUiThread(()->status.setText("Symbol/API error: "+errorText(ex)));}
            }catch(Exception e){
                if(request==symbolRequestVersion)runOnUiThread(()->status.setText("Symbol/API error: "+errorText(e)));
            }
        },"SBN-Resolve-"+sym).start();
    }

    void showLiveSuggestions(String q,java.util.List<InstrumentResolver.Instrument> hits,int req){
        if(req!=symbolRequestVersion||hits==null||hits.isEmpty()||suppressSymbolSuggestions)return;
        String typed=symbol.getText().toString().trim().toUpperCase(Locale.US);
        if(!typed.equals(q)||typed.equals(currentResolvedSymbol))return;
        liveSuggestions=new java.util.ArrayList<>(hits);
        java.util.ArrayList<String> rows=new java.util.ArrayList<>();
        for(InstrumentResolver.Instrument x:hits)rows.add(x.symbol+(x.displayName==null||x.displayName.isEmpty()?"":"  •  "+x.displayName)+("IDX_I".equals(x.exchangeSegment)?"  •  INDEX":""));
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,rows);
        symbol.setAdapter(a);
        if(symbol.hasFocus()){symbol.showDropDown();}
    }

    void resolveSymbolOnly(){
        String q=symbol.getText().toString().trim().toUpperCase(Locale.US);
        if(q.isEmpty()){Toast.makeText(this,"Type symbol/name first",Toast.LENGTH_SHORT).show();return;}
        final int request=++symbolRequestVersion;
        currentSid=""; currentResolvedSymbol=""; lastLtpPrice=Double.NaN;
        pauseReplay(); base1m.clear(); currentData.clear(); chart.setData(currentData); chart.setLivePrice(Double.NaN); price.setText("LTP: --");refreshPaperOverlay();
        resolved.setText("Searching "+q+" …"); status.setText("Searching NIFTY/BANKNIFTY + local NSE symbol cache • "+q+" …");
        new Thread(()->{
            try{
                List<InstrumentResolver.Instrument> hits=InstrumentResolver.searchFreshOnMiss(q,120);
                if(request!=symbolRequestVersion)return;
                runOnUiThread(()->{
                    InstrumentResolver.Instrument exact=null;
                    for(InstrumentResolver.Instrument x:hits)if(InstrumentResolver.queryMatchesInstrument(q,x)){exact=x;break;}
                    if(exact!=null){selectResolvedInstrument(exact,request);}else showSymbolResults(q,hits);
                });
            }catch(Exception e){if(request==symbolRequestVersion)runOnUiThread(()->status.setText("Symbol search error: "+e.getMessage()));}
        }).start();
    }

    void selectResolvedInstrument(InstrumentResolver.Instrument x,int request){
        if(request!=symbolRequestVersion)return;
        currentResolvedSymbol=x.symbol.toUpperCase(Locale.US); currentSid=x.securityId; currentExchangeSegment=x.exchangeSegment; currentInstrumentType=x.instrumentType; symbolRequestVersion++;
        suppressSymbolSuggestions=true;
        symbol.setText(x.symbol,false);
        symbol.setSelection(symbol.getText().length());
        symbol.dismissDropDown();
        suppressSymbolSuggestions=false;
        resolved.setText(x.symbol+" • "+x.exchangeSegment+" • "+x.instrumentType+" • Security ID "+x.securityId);
        lastLtpPrice=Double.NaN;
        pauseReplay(); base1m.clear(); currentData.clear(); chart.setData(currentData); chart.setLivePrice(Double.NaN); price.setText(x.symbol+"  LTP --");refreshPaperOverlay();
        status.setText("Selected "+x.symbol+" • loading price/history…");
        loadLtp(); loadDhanMax();
    }

    void showSymbolResults(String q,List<InstrumentResolver.Instrument> hits){
        if(hits==null||hits.isEmpty()){status.setText("No NSE equity/index match for "+q+" after fresh symbol refresh. Check spelling.");return;}
        String[] rows=new String[hits.size()]; for(int i=0;i<hits.size();i++)rows[i]=hits.get(i).toString();
        new AlertDialog.Builder(this).setTitle("NSE SYMBOL / INDEX SEARCH • "+q+" • select match").setItems(rows,(d,which)->{
            InstrumentResolver.Instrument x=hits.get(which); selectResolvedInstrument(x,symbolRequestVersion);
        }).setNegativeButton("CANCEL",null).show();
    }

    void loadLtp(){
        if(!validate(true))return;
        final String tok=token.getText().toString().trim(), cid=clientId.getText().toString().trim();
        price.setText("LTP: loading…");
        withResolvedSid((sid,seg,type)->{try{double p=DhanRepository.ltp(tok,cid,sid,seg);runOnUiThread(()->{
            lastLtpPrice=p;
            boolean replay=chart!=null&&chart.isReplayMode();
            int selectedIndex = chart.getSelectedIndex();
            Candle selectedCandle = chart.getCandle(selectedIndex);

            if (replay) {
                price.setText("LIVE LTP ₹"+fmt(p)
                    +" • REPLAY ₹"+fmt(chart.executionPrice()));
            } else if (selectedCandle != null) {
                price.setText("HIST CLOSE ₹"+fmt(selectedCandle.close)
                    +" • LIVE LTP ₹"+fmt(p));
            } else {
                price.setText(symbol.getText()+"  LTP ₹"+fmt(p));
            }
            chart.setLivePrice(p);
            PaperTradingManager.RiskEvent ev=replay?null:paper.checkPrice(paperSymbol(),p);
            refreshPaperOverlay(replay?chart.executionPrice():p);
            if(replay)status.setText("Replay active • live LTP refreshed separately");
            else if(ev!=null)status.setText("PAPER "+ev.type+" HIT • "+paperSymbol()+" exit ₹"+fmt(ev.exitPrice)+" • Qty "+ev.closedQty+" • Realized ₹"+fmt(ev.realizedAfter));
            else status.setText("Live price loaded • Paper P&L updated");
        });}catch(Exception e){runOnUiThread(()->{price.setText("LTP: ERROR");status.setText("LTP error: "+errorText(e));});}});
    }

    void showGoToDialog(){
        if(currentData==null||currentData.isEmpty()){
            Toast.makeText(this,"Load historical candles first",Toast.LENGTH_SHORT).show();return;
        }

        final Calendar cal=Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"),Locale.US);
        cal.setTimeInMillis(currentData.get(currentData.size()-1).time);

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(8),dp(18),0);

        TextView hint=label("Jump to date & time • IST",12);
        hint.setTextColor(Color.LTGRAY);
        box.addView(hint,new LinearLayout.LayoutParams(-1,dp(38)));

        final SimpleDateFormat dateFmt=new SimpleDateFormat("dd MMM yyyy",Locale.US);
        final SimpleDateFormat timeFmt=new SimpleDateFormat("HH:mm",Locale.US);
        dateFmt.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        timeFmt.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));

        LinearLayout pickerRow=new LinearLayout(this);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);

        Button dateBtn=new Button(this);
        dateBtn.setAllCaps(false);
        Button timeBtn=new Button(this);
        timeBtn.setAllCaps(false);

        final Runnable refresh=()->{
            dateBtn.setText("CALENDAR\n"+dateFmt.format(cal.getTime()));
            timeBtn.setText(selectedTf.equals("1D")?"TIME\nDaily":"TIME\n"+timeFmt.format(cal.getTime())+" IST");
        };
        refresh.run();

        dateBtn.setOnClickListener(v->{
            DatePickerDialog d=new DatePickerDialog(this,(view,y,mon,day)->{
                cal.set(Calendar.YEAR,y);
                cal.set(Calendar.MONTH,mon);
                cal.set(Calendar.DAY_OF_MONTH,day);
                refresh.run();
            },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH));
            d.setTitle("Select date");
            d.show();
        });

        timeBtn.setEnabled(!selectedTf.equals("1D"));
        timeBtn.setAlpha(selectedTf.equals("1D") ? 0.55f : 1f);
        timeBtn.setOnClickListener(v->{
            TimePickerDialog t=new TimePickerDialog(this,(view,h,min)->{
                cal.set(Calendar.HOUR_OF_DAY,h);
                cal.set(Calendar.MINUTE,min);
                cal.set(Calendar.SECOND,0);
                cal.set(Calendar.MILLISECOND,0);
                refresh.run();
            },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true);
            t.setTitle("Select time • IST");
            t.show();
        });

        pickerRow.addView(dateBtn,new LinearLayout.LayoutParams(0,dp(66),1));
        pickerRow.addView(timeBtn,new LinearLayout.LayoutParams(0,dp(66),1));
        box.addView(pickerRow);

        if(!selectedTf.equals("1D")){
            TextView quickTitle=label("Quick market time",11);
            quickTitle.setTextColor(Color.LTGRAY);
            box.addView(quickTitle,new LinearLayout.LayoutParams(-1,dp(32)));

            LinearLayout quick=new LinearLayout(this);
            quick.setOrientation(LinearLayout.HORIZONTAL);
            Button open=new Button(this);open.setText("09:15");open.setTextSize(11);
            Button noon=new Button(this);noon.setText("12:00");noon.setTextSize(11);
            Button close=new Button(this);close.setText("15:30");close.setTextSize(11);
            View.OnClickListener qt=v->{
                String[] p=((Button)v).getText().toString().split(":");
                cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(p[0]));
                cal.set(Calendar.MINUTE,Integer.parseInt(p[1]));
                cal.set(Calendar.SECOND,0);
                cal.set(Calendar.MILLISECOND,0);
                refresh.run();
            };
            open.setOnClickListener(qt);noon.setOnClickListener(qt);close.setOnClickListener(qt);
            quick.addView(open,new LinearLayout.LayoutParams(0,dp(44),1));
            quick.addView(noon,new LinearLayout.LayoutParams(0,dp(44),1));
            quick.addView(close,new LinearLayout.LayoutParams(0,dp(44),1));
            box.addView(quick);
        }

        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle("GO TO")
            .setView(box)
            .setPositiveButton("GO",null)
            .setNegativeButton("CANCEL",null)
            .create();

        dlg.setOnShowListener(x->dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(selectedTf.equals("1D")){
                cal.set(Calendar.HOUR_OF_DAY,12);
                cal.set(Calendar.MINUTE,0);
                cal.set(Calendar.SECOND,0);
                cal.set(Calendar.MILLISECOND,0);
            }
            int idx=chart.jumpToTime(cal.getTimeInMillis());
            if(idx>=0){
                Candle c=chart.getCandle(idx);
                SimpleDateFormat full=new SimpleDateFormat(selectedTf.equals("1D")?"dd MMM yyyy":"dd MMM yyyy • HH:mm:ss 'IST'",Locale.US);
                full.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
                status.setText("GO TO • "+full.format(new Date(c.time))+" • O "+fmt(c.open)+" H "+fmt(c.high)+" L "+fmt(c.low)+" C "+fmt(c.close));
                dlg.dismiss();
            }else{
                Toast.makeText(this,"No candle found for selected date/time",Toast.LENGTH_LONG).show();
            }
        }));
        dlg.show();
    }

    String fmt(double x){return String.format(Locale.US,"%.2f",x);}
    TextView makeTfChip(String text){
        TextView t=label(text,12);t.setGravity(Gravity.CENTER);t.setTypeface(null,1);t.setClickable(true);t.setFocusable(true);t.setBackground(makeChipBg(false));return t;
    }
    android.graphics.drawable.Drawable makeChipBg(boolean active){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();
        g.setColor(active?Color.rgb(35,90,86):Color.rgb(30,34,41));g.setCornerRadius(dp(5));g.setStroke(dp(1),active?Color.rgb(43,190,170):Color.rgb(65,70,80));return g;
    }
    void updateTfHighlight(){
        for(Map.Entry<String,TextView> e:tfChipMap.entrySet())e.getValue().setBackground(makeChipBg(e.getKey().equals(selectedTf)));
    }
    void showIntervalMenu(){
        if(symbol!=null)symbol.dismissDropDown();
        final String[] items={"1 minute","2 minutes","3 minutes","5 minutes","6 minutes","7 minutes","8 minutes","9 minutes","10 minutes","15 minutes","25 minutes","1 hour","1 day"};
        final String[] vals={"1m","2m","3m","5m","6m","7m","8m","9m","10m","15m","25m","1H","1D"};
        int checked=0;for(int i=0;i<vals.length;i++)if(vals[i].equals(selectedTf)){checked=i;break;}
        new AlertDialog.Builder(this).setTitle("INTERVAL").setSingleChoiceItems(items,checked,(d,w)->{d.dismiss();selectTf(vals[w]);}).setNegativeButton("CANCEL",null).show();
    }
    void selectTf(String x){
        if(symbol!=null)symbol.dismissDropDown();
        selectedTf=x;
        getPreferences(MODE_PRIVATE).edit().putString("selected_tf",x).apply();
        if(intervalButton!=null)intervalButton.setText(x+" ▼");
        updateTfHighlight();
        int m=1;
        if(x.endsWith("m"))m=Integer.parseInt(x.substring(0,x.length()-1));
        else if(x.equals("1H"))m=60;
        selectedMinutes=m;
        interval=x.equals("1D")?"1D":((m==1||m==5||m==15||m==25||m==60)?String.valueOf(m):"1");
        pauseReplay();

        // TradingView-like behavior: change the visible chart immediately when local 1m data exists.
        if(x.equals("1D")){
            status.setText("1D selected • loading daily candles…");
            if(!currentSid.isEmpty() && !token.getText().toString().trim().isEmpty())loadDhanMax();
            else {currentData.clear();chart.setData(currentData);status.setText("1D selected • search symbol / load history");}
            return;
        }
        if(!base1m.isEmpty()){
            List<Candle> d=(m==1)?new ArrayList<>(base1m):TimeframeAggregator.aggregateMinutes(base1m,m);
            show(d,x+(m>1?" • built from 1m":""));
        }else if(!currentSid.isEmpty() && !token.getText().toString().trim().isEmpty()){
            status.setText(x+" selected • loading candles…");loadDhanMax();
        }else status.setText(x+" selected • search symbol / load history");
    }
    void show(List<Candle>d,String msg){pauseReplay();currentData=new ArrayList<>(d);chart.setData(d);refreshPaperOverlay();int bull=0,bear=0;for(Candle c:d){if(c.signal==Candle.Signal.BULLISH)bull++;if(c.signal==Candle.Signal.BEARISH)bear++;}status.setText(msg+" • candles "+d.size()+" • Bull ▲ "+bull+" • Bear ▼ "+bear);}
    double lastTradePrice(){
        // Replay uses its candle close; normal trading prefers the latest live LTP.
        if(chart!=null && chart.isReplayMode()) return chart.executionPrice();
        if(lastLtpPrice>0 && !Double.isNaN(lastLtpPrice) && !Double.isInfinite(lastLtpPrice)) return lastLtpPrice;
        if(currentData!=null&&!currentData.isEmpty())return currentData.get(currentData.size()-1).close;
        return 0;
    }

    void refreshPaperOverlay(){refreshPaperOverlay(lastTradePrice());}
    void refreshPaperOverlay(double mark){
        if(chart==null||paper==null)return;
        String ps=paperSymbol();int q=paper.qty(ps);if(mark<=0)mark=lastTradePrice();
        if(q==0){chart.setPaperPosition(ps,0,0,0,0,paper.realized(ps),mark);if(paperPnl!=null){paperPnl.setText("PAPER P&L: Realized ₹"+fmt(paper.realized(ps))+" • No open position");paperPnl.setTextColor(Color.LTGRAY);}return;}
        double open=paper.unrealized(ps,mark),real=paper.realized(ps);
        chart.setPaperPosition(ps,q,paper.avg(ps),paper.stopLoss(ps),paper.target(ps),real,mark);
        if(paperPnl!=null){paperPnl.setText("PAPER P&L: "+(open>=0?"+₹":"-₹")+fmt(Math.abs(open))+" • Realized ₹"+fmt(real)+" • "+(q>0?"LONG ":"SHORT ")+Math.abs(q));paperPnl.setTextColor(open>=0?Color.rgb(66,200,120):Color.rgb(239,83,80));}
    }
    void applyChartRisk(String type,double value){
        String ps=paperSymbol();int q=paper.qty(ps);if(q==0){Toast.makeText(this,"Open a paper position first",Toast.LENGTH_SHORT).show();return;}
        double avg=paper.avg(ps),sl=paper.stopLoss(ps),tg=paper.target(ps);
        boolean isSl="SL".equals(type);
        boolean valid=q>0?(isSl?value<avg:value>avg):(isSl?value>avg:value<avg);
        if(!valid){Toast.makeText(this,(q>0?(isSl?"LONG SL must be below entry":"LONG target must be above entry"):(isSl?"SHORT SL must be above entry":"SHORT target must be below entry")),Toast.LENGTH_LONG).show();refreshPaperOverlay();return;}
        if(isSl)sl=value;else tg=value;paper.setRisk(ps,sl,tg);refreshPaperOverlay();status.setText(ps+" • "+(isSl?"Stop Loss":"Target")+" set from chart @ ₹"+fmt(value)+" • drag line anytime to adjust");
    }

    String paperSymbol(){String x=currentResolvedSymbol; if(x==null||x.trim().isEmpty())x=symbol.getText().toString();return x.trim().toUpperCase(Locale.US);}
    void showPortfolioDialog(){
        java.util.List<String> names=paper.symbols();
        StringBuilder report=new StringBuilder();
        report.append("VIRTUAL ACCOUNT\n");
        report.append("Available cash: ₹").append(fmt(paper.balance())).append("\n");
        report.append("Realized P&L: ₹").append(fmt(paper.totalRealized())).append("\n");
        report.append("Open positions: ").append(paper.openPositionCount()).append("\n\n");
        if(names.isEmpty()) report.append("No paper trades yet. Search a symbol, load its price/history, then use PAPER TRADE.");
        else {
            for(String name:names){
                int q=paper.qty(name);
                report.append(name).append("  |  ");
                if(q==0) report.append("FLAT");
                else report.append(q>0?"LONG ":"SHORT ").append(Math.abs(q)).append(" @ ₹").append(fmt(paper.avg(name)));
                report.append("\n   Realized: ₹").append(fmt(paper.realized(name)));
                if(name.equalsIgnoreCase(paperSymbol()) && q!=0){
                    double mark=lastTradePrice();
                    if(mark>0)report.append("  |  Unrealized: ₹").append(fmt(paper.unrealized(name,mark)));
                }
                report.append("\n");
            }
        }
        TextView body=label(report.toString(),14);body.setTextColor(Color.WHITE);body.setPadding(dp(18),dp(12),dp(18),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("ACCOUNT / PORTFOLIO").setView(scroll).setPositiveButton("CLOSE",null).setNeutralButton("PAPER MONEY",(d,w)->showPaperMoneyDialog()).show();
    }

    void showPaperTradeDialog(){
        final String ps=paperSymbol();
        if(ps.isEmpty()){
            Toast.makeText(this,"Search a symbol first",Toast.LENGTH_SHORT).show();
            return;
        }
        if(currentData==null || currentData.isEmpty()){
            Toast.makeText(this,"Load historical candles first",Toast.LENGTH_SHORT).show();
            return;
        }

        final double liveMark=lastTradePrice();
        final double mark=(liveMark>0 && Double.isFinite(liveMark))
                ?liveMark:currentData.get(currentData.size()-1).close;

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);

        TextView account=label("",14);
        EditText qty=new EditText(this);
        qty.setHint("Quantity");
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        qty.setText("1");

        EditText sl=new EditText(this);
        sl.setHint("Stop Loss price (optional)");
        sl.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);

        EditText target=new EditText(this);
        target.setHint("Target price (optional)");
        target.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);

        if(paper.stopLoss(ps)>0)sl.setText(fmt(paper.stopLoss(ps)));
        if(paper.target(ps)>0)target.setText(fmt(paper.target(ps)));

        CheckBox historical=new CheckBox(this);
        historical.setText("Use historical Date/Time candle price");
        historical.setTextColor(Color.WHITE);

        final TimeZone ist=TimeZone.getTimeZone("Asia/Kolkata");
        final Calendar chosen=Calendar.getInstance(ist);
        chosen.setTimeInMillis(currentData.get(currentData.size()-1).time);

        final Button dateBtn=new Button(this);
        final Button timeBtn=new Button(this);
        String[] priceFields={"Open","High","Low","Close"};
        Spinner field=new Spinner(this);
        field.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,priceFields));

        SimpleDateFormat dateFmt=new SimpleDateFormat("dd MMM yyyy",Locale.US);
        dateFmt.setTimeZone(ist);
        SimpleDateFormat timeFmt=new SimpleDateFormat("HH:mm",Locale.US);
        timeFmt.setTimeZone(ist);

        Runnable updateDate=()->dateBtn.setText("DATE • "+dateFmt.format(chosen.getTime()));
        Runnable updateTime=()->timeBtn.setText("TIME • "+timeFmt.format(chosen.getTime())+" IST");
        updateDate.run();
        updateTime.run();

        dateBtn.setOnClickListener(v->{
            DatePickerDialog dp=new DatePickerDialog(this,(view,y,m,d)->{
                chosen.set(Calendar.YEAR,y);
                chosen.set(Calendar.MONTH,m);
                chosen.set(Calendar.DAY_OF_MONTH,d);
                updateDate.run();
            },chosen.get(Calendar.YEAR),chosen.get(Calendar.MONTH),
                    chosen.get(Calendar.DAY_OF_MONTH));
            dp.show();
        });
        timeBtn.setOnClickListener(v->{
            TimePickerDialog tp=new TimePickerDialog(this,(view,h,m)->{
                chosen.set(Calendar.HOUR_OF_DAY,h);
                chosen.set(Calendar.MINUTE,m);
                chosen.set(Calendar.SECOND,0);
                chosen.set(Calendar.MILLISECOND,0);
                updateTime.run();
            },chosen.get(Calendar.HOUR_OF_DAY),chosen.get(Calendar.MINUTE),true);
            tp.show();
        });

        Button editEntry=new Button(this);
        editEntry.setText("EDIT ENTRY PRICE");
        editEntry.setOnClickListener(v->showEditEntryDialog(ps));

        Runnable updateAccount=()->{
            int pos=paper.qty(ps);
            double u=mark>0?paper.unrealized(ps,mark):0;
            account.setText(ps+" • Price ₹"+fmt(mark)
                    +"\nAvailable Cash ₹"+fmt(paper.balance())
                    +"\nPosition "+pos+" @ ₹"+fmt(paper.avg(ps))
                    +"\nSL "+riskText(paper.stopLoss(ps))
                    +" • Target "+riskText(paper.target(ps))
                    +"\nRealized ₹"+fmt(paper.realized(ps))
                    +" • Unrealized ₹"+fmt(u)
                    +(mark>0?"\nEquity ₹"+fmt(paper.equity(ps,mark)):"")
                    +"\nHistorical price applies to the selected candle only.");
        };
        updateAccount.run();

        box.addView(account);
        box.addView(editEntry);
        box.addView(qty);
        box.addView(sl);
        box.addView(target);
        box.addView(historical);
        box.addView(dateBtn);
        box.addView(timeBtn);
        box.addView(field);

        historical.setOnCheckedChangeListener((button,checked)->{
            dateBtn.setEnabled(checked);
            timeBtn.setEnabled(checked && !selectedTf.equals("1D"));
            field.setEnabled(checked);
        });
        dateBtn.setEnabled(false);
        timeBtn.setEnabled(false);
        field.setEnabled(false);

        AlertDialog dlg=new AlertDialog.Builder(this)
                .setTitle("PAPER TRADE • "+ps)
                .setView(box)
                .setPositiveButton("BUY",null)
                .setNegativeButton("SELL",null)
                .setNeutralButton("CLEAR POSITION",null)
                .create();

        dlg.setOnShowListener(x->{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                double px;
                String source="LIVE / REPLAY";
                if(historical.isChecked()){
                    px=resolveHistoricalTradePrice(chosen.getTimeInMillis(),
                            field.getSelectedItemPosition());
                    source="HISTORICAL "+priceFields[field.getSelectedItemPosition()]
                            +" • "+dateFmt.format(chosen.getTime())
                            +(selectedTf.equals("1D")?"":" "+timeFmt.format(chosen.getTime())+" IST");
                }else{
                    px=lastTradePrice();
                }
                if(px<=0 || !Double.isFinite(px)){
                    Toast.makeText(this,
                            historical.isChecked()
                                    ?"No matching candle at the selected date/time"
                                    :"Load candles/price first",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                paperTrade(ps,true,qty,sl,target,dlg,px,source);
            });
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{
                double px;
                String source="LIVE / REPLAY";
                if(historical.isChecked()){
                    px=resolveHistoricalTradePrice(chosen.getTimeInMillis(),
                            field.getSelectedItemPosition());
                    source="HISTORICAL "+priceFields[field.getSelectedItemPosition()]
                            +" • "+dateFmt.format(chosen.getTime())
                            +(selectedTf.equals("1D")?"":" "+timeFmt.format(chosen.getTime())+" IST");
                }else{
                    px=lastTradePrice();
                }
                if(px<=0 || !Double.isFinite(px)){
                    Toast.makeText(this,
                            historical.isChecked()
                                    ?"No matching candle at the selected date/time"
                                    :"Load candles/price first",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                paperTrade(ps,false,qty,sl,target,dlg,px,source);
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                paper.clearPositionAndPnl(ps);
                refreshPaperOverlay();
                dlg.dismiss();
                status.setText(ps+" paper position/P&L cleared • Cash ₹"+fmt(paper.balance()));
            });
        });
        dlg.show();
    }

    double resolveHistoricalTradePrice(long selectedTime,int fieldIndex){
        if(currentData==null || currentData.isEmpty())return Double.NaN;

        TimeZone ist=TimeZone.getTimeZone("Asia/Kolkata");
        Calendar selected=Calendar.getInstance(ist);
        selected.setTimeInMillis(selectedTime);
        Candle best=null;
        long bestDiff=Long.MAX_VALUE;

        for(Candle c:currentData){
            Calendar ct=Calendar.getInstance(ist);
            ct.setTimeInMillis(c.time);
            if(ct.get(Calendar.YEAR)!=selected.get(Calendar.YEAR)
                    || ct.get(Calendar.DAY_OF_YEAR)!=selected.get(Calendar.DAY_OF_YEAR))
                continue;

            long diff=Math.abs(c.time-selectedTime);
            if(diff<bestDiff){
                best=c;
                bestDiff=diff;
            }
        }

        if(best==null)return Double.NaN;

        if(!selectedTf.equals("1D")){
            long intervalMs=Math.max(1,selectedMinutes)*60_000L;
            if(bestDiff>intervalMs/2+1_000L)return Double.NaN;
        }

        double px;
        switch(fieldIndex){
            case 0:px=best.open;break;
            case 1:px=best.high;break;
            case 2:px=best.low;break;
            case 3:px=best.close;break;
            default:return Double.NaN;
        }
        return px>0 && Double.isFinite(px)?px:Double.NaN;
    }

    void showEditEntryDialog(String ps){
        int q=paper.qty(ps);
        if(q==0){
            Toast.makeText(this,"No open position to edit",Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20),dp(8),dp(20),0);

        TextView info=label("Position: "+(q>0?"LONG ":"SHORT ")
                +Math.abs(q)+" shares\nCurrent average entry: ₹"+fmt(paper.avg(ps)),14);
        EditText entry=new EditText(this);
        entry.setHint("New average entry price");
        entry.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        entry.setText(fmt(paper.avg(ps)));
        box.addView(info);
        box.addView(entry);

        AlertDialog dlg=new AlertDialog.Builder(this)
                .setTitle("EDIT ENTRY • "+ps)
                .setView(box)
                .setPositiveButton("SAVE",null)
                .setNegativeButton("CANCEL",null)
                .create();

        dlg.setOnShowListener(x->dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                double newAvg=Double.parseDouble(entry.getText().toString().trim());
                String err=paper.setAverageEntry(ps,newAvg);
                if(err!=null){
                    Toast.makeText(this,err,Toast.LENGTH_LONG).show();
                    return;
                }

                double sl=paper.stopLoss(ps);
                double tgt=paper.target(ps);
                boolean removed=false;
                if(q>0){
                    if(sl>0 && sl>=newAvg){sl=0;removed=true;}
                    if(tgt>0 && tgt<=newAvg){tgt=0;removed=true;}
                }else{
                    if(sl>0 && sl<=newAvg){sl=0;removed=true;}
                    if(tgt>0 && tgt>=newAvg){tgt=0;removed=true;}
                }
                paper.setRisk(ps,sl,tgt);
                refreshPaperOverlay();
                dlg.dismiss();

                String msg=ps+" average entry corrected to ₹"+fmt(newAvg)
                        +" • Quantity, cash and realized P&L unchanged";
                if(removed)msg+=" • Invalid SL/Target cleared";
                status.setText(msg);
                Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
            }catch(Exception e){
                Toast.makeText(this,"Enter a valid positive entry price",Toast.LENGTH_LONG).show();
            }
        }));
        dlg.show();
    }

    void paperTrade(String ps, boolean buy, EditText q, EditText slField,
                    EditText targetField, AlertDialog dlg, double px, String priceSource) {
        try {
            int orderQty = Math.max(1, Integer.parseInt(q.getText().toString().trim()));

            if (px <= 0 || Double.isNaN(px) || Double.isInfinite(px)) {
                Toast.makeText(this, "Invalid execution price", Toast.LENGTH_SHORT).show();
                return;
            }

            double sl = parseOptionalPrice(slField);
            double tgt = parseOptionalPrice(targetField);
            String err = paper.trade(ps, buy, orderQty, px);
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                status.setText("PAPER ORDER REJECTED • " + err);
                return;
            }

            paper.setRisk(ps, sl, tgt);

            refreshPaperOverlay(px);
            status.setText("PAPER " + (buy ? "BUY" : "SELL") + " " + ps
                    + " • " + orderQty + " @ ₹" + fmt(px)
                    + " • " + priceSource
                    + " • Pos " + paper.qty(ps) + " @ ₹" + fmt(paper.avg(ps))
                    + " • SL " + riskText(paper.stopLoss(ps))
                    + " • Target " + riskText(paper.target(ps))
                    + " • Cash ₹" + fmt(paper.balance()));
            dlg.dismiss();
        } catch (Exception e) {
            Toast.makeText(this, "Check Quantity / Stop Loss / Target values",
                    Toast.LENGTH_SHORT).show();
        }
    }

    double parseOptionalPrice(EditText e){
        String x=e.getText().toString().trim(); if(x.isEmpty())return 0;
        double v=Double.parseDouble(x); return v>0?v:0;
    }
    String riskText(double x){return x>0?"₹"+fmt(x):"OFF";}

    /** Broker-style independent SL/Target order management for the virtual position. */
    void showRiskOrderDialog(){
        final String ps=paperSymbol();
        if(ps.isEmpty()){Toast.makeText(this,"Search a symbol first",Toast.LENGTH_SHORT).show();return;}
        final int q=paper.qty(ps);
        if(q==0){Toast.makeText(this,"Open a virtual position first, then attach SL/Target",Toast.LENGTH_LONG).show();return;}
        final boolean replayMode = chart != null && chart.isReplayMode();
        final double markValue = replayMode ? chart.executionPrice() : lastLtpPrice;
        if (!replayMode && (markValue <= 0 || Double.isNaN(markValue) || Double.isInfinite(markValue))) {
            Toast.makeText(this, "GET PRICE दबाकर current live LTP लोड करें, फिर SL / Target सेट करें", Toast.LENGTH_LONG).show();
            return;
        }
        final double mark = markValue > 0 && !Double.isNaN(markValue) && !Double.isInfinite(markValue)
                ? markValue : (replayMode ? paper.avg(ps) : 0);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        TextView info=label(ps+" • "+(q>0?"LONG ":"SHORT ")+Math.abs(q)+" units\nAverage ₹"+fmt(paper.avg(ps))+" • Current ₹"+fmt(mark)+"\nAttach virtual exit orders independently of chart lines.",14);
        EditText sl=new EditText(this);sl.setHint("Stop-loss trigger price (optional)");sl.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText target=new EditText(this);target.setHint("Target price (optional)");target.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(paper.stopLoss(ps)>0)sl.setText(fmt(paper.stopLoss(ps)));
        if(paper.target(ps)>0)target.setText(fmt(paper.target(ps)));
        TextView note=label("OCO-style simulation: when SL or Target is hit, the virtual position closes and the other level is cleared. Triggers are checked when a price is refreshed or during candle replay; this is not a real broker order.",12);
        note.setTextColor(Color.LTGRAY);note.setPadding(0,dp(8),0,dp(8));
        box.addView(info);box.addView(sl);box.addView(target);box.addView(note);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("SL / TARGET ORDERS • "+ps).setView(box).setPositiveButton("SAVE / UPDATE",null).setNegativeButton("CANCEL",null).setNeutralButton("CANCEL BOTH",null).create();
        dlg.setOnShowListener(v->{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                try{
                    double stop=parseOptionalPrice(sl),tp=parseOptionalPrice(target);
                    double ref = mark;
                    if (ref <= 0 || Double.isNaN(ref) || Double.isInfinite(ref)) {
                        Toast.makeText(this, "Current price unavailable. Refresh price and try again.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    double entry = paper.avg(ps);
                    if (entry <= 0 || Double.isNaN(entry) || Double.isInfinite(entry)) {
                        Toast.makeText(this, "Invalid position entry price", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (q > 0) {
                        if (stop > 0 && stop >= entry) {
                            Toast.makeText(this, "LONG SL must be below average entry ₹" + fmt(entry), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (tp > 0 && tp <= entry) {
                            Toast.makeText(this, "LONG Target must be above average entry ₹" + fmt(entry), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (stop > 0 && ref <= stop) {
                            Toast.makeText(this, "SL already reached at current price", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (tp > 0 && ref >= tp) {
                            Toast.makeText(this, "Target already reached at current price", Toast.LENGTH_LONG).show();
                            return;
                        }
                    } else {
                        if (stop > 0 && stop <= entry) {
                            Toast.makeText(this, "SHORT SL must be above average entry ₹" + fmt(entry), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (tp > 0 && tp >= entry) {
                            Toast.makeText(this, "SHORT Target must be below average entry ₹" + fmt(entry), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (stop > 0 && ref >= stop) {
                            Toast.makeText(this, "SL already reached at current price", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (tp > 0 && ref <= tp) {
                            Toast.makeText(this, "Target already reached at current price", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    paper.setRisk(ps,stop,tp);refreshPaperOverlay();
                    status.setText(ps+" • Virtual SL "+riskText(stop)+" • Target "+riskText(tp)+" • Saved independently of chart");
                    Toast.makeText(this,"Virtual SL / Target updated",Toast.LENGTH_SHORT).show();dlg.dismiss();
                }catch(Exception e){Toast.makeText(this,"Enter valid trigger/target prices",Toast.LENGTH_SHORT).show();}
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(w->{paper.clearRisk(ps);refreshPaperOverlay();status.setText(ps+" • Virtual SL and Target cancelled");dlg.dismiss();});
        });dlg.show();
    }

    void showPaperMoneyDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(8),dp(20),0);
        TextView current=label("Available Paper Cash ₹"+fmt(paper.balance()),16);current.setTypeface(null,1);
        EditText amount=new EditText(this);amount.setHint("Amount e.g. 500000");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(current);box.addView(amount);
        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("PAPER MONEY").setView(box).setPositiveButton("ADD FUNDS",null).setNegativeButton("SET BALANCE",null).setNeutralButton("RESET ACCOUNT",null).create();
        dlg.setOnShowListener(x->{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{double a=Double.parseDouble(amount.getText().toString().trim());if(a<=0)throw new Exception();paper.addFunds(a);current.setText("Available Paper Cash ₹"+fmt(paper.balance()));status.setText("Paper money added ₹"+fmt(a)+" • Balance ₹"+fmt(paper.balance()));amount.setText("");}catch(Exception e){Toast.makeText(this,"Enter valid amount",Toast.LENGTH_SHORT).show();}
            });
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{
                try{double a=Double.parseDouble(amount.getText().toString().trim());if(a<0)throw new Exception();paper.setBalance(a);current.setText("Available Paper Cash ₹"+fmt(paper.balance()));status.setText("Paper cash set to ₹"+fmt(a));amount.setText("");}catch(Exception e){Toast.makeText(this,"Enter valid amount",Toast.LENGTH_SHORT).show();}
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{paper.resetTo(0);refreshPaperOverlay();current.setText("Available Paper Cash ₹0.00");status.setText("Paper account reset • Balance ₹0.00 • Position cleared");});
        });
        dlg.show();
    }

    void startReplay(){
        if(currentData==null||currentData.isEmpty()){Toast.makeText(this,"Load historical candles first",Toast.LENGTH_SHORT).show();return;}
        pauseReplay();
        int idx=chart.startReplayAtSelected();
        if(idx>=0){
            Candle c=chart.getCandle(idx);
            price.setText("REPLAY CLOSE ₹"+fmt(c.close));
            refreshPaperOverlay(c.close);
            status.setText("MANUAL BACKTEST / REPLAY • "+formatCandleTime(c.time)+" • C ₹"+fmt(c.close)+" • future candles hidden");
        }
    }

    void replayNext(){
        if(!chart.isReplayMode()){startReplay();return;}
        int idx=chart.stepReplay();
        Candle c=chart.getCandle(idx);
        if(c!=null){
            price.setText("REPLAY CLOSE ₹"+fmt(c.close));
            PaperTradingManager.RiskEvent ev=paper.checkCandle(paperSymbol(),c.high,c.low);
            refreshPaperOverlay(c.close);
            if(ev!=null){
                status.setText("REPLAY • PAPER "+ev.type+" HIT • "+paperSymbol()+" exit ₹"+fmt(ev.exitPrice)+" • Qty "+ev.closedQty+" • Realized ₹"+fmt(ev.realizedAfter)+" • "+formatCandleTime(c.time));
                pauseReplay();
            }else status.setText("REPLAY • "+formatCandleTime(c.time)+" • O "+fmt(c.open)+" H "+fmt(c.high)+" L "+fmt(c.low)+" C "+fmt(c.close));
        }
        if(chart.replayAtEnd()) pauseReplay();
    }

    final Runnable replayTick=new Runnable(){@Override public void run(){
        if(!replayPlaying)return;
        replayNext();
        if(replayPlaying&&!chart.replayAtEnd()) replayHandler.postDelayed(this,replayDelayMs);
    }};

    void toggleReplayPlay(){
        if(!chart.isReplayMode())startReplay();
        if(!chart.isReplayMode())return;
        if(replayPlaying){pauseReplay();return;}
        replayPlaying=true; replayPlayBtn.setText("⏸");
        replayHandler.removeCallbacks(replayTick); replayHandler.post(replayTick);
    }

    void pauseReplay(){
        replayPlaying=false; replayHandler.removeCallbacks(replayTick); if(replayPlayBtn!=null)replayPlayBtn.setText("▶");
    }

    void stopReplay(){
        pauseReplay();
        chart.stopReplay();
        if(lastLtpPrice>0&&!Double.isNaN(lastLtpPrice)&&!Double.isInfinite(lastLtpPrice))
            price.setText(symbol.getText()+"  LTP ₹"+fmt(lastLtpPrice));
        else price.setText("LTP: --");
        refreshPaperOverlay();
        status.setText("Replay reset • all historical candles visible");
    }

    void showReplaySpeed(Button b){
        final String[] names={"0.5x","1x","2x","5x","10x"};
        final int[] delays={1400,700,350,140,70};
        new AlertDialog.Builder(this).setTitle("Replay speed").setItems(names,(d,which)->{replayDelayMs=delays[which];b.setText(names[which]);if(replayPlaying){replayHandler.removeCallbacks(replayTick);replayHandler.postDelayed(replayTick,replayDelayMs);}}).show();
    }

    String formatCandleTime(long t){
        SimpleDateFormat f=new SimpleDateFormat(selectedTf.equals("1D")?"dd MMM yyyy":"dd MMM yyyy HH:mm:ss 'IST'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata")); return f.format(new Date(t));
    }

    void loadDhanMax(){
        if(!validate(false))return; final String tok=token.getText().toString().trim();
        final String apiInterval=(selectedTf.equals("1D")?"1D":((selectedMinutes==2||selectedMinutes==3||selectedMinutes==6||selectedMinutes==7||selectedMinutes==8||selectedMinutes==9||selectedMinutes==10)?"1":interval));
        status.setText("Resolving symbol & loading MAX history • "+selectedTf+" …");
        pauseReplay(); currentData.clear(); chart.setData(currentData);
        withResolvedSid((sid,seg,type)->{try{List<Candle>d=DhanRepository.historicalMax(tok,sid,seg,type,apiInterval,(done,total,count,range)->runOnUiThread(()->status.setText("Fetching "+symbol.getText()+" • "+done+"/"+total+" • candles "+count+" • "+range)));runOnUiThread(()->{if(apiInterval.equals("1"))base1m=d;List<Candle> display=d;if(!selectedTf.equals("1D")&&selectedMinutes>1&&apiInterval.equals("1"))display=TimeframeAggregator.aggregateMinutes(d,selectedMinutes);show(display,"DHAN MAX • "+symbol.getText()+" • "+selectedTf+(apiInterval.equals("1")&&selectedMinutes>1?" • from 1m":""));});}catch(Exception e){runOnUiThread(()->status.setText("History error: "+errorText(e)));}});
    }
}
