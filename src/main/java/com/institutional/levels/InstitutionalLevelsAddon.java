package com.institutional.levels;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeCoordinateBase;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;

/**
 * Institutional Levels Left-Column Addon for Bookmap.
 *
 * Automatically monitors C:\Users\gssjr\.bookmap\institutional_levels_<COIN>.json
 * and renders high-confluence institutional levels (R1, R2, S1, S2, Whale Inflow, Outflow, Super-Confluence)
 * strictly in the leftmost margin (under the '+' column button), tracking the vertical price axis.
 *
 * ZERO lines or clutter are drawn over the heatmap canvas.
 */
@Layer1Attachable
@Layer1StrategyName("Institutional Levels (Left Column)")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class InstitutionalLevelsAddon implements
        Layer1ApiFinishable,
        Layer1ApiAdminAdapter,
        Layer1ApiDataAdapter,
        Layer1ApiInstrumentAdapter,
        ScreenSpacePainterFactory {

    private static final String INDICATOR_NAME = "Institutional Levels Column";
    private static final File BOOKMAP_DIR = new File(System.getProperty("user.home"), ".bookmap");

    private final Layer1ApiProvider provider;
    private final Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    private final Map<String, String> indicatorsUserNameToFullName = new HashMap<>();

    private final Map<String, InstrumentInfo> instrumentInfos = new ConcurrentHashMap<>();
    private final Map<String, InstitutionalPainter> painters = new ConcurrentHashMap<>();

    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "InstitutionalLevelsAddon-Poller");
        t.setDaemon(true);
        return t;
    });

    public InstitutionalLevelsAddon(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
        System.out.println("[InstitutionalLevels] Initialized addon with provider");

        // Pre-register indicator
        addIndicator();

        // Background poller checks for JSON file updates every 1.5 seconds
        pollScheduler.scheduleWithFixedDelay(this::checkAllPaintersForFileUpdates, 1500, 1500, TimeUnit.MILLISECONDS);
    }

    @Override
    public void finish() {
        pollScheduler.shutdownNow();

        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                        .builder(InstitutionalLevelsAddon.class, userName).setIsAdd(false).build();
                provider.sendUserMessage(message);
            }
        }

        for (InstitutionalPainter p : painters.values()) {
            p.dispose();
        }
        painters.clear();
        instrumentInfos.clear();
    }

    @Override
    public void onUserMessage(Object data) {
        if (data.getClass() == UserMessageLayersChainCreatedTargeted.class) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == getClass()) {
                addIndicator();
            }
        }
    }

    public synchronized void addIndicator() {
        Layer1ApiUserMessageModifyScreenSpacePainter message = Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(InstitutionalLevelsAddon.class, INDICATOR_NAME)
                .setIsAdd(true)
                .setScreenSpacePainterFactory(this)
                .build();

        synchronized (indicatorsFullNameToUserName) {
            indicatorsFullNameToUserName.put(message.fullName, message.userName);
            indicatorsUserNameToFullName.put(message.userName, message.fullName);
        }
        System.out.println("[InstitutionalLevels] Registered indicator: " + message.fullName);
        provider.sendUserMessage(message);
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String indicatorName, String indicatorAlias,
            ScreenSpaceCanvasFactory screenSpaceCanvasFactory) {

        System.out.println("[InstitutionalLevels] createScreenSpacePainter for " + indicatorAlias);
        ScreenSpaceCanvas heatmapCanvas = screenSpaceCanvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);
        InstitutionalPainter painter = new InstitutionalPainter(heatmapCanvas, indicatorAlias);
        painters.put(indicatorAlias, painter);
        painter.checkAndReload(true);
        return painter;
    }

    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
        System.out.println("[InstitutionalLevels] onInstrumentAdded: " + alias + " pips=" + (instrumentInfo != null ? instrumentInfo.pips : "null"));
        instrumentInfos.put(alias, instrumentInfo);
        InstitutionalPainter painter = painters.get(alias);
        if (painter != null) {
            painter.setInstrumentInfo(instrumentInfo);
            painter.update();
        }
        recordOpenInstrument(alias);
    }

    private void recordOpenInstrument(String alias) {
        try {
            if (!BOOKMAP_DIR.exists()) BOOKMAP_DIR.mkdirs();
            File f = new File(BOOKMAP_DIR, "open_instruments.txt");
            List<String> lines = f.exists() ? Files.readAllLines(f.toPath()) : new ArrayList<>();
            if (!lines.contains(alias)) {
                lines.add(alias);
                Files.write(f.toPath(), lines);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInstrumentRemoved(String alias) {
        instrumentInfos.remove(alias);
        InstitutionalPainter painter = painters.remove(alias);
        if (painter != null) {
            painter.dispose();
        }
    }

    private void checkAllPaintersForFileUpdates() {
        try {
            for (InstitutionalPainter painter : painters.values()) {
                painter.checkAndReload(false);
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // LEVEL DATA MODEL
    // =========================================================================

    public static class LevelPill {
        public final String label;
        public final double price;
        public final Color bgColor;
        public final Color borderColor;
        public final Color textColor;
        public final int priority;

        public LevelPill(String label, double price, Color bgColor, Color borderColor, Color textColor, int priority) {
            this.label = label;
            this.price = price;
            this.bgColor = bgColor;
            this.borderColor = borderColor;
            this.textColor = textColor;
            this.priority = priority;
        }

        public LevelPill(String label, double price, Color bgColor, Color borderColor, Color textColor) {
            this(label, price, bgColor, borderColor, textColor, 50);
        }
    }

    // =========================================================================
    // SCREEN SPACE PAINTER (LEFT COLUMN ONLY)
    // =========================================================================

    public class InstitutionalPainter implements ScreenSpacePainterAdapter {

        private static final int PILL_WIDTH = 150;
        private static final int PILL_HEIGHT = 19;
        private static final int LEFT_MARGIN_X = 4;
        private static final int MAX_VISIBLE_PILLS = 8;

        // Badge Color Schemes & Hierarchy
        // Super-Confluence: Obsidian Gold background #341C04, Electric Amber border #FFB300, Yellow text #FFF59D
        private static final Color COLOR_CONF_BG = new Color(0x34, 0x1C, 0x04, 245);
        private static final Color COLOR_CONF_BORDER = new Color(0xFF, 0xB3, 0x00);
        private static final Color COLOR_CONF_TEXT = new Color(0xFF, 0xF5, 0x9D);

        // CEX Outflow / Accumulation: Dark Teal #042630, Electric Cyan #00E5FF, White text
        private static final Color COLOR_CEX_OUT_BG = new Color(0x04, 0x26, 0x30, 245);
        private static final Color COLOR_CEX_OUT_BORDER = new Color(0x00, 0xE5, 0xFF);
        private static final Color COLOR_CEX_OUT_TEXT = Color.WHITE;

        // CEX Inflow / Distribution: Deep Plum #2D0A26, Pink/Magenta #FF4081, White text
        private static final Color COLOR_CEX_IN_BG = new Color(0x2D, 0x0A, 0x26, 245);
        private static final Color COLOR_CEX_IN_BORDER = new Color(0xFF, 0x40, 0x81);
        private static final Color COLOR_CEX_IN_TEXT = Color.WHITE;

        // Whale Buy Tape: Deep Forest #0A2A14, Lime #00E676, White text
        private static final Color COLOR_WHL_BUY_BG = new Color(0x0A, 0x2A, 0x14, 245);
        private static final Color COLOR_WHL_BUY_BORDER = new Color(0x00, 0xE6, 0x76);
        private static final Color COLOR_WHL_BUY_TEXT = Color.WHITE;

        // Whale Sell Tape: Deep Crimson #2A0A0E, Red #FF4444, White text
        private static final Color COLOR_WHL_SELL_BG = new Color(0x2A, 0x0A, 0x0E, 245);
        private static final Color COLOR_WHL_SELL_BORDER = new Color(0xFF, 0x44, 0x44);
        private static final Color COLOR_WHL_SELL_TEXT = Color.WHITE;

        // Macro OI: Deep Navy #0D1B2A, Electric Blue #00B4D8, White text
        private static final Color COLOR_OI_MACRO_BG = new Color(0x0D, 0x1B, 0x2A, 245);
        private static final Color COLOR_OI_MACRO_BORDER = new Color(0x00, 0xB4, 0xD8);
        private static final Color COLOR_OI_MACRO_TEXT = Color.WHITE;

        // Scalp OI: Charcoal Slate #1E2229, Orange #FF9E00, Peach text #FFD180
        private static final Color COLOR_OI_SCALP_BG = new Color(0x1E, 0x22, 0x29, 245);
        private static final Color COLOR_OI_SCALP_BORDER = new Color(0xFF, 0x9E, 0x00);
        private static final Color COLOR_OI_SCALP_TEXT = new Color(0xFF, 0xD1, 0x80);

        // Support / Resistance: Standard emerald & crimson
        private static final Color COLOR_SUP_BG = new Color(0x0A, 0x2A, 0x14, 240);
        private static final Color COLOR_SUP_BORDER = new Color(0x00, 0xE6, 0x76);
        private static final Color COLOR_SUP_TEXT = Color.WHITE;

        private static final Color COLOR_RES_BG = new Color(0x2A, 0x0A, 0x0E, 240);
        private static final Color COLOR_RES_BORDER = new Color(0xFF, 0x44, 0x44);
        private static final Color COLOR_RES_TEXT = Color.WHITE;

        private final ScreenSpaceCanvas canvas;
        private final String alias;
        private final String coinSymbol;

        private InstrumentInfo instrumentInfo;
        private long lastFileModified = 0L;
        private final List<CanvasIcon> activeIcons = Collections.synchronizedList(new ArrayList<>());
        private final List<LevelPill> currentPills = new ArrayList<>();
        private double jsonPips = 0.0;

        public InstitutionalPainter(ScreenSpaceCanvas canvas, String alias) {
            this.canvas = canvas;
            this.alias = alias;
            this.coinSymbol = cleanSymbol(alias);
            this.instrumentInfo = instrumentInfos.get(alias);
        }

        public void setInstrumentInfo(InstrumentInfo info) {
            this.instrumentInfo = info;
        }

        public synchronized void checkAndReload(boolean force) {
            File file = resolveJsonFile(coinSymbol);
            if (file == null || !file.exists()) {
                return;
            }

            long modified = file.lastModified();
            if (force || modified > lastFileModified) {
                lastFileModified = modified;
                loadJsonData(file);
                update();
            }
        }

        private File resolveJsonFile(String coin) {
            if (!BOOKMAP_DIR.exists()) return null;

            File coinFile = new File(BOOKMAP_DIR, "institutional_levels_" + coin + ".json");
            if (coinFile.exists()) return coinFile;

            File latestFile = new File(BOOKMAP_DIR, "institutional_levels_latest.json");
            if (latestFile.exists()) return latestFile;

            return null;
        }

        private void loadJsonData(File file) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
                Map<String, Object> root = SimpleJson.parseObject(content);
                if (root == null) return;

                if (root.containsKey("pips")) {
                    jsonPips = SimpleJson.asDouble(root.get("pips"));
                } else if (root.containsKey("tick_size")) {
                    jsonPips = SimpleJson.asDouble(root.get("tick_size"));
                }

                double currentPrice = SimpleJson.asDouble(root.get("current_price"));

                List<LevelPill> pills = new ArrayList<>();

                // 1. Top Resistances (RES 1, RES 2)
                List<Object> resList = SimpleJson.asList(root.get("top_resistances"));
                int rIdx = 1;
                for (Object o : resList) {
                    if (rIdx > 2) break; // Enforce top 2
                    Map<String, Object> m = SimpleJson.asMap(o);
                    if (m != null && hasPrice(m)) {
                        double p = getDoubleField(m, "price", "px");
                        String label = "RES " + rIdx + ": " + formatPrice(p);
                        pills.add(new LevelPill(
                                label, p,
                                COLOR_RES_BG,
                                COLOR_RES_BORDER,
                                COLOR_RES_TEXT,
                                rIdx == 1 ? 95 : 45
                        ));
                        rIdx++;
                    }
                }

                // 2. Top Supports (SUP 1, SUP 2)
                List<Object> supList = SimpleJson.asList(root.get("top_supports"));
                int sIdx = 1;
                for (Object o : supList) {
                    if (sIdx > 2) break; // Enforce top 2
                    Map<String, Object> m = SimpleJson.asMap(o);
                    if (m != null && hasPrice(m)) {
                        double p = getDoubleField(m, "price", "px");
                        String label = "SUP " + sIdx + ": " + formatPrice(p);
                        pills.add(new LevelPill(
                                label, p,
                                COLOR_SUP_BG,
                                COLOR_SUP_BORDER,
                                COLOR_SUP_TEXT,
                                sIdx == 1 ? 95 : 45
                        ));
                        sIdx++;
                    }
                }

                // 3. CEX Flows (cex_inflows, cex_outflows) -> CEX IN: <px> ($<usd>) and CEX OUT: <px> ($<usd>)
                Map<String, Object> cf = SimpleJson.asMap(root.get("cex_flows"));
                if (cf == null) {
                    cf = SimpleJson.asMap(root.get("whale_flows"));
                }
                if (cf != null) {
                    // Outflows / Accumulation
                    List<Object> outflows = getListField(cf, "cex_outflows", "outflows", "outflows_accumulation");
                    for (Object o : outflows) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double usd = getUsdField(m, p);
                            String label = "CEX OUT: " + formatPrice(p) + " ($" + formatUsd(usd) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_CEX_OUT_BG,
                                    COLOR_CEX_OUT_BORDER,
                                    COLOR_CEX_OUT_TEXT,
                                    80
                            ));
                        }
                    }

                    // Inflows / Distribution
                    List<Object> inflows = getListField(cf, "cex_inflows", "inflows", "inflows_distribution");
                    for (Object o : inflows) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double usd = getUsdField(m, p);
                            String label = "CEX IN: " + formatPrice(p) + " ($" + formatUsd(usd) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_CEX_IN_BG,
                                    COLOR_CEX_IN_BORDER,
                                    COLOR_CEX_IN_TEXT,
                                    80
                            ));
                        }
                    }
                }

                // 4. Whale Trades (whale_buys, whale_sells) -> WHL BUY: <px> ($<usd>) and WHL SELL: <px> ($<usd>)
                Map<String, Object> wt = SimpleJson.asMap(root.get("whale_trades"));
                if (wt == null) {
                    wt = SimpleJson.asMap(root.get("trades"));
                }
                if (wt != null) {
                    // Whale Buys
                    List<Object> buys = getListField(wt, "whale_buys", "buys");
                    for (Object o : buys) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double usd = getUsdField(m, p);
                            String label = "WHL BUY: " + formatPrice(p) + " ($" + formatUsd(usd) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_WHL_BUY_BG,
                                    COLOR_WHL_BUY_BORDER,
                                    COLOR_WHL_BUY_TEXT,
                                    70
                            ));
                        }
                    }

                    // Whale Sells
                    List<Object> sells = getListField(wt, "whale_sells", "sells");
                    for (Object o : sells) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double usd = getUsdField(m, p);
                            String label = "WHL SELL: " + formatPrice(p) + " ($" + formatUsd(usd) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_WHL_SELL_BG,
                                    COLOR_WHL_SELL_BORDER,
                                    COLOR_WHL_SELL_TEXT,
                                    70
                            ));
                        }
                    }
                }

                // 5. Macro OI (macro_longs, macro_shorts) -> OI-M: <px> (+<pct>)
                Map<String, Object> moi = SimpleJson.asMap(root.get("macro_oi"));
                if (moi != null) {
                    List<Object> mLongs = getListField(moi, "macro_longs", "longs");
                    for (Object o : mLongs) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double pct = getPctField(m);
                            String label = "OI-M: " + formatPrice(p) + " (" + formatPct(pct) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_OI_MACRO_BG,
                                    COLOR_OI_MACRO_BORDER,
                                    COLOR_OI_MACRO_TEXT,
                                    60
                            ));
                        }
                    }

                    List<Object> mShorts = getListField(moi, "macro_shorts", "shorts");
                    for (Object o : mShorts) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double pct = getPctField(m);
                            String label = "OI-M: " + formatPrice(p) + " (" + formatPct(pct) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_OI_MACRO_BG,
                                    COLOR_OI_MACRO_BORDER,
                                    COLOR_OI_MACRO_TEXT,
                                    60
                            ));
                        }
                    }
                }

                // 6. Scalp OI (scalp_longs, scalp_shorts) -> OI-S: <px> (+<pct>)
                Map<String, Object> soi = SimpleJson.asMap(root.get("scalp_oi"));
                if (soi != null) {
                    List<Object> sLongs = getListField(soi, "scalp_longs", "longs");
                    for (Object o : sLongs) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double pct = getPctField(m);
                            String label = "OI-S: " + formatPrice(p) + " (" + formatPct(pct) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_OI_SCALP_BG,
                                    COLOR_OI_SCALP_BORDER,
                                    COLOR_OI_SCALP_TEXT,
                                    50
                            ));
                        }
                    }

                    List<Object> sShorts = getListField(soi, "scalp_shorts", "shorts");
                    for (Object o : sShorts) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && hasPrice(m)) {
                            double p = getDoubleField(m, "price", "px");
                            double pct = getPctField(m);
                            String label = "OI-S: " + formatPrice(p) + " (" + formatPct(pct) + ")";
                            pills.add(new LevelPill(
                                    label, p,
                                    COLOR_OI_SCALP_BG,
                                    COLOR_OI_SCALP_BORDER,
                                    COLOR_OI_SCALP_TEXT,
                                    50
                            ));
                        }
                    }
                }

                // 7. Direct / Pre-computed Confluences
                List<Object> confs = getListField(root, "confluences", "super_confluences");
                for (Object o : confs) {
                    Map<String, Object> m = SimpleJson.asMap(o);
                    if (m != null && hasPrice(m)) {
                        double p = getDoubleField(m, "price", "px");
                        String tag = SimpleJson.asString(m.get("tag"));
                        if (tag.isEmpty()) tag = SimpleJson.asString(m.get("label"));
                        String label = tag.startsWith("CONF:") ? tag : ("CONF: " + (tag.isEmpty() ? "SUPER" : tag) + " " + formatPrice(p));
                        pills.add(new LevelPill(
                                label, p,
                                COLOR_CONF_BG,
                                COLOR_CONF_BORDER,
                                COLOR_CONF_TEXT,
                                100
                        ));
                    }
                }

                if (currentPrice <= 0.0 && !pills.isEmpty()) {
                    currentPrice = pills.get(0).price;
                }

                // Consolidate proximate levels (< 0.20% diff) and enforce max 8 visible badges total
                List<LevelPill> consolidated = consolidateLevels(pills, currentPrice);

                synchronized (this) {
                    currentPills.clear();
                    currentPills.addAll(consolidated);
                }

            } catch (Throwable t) {
                // Ignore parse errors on active write
            }
        }

        private boolean hasPrice(Map<String, Object> m) {
            return m != null && (m.containsKey("price") || m.containsKey("px") || m.containsKey("level"));
        }

        private double getDoubleField(Map<String, Object> m, String... keys) {
            if (m == null) return 0.0;
            for (String k : keys) {
                if (m.containsKey(k)) {
                    return SimpleJson.asDouble(m.get(k));
                }
            }
            return 0.0;
        }

        private double getUsdField(Map<String, Object> m, double fallbackPx) {
            if (m == null) return 0.0;
            double usd = getDoubleField(m, "usd", "volume_usd", "vol_usd", "amount_usd", "vol", "notional");
            if (usd == 0.0 && m.containsKey("volume") && fallbackPx > 0) {
                usd = SimpleJson.asDouble(m.get("volume")) * fallbackPx;
            }
            return usd;
        }

        private double getPctField(Map<String, Object> m) {
            if (m == null) return 0.0;
            return getDoubleField(m, "pct", "delta_pct", "oi_pct", "change_pct", "oi_delta_pct", "delta_oi_pct", "shift");
        }

        private List<Object> getListField(Map<String, Object> m, String... keys) {
            if (m == null) return Collections.emptyList();
            for (String k : keys) {
                if (m.containsKey(k)) {
                    List<Object> list = SimpleJson.asList(m.get(k));
                    if (list != null && !list.isEmpty()) return list;
                }
            }
            return Collections.emptyList();
        }

        private List<LevelPill> consolidateLevels(List<LevelPill> input, double currPrice) {
            if (input == null || input.isEmpty()) return Collections.emptyList();

            // Sort by price ascending
            List<LevelPill> sorted = new ArrayList<>(input);
            sorted.sort((a, b) -> Double.compare(a.price, b.price));

            List<LevelPill> mergedList = new ArrayList<>();
            int n = sorted.size();
            boolean[] used = new boolean[n];

            for (int i = 0; i < n; i++) {
                if (used[i]) continue;

                LevelPill base = sorted.get(i);
                List<LevelPill> cluster = new ArrayList<>();
                cluster.add(base);

                for (int j = i + 1; j < n; j++) {
                    if (used[j]) continue;
                    LevelPill next = sorted.get(j);

                    double clusterAvg = getAveragePrice(cluster);
                    double pctDiff = Math.abs(next.price - clusterAvg) / Math.max(1e-9, clusterAvg);

                    if (pctDiff <= 0.0020) { // within 0.20%
                        cluster.add(next);
                        used[j] = true;
                    } else {
                        break;
                    }
                }

                if (cluster.size() > 1) {
                    double avgPrice = getAveragePrice(cluster);
                    List<String> tags = new ArrayList<>();
                    for (LevelPill p : cluster) {
                        String t = extractTag(p.label);
                        if (t != null && !t.isEmpty()) {
                            for (String part : t.split("\\+")) {
                                part = part.trim();
                                if (!part.isEmpty() && !tags.contains(part)) {
                                    tags.add(part);
                                }
                            }
                        }
                    }
                    String combinedTags = String.join("+", tags);
                    String comboLabel = "CONF: " + combinedTags + " " + formatPrice(avgPrice);

                    mergedList.add(new LevelPill(
                            comboLabel,
                            avgPrice,
                            COLOR_CONF_BG,
                            COLOR_CONF_BORDER,
                            COLOR_CONF_TEXT,
                            100
                    ));
                } else {
                    mergedList.add(base);
                }
            }

            // Enforce max 8 visible badges total
            if (mergedList.size() > MAX_VISIBLE_PILLS) {
                mergedList.sort((a, b) -> {
                    int cmp = Integer.compare(b.priority, a.priority);
                    if (cmp != 0) return cmp;
                    double distA = Math.abs(a.price - currPrice);
                    double distB = Math.abs(b.price - currPrice);
                    return Double.compare(distA, distB);
                });
                mergedList = new ArrayList<>(mergedList.subList(0, MAX_VISIBLE_PILLS));
                mergedList.sort((a, b) -> Double.compare(a.price, b.price));
            }

            return mergedList;
        }

        private static double getAveragePrice(List<LevelPill> cluster) {
            double sum = 0.0;
            for (LevelPill p : cluster) sum += p.price;
            return sum / cluster.size();
        }

        private String extractTag(String label) {
            if (label == null || label.isEmpty()) return "";
            if (label.startsWith("CONF: ")) {
                int spaceIdx = label.lastIndexOf(' ');
                return spaceIdx > 6 ? label.substring(6, spaceIdx).trim() : label.substring(6).trim();
            }
            if (label.startsWith("RES ")) {
                int colon = label.indexOf(':');
                return colon > 4 ? "R" + label.substring(4, colon).trim() : "RES";
            }
            if (label.startsWith("SUP ")) {
                int colon = label.indexOf(':');
                return colon > 4 ? "S" + label.substring(4, colon).trim() : "SUP";
            }
            if (label.startsWith("CEX IN:")) return "CEX-IN";
            if (label.startsWith("CEX OUT:")) return "CEX-OUT";
            if (label.startsWith("WHL BUY:")) return "W-BUY";
            if (label.startsWith("WHL SELL:")) return "W-SELL";
            if (label.startsWith("OI-M:")) return "OI-M";
            if (label.startsWith("OI-S:")) return "OI-S";
            if (label.startsWith("OUTFLOW")) return "CEX-OUT";
            if (label.startsWith("INFLOW")) return "CEX-IN";
            int colon = label.indexOf(':');
            if (colon > 0) return label.substring(0, colon).trim();
            int space = label.indexOf(' ');
            return space > 0 ? label.substring(0, space).trim() : label;
        }

        @Override
        public void onMoveEnd() {
            update();
        }

        public synchronized void update() {
            if (canvas == null) return;

            // Clear old icons
            synchronized (activeIcons) {
                for (CanvasIcon icon : activeIcons) {
                    canvas.removeShape(icon);
                }
                activeIcons.clear();

                if (currentPills.isEmpty()) return;

                double defaultPips = (instrumentInfo != null && instrumentInfo.pips > 0) ? instrumentInfo.pips : 0.0;

                System.out.println("[InstitutionalLevels] update for " + alias + " pills=" + currentPills.size() + " defaultPips=" + defaultPips + " jsonPips=" + jsonPips);

                // Paint new pills strictly in the left margin [LEFT_MARGIN_X .. LEFT_MARGIN_X + PILL_WIDTH]
                for (LevelPill pill : currentPills) {
                    double pips = defaultPips;
                    if (pips <= 0) {
                        pips = (jsonPips > 0) ? jsonPips : estimatePips(pill.price);
                    }

                    double priceInPips = pill.price / pips;

                    CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, LEFT_MARGIN_X, 0);
                    CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, LEFT_MARGIN_X + PILL_WIDTH, 0);

                    CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, -PILL_HEIGHT / 2, priceInPips);
                    CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, PILL_HEIGHT - PILL_HEIGHT / 2, priceInPips);

                    PreparedImage img = generatePillImage(PILL_WIDTH, PILL_HEIGHT, pill);
                    CanvasIcon icon = new CanvasIcon(img, x1, y1, x2, y2);
                    canvas.addShape(icon);
                    activeIcons.add(icon);
                }
            }
        }

        private double estimatePips(double price) {
            if (price >= 1000.0) return 0.1;
            if (price >= 100.0) return 0.01;
            if (price >= 1.0) return 0.001;
            if (price >= 0.1) return 0.0001;
            if (price >= 0.01) return 0.00001;
            if (price >= 0.001) return 0.000001;
            return 0.0000001;
        }

        private PreparedImage generatePillImage(int w, int h, LevelPill pill) {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Fill rounded capsule background
            g.setColor(pill.bgColor);
            g.fillRoundRect(0, 0, w - 1, h - 1, 6, 6);

            // Border stroke
            g.setColor(pill.borderColor);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);

            // Text Label
            g.setColor(pill.textColor);
            Font font = new Font("Consolas", Font.BOLD, 10);
            g.setFont(font);
            java.awt.FontMetrics fm = g.getFontMetrics();

            // Auto-adjust font size if text is wide so it fits nicely within 150px
            if (fm.stringWidth(pill.label) > w - 10) {
                font = new Font("Consolas", Font.BOLD, 9);
                g.setFont(font);
                fm = g.getFontMetrics();
            }
            if (fm.stringWidth(pill.label) > w - 8) {
                font = new Font("Consolas", Font.PLAIN, 9);
                g.setFont(font);
                fm = g.getFontMetrics();
            }

            // Mathematically centered text
            int textY = ((h - fm.getHeight()) / 2) + fm.getAscent();
            g.drawString(pill.label, 6, textY);

            g.dispose();
            return new PreparedImage(image);
        }

        private String formatPrice(double price) {
            if (price >= 1000.0) return String.format("%.1f", price);
            if (price >= 1.0) return String.format("%.2f", price);
            if (price >= 0.01) return String.format("%.4f", price);
            if (price >= 0.0001) return String.format("%.6f", price);
            return String.format("%.7f", price);
        }

        private String formatUsd(double usd) {
            double abs = Math.abs(usd);
            if (abs >= 1_000_000_000.0) {
                return String.format("%.1fB", usd / 1_000_000_000.0);
            } else if (abs >= 1_000_000.0) {
                return String.format("%.1fM", usd / 1_000_000.0);
            } else if (abs >= 1_000.0) {
                if (abs < 10_000.0) {
                    return String.format("%.1fK", usd / 1_000.0);
                }
                return String.format("%.0fK", usd / 1_000.0);
            } else if (abs > 0.0) {
                return String.format("%.0f", usd);
            }
            return "0";
        }

        private String formatPct(double pct) {
            if (Math.abs(pct) <= 1.0 && pct != 0.0) {
                pct = pct * 100.0;
            }
            if (pct >= 0.0) {
                return String.format("+%.1f%%", pct);
            } else {
                return String.format("%.1f%%", pct);
            }
        }

        @Override
        public void dispose() {
            synchronized (activeIcons) {
                for (CanvasIcon icon : activeIcons) {
                    canvas.removeShape(icon);
                }
                activeIcons.clear();
            }
            canvas.dispose();
        }
    }

    private static String cleanSymbol(String alias) {
        if (alias == null) return "UNKNOWN";
        String s = alias.trim().toUpperCase();
        int atIdx = s.indexOf('@');
        if (atIdx >= 0) {
            s = s.substring(0, atIdx);
        }
        if (s.endsWith("USDT")) {
            s = s.substring(0, s.length() - 4);
        } else if (s.endsWith("USD")) {
            s = s.substring(0, s.length() - 3);
        }
        return s;
    }

    // =========================================================================
    // LIGHTWEIGHT ZERO-DEPENDENCY JSON PARSER
    // =========================================================================

    public static class SimpleJson {

        @SuppressWarnings("unchecked")
        public static Map<String, Object> asMap(Object obj) {
            return (obj instanceof Map) ? (Map<String, Object>) obj : null;
        }

        @SuppressWarnings("unchecked")
        public static List<Object> asList(Object obj) {
            return (obj instanceof List) ? (List<Object>) obj : Collections.emptyList();
        }

        public static double asDouble(Object obj) {
            if (obj instanceof Number) return ((Number) obj).doubleValue();
            if (obj instanceof String) {
                try { return Double.parseDouble((String) obj); } catch (Exception ignored) {}
            }
            return 0.0;
        }

        public static String asString(Object obj) {
            return obj != null ? obj.toString().trim() : "";
        }

        public static Map<String, Object> parseObject(String json) {
            if (json == null) return null;
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) return null;
            return (Map<String, Object>) new Parser(json).parseValue();
        }

        private static class Parser {
            private final String src;
            private int pos = 0;

            Parser(String src) {
                this.src = src;
            }

            private void skipWhitespace() {
                while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                    pos++;
                }
            }

            Object parseValue() {
                skipWhitespace();
                if (pos >= src.length()) return null;
                char c = src.charAt(pos);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                return parseNumber();
            }

            Map<String, Object> parseObject() {
                Map<String, Object> map = new HashMap<>();
                pos++; // skip '{'
                while (pos < src.length()) {
                    skipWhitespace();
                    if (pos >= src.length()) break;
                    if (src.charAt(pos) == '}') {
                        pos++;
                        return map;
                    }
                    if (src.charAt(pos) == ',') {
                        pos++;
                        continue;
                    }
                    String key = parseString();
                    skipWhitespace();
                    if (pos < src.length() && src.charAt(pos) == ':') {
                        pos++;
                    }
                    Object val = parseValue();
                    map.put(key, val);
                }
                return map;
            }

            List<Object> parseArray() {
                List<Object> list = new ArrayList<>();
                pos++; // skip '['
                while (pos < src.length()) {
                    skipWhitespace();
                    if (pos >= src.length()) break;
                    if (src.charAt(pos) == ']') {
                        pos++;
                        return list;
                    }
                    if (src.charAt(pos) == ',') {
                        pos++;
                        continue;
                    }
                    list.add(parseValue());
                }
                return list;
            }

            String parseString() {
                if (pos >= src.length() || src.charAt(pos) != '"') return "";
                pos++; // skip opening '"'
                StringBuilder sb = new StringBuilder();
                while (pos < src.length()) {
                    char c = src.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\' && pos < src.length()) {
                        char esc = src.charAt(pos++);
                        if (esc == 'n') sb.append('\n');
                        else if (esc == 'r') sb.append('\r');
                        else if (esc == 't') sb.append('\t');
                        else if (esc == 'u' && pos + 4 <= src.length()) {
                            try {
                                sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                                pos += 4;
                            } catch (Exception ignored) {
                                sb.append(esc);
                            }
                        } else {
                            sb.append(esc);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            Boolean parseBoolean() {
                if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
                if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
                return Boolean.FALSE;
            }

            Object parseNull() {
                if (src.startsWith("null", pos)) { pos += 4; }
                return null;
            }

            Number parseNumber() {
                int start = pos;
                if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                    pos++;
                }
                while (pos < src.length()) {
                    char c = src.charAt(pos);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                        pos++;
                    } else {
                        break;
                    }
                }
                String numStr = src.substring(start, pos);
                try {
                    if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                        return Double.parseDouble(numStr);
                    }
                    return Long.parseLong(numStr);
                } catch (Exception e) {
                    return 0.0;
                }
            }
        }
    }
}
