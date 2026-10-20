# SBN Virtual Trading v1.29.0

NSE-focused virtual trading app. No scanner module is included.

## v1.29 changes
- Keeps the existing NSE cash symbol search and NIFTY50/BANKNIFTY index support.
- Keeps chart intervals and persists the selected interval between launches.
- Selecting an interval closes the symbol suggestion dropdown; symbol suggestions are suppressed after an exact selection.
- Adds an Account/Portfolio dialog showing virtual cash, total realized P&L, open position count, and tracked symbols with position quantity, average price, and realized P&L. For the currently displayed symbol, unrealized P&L is shown when a usable chart/price is loaded.
- Retains chart-based stop-loss/target, paper money controls, GO TO, historical chart loading, and candle replay.

## Identity
- applicationId: `com.sbn.virtualtrading`
- versionName: `1.29.0`
- versionCode: `29`

## Build
Use the matching `SBNVirtualTrading_v1_28_NSE_VIRTUAL_PORTFOLIO_Colab.ipynb` and upload the matching source ZIP. The notebook uses the persistent Drive-backed signing key path from earlier builds.

This is a virtual-trading simulation. Market data depends on the configured provider and account/API permissions.


## v1.29: Broker-style virtual SL/Target orders
- Added a dedicated SL / TARGET ORDERS screen, independent of chart placement.
- Set/update stop-loss trigger and target price for an open virtual position; cancel both without touching the chart.
- Virtual OCO behavior closes the position when a level is detected by price refresh or historical candle replay. No real broker orders are sent.
