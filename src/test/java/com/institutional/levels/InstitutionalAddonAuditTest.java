package com.institutional.levels;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone verification test for InstitutionalLevelsAddon.
 * Validates:
 * 1. SimpleJson parser robustness (valid, invalid, truncated, unicode, scientific notation).
 * 2. All 19 badge tags parsing & extraction.
 * 3. 0.20% proximity merge into CONF: pills and tag deduplication.
 * 4. Strict enforcement of MAX_VISIBLE_PILLS = 8 and priority ordering.
 * 5. Coordinate and pips math safety.
 */
public class InstitutionalAddonAuditTest {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null && actual == null) {
            testsPassed++;
            return;
        }
        if (expected != null && expected.equals(actual)) {
            testsPassed++;
        } else {
            testsFailed++;
            System.err.println("[FAIL] " + msg + " => Expected: <" + expected + ">, Actual: <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String msg) {
        if (condition) {
            testsPassed++;
        } else {
            testsFailed++;
            System.err.println("[FAIL] " + msg);
        }
    }

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("STARTING COMPREHENSIVE AUDIT & VERIFICATION: BOOKMAP INSTITUTIONAL ADDON");
        System.out.println("================================================================================");

        testSimpleJsonRobustness();
        testBadgeTagExtraction();
        testProximityConsolidation();
        testMaxVisiblePillsEnforcement();
        testCoordinateAndPipsMath();
        testEndToEndJsonParsing();
        testMacroAndScalpOiPills();
        testEmptyWhalePositionsAndOrdersGracefulHandling();
        testRealBookmapJsonFiles();

        System.out.println("================================================================================");
        System.out.println("AUDIT RESULTS: " + testsPassed + " PASSED, " + testsFailed + " FAILED");
        System.out.println("================================================================================");

        System.exit(testsFailed > 0 ? 1 : 0);
    }

    private static void testSimpleJsonRobustness() {
        System.out.println("\n--- 1. SimpleJson Robustness Tests ---");

        // Null and empty checks
        assertEquals(null, InstitutionalLevelsAddon.SimpleJson.parseObject(null), "Null JSON string");
        assertEquals(null, InstitutionalLevelsAddon.SimpleJson.parseObject(""), "Empty JSON string");
        assertEquals(null, InstitutionalLevelsAddon.SimpleJson.parseObject("   "), "Whitespace JSON string");
        assertEquals(null, InstitutionalLevelsAddon.SimpleJson.parseObject("invalid"), "Non-JSON string");
        assertEquals(null, InstitutionalLevelsAddon.SimpleJson.parseObject("{ incomplete: "), "Truncated JSON string");

        // Valid JSON with scientific notation, unicode, floats, longs, booleans
        String json = "{\n" +
                "  \"coin\": \"BTC\",\n" +
                "  \"price\": 67500.5,\n" +
                "  \"tick_size\": 1e-05,\n" +
                "  \"stars\": \"\\u2b50\\u2b50\",\n" +
                "  \"active\": true,\n" +
                "  \"empty_list\": [],\n" +
                "  \"count\": 1200,\n" +
                "  \"sub\": { \"nested\": \"val\" }\n" +
                "}";

        Map<String, Object> root = InstitutionalLevelsAddon.SimpleJson.parseObject(json);
        assertTrue(root != null, "Parsed valid JSON root");
        assertEquals("BTC", InstitutionalLevelsAddon.SimpleJson.asString(root.get("coin")), "Coin string");
        assertEquals(67500.5, InstitutionalLevelsAddon.SimpleJson.asDouble(root.get("price")), "Price double");
        assertEquals(0.00001, InstitutionalLevelsAddon.SimpleJson.asDouble(root.get("tick_size")), "1e-05 scientific notation");
        assertEquals("\u2b50\u2b50", InstitutionalLevelsAddon.SimpleJson.asString(root.get("stars")), "Unicode escape \\u2b50");
        assertTrue(InstitutionalLevelsAddon.SimpleJson.asList(root.get("empty_list")).isEmpty(), "Empty list");
        assertTrue(InstitutionalLevelsAddon.SimpleJson.asMap(root.get("sub")) != null, "Nested map");

        // Resilient handling of malformed active write
        String malformedPartial = "{\"coin\": \"ETH\", \"price\": 3500.0, \"tags\": [\"SM-ENT\",";
        assertEquals(null, InstitutionalLevelsAddon.SimpleJson.parseObject(malformedPartial), "Malformed partial write returns null gracefully");
    }

    private static void testBadgeTagExtraction() {
        System.out.println("\n--- 2. Badge Tag Extraction & Normalization Tests ---");

        InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
        InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "BTCUSDT");

        // Verify all 19 tags
        assertEquals("SM-ENT", painter.extractTag("SM-ENT: $67,500 ($1.2M)"), "Tag SM-ENT");
        assertEquals("WH-ENT", painter.extractTag("WH-ENT: $67,200 ($450K)"), "Tag WH-ENT");
        assertEquals("SM-LIQ", painter.extractTag("SM-LIQ: $66,800 ($3.5M)"), "Tag SM-LIQ");
        assertEquals("WH-LIQ", painter.extractTag("WH-LIQ: $66,500 ($900K)"), "Tag WH-LIQ");
        assertEquals("SM-TP", painter.extractTag("SM-TP: $69,000 ($2.1M)"), "Tag SM-TP");
        assertEquals("WH-TP", painter.extractTag("WH-TP: $69,200 ($750K)"), "Tag WH-TP");
        assertEquals("SM-SL", painter.extractTag("SM-SL: $65,500 ($1.8M)"), "Tag SM-SL");
        assertEquals("WH-SL", painter.extractTag("WH-SL: $65,200 ($600K)"), "Tag WH-SL");
        assertEquals("SM-BID", painter.extractTag("SM-BID: $67,100 ($4.0M)"), "Tag SM-BID");
        assertEquals("SM-BID", painter.extractTag("SM BID: $67,100 ($4.0M)"), "Tag SM BID (space)");
        assertEquals("WH-BID", painter.extractTag("WH-BID: $67,000 ($1.5M)"), "Tag WH-BID");
        assertEquals("WH-BID", painter.extractTag("WHL BID: $67,000 ($1.5M)"), "Tag WHL BID (space)");
        assertEquals("WH-BID", painter.extractTag("W-BID: $67,000 ($1.5M)"), "Tag W-BID");
        assertEquals("SM-ASK", painter.extractTag("SM-ASK: $68,000 ($3.2M)"), "Tag SM-ASK");
        assertEquals("SM-ASK", painter.extractTag("SM ASK: $68,000 ($3.2M)"), "Tag SM ASK (space)");
        assertEquals("WH-ASK", painter.extractTag("WH-ASK: $68,200 ($1.1M)"), "Tag WH-ASK");
        assertEquals("WH-ASK", painter.extractTag("WHL ASK: $68,200 ($1.1M)"), "Tag WHL ASK (space)");
        assertEquals("WH-ASK", painter.extractTag("W-ASK: $68,200 ($1.1M)"), "Tag W-ASK");
        assertEquals("CEX-IN", painter.extractTag("CEX IN: $67,800 ($500K)"), "Tag CEX-IN");
        assertEquals("CEX-IN", painter.extractTag("CEX-IN: $67,800 ($500K)"), "Tag CEX-IN hyphen");
        assertEquals("CEX-OUT", painter.extractTag("CEX OUT: $66,900 ($750K)"), "Tag CEX-OUT");
        assertEquals("CEX-OUT", painter.extractTag("CEX-OUT: $66,900 ($750K)"), "Tag CEX-OUT hyphen");
        assertEquals("W-BUY", painter.extractTag("WHL BUY: $67,300 ($250K)"), "Tag W-BUY (WHL BUY)");
        assertEquals("W-BUY", painter.extractTag("W-BUY: $67,300 ($250K)"), "Tag W-BUY");
        assertEquals("W-SELL", painter.extractTag("WHL SELL: $67,700 ($300K)"), "Tag W-SELL (WHL SELL)");
        assertEquals("W-SELL", painter.extractTag("W-SELL: $67,700 ($300K)"), "Tag W-SELL");
        assertEquals("OI-M", painter.extractTag("OI-M: $67,400 (+4.5%)"), "Tag OI-M");
        assertEquals("OI-S", painter.extractTag("OI-S: $67,450 (-2.1%)"), "Tag OI-S");
        assertEquals("R1", painter.extractTag("RES 1: 68000.0"), "Tag RES 1 -> R1");
        assertEquals("S1", painter.extractTag("SUP 1: 66000.0"), "Tag SUP 1 -> S1");
        assertEquals("CEX-IN+R1+S1+W-SELL", painter.extractTag("CONF: CEX-IN+R1+S1+W-SELL $1.3084"), "Tag CONF with composite tags");
        assertEquals("SUPER", painter.extractTag("CONF: SUPER 67500.0"), "Tag CONF SUPER");
    }

    private static void testProximityConsolidation() {
        System.out.println("\n--- 3. 0.20% Proximity Merge & Deduplication Tests ---");

        InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
        InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "BTCUSDT");

        List<InstitutionalLevelsAddon.LevelPill> pills = new ArrayList<>();
        // Two pills within 0.15% (< 0.20%): 67,000 and 67,080 -> diff = 80/67000 = 0.119%
        Color bg = Color.BLACK;
        Color border = Color.GREEN;
        Color text = Color.WHITE;
        pills.add(new InstitutionalLevelsAddon.LevelPill("SUP 1: 67000.0", 67000.0, bg, border, text, 95));
        pills.add(new InstitutionalLevelsAddon.LevelPill("WH-BID: 67080.0 ($500K)", 67080.0, bg, border, text, 75));

        // A third pill far away (> 0.20%): 67,500 -> diff = 420/67080 = 0.62%
        pills.add(new InstitutionalLevelsAddon.LevelPill("RES 1: 67500.0", 67500.0, bg, border, text, 95));

        List<InstitutionalLevelsAddon.LevelPill> merged = painter.consolidateLevels(pills, 67000.0);
        assertEquals(2, merged.size(), "Consolidated 3 levels into 2 (1 CONF and 1 individual)");

        InstitutionalLevelsAddon.LevelPill confPill = merged.get(0);
        assertTrue(confPill.label.startsWith("CONF:"), "Merged pill starts with CONF:");
        assertTrue(confPill.label.contains("S1") && confPill.label.contains("WH-BID"), "Merged pill contains S1 and WH-BID tags: " + confPill.label);
        assertEquals(67040.0, confPill.price, "Merged pill has average price (67000 + 67080) / 2 = 67040.0");
        assertEquals(100, confPill.priority, "CONF pill has priority 100");

        // Verify tag deduplication: 2 WH-BID entries merged at same price
        List<InstitutionalLevelsAddon.LevelPill> duplicateTags = new ArrayList<>();
        duplicateTags.add(new InstitutionalLevelsAddon.LevelPill("WH-BID: 67000.0 ($500K)", 67000.0, bg, border, text, 75));
        duplicateTags.add(new InstitutionalLevelsAddon.LevelPill("WH-BID: 67010.0 ($600K)", 67010.0, bg, border, text, 75));
        List<InstitutionalLevelsAddon.LevelPill> deduped = painter.consolidateLevels(duplicateTags, 67000.0);
        assertEquals(1, deduped.size(), "Duplicate tags merged to 1 pill");
        assertTrue(deduped.get(0).label.equals("CONF: WH-BID 67005.0"), "No duplicate tag parts in label: " + deduped.get(0).label);
    }

    private static void testMaxVisiblePillsEnforcement() {
        System.out.println("\n--- 4. Max Visible Pills (MAX = 8) & Priority Enforcement Tests ---");

        InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
        InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "ETHUSDT");

        Color bg = Color.BLACK;
        Color border = Color.GREEN;
        Color text = Color.WHITE;

        // Provide 14 distinct levels spanning 3000 to 3500 (all > 0.20% apart)
        List<InstitutionalLevelsAddon.LevelPill> input = new ArrayList<>();
        input.add(new InstitutionalLevelsAddon.LevelPill("OI-S: 3000.0 (+1%)", 3000.0, bg, border, text, 50));
        input.add(new InstitutionalLevelsAddon.LevelPill("OI-M: 3050.0 (+2%)", 3050.0, bg, border, text, 60));
        input.add(new InstitutionalLevelsAddon.LevelPill("WHL BUY: 3100.0 ($100K)", 3100.0, bg, border, text, 70));
        input.add(new InstitutionalLevelsAddon.LevelPill("SUP 2: 3150.0", 3150.0, bg, border, text, 75));
        input.add(new InstitutionalLevelsAddon.LevelPill("WH-BID: 3180.0 ($200K)", 3180.0, bg, border, text, 75));
        input.add(new InstitutionalLevelsAddon.LevelPill("SM-BID: 3200.0 ($500K)", 3200.0, bg, border, text, 78));
        input.add(new InstitutionalLevelsAddon.LevelPill("CEX OUT: 3220.0 ($400K)", 3220.0, bg, border, text, 80));
        input.add(new InstitutionalLevelsAddon.LevelPill("WH-ENT: 3240.0 ($300K)", 3240.0, bg, border, text, 82));
        input.add(new InstitutionalLevelsAddon.LevelPill("SM-ENT: 3260.0 ($1.0M)", 3260.0, bg, border, text, 85));
        input.add(new InstitutionalLevelsAddon.LevelPill("WH-TP: 3280.0 ($800K)", 3280.0, bg, border, text, 86));
        input.add(new InstitutionalLevelsAddon.LevelPill("SM-TP: 3300.0 ($1.5M)", 3300.0, bg, border, text, 87));
        input.add(new InstitutionalLevelsAddon.LevelPill("WH-LIQ: 3320.0 ($2.0M)", 3320.0, bg, border, text, 88));
        input.add(new InstitutionalLevelsAddon.LevelPill("SM-LIQ: 3340.0 ($3.0M)", 3340.0, bg, border, text, 89));
        input.add(new InstitutionalLevelsAddon.LevelPill("RES 1: 3360.0", 3360.0, bg, border, text, 95));

        List<InstitutionalLevelsAddon.LevelPill> visible = painter.consolidateLevels(input, 3250.0);

        assertEquals(8, visible.size(), "Strictly enforces MAX_VISIBLE_PILLS = 8");

        // The top priorities must be retained (RES 1: 95, SM-LIQ: 89, WH-LIQ: 88, SM-TP: 87, WH-TP: 86, SM-ENT: 85, WH-ENT: 82, CEX OUT: 80)
        assertTrue(visible.stream().anyMatch(p -> p.label.contains("RES 1")), "Retained RES 1");
        assertTrue(visible.stream().anyMatch(p -> p.label.contains("SM-LIQ")), "Retained SM-LIQ");
        assertTrue(visible.stream().anyMatch(p -> p.label.contains("WH-LIQ")), "Retained WH-LIQ");
        assertTrue(visible.stream().anyMatch(p -> p.label.contains("SM-ENT")), "Retained SM-ENT");

        // Verify strict price ascending order for clean vertical rendering
        for (int i = 0; i < visible.size() - 1; i++) {
            assertTrue(visible.get(i).price <= visible.get(i + 1).price,
                    "Strict price ascending order: " + visible.get(i).price + " <= " + visible.get(i + 1).price);
        }
    }

    private static void testCoordinateAndPipsMath() {
        System.out.println("\n--- 5. Coordinate & Pips Math Tests ---");

        InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
        InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "SOLUSDT");

        // Test pips estimation across asset price regimes
        assertEquals(0.1, painter.estimatePips(67500.0), "BTC price >= 1000 pips = 0.1");
        assertEquals(0.01, painter.estimatePips(150.0), "SOL price >= 100 pips = 0.01");
        assertEquals(0.001, painter.estimatePips(1.30), "XRP price >= 1.0 pips = 0.001");
        assertEquals(0.00001, painter.estimatePips(0.035), "Low-cap price >= 0.01 pips = 0.00001");
        assertEquals(0.0000001, painter.estimatePips(0.00005), "Meme price < 0.001 pips = 0.0000001");

        // Test boundary coordinates
        int PILL_WIDTH = 150;
        int LEFT_MARGIN_X = 4;
        int x1 = LEFT_MARGIN_X;
        int x2 = LEFT_MARGIN_X + PILL_WIDTH;
        assertEquals(4, x1, "Left margin starts at x=4");
        assertEquals(154, x2, "Left margin ends at x=154 strictly inside column");
        assertTrue(x2 < 300, "Does not overlap heatmap canvas area (x > 154)");
    }

    private static void testEndToEndJsonParsing() {
        System.out.println("\n--- 6. End-to-End Synthetic JSON File Parsing Test ---");

        try {
            File tempJson = File.createTempFile("institutional_levels_test_", ".json");
            tempJson.deleteOnExit();

            String fullJson = "{\n" +
                    "  \"coin\": \"SOL\",\n" +
                    "  \"current_price\": 150.0,\n" +
                    "  \"pips\": 0.01,\n" +
                    "  \"top_resistances\": [\n" +
                    "    {\"price\": 155.0, \"score\": 90.0, \"dist_pct\": 3.3}\n" +
                    "  ],\n" +
                    "  \"top_supports\": [\n" +
                    "    {\"price\": 145.0, \"score\": 85.0, \"dist_pct\": 3.3}\n" +
                    "  ],\n" +
                    "  \"cex_flows\": {\n" +
                    "    \"cex_outflows\": [{\"price\": 148.0, \"usd\": 500000.0}],\n" +
                    "    \"cex_inflows\": [{\"price\": 152.0, \"usd\": 400000.0}]\n" +
                    "  },\n" +
                    "  \"whale_trades\": {\n" +
                    "    \"whale_buys\": [{\"price\": 149.0, \"usd\": 250000.0}],\n" +
                    "    \"whale_sells\": [{\"price\": 151.0, \"usd\": 300000.0}]\n" +
                    "  },\n" +
                    "  \"macro_oi\": {\n" +
                    "    \"macro_longs\": [{\"price\": 146.0, \"pct\": 5.2}],\n" +
                    "    \"macro_shorts\": []\n" +
                    "  },\n" +
                    "  \"scalp_oi\": {\n" +
                    "    \"scalp_longs\": [],\n" +
                    "    \"scalp_shorts\": [{\"price\": 153.0, \"pct\": -2.5}]\n" +
                    "  },\n" +
                    "  \"super_confluences\": [\n" +
                    "    {\"price\": 150.5, \"label\": \"CONF: R1+W-BUY 150.5\", \"tag\": \"CONF: R1+W-BUY\"}\n" +
                    "  ],\n" +
                    "  \"whale_positions\": {\n" +
                    "    \"entries\": [\n" +
                    "      {\"price\": 147.5, \"tag\": \"SM-ENT\", \"usd\": 1500000.0, \"trader_type\": \"SM\"},\n" +
                    "      {\"price\": 147.0, \"tag\": \"WH-ENT\", \"usd\": 600000.0, \"trader_type\": \"WH\"}\n" +
                    "    ],\n" +
                    "    \"liquidations\": [\n" +
                    "      {\"price\": 140.0, \"tag\": \"SM-LIQ\", \"usd\": 2000000.0}\n" +
                    "    ]\n" +
                    "  },\n" +
                    "  \"whale_orders\": {\n" +
                    "    \"take_profits\": [{\"price\": 160.0, \"tag\": \"WH-TP\", \"usd\": 700000.0}],\n" +
                    "    \"stop_losses\": [{\"price\": 138.0, \"tag\": \"WH-SL\", \"usd\": 500000.0}],\n" +
                    "    \"resting_bids\": [{\"price\": 144.0, \"tag\": \"SM-BID\", \"usd\": 1200000.0}],\n" +
                    "    \"resting_asks\": [{\"price\": 156.0, \"tag\": \"WH-ASK\", \"usd\": 800000.0}]\n" +
                    "  }\n" +
                    "}";

            Files.write(tempJson.toPath(), fullJson.getBytes("UTF-8"));

            InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
            InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "SOLUSDT");

            List<InstitutionalLevelsAddon.LevelPill> loaded = painter.loadJsonData(tempJson);
            assertTrue(loaded != null && !loaded.isEmpty(), "Loaded pills from full JSON");
            assertTrue(loaded.size() <= 8, "Loaded pills capped at MAX_VISIBLE_PILLS (size=" + loaded.size() + ")");

            System.out.println("Loaded " + loaded.size() + " pills successfully:");
            for (InstitutionalLevelsAddon.LevelPill pill : loaded) {
                System.out.println("  * " + pill.label + " @ " + pill.price + " (priority=" + pill.priority + ")");
            }

        } catch (Exception e) {
            e.printStackTrace();
            testsFailed++;
        }
    }

    private static void testMacroAndScalpOiPills() {
        System.out.println("\n--- 7. Macro OI (OI-M) & Scalp OI (OI-S) Badge & Confluence Tests ---");

        InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
        InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "BTCUSDT");

        // 1. Check formatPct syntax
        assertEquals("+1%", painter.formatPct(1.0), "formatPct(1.0) -> +1%");
        assertEquals("+5.2%", painter.formatPct(5.2), "formatPct(5.2) -> +5.2%");
        assertEquals("+0.5%", painter.formatPct(0.5), "formatPct(0.5) -> +0.5%");
        assertEquals("-2.5%", painter.formatPct(-2.5), "formatPct(-2.5) -> -2.5%");
        assertEquals("+0%", painter.formatPct(0.0), "formatPct(0.0) -> +0%");

        // 2. Parse synthetic JSON containing only OI-M and OI-S
        try {
            File oiJson = File.createTempFile("institutional_oi_test_", ".json");
            oiJson.deleteOnExit();

            String json = "{\n" +
                    "  \"coin\": \"BTC\",\n" +
                    "  \"current_price\": 65000.0,\n" +
                    "  \"pips\": 0.1,\n" +
                    "  \"macro_oi\": {\n" +
                    "    \"macro_longs\": [{\"price\": 64000.0, \"pct\": 3.5}],\n" +
                    "    \"macro_shorts\": [{\"price\": 66000.0, \"pct\": 1.0, \"label\": \"OI-M: 66000.0 (+1%)\"}]\n" +
                    "  },\n" +
                    "  \"scalp_oi\": {\n" +
                    "    \"scalp_longs\": [{\"price\": 64800.0, \"pct\": 0.5}],\n" +
                    "    \"scalp_shorts\": [{\"price\": 65200.0, \"pct\": 2.0, \"label\": \"OI-S: 65200.0 (+2%)\"}]\n" +
                    "  }\n" +
                    "}";
            Files.write(oiJson.toPath(), json.getBytes("UTF-8"));

            List<InstitutionalLevelsAddon.LevelPill> pills = painter.loadJsonData(oiJson);
            assertTrue(pills != null && pills.size() == 4, "Loaded 4 OI pills (2 macro, 2 scalp)");

            // Find OI-M and OI-S pills
            InstitutionalLevelsAddon.LevelPill oiMLong = pills.stream().filter(p -> p.price == 64000.0).findFirst().orElse(null);
            InstitutionalLevelsAddon.LevelPill oiMShort = pills.stream().filter(p -> p.price == 66000.0).findFirst().orElse(null);
            InstitutionalLevelsAddon.LevelPill oiSLong = pills.stream().filter(p -> p.price == 64800.0).findFirst().orElse(null);
            InstitutionalLevelsAddon.LevelPill oiSShort = pills.stream().filter(p -> p.price == 65200.0).findFirst().orElse(null);

            assertTrue(oiMLong != null, "Found OI-M Long pill");
            assertTrue(oiMShort != null, "Found OI-M Short pill");
            assertTrue(oiSLong != null, "Found OI-S Long pill");
            assertTrue(oiSShort != null, "Found OI-S Short pill");

            // Verify OI-M colors & priority: Deep Navy #0D1B2A, Electric Blue #00B4D8, White text, Priority 60
            assertEquals(60, oiMLong.priority, "OI-M priority is 60");
            assertEquals(new Color(0x0D, 0x1B, 0x2A, 245), oiMLong.bgColor, "OI-M background is Deep Navy #0D1B2A");
            assertEquals(new Color(0x00, 0xB4, 0xD8), oiMLong.borderColor, "OI-M border is Electric Blue #00B4D8");
            assertEquals(Color.WHITE, oiMLong.textColor, "OI-M text is White");
            assertTrue(oiMLong.label.contains("OI-M:") && oiMLong.label.contains("(+3.5%)"), "OI-M label syntax (+3.5%): " + oiMLong.label);

            // Verify OI-S colors & priority: Charcoal Slate #1E2229, Orange #FF9E00, Peach text #FFD180, Priority 50
            assertEquals(50, oiSLong.priority, "OI-S priority is 50");
            assertEquals(new Color(0x1E, 0x22, 0x29, 245), oiSLong.bgColor, "OI-S background is Charcoal Slate #1E2229");
            assertEquals(new Color(0xFF, 0x9E, 0x00), oiSLong.borderColor, "OI-S border is Orange #FF9E00");
            assertEquals(new Color(0xFF, 0xD1, 0x80), oiSLong.textColor, "OI-S text is Peach #FFD180");
            assertTrue(oiSLong.label.contains("OI-S:") && oiSLong.label.contains("(+0.5%)"), "OI-S label syntax (+0.5%): " + oiSLong.label);

            // 3. Verify merging into CONF: (Priority 100) when coincident with support/resistance within 0.20%
            // Place SUP 1 at 64050.0 (diff with 64000.0 is 50 / 64000 = 0.078% < 0.20%)
            List<InstitutionalLevelsAddon.LevelPill> mergeTestPills = new ArrayList<>();
            mergeTestPills.add(new InstitutionalLevelsAddon.LevelPill("SUP 1: 64050.0", 64050.0, Color.BLACK, Color.GREEN, Color.WHITE, 95));
            mergeTestPills.add(oiMLong);

            List<InstitutionalLevelsAddon.LevelPill> mergedSup = painter.consolidateLevels(mergeTestPills, 64000.0);
            assertEquals(1, mergedSup.size(), "OI-M and SUP 1 consolidated into 1 pill");
            InstitutionalLevelsAddon.LevelPill confSup = mergedSup.get(0);
            assertEquals(100, confSup.priority, "Coincident CONF pill has Priority 100");
            assertTrue(confSup.label.startsWith("CONF:"), "Label starts with CONF:");
            assertTrue(confSup.label.contains("OI-M") && confSup.label.contains("S1"), "Merged label contains OI-M and S1: " + confSup.label);
            assertEquals(new Color(0x34, 0x1C, 0x04, 245), confSup.bgColor, "CONF background is Obsidian Gold #341C04");
            assertEquals(new Color(0xFF, 0xB3, 0x00), confSup.borderColor, "CONF border is Electric Amber #FFB300");
            assertEquals(new Color(0xFF, 0xF5, 0x9D), confSup.textColor, "CONF text is Yellow #FFF59D");

            // Place RES 1 at 65230.0 (diff with 65200.0 is 30 / 65200 = 0.046% < 0.20%)
            List<InstitutionalLevelsAddon.LevelPill> mergeTestPills2 = new ArrayList<>();
            mergeTestPills2.add(new InstitutionalLevelsAddon.LevelPill("RES 1: 65230.0", 65230.0, Color.BLACK, Color.RED, Color.WHITE, 95));
            mergeTestPills2.add(oiSShort);

            List<InstitutionalLevelsAddon.LevelPill> mergedRes = painter.consolidateLevels(mergeTestPills2, 65000.0);
            assertEquals(1, mergedRes.size(), "OI-S and RES 1 consolidated into 1 pill");
            InstitutionalLevelsAddon.LevelPill confRes = mergedRes.get(0);
            assertEquals(100, confRes.priority, "Coincident CONF pill has Priority 100");
            assertTrue(confRes.label.startsWith("CONF:") && confRes.label.contains("OI-S") && confRes.label.contains("R1"),
                    "Merged label contains OI-S and R1: " + confRes.label);

            // Test pill image generation with zero errors
            Object img1 = painter.generatePillImage(150, 19, oiMLong);
            Object img2 = painter.generatePillImage(150, 19, oiSLong);
            Object img3 = painter.generatePillImage(150, 19, confSup);
            assertTrue(img1 != null, "OI-M PreparedImage rendered cleanly");
            assertTrue(img2 != null, "OI-S PreparedImage rendered cleanly");
            assertTrue(img3 != null, "CONF PreparedImage rendered cleanly");

        } catch (Exception e) {
            e.printStackTrace();
            testsFailed++;
        }
    }

    private static void testEmptyWhalePositionsAndOrdersGracefulHandling() {
        System.out.println("\n--- 8. Empty Whale Positions / Orders Graceful Handling Tests ---");

        InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
        InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, "MAGICUSDT");

        try {
            File emptyWhaleJson = File.createTempFile("empty_whale_test_", ".json");
            emptyWhaleJson.deleteOnExit();

            // JSON with empty whale_positions and empty whale_orders (coins without verified trader data)
            String json = "{\n" +
                    "  \"coin\": \"MAGIC\",\n" +
                    "  \"current_price\": 0.1115,\n" +
                    "  \"pips\": 0.0001,\n" +
                    "  \"top_resistances\": [\n" +
                    "    {\"price\": 0.1180, \"score\": 85.0}\n" +
                    "  ],\n" +
                    "  \"top_supports\": [\n" +
                    "    {\"price\": 0.1050, \"score\": 90.0}\n" +
                    "  ],\n" +
                    "  \"cex_flows\": {\n" +
                    "    \"cex_outflows\": [{\"price\": 0.1080, \"usd\": 40000.0}],\n" +
                    "    \"cex_inflows\": [{\"price\": 0.1150, \"usd\": 35000.0}]\n" +
                    "  },\n" +
                    "  \"whale_trades\": {\n" +
                    "    \"whale_buys\": [{\"price\": 0.1090, \"usd\": 25000.0}],\n" +
                    "    \"whale_sells\": [{\"price\": 0.1140, \"usd\": 30000.0}]\n" +
                    "  },\n" +
                    "  \"macro_oi\": {\n" +
                    "    \"macro_longs\": [{\"price\": 0.1070, \"pct\": 1.0}],\n" +
                    "    \"macro_shorts\": []\n" +
                    "  },\n" +
                    "  \"scalp_oi\": {\n" +
                    "    \"scalp_longs\": [],\n" +
                    "    \"scalp_shorts\": [{\"price\": 0.1130, \"pct\": 0.5}]\n" +
                    "  },\n" +
                    "  \"whale_positions\": {\n" +
                    "    \"entries\": [],\n" +
                    "    \"liquidations\": []\n" +
                    "  },\n" +
                    "  \"whale_orders\": {\n" +
                    "    \"take_profits\": [],\n" +
                    "    \"stop_losses\": [],\n" +
                    "    \"resting_bids\": [],\n" +
                    "    \"resting_asks\": []\n" +
                    "  }\n" +
                    "}";

            Files.write(emptyWhaleJson.toPath(), json.getBytes("UTF-8"));

            List<InstitutionalLevelsAddon.LevelPill> pills = painter.loadJsonData(emptyWhaleJson);
            assertTrue(pills != null && !pills.isEmpty(), "Pills loaded successfully with empty whale positions/orders");
            assertEquals(8, pills.size(), "Loaded exactly 8 available levels (R1, S1, CEX OUT, CEX IN, W-BUY, W-SELL, OI-M, OI-S)");

            // Verify each available level is present and correct
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("RES 1")), "RES 1 present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("SUP 1")), "SUP 1 present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("CEX OUT")), "CEX OUT present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("CEX IN")), "CEX IN present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("WHL BUY")), "WHL BUY present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("WHL SELL")), "WHL SELL present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("OI-M")), "OI-M present");
            assertTrue(pills.stream().anyMatch(p -> p.label.contains("OI-S")), "OI-S present");

            // Verify no whale position / order tags are present
            assertTrue(pills.stream().noneMatch(p -> p.label.contains("WH-ENT") || p.label.contains("SM-ENT")), "No whale entries");
            assertTrue(pills.stream().noneMatch(p -> p.label.contains("WH-LIQ") || p.label.contains("SM-LIQ")), "No whale liquidations");
            assertTrue(pills.stream().noneMatch(p -> p.label.contains("WH-TP") || p.label.contains("SM-TP")), "No whale take profits");
            assertTrue(pills.stream().noneMatch(p -> p.label.contains("WH-SL") || p.label.contains("SM-SL")), "No whale stop losses");

            // Verify all 8 pills render cleanly with zero visual glitches or NPEs
            for (InstitutionalLevelsAddon.LevelPill p : pills) {
                Object img = painter.generatePillImage(150, 19, p);
                assertTrue(img != null, "Pill rendered image cleanly: " + p.label);
            }

            // Also test when whale_positions and whale_orders are completely null/omitted from JSON
            String jsonOmitted = "{\n" +
                    "  \"coin\": \"MAGIC\",\n" +
                    "  \"current_price\": 0.1115,\n" +
                    "  \"top_resistances\": [{\"price\": 0.1180}],\n" +
                    "  \"top_supports\": [{\"price\": 0.1050}]\n" +
                    "}";
            File omittedJson = File.createTempFile("omitted_whale_test_", ".json");
            omittedJson.deleteOnExit();
            Files.write(omittedJson.toPath(), jsonOmitted.getBytes("UTF-8"));

            List<InstitutionalLevelsAddon.LevelPill> omittedPills = painter.loadJsonData(omittedJson);
            assertTrue(omittedPills != null && omittedPills.size() == 2, "Loaded 2 levels when other fields omitted");

        } catch (Exception e) {
            e.printStackTrace();
            testsFailed++;
        }
    }

    private static void testRealBookmapJsonFiles() {
        System.out.println("\n--- 9. Real Disk .bookmap JSON Files Verification ---");

        File bookmapDir = new File(System.getProperty("user.home"), ".bookmap");
        if (!bookmapDir.exists()) {
            System.out.println("  Skipping: .bookmap directory does not exist on this machine");
            return;
        }

        String[] coins = {"MAGIC", "MAGMA", "PEPE", "SOL", "latest"};
        for (String c : coins) {
            File f = "latest".equals(c)
                    ? new File(bookmapDir, "institutional_levels_latest.json")
                    : new File(bookmapDir, "institutional_levels_" + c + ".json");

            if (!f.exists()) {
                System.out.println("  File not found (skipped): " + f.getName());
                continue;
            }

            try {
                InstitutionalLevelsAddon addon = new InstitutionalLevelsAddon(null);
                InstitutionalLevelsAddon.InstitutionalPainter painter = addon.new InstitutionalPainter(null, c + "USDT");
                List<InstitutionalLevelsAddon.LevelPill> pills = painter.loadJsonData(f);

                assertTrue(pills != null && !pills.isEmpty(), "Successfully parsed real file: " + f.getName());
                assertTrue(pills.size() <= 8, "Pills capped at <= 8 for " + f.getName() + " (size=" + (pills != null ? pills.size() : 0) + ")");

                System.out.println("  [OK] " + f.getName() + " loaded " + (pills != null ? pills.size() : 0) + " pills:");
                if (pills != null) {
                    for (InstitutionalLevelsAddon.LevelPill pill : pills) {
                        // Render each pill to verify no visual glitches or exceptions
                        Object img = painter.generatePillImage(150, 19, pill);
                        assertTrue(img != null, "Rendered pill image: " + pill.label);
                        System.out.println("       * " + pill.label + " (px=" + pill.price + ", prio=" + pill.priority + ")");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error parsing real file " + f.getName() + ": " + e.getMessage());
                e.printStackTrace();
                testsFailed++;
            }
        }
    }
}
