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

        public LevelPill(String label, double price, Color bgColor, Color borderColor, Color textColor) {
            this.label = label;
            this.price = price;
            this.bgColor = bgColor;
            this.borderColor = borderColor;
            this.textColor = textColor;
        }
    }

    // =========================================================================
    // SCREEN SPACE PAINTER (LEFT COLUMN ONLY)
    // =========================================================================

    public class InstitutionalPainter implements ScreenSpacePainterAdapter {

        private static final int PILL_WIDTH = 150;
        private static final int PILL_HEIGHT = 19;
        private static final int LEFT_MARGIN_X = 4;

        private final ScreenSpaceCanvas canvas;
        private final String alias;
        private final String coinSymbol;

        private InstrumentInfo instrumentInfo;
        private long lastFileModified = 0L;
        private final List<CanvasIcon> activeIcons = Collections.synchronizedList(new ArrayList<>());
        private final List<LevelPill> currentPills = new ArrayList<>();

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

                List<LevelPill> pills = new ArrayList<>();

                // Parse resistances (Crimson)
                List<Object> resList = SimpleJson.asList(root.get("top_resistances"));
                int rIdx = 1;
                for (Object o : resList) {
                    Map<String, Object> m = SimpleJson.asMap(o);
                    if (m != null && m.containsKey("price")) {
                        double p = SimpleJson.asDouble(m.get("price"));
                        String label = "RES " + rIdx + ": " + formatPrice(p);
                        pills.add(new LevelPill(
                                label, p,
                                new Color(42, 10, 14, 240),
                                new Color(255, 68, 68),
                                Color.WHITE
                        ));
                        rIdx++;
                    }
                }

                // Parse supports (Emerald)
                List<Object> supList = SimpleJson.asList(root.get("top_supports"));
                int sIdx = 1;
                for (Object o : supList) {
                    Map<String, Object> m = SimpleJson.asMap(o);
                    if (m != null && m.containsKey("price")) {
                        double p = SimpleJson.asDouble(m.get("price"));
                        String label = "SUP " + sIdx + ": " + formatPrice(p);
                        pills.add(new LevelPill(
                                label, p,
                                new Color(10, 42, 20, 240),
                                new Color(0, 230, 118),
                                Color.WHITE
                        ));
                        sIdx++;
                    }
                }

                // Parse whale flows
                Map<String, Object> wf = SimpleJson.asMap(root.get("whale_flows"));
                if (wf != null) {
                    // Outflows Accumulation (Cyan)
                    List<Object> outflows = SimpleJson.asList(wf.get("outflows_accumulation"));
                    for (Object o : outflows) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && m.containsKey("price")) {
                            double p = SimpleJson.asDouble(m.get("price"));
                            double z = SimpleJson.asDouble(m.get("z_score"));
                            String label = "OUTFLOW: " + formatPrice(p) + " (+" + String.format("%.1f", z) + "s)";
                            pills.add(new LevelPill(
                                    label, p,
                                    new Color(4, 38, 48, 245),
                                    new Color(0, 229, 255),
                                    new Color(224, 247, 250)
                            ));
                        }
                    }

                    // Inflows Distribution (Magenta)
                    List<Object> inflows = SimpleJson.asList(wf.get("inflows_distribution"));
                    for (Object o : inflows) {
                        Map<String, Object> m = SimpleJson.asMap(o);
                        if (m != null && m.containsKey("price")) {
                            double p = SimpleJson.asDouble(m.get("price"));
                            double z = SimpleJson.asDouble(m.get("z_score"));
                            String label = "INFLOW: " + formatPrice(p) + " (+" + String.format("%.1f", z) + "s)";
                            pills.add(new LevelPill(
                                    label, p,
                                    new Color(45, 10, 38, 245),
                                    new Color(255, 64, 129),
                                    new Color(252, 228, 236)
                            ));
                        }
                    }
                }

                // Merge proximate levels into Super-Confluence (Amber)
                List<LevelPill> consolidated = consolidateLevels(pills);

                synchronized (this) {
                    currentPills.clear();
                    currentPills.addAll(consolidated);
                }

            } catch (Throwable t) {
                // Ignore parse errors on active write
            }
        }

        private List<LevelPill> consolidateLevels(List<LevelPill> input) {
            List<LevelPill> result = new ArrayList<>();
            boolean[] merged = new boolean[input.size()];

            for (int i = 0; i < input.size(); i++) {
                if (merged[i]) continue;
                LevelPill a = input.get(i);

                LevelPill superConf = null;
                for (int j = i + 1; j < input.size(); j++) {
                    if (merged[j]) continue;
                    LevelPill b = input.get(j);

                    double pctDiff = Math.abs(a.price - b.price) / Math.max(1e-9, a.price);
                    if (pctDiff <= 0.0025) { // within 0.25%
                        merged[j] = true;
                        String tagA = extractTag(a.label);
                        String tagB = extractTag(b.label);
                        String comboLabel = "CONF: " + tagA + "+" + tagB + " " + formatPrice((a.price + b.price) / 2.0);
                        superConf = new LevelPill(
                                comboLabel, (a.price + b.price) / 2.0,
                                new Color(55, 28, 4, 250),
                                new Color(255, 179, 0),
                                new Color(255, 241, 118)
                        );
                        break;
                    }
                }

                if (superConf != null) {
                    result.add(superConf);
                } else {
                    result.add(a);
                }
            }
            return result;
        }

        private String extractTag(String label) {
            if (label == null) return "";
            if (label.startsWith("RES ")) return "R" + label.substring(4, label.indexOf(':'));
            if (label.startsWith("SUP ")) return "S" + label.substring(4, label.indexOf(':'));
            if (label.startsWith("OUTFLOW")) return "OUT";
            if (label.startsWith("INFLOW")) return "IN";
            int colonIdx = label.indexOf(':');
            return colonIdx >= 0 ? label.substring(0, colonIdx) : label.split(" ")[0];
        }

        private double jsonPips = 0.0;

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
            g.setFont(new Font("Consolas", Font.BOLD, 10));

            // Mathematically centered text
            java.awt.FontMetrics fm = g.getFontMetrics();
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
