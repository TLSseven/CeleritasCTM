package team.celeritasctm.client.resource;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用语义回归测试（无 JUnit；由 Gradle 的 JavaExec 任务直接运行 main）。
 *
 * <p>覆盖本次修复的通用资源规则缺陷：
 * <ul>
 *   <li>{@code tiles} 逗号分隔</li>
 *   <li>多值状态谓词：属性内 OR、属性间 AND，写坏不放大</li>
 *   <li>文件名主干精确匹配（不再剥数字后缀）与 {@code block<id>} 推断</li>
 *   <li>{@code height=} 仍是 repeat 尺寸；{@code heights} 优先于 min/max（不并集放宽）</li>
 *   <li>biome 注册名/显示名候选、{@code !} 排除、缺上下文 fail-closed</li>
 *   <li>数字 ID 永不变成空气、约束不丢失</li>
 *   <li>快照原子性：不可变副本 + 单一 glassRule 字段</li>
 * </ul>
 *
 * <p>不依赖方块注册表：数字 ID 的“已解析”分支由 CtmRuleSupportTest 覆盖，
 * 这里只验证注册表缺失时的保守行为（保留裸 ID、绝不放弃约束）。
 */
public final class CtmGenericRuleTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        testTilesCommaDelimiter();
        testMultivaluePredicate();
        testMalformedTokensNeverWiden();
        testFilenameStemExact();
        testBlockIdFileNameInference();
        testBlockIdInferenceKeepsTileConstraint();
        testNamespaceAwareTileMatch();
        testHeightIsRepeatNotSelector();
        testHeightsPrecedenceOverMinMax();
        testBiomeFilterParsing();
        testBiomeMatching();
        testUnrestrictedRuleIgnoresBiomeContext();
        testMetadataNullStateFailClosed();
        testNumericIdNeverAir();
        testSnapshotAtomicity();
        testFindMatchPriority();
        testHotPathNoPropertyMapForPlainBlockRule();
        testHotPathPrecomputedCanonicalKey();
        testMatchesTileCleanMatchesMatchesTile();
        testFindMatchStillAppliesEnvironmentRules();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("FAIL: " + f);
        if (!failures.isEmpty()) System.exit(1);
    }

    private static CtmProperties parse(String text, String path) {
        return parse(text, "example", path);
    }

    private static CtmProperties parse(String text, String ns, String path) {
        InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        return CtmPropertiesLoader.parseProperties(in, new ResourceLocation(ns, path), 3);
    }

    // ---------------- tiles ----------------

    private static void testTilesCommaDelimiter() {
        String[] space = CtmProperties.parseTiles("0 1 2");
        String[] comma = CtmProperties.parseTiles("0,1,2");
        check("tiles space separated", Arrays.equals(space, new String[]{"0", "1", "2"}));
        check("tiles comma separated", Arrays.equals(comma, new String[]{"0", "1", "2"}));

        String[] mixed = CtmProperties.parseTiles("1-3, x");
        check("tiles mixed delimiter and range",
            Arrays.equals(mixed, new String[]{"1", "2", "3", "x"}));

        CtmProperties p = parse("method=ctm\ntiles=0,1,2\nmatchBlocks=examplemod:stone_full_1\n",
            "mcpatcher/ctm/comma.properties");
        check("comma tiles reach rule", p != null && p.getTileCount() == 3);
    }

    // ---------------- multivalue predicates ----------------

    private static void testMultivaluePredicate() {
        CtmRuleSupport.BlockMatch m = CtmRuleSupport.parseMatchToken("examplemod:leaves:age=7,8");
        check("multivalue parsed", m != null && m.getPropertyValues().get("age").size() == 2);

        Map<String, String> age8 = new LinkedHashMap<>();
        age8.put("age", "8");
        Map<String, String> age7 = new LinkedHashMap<>();
        age7.put("age", "7");
        Map<String, String> age3 = new LinkedHashMap<>();
        age3.put("age", "3");
        check("multivalue OR matches 8", m != null && m.matchesProperties(age8));
        check("multivalue OR matches 7", m != null && m.matchesProperties(age7));
        check("multivalue rejects 3", m != null && !m.matchesProperties(age3));

        // 属性间 AND
        CtmRuleSupport.BlockMatch two = CtmRuleSupport.parseMatchToken("examplemod:leaves:age=7:stage=2");
        Map<String, String> both = new LinkedHashMap<>();
        both.put("age", "7");
        both.put("stage", "2");
        Map<String, String> onlyAge = new LinkedHashMap<>();
        onlyAge.put("age", "7");
        check("two props AND matches", two != null && two.matchesProperties(both));
        check("two props AND rejects partial", two != null && !two.matchesProperties(onlyAge));
    }

    private static void testMalformedTokensNeverWiden() {
        check("empty value rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:age=") == null);
        check("trailing comma rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:age=7,") == null);
        check("leading comma rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:age=,7") == null);
        check("double comma rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:age=7,,8") == null);
        check("double equals rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:age=7=8") == null);
        check("bare value without property rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:7") == null);
        check("empty predicate name rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:=7") == null);
        check("trailing colon rejected", CtmRuleSupport.parseMatchToken("examplemod:foo:") == null);
        check("double colon rejected", CtmRuleSupport.parseMatchToken("examplemod::foo") == null);

        // 规则级：唯一 token 写坏 → 整条跳过，即使有 matchTiles 也不得放宽
        CtmProperties p = parse("method=ctm\ntiles=0-3\nmatchTiles=blocks/stone\n"
            + "matchBlocks=examplemod:foo:age=\n", "mcpatcher/ctm/narrow.properties");
        check("bad token with matchTiles skips rule", p == null);
    }

    // ---------------- filename ----------------

    private static void testFilenameStemExact() {
        check("stem strips only extension",
            "planks2".equals(CtmPropertiesLoader.filenameStem("mcpatcher/ctm/planks2.properties")));
        check("stem keeps underscore digits",
            "planks_2".equals(CtmPropertiesLoader.filenameStem("a/b/planks_2.properties")));
        check("stem without extension stays",
            "planks".equals(CtmPropertiesLoader.filenameStem("a/b/planks")));
        check("stem of null is empty", "".equals(CtmPropertiesLoader.filenameStem(null)));
    }

    private static void testBlockIdFileNameInference() {
        check("block171a -> 171",
            Integer.valueOf(171).equals(
                CtmPropertiesLoader.parseBlockIdFromFileName("ctm/3_decoration/block171a.properties")));
        check("block5 -> 5",
            Integer.valueOf(5).equals(
                CtmPropertiesLoader.parseBlockIdFromFileName("ctm/block5.properties")));
        check("block without digits -> null",
            CtmPropertiesLoader.parseBlockIdFromFileName("ctm/block.properties") == null);
        check("block0 -> 0 (explicit, not treated as absent)",
            Integer.valueOf(0).equals(
                CtmPropertiesLoader.parseBlockIdFromFileName("ctm/block0.properties")));
        check("block4096 -> null (out of range)",
            CtmPropertiesLoader.parseBlockIdFromFileName("ctm/block4096.properties") == null);
        check("deadbeef -> null",
            CtmPropertiesLoader.parseBlockIdFromFileName("ctm/deadbeef.properties") == null);
    }

    private static void testBlockIdInferenceKeepsTileConstraint() {
        // 只有 matchTiles 的规则：文件名 block171 也要补上方块约束（两条约束取交）
        CtmProperties onlyTiles = parse("method=ctm\ntiles=0-3\nmatchTiles=blocks/stone\n",
            "mcpatcher/ctm/block171a.properties");
        check("block<id> inference applies with matchTiles", onlyTiles != null);
        check("block<id> constraint kept (deferred without registry)",
            onlyTiles != null && onlyTiles.hasBlockConstraint());
        check("block<id> does not widen to all blocks",
            onlyTiles != null && !onlyTiles.matchesBlock(null));

        // 纯文件名规则（无 matchTiles/matchBlocks）：block<id> 推断出方块约束
        CtmProperties onlyFile = parse("method=ctm\ntiles=0-3\n",
            "mcpatcher/ctm/block171a.properties");
        check("block<id> file-only rule has block constraint",
            onlyFile != null && onlyFile.hasBlockConstraint());
        check("block<id> file-only rule not treated as air/unconstrained",
            onlyFile != null && onlyFile.hasAnyMatch() && !onlyFile.matchesBlock(null));
    }

    // ---------------- height ----------------

    private static void testHeightIsRepeatNotSelector() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nheight=2\nwidth=3\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/repeat.properties");
        check("height= stays repeat grid", p != null && p.getHeight() == 2 && p.getWidth() == 3);
        check("height= does not become height filter", p != null && !p.hasHeightRestriction());
    }

    private static void testHeightsPrecedenceOverMinMax() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nheights=0-63\nminHeight=100\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/heights.properties");
        check("heights rule parsed", p != null && p.hasHeightRestriction());
        check("heights wins without union widening", p != null
            && p.getHeightRanges().size() == 1
            && p.getHeightRanges().get(0).contains(10)
            && !p.getHeightRanges().get(0).contains(120));

        CtmProperties minMax = parse("method=ctm\ntiles=0-3\nminHeight=64\nmaxHeight=128\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/minmax.properties");
        check("min/max used when heights absent", minMax != null
            && minMax.getHeightRanges().size() == 1
            && minMax.getHeightRanges().get(0).contains(64)
            && minMax.getHeightRanges().get(0).contains(128));

        CtmProperties bad = parse("method=ctm\ntiles=0-3\nheights=abc\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/badheights.properties");
        check("bad heights skips rule", bad == null);
    }

    // ---------------- biome ----------------

    private static void testBiomeFilterParsing() {
        CtmRuleSupport.BiomeFilter f = CtmRuleSupport.parseBiomeFilter("forest desert");
        check("biome include list parsed", f != null && f.included.size() == 2
            && f.included.contains("minecraft:forest")
            && f.included.contains("minecraft:desert"));
        check("include list has no exclusions", f != null && f.excluded.isEmpty());

        // OptiFine：前导 ! 否定整个列表
        CtmRuleSupport.BiomeFilter negAll = CtmRuleSupport.parseBiomeFilter("!desert savanna");
        check("leading ! negates whole list", negAll != null
            && negAll.included.isEmpty()
            && negAll.excluded.size() == 2
            && negAll.excluded.contains("minecraft:desert")
            && negAll.excluded.contains("minecraft:savanna"));

        check("blank biome filter is null", CtmRuleSupport.parseBiomeFilter("   ") == null);
        check("bare ! is null", CtmRuleSupport.parseBiomeFilter("!") == null);
        check("inner ! is invalid", CtmRuleSupport.parseBiomeFilter("desert !savanna") == null);
    }

    private static void testBiomeMatching() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nbiomes=forest, minecraft:desert\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/biomes.properties");
        check("biome rule parsed", p != null && p.hasBiomeRestriction());
        check("biome include matches registry key",
            p != null && p.matchesBiome("minecraft:forest"));
        check("biome include matches legacy display name alias",
            p != null && p.matchesBiome(CtmRuleSupport.canonicalBlockKey("Desert")));
        check("biome include rejects other", p != null && !p.matchesBiome("minecraft:plains"));
        check("biome restriction fails closed without context",
            p != null && !p.matchesBiome((String) null));

        // 注册名 + 显示名两条候选键：任一命中即可（不硬编码映射表）
        check("candidate list matches any alias",
            p != null && p.matchesBiomeKeys(Arrays.asList("minecraft:plains", "minecraft:forest")));

        CtmProperties excluded = parse("method=ctm\ntiles=0-3\nbiomes=!desert\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/exclude.properties");
        check("exclusion-only rule restricts", excluded != null && excluded.hasBiomeRestriction());
        check("exclusion matches other biomes",
            excluded != null && excluded.matchesBiome("minecraft:plains"));
        check("exclusion rejects excluded biome",
            excluded != null && !excluded.matchesBiome("minecraft:desert"));
        check("exclusion fails closed without context",
            excluded != null && !excluded.matchesBiome((String) null));
    }

    private static void testUnrestrictedRuleIgnoresBiomeContext() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\n"
            + "matchBlocks=examplemod:stone_full_1\n", "mcpatcher/ctm/nobiome.properties");
        check("unrestricted rule has no biome filter", p != null && !p.hasBiomeRestriction());
        check("unrestricted rule matches without biome context",
            p != null && p.matchesBiomeKeys(null));
    }

    // ---------------- numeric ID ----------------

    private static void testNumericIdNeverAir() {
        // 显式 ID 是合法语法（含 0）；未注册 ID 不得被映射成空气而静默丢约束
        check("id 0 still parses as numeric", CtmRuleSupport.parseMatchToken("0") != null);
        check("out-of-range id rejected", CtmRuleSupport.parseMatchToken("4096") == null);

        CtmProperties p = new CtmProperties(new ResourceLocation("example", "x.properties"), "ctm", 1);
        p.addDeferredNumericId(-5);
        p.addDeferredNumericId(4096);
        check("negative/out-of-range never deferred", !p.hasDeferredNumericIds());
        p.addDeferredNumericId(0);
        p.addDeferredNumericId(171);
        check("in-range numeric ids deferred", p.hasDeferredNumericIds()
            && p.getDeferredNumericIds().size() == 2);

        // 注册表不可用：规则保留约束，绝不退化成“任意方块”
        CtmProperties parsed = parse("method=ctm\ntiles=0-3\nmatchBlocks=171\n",
            "mcpatcher/ctm/block171.properties");
        check("numeric-only rule not dropped", parsed != null);
        check("numeric-only rule has block constraint", parsed != null && parsed.hasBlockConstraint());
        check("numeric-only rule not air", parsed != null && parsed.getDeferredNumericIds().isEmpty()
            ? parsed.hasBlockMatches() : parsed.hasDeferredNumericIds());
        check("numeric-only rule does not match null state",
            parsed != null && !parsed.matchesBlock(null));

        // matchTiles + 数字 ID：纹理命中但方块约束仍在
        CtmProperties mixed = parse("method=ctm\ntiles=0-3\nmatchTiles=blocks/stone\nmatchBlocks=171\n",
            "mcpatcher/ctm/mixedid.properties");
        check("tiles+numeric keeps constraint", mixed != null && mixed.hasBlockConstraint());
        check("tiles+numeric does not widen block match",
            mixed != null && !mixed.matchesBlock(null));
        check("tiles+numeric still matches its texture",
            mixed != null && mixed.matchesTile("minecraft:blocks/stone"));
    }

    // ---------------- namespace ----------------

    private static void testNamespaceAwareTileMatch() {
        CtmProperties p = new CtmProperties(new ResourceLocation("examplemod", "ns.properties"), "ctm", 1);
        p.addMatchTile("examplemod:blocks/foo");
        check("explicit namespace matches same namespace", p.matchesTile("examplemod:blocks/foo"));
        check("explicit namespace rejects other namespace", !p.matchesTile("minecraft:blocks/foo"));
        check("explicit namespace rejects bare name", !p.matchesTile("blocks/foo"));

        CtmProperties plain = new CtmProperties(new ResourceLocation("examplemod", "plain.properties"), "ctm", 1);
        plain.addMatchTile("blocks/foo");
        check("unqualified tile matches any namespace", plain.matchesTile("minecraft:blocks/foo"));
    }

    // ---------------- snapshot ----------------

    private static void testSnapshotAtomicity() {
        CtmProperties a = new CtmProperties(new ResourceLocation("a", "a.properties"), "ctm", 0);
        List<CtmProperties> source = new ArrayList<>();
        source.add(a);
        Map<String, List<CtmProperties>> tileIndex = new LinkedHashMap<>();
        List<CtmProperties> tileList = new ArrayList<>();
        tileList.add(a);
        tileIndex.put("foo", tileList);

        CtmProperties builtinGlass = new CtmProperties(
            new ResourceLocation("celeritasctm", "glass.properties"), "ctm", Integer.MIN_VALUE);
        CtmPropertiesLoader.RuleSnapshot snap = new CtmPropertiesLoader.RuleSnapshot(
            source, tileIndex, Collections.<String, List<CtmProperties>>emptyMap(),
            builtinGlass, Collections.<CtmProperties>emptyList());

        source.clear();
        tileList.clear();
        check("snapshot copies rule list", snap.properties.size() == 1);
        check("snapshot copies index lists", snap.tileIndex.get("foo").size() == 1);
        check("snapshot keeps single glass rule", snap.glassRule == builtinGlass);

        CtmPropertiesLoader.RuleSnapshot empty = CtmPropertiesLoader.RuleSnapshot.empty();
        check("empty snapshot glass rule is null", empty.glassRule == null);
        check("empty snapshot has no rules", empty.properties.isEmpty());

        // 玻璃回退只可能是内置对象，绝不把资源包规则当全局模板
        check("glass fallback is builtin namespace",
            "celeritasctm".equals(snap.glassRule.getResourceId().getNamespace()));
        check("getGlassRule and getSnapshot see same field",
            CtmPropertiesLoader.getGlassRule() == CtmPropertiesLoader.getSnapshot().glassRule);
    }

    // ---------------- findMatch priority ----------------

    /**
     * 内置玻璃回退即使被放在 tile 索引最前面，也不能抢在资源包规则之前命中：
     * findMatch 的普通循环必须跳过 snap.glassRule，回退只在最后一步发生。
     */
    private static void testFindMatchPriority() {
        CtmProperties packRule = new CtmProperties(
            new ResourceLocation("examplemod", "pack_glass.properties"), "ctm", 5);
        packRule.addMatchTile("minecraft:blocks/glass");
        packRule.setTiles("0-3");
        packRule.setTileBased(true);

        CtmProperties builtinGlass = new CtmProperties(
            new ResourceLocation("celeritasctm", "glass.properties"), "ctm", Integer.MIN_VALUE);
        builtinGlass.addMatchTile("minecraft:blocks/glass");
        builtinGlass.setTiles("0-46");
        builtinGlass.setTileBased(true);

        // 故意把内置回退排在索引列表最前面，模拟“顺序错误”的场景
        Map<String, List<CtmProperties>> tileIndex = new LinkedHashMap<>();
        tileIndex.put("glass", new ArrayList<>(Arrays.asList(builtinGlass, packRule)));
        CtmPropertiesLoader.RuleSnapshot snap = new CtmPropertiesLoader.RuleSnapshot(
            Arrays.asList(builtinGlass, packRule), tileIndex,
            Collections.<String, List<CtmProperties>>emptyMap(),
            builtinGlass, Collections.<CtmProperties>emptyList());

        CtmProperties hit = CtmPropertiesLoader.findMatchInSnapshot(
            snap, "minecraft:blocks/glass", null, null, null, null);
        check("pack tile rule wins over builtin glass fallback", hit == packRule);

        // 只有内置回退时仍可用，但只作为最后手段
        Map<String, List<CtmProperties>> builtinOnlyIndex = new LinkedHashMap<>();
        builtinOnlyIndex.put("glass", new ArrayList<>(Collections.singletonList(builtinGlass)));
        CtmPropertiesLoader.RuleSnapshot onlyBuiltin = new CtmPropertiesLoader.RuleSnapshot(
            Collections.singletonList(builtinGlass), builtinOnlyIndex,
            Collections.<String, List<CtmProperties>>emptyMap(),
            builtinGlass, Collections.<CtmProperties>emptyList());
        check("builtin glass used as last-resort fallback",
            CtmPropertiesLoader.findMatchInSnapshot(
                onlyBuiltin, "minecraft:blocks/glass", null, null, null, null) == builtinGlass);

        // 精确命名空间：其它命名空间的 glass 纹理不得被内置回退命中
        check("builtin fallback does not hijack other namespace glass",
            CtmPropertiesLoader.findMatchInSnapshot(
                onlyBuiltin, "othermod:blocks/glass", null, null, null, null) == null);

        // 无纹理名时绝不返回玻璃回退（避免把未知纹理当玻璃）
        check("builtin fallback needs a matching texture",
            CtmPropertiesLoader.findMatchInSnapshot(
                onlyBuiltin, null, null, null, null, null) == null);

        // 空快照/缺快照安全
        check("null snapshot returns null",
            CtmPropertiesLoader.findMatchInSnapshot(
                null, "minecraft:blocks/glass", null, null, null, null) == null);
    }

    // ---------------- metadata ----------------

    /**
     * metadata 限制存在时必须 fail-closed：没有状态就无法判定，不能放宽为全部匹配。
     * 未限制（未写 metadata）时 null 状态仍然可匹配。
     */
    private static void testMetadataNullStateFailClosed() {
        CtmProperties unrestricted = parse("method=ctm\ntiles=0-3\n"
            + "matchBlocks=examplemod:stone_full\n", "mcpatcher/ctm/meta_none.properties");
        check("unrestricted metadata matches null state",
            unrestricted != null && unrestricted.matchesMetadata(null));

        CtmProperties restricted = parse("method=ctm\ntiles=0-3\nmetadata=1 5\n"
            + "matchBlocks=examplemod:stone_full\n", "mcpatcher/ctm/meta_set.properties");
        check("metadata restriction kept", restricted != null && restricted.getMetadata() != null);
        check("restricted metadata does not match null state",
            restricted != null && !restricted.matchesMetadata(null));
    }

    // ---------------- hot path ----------------

    /**
     * 无状态谓词的纯 {@code matchBlocks=ns:name} 规则不得为每个方块状态构建
     * 属性 HashMap：{@link CtmProperties#matchesBlock} 与 {@code findMatch} 都应在
     * 键命中后直接返回。用状态代理统计 {@code getProperties()} 调用次数验证。
     */
    private static void testHotPathNoPropertyMapForPlainBlockRule() {
        CtmProperties plain = parse("method=ctm\ntiles=0-3\nmatchBlocks=examplemod:stone\n",
            "mcpatcher/ctm/plain.properties");
        check("plain block rule parsed", plain != null && plain.hasBlockMatches());

        int[] propertyReads = new int[1];
        IBlockState state = stateProxy("examplemod:stone", Collections.<String, String>emptyMap(), propertyReads);

        check("plain block rule matches state directly", plain.matchesBlock(state));
        check("plain block rule reads no state properties", propertyReads[0] == 0);

        CtmPropertiesLoader.RuleSnapshot snap = blockSnapshot(plain);
        propertyReads[0] = 0;
        check("findMatch hits plain block rule",
            CtmPropertiesLoader.findMatchInSnapshot(snap, null, state, null, null, null) == plain);
        check("findMatch does not build property map for plain rule", propertyReads[0] == 0);

        // 带谓词的规则仍然要读属性表（否则会漏判）
        CtmProperties pred = parse("method=ctm\ntiles=0-3\nmatchBlocks=examplemod:stone:age=7\n",
            "mcpatcher/ctm/pred.properties");
        int[] predReads = new int[1];
        Map<String, String> props = new LinkedHashMap<>();
        props.put("age", "7");
        IBlockState predState = stateProxy("examplemod:stone", props, predReads);
        check("predicate rule matches matching state", pred.matchesBlock(predState));
        check("predicate rule reads properties when needed", predReads[0] > 0);
        check("predicate rule rejects wrong value",
            !pred.matchesBlock(stateProxy("examplemod:stone", singleton("age", "3"), new int[1])));
        check("predicate rule does not match other block",
            !pred.matchesBlock(stateProxy("examplemod:dirt", props, new int[1])));

        // 请求内共享：多个带谓词的候选只构建一次状态属性表
        CtmProperties nonMatch = parse("method=ctm\ntiles=0-3\nmatchBlocks=examplemod:stone:age=3\n",
            "mcpatcher/ctm/pred_a.properties");
        CtmProperties match = parse("method=ctm\ntiles=0-3\nmatchBlocks=examplemod:stone:age=7\n",
            "mcpatcher/ctm/pred_b.properties");
        int[] sharedReads = new int[1];
        IBlockState sharedState = stateProxy("examplemod:stone", props, sharedReads);
        Map<String, List<CtmProperties>> index = new LinkedHashMap<>();
        index.put("examplemod:stone", new ArrayList<>(Arrays.asList(nonMatch, match)));
        CtmPropertiesLoader.RuleSnapshot predicateSnap = new CtmPropertiesLoader.RuleSnapshot(
            Arrays.asList(nonMatch, match), Collections.<String, List<CtmProperties>>emptyMap(),
            index, null, Collections.<CtmProperties>emptyList());
        check("findMatch resolves predicate rule",
            CtmPropertiesLoader.findMatchInSnapshot(predicateSnap, null, sharedState, null, null, null) == match);
        check("state property map built once per request", sharedReads[0] == 1);
    }

    /** 请求内传入的规范化键必须被直接使用（同一键不同字符串写法不应影响结果）。 */
    private static void testHotPathPrecomputedCanonicalKey() {
        CtmProperties plain = parse("method=ctm\ntiles=0-3\nmatchBlocks=examplemod:stone\n",
            "mcpatcher/ctm/key.properties");
        int[] reads = new int[1];
        IBlockState state = stateProxy("examplemod:stone", Collections.<String, String>emptyMap(), reads);
        check("precomputed key matches", plain.matchesBlock(state, "examplemod:stone"));
        check("precomputed key rejects other", !plain.matchesBlock(state, "examplemod:dirt"));
        check("precomputed key path reads no properties", reads[0] == 0);
        check("null precomputed key falls back to state key",
            plain.matchesBlock(state, null) && reads[0] == 0);

        // 无方块约束的规则不受键影响
        CtmProperties tileOnly = new CtmProperties(new ResourceLocation("example", "t.properties"), "ctm", 1);
        tileOnly.addMatchTile("blocks/stone");
        check("unconstrained rule accepts any key", tileOnly.matchesBlock(state, "examplemod:dirt"));
    }

    /** matchesTileClean 必须是 matchesTile 的等价快路径（索引/候选共用已清理名）。 */
    private static void testMatchesTileCleanMatchesMatchesTile() {
        CtmProperties p = new CtmProperties(new ResourceLocation("examplemod", "t.properties"), "ctm", 1);
        p.addMatchTile("minecraft:blocks/stone");
        p.addMatchTile("blocks/dirt");
        String[] probes = {
            "minecraft:blocks/stone", "examplemod:blocks/stone", "blocks/dirt",
            "othermod:blocks/dirt", "minecraft:blocks/stone.png", null
        };
        for (String probe : probes) {
            boolean expected = p.matchesTile(probe);
            boolean actual = p.matchesTileClean(
                CtmProperties.cleanTextureName(probe),
                probe == null ? null : CtmProperties.namespaceOf(probe));
            check("matchesTileClean parity for " + probe, expected == actual);
        }
        check("matchesTileClean namespace-aware reject",
            !p.matchesTileClean("stone", "examplemod"));
        check("matchesTileClean namespace-aware accept",
            p.matchesTileClean("stone", "minecraft"));
    }

    /** 惰性 EnvContext：有限制规则仍然 fail-closed/正确判定，无限制规则不受影响。 */
    private static void testFindMatchStillAppliesEnvironmentRules() {
        CtmProperties limited = parse("method=ctm\ntiles=0-3\nheights=0-63\n"
            + "matchBlocks=examplemod:stone\n", "mcpatcher/ctm/hotheight.properties");
        check("height-limited block rule parsed", limited != null && limited.hasHeightRestriction());
        check("height-limited rule needs environment", limited.needsEnvironment());

        CtmProperties plain = parse("method=ctm\ntiles=0-3\nmatchBlocks=examplemod:stone\n",
            "mcpatcher/ctm/hotplain.properties");
        check("plain block rule needs no environment", plain != null && !plain.needsEnvironment());

        IBlockState state = stateProxy("examplemod:stone", Collections.<String, String>emptyMap(), new int[1]);
        net.minecraft.util.math.BlockPos inside = new net.minecraft.util.math.BlockPos(0, 10, 0);
        net.minecraft.util.math.BlockPos outside = new net.minecraft.util.math.BlockPos(0, 200, 0);

        check("height-limited rule matches inside range",
            CtmPropertiesLoader.findMatchInSnapshot(blockSnapshot(limited), null, state, null, null, inside)
                == limited);
        check("height-limited rule fails closed outside range",
            CtmPropertiesLoader.findMatchInSnapshot(blockSnapshot(limited), null, state, null, null, outside)
                == null);
        check("height-limited rule fails closed without position",
            CtmPropertiesLoader.findMatchInSnapshot(blockSnapshot(limited), null, state, null, null, null)
                == null);
    }

    private static Map<String, String> singleton(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static CtmPropertiesLoader.RuleSnapshot blockSnapshot(CtmProperties rule) {
        Map<String, List<CtmProperties>> blockIndex = new LinkedHashMap<>();
        for (CtmRuleSupport.BlockMatch m : rule.getBlockMatches()) {
            if (m.getKey() != null && !m.isNumeric()) {
                blockIndex.computeIfAbsent(m.getKey(), k -> new ArrayList<>()).add(rule);
            }
        }
        return new CtmPropertiesLoader.RuleSnapshot(
            Collections.singletonList(rule),
            Collections.<String, List<CtmProperties>>emptyMap(),
            blockIndex, null, Collections.<CtmProperties>emptyList());
    }

    /** 计数 getProperties() 调用的 IBlockState 代理；方块注册名固定。 */
    private static IBlockState stateProxy(final String registryName, final Map<String, String> properties,
                                          final int[] propertyReads) {
        final Block block = fakeBlock(registryName);
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBlock": return block;
                case "getProperties":
                case "getPropertiesMap":
                    propertyReads[0]++;
                    return fakeProperties(properties);
                case "toString": return "teststate@" + registryName;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: return null;
            }
        };
        return (IBlockState) Proxy.newProxyInstance(
            CtmGenericRuleTest.class.getClassLoader(), new Class<?>[] { IBlockState.class }, h);
    }

    /**
     * 构造 IProperty 键的不可变属性表（与真实 {@code IBlockState#getProperties()}
     * 的返回类型一致：{@code ImmutableMap}）。
     */
    private static com.google.common.collect.ImmutableMap<net.minecraft.block.properties.IProperty<?>, Comparable<?>>
            fakeProperties(Map<String, String> properties) {
        com.google.common.collect.ImmutableMap.Builder<net.minecraft.block.properties.IProperty<?>, Comparable<?>>
            out = com.google.common.collect.ImmutableMap.builder();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            final String name = e.getKey();
            final String value = e.getValue();
            net.minecraft.block.properties.IProperty<?> key =
                (net.minecraft.block.properties.IProperty<?>) Proxy.newProxyInstance(
                    CtmGenericRuleTest.class.getClassLoader(),
                    new Class<?>[] { net.minecraft.block.properties.IProperty.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getName": return name;
                            case "toString": return name;
                            case "hashCode": return name.hashCode();
                            case "equals": return proxy == args[0];
                            default: return null;
                        }
                    });
            out.put(key, value);
        }
        return out.build();
    }

    /**
     * 不触发 Block 构造器副作用（SoundType/SoundEvents 静态初始化）地创建 Block：
     * 需要的是 getRegistryName 的虚分派，不需要构造器。
     */
    private static Block fakeBlock(String registryName) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            NamedBlock block = (NamedBlock) allocate.invoke(unsafe, NamedBlock.class);
            // getRegistryName()/setRegistryName() 在 Forge 的 IForgeRegistryEntry.Impl 中是 final，
            // 且 setRegistryName 会走 GameData 前缀校验（脱离游戏进程不可用）。
            // getRegistryName() 优先读 delegate.name()，因此这里注入一个只回答名字的代理。
            final ResourceLocation name = new ResourceLocation(registryName);
            Object delegate = Proxy.newProxyInstance(
                CtmGenericRuleTest.class.getClassLoader(),
                new Class<?>[] { net.minecraftforge.registries.IRegistryDelegate.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "name": return name;
                        case "get": return block;
                        case "type": return Block.class;
                        case "toString": return "delegate@" + name;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return null;
                    }
                });
            java.lang.reflect.Field delegateField = Class.forName(
                "net.minecraftforge.registries.IForgeRegistryEntry$Impl").getDeclaredField("delegate");
            delegateField.setAccessible(true);
            delegateField.set(block, delegate);
            return block;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate fake block", e);
        }
    }

    /**
     * 只用于提供注册名的假方块。构造器永远不会被调用（实例经 Unsafe 分配），
     * 但 Java 要求子类构造器显式调用父类构造器。
     */
    public static class NamedBlock extends Block {
        public NamedBlock() {
            super(Material.ROCK);
        }
    }

    // ---------------- harness ----------------

    private static void check(String name, boolean condition) {
        if (condition) passed++;
        else failures.add(name);
    }
}
