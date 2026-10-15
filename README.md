# Bookmap Institutional Levels Column Addon

[![Platform](https://img.shields.io/badge/Platform-Bookmap_7.4_/_7.8-blue.svg)](https://bookmap.com)
[![JDK](https://img.shields.io/badge/JDK-21_/_17-orange.svg)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

An institutional order flow and microstructure addon for **Bookmap (L1 Core API)**. Automatically ingests mathematical institutional price levels calculated by the **4-Way Institutional Level Confluence Engine** and paints them **strictly within the leftmost margin (under the `+` column button)**.

---

## 🎯 Key Design Features

1. **Zero Heatmap / Chart Clutter**:
   - The user requested that institutional levels must **never** draw horizontal grid lines or clutter the Bookmap heatmap canvas.
   - All badges are rendered strictly between horizontal pixels $X = 4\text{px}$ and $X = 116\text{px}$ using Bookmap's `ScreenSpaceCanvasType.HEATMAP` and `CompositeCoordinateBase.PIXEL_ZERO`.
   - The chart area, orderbook bubbles, and volume bars remain 100% clean.

2. **Full Timeframe Invariance (Price-Anchored)**:
   - Badges are pinned to the vertical price axis using `CompositeCoordinateBase.DATA_ZERO` with precise instrument pip conversion (`priceInPips = price / pips`).
   - Whether you zoom Bookmap into a **10-second scalping tape** or zoom out to a **4-hour / 1-day macro heatmap**, the badges stick to their exact price levels.
   - When scrolling up or down on the vertical price scale, the badges track the price dynamically.

3. **Autonomous 1.5s Bridge Poller**:
   - Addon spawns a lightweight background daemon thread that checks `C:\Users\<User>\.bookmap\institutional_levels_<COIN>.json`.
   - When the Python correlation engine updates the levels, Bookmap auto-refreshes the pills on screen within 1.5 seconds without needing a restart.

4. **Micro-Price Adaptive Pips Engine**:
   - Handles high-value assets ($BTC > $80,000) down to sub-cent meme trench assets ($GPS = $0.008049) with adaptive dynamic pip estimation and JSON tick-size fallback.

5. **Institutional High-Contrast Color Badging**:
   - 🔴 **Resistance (`R1`, `R2`, `R3`)**: Deep Crimson (`#2A0A0E`) fill with vibrant red outline (`#FF4444`).
   - 🟢 **Support (`S1`, `S2`, `S3`)**: Dark Forest Green (`#0A2A14`) fill with vibrant lime outline (`#00E676`).
   - 🔥 **Super-Confluence (`🔥 R1+Inflow`, `🔥 S1+Outflow`)**: Gold/Amber (`#341C04`) fill with electric gold outline (`#FFB300`) for levels within 0.25% of each other.
   - 🏦 **Whale Outflow Accumulation**: Dark Teal (`#042630`) fill with electric cyan outline (`#00E5FF`) representing defended cost-basis spikes ($>+2\sigma$).
   - 🚨 **Whale Inflow Distribution**: Dark Plum (`#2D0A26`) fill with electric magenta outline (`#FF4081`) representing heavy spot dumping resistance ($>+2\sigma$).

---

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────┐
│   4-Way Institutional Level Confluence Engine (Python)  │
│   • Volume-at-Price Nodes (High execution density)     │
│   • Futures ΔOI Delta (Fresh capital vs liquidation)   │
│   • Top 20% Trader vs Retail Long/Short Ratio Shifts   │
│   • Dynamic CEX Whale Transfer Z-Score Spikes (>+2σ)   │
└───────────────────────────┬────────────────────────────┘
                            │ Writes JSON every run
                            ▼
           C:\Users\<User>\.bookmap\
           ├── institutional_levels_BTC.json
           ├── institutional_levels_GPS.json
           ├── institutional_levels_MAGIC.json
           └── institutional_levels_latest.json
                            │ Polled every 1.5s
                            ▼
┌────────────────────────────────────────────────────────┐
│     Bookmap Institutional Levels Column Addon (Java)   │
│     • ScreenSpacePainter on HEATMAP canvas             │
│     • Horizontal: PIXEL_ZERO (Left Margin: X 4..116px) │
│     • Vertical: DATA_ZERO (Anchored to Price / Pips)   │
│     • Zero chart canvas lines                          │
└────────────────────────────────────────────────────────┘
```

---

## ⚡ Quick Start

### 1. Build the Addon JAR
Requires JDK 17 or JDK 21 and the Bookmap installation libraries in `C:\Program Files\Bookmap\lib`:
```powershell
.\gradlew.bat jar
```
The compiled jar will be created at:
`build\libs\institutional-levels-column.jar`

### 2. Load into Bookmap
1. Open Bookmap.
2. Go to **Settings** → **Configure add-ons**.
3. Click **Add**, select `build\libs\institutional-levels-column.jar` (or `C:\Users\gssjr\.bookmap\institutional-levels-column.jar`).
4. Check **Institutional Levels (Left Column)** to enable.
5. On the chart top toolbar, click the **Indicators** icon (`fx` icon or press `Ctrl + I`) and ensure **Institutional Levels Column** is checked.

### 3. Generate Levels for Any Coin
Run the Python engine for a single coin:
```powershell
python institutional_levels.py --coin DOGE --lookback 4h
```
Or auto-detect all open coins currently running in Bookmap tabs:
```powershell
python institutional_levels.py --all-open --lookback 4h
```

---

## 📄 License
MIT License. Built for crypto scalpers and quantitative order flow traders.
