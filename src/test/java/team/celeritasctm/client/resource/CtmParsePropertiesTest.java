package team.celeritasctm.client.resource;

import net.minecraft.util.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 直接调用 {@link CtmPropertiesLoader#parseProperties} 的行为测试（A09/A11/A12）。
 *
 * <p>不测数字 ID 解析分支：{@code Block.getBlockById} 需要已初始化的方块注册表，
 * 脱离游戏进程无法提供；该分支的归一化逻辑已由 {@link CtmRuleSupportTest} 覆盖。
 *
 * <p>运行需要 Minecraft 类在 classpath（{@code ResourceLocation}）；由 Gradle 的
 * {@code testCtmPropertiesParsing} 任务运行。
 */
public final class CtmParsePropertiesTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        testConquestPredicateRule();
        testSidesAloneGivesUpDown();
        testFacesAndSidesUnionInRule();
        testBadMetadataSkipsRule();
        testBadConnectSkipsRule();
        testGoodConnectKept();
        testOutOfRangeMetadataSkips();
        testInvalidMatchBlockTokenWarnsAndSkips();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("FAIL: " + f);
        if (!failures.isEmpty()) System.exit(1);
    }

    private static CtmProperties parse(String text, String path) {
        InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        return CtmPropertiesLoader.parseProperties(in, new ResourceLocation("conquest", path), 3);
    }

    private static void testConquestPredicateRule() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\n"
            + "matchBlocks=conquest:Leaves_cherry:age=7\n", "mcpatcher/ctm/leaves_cherry_a.properties");
        check("predicate rule parsed", p != null);
        check("predicate rule keeps one block match", p != null && p.getBlockMatches().size() == 1);
        check("predicate rule key normalized", p != null
            && "conquest:leaves_cherry".equals(p.getBlockMatches().get(0).getKey()));
        check("predicate rule keeps age=7", p != null
            && "7".equals(p.getBlockMatches().get(0).getProperties().get("age")));
    }

    private static void testSidesAloneGivesUpDown() {
        // A11：只有 sides 时必须取代默认 faces=all
        CtmProperties p = parse("method=ctm\ntiles=0-3\nsides=top bottom\n"
            + "matchBlocks=conquest:stone_full_1\n", "mcpatcher/ctm/sides.properties");
        check("sides-only rule parsed", p != null);
        check("sides-only restricts to up/down", p != null && p.getFaces().size() == 2
            && p.getFaces().contains("up") && p.getFaces().contains("down"));
        check("sides-only excludes north", p != null && !p.getFaces().contains("north"));
    }

    private static void testFacesAndSidesUnionInRule() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nfaces=top\nsides=north\n"
            + "matchBlocks=conquest:stone_full_1\n", "mcpatcher/ctm/union.properties");
        check("faces+sides union in rule", p != null && p.getFaces().size() == 2
            && p.getFaces().contains("up") && p.getFaces().contains("north"));
    }

    private static void testBadMetadataSkipsRule() {
        // A12：坏 metadata 必须跳过整条规则（fail-closed），不能放宽为全匹配
        CtmProperties p = parse("method=ctm\ntiles=0-3\nmetadata=abc\n"
            + "matchBlocks=conquest:stone_full_1\n", "mcpatcher/ctm/badmeta.properties");
        check("bad metadata rule skipped", p == null);
    }

    private static void testOutOfRangeMetadataSkips() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nmetadata=99\n"
            + "matchBlocks=conquest:stone_full_1\n", "mcpatcher/ctm/metarange.properties");
        check("out-of-range metadata rule skipped", p == null);
    }

    private static void testBadConnectSkipsRule() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nconnect=blocks\n"
            + "matchBlocks=conquest:stone_full_1\n", "mcpatcher/ctm/badconnect.properties");
        check("unknown connect rule skipped", p == null);
    }

    private static void testGoodConnectKept() {
        CtmProperties p = parse("method=ctm\ntiles=0-3\nconnect=material\n"
            + "matchBlocks=conquest:stone_full_1\n", "mcpatcher/ctm/goodconnect.properties");
        check("valid connect kept", p != null && "material".equals(p.getConnect()));
    }

    private static void testInvalidMatchBlockTokenWarnsAndSkips() {
        // 谓词缺值：该 token 无效；只剩无效 token 且无 matchTiles → 整条规则跳过
        CtmProperties p = parse("method=ctm\ntiles=0-3\n"
            + "matchBlocks=conquest:foo:age=\n", "mcpatcher/ctm/badtoken.properties");
        check("rule with only invalid token skipped", p == null);

        // 一个有效 + 一个无效：保留有效条目
        CtmProperties mixed = parse("method=ctm\ntiles=0-3\n"
            + "matchBlocks=conquest:foo:age= conquest:stone_full_1\n", "mcpatcher/ctm/mixed.properties");
        check("mixed tokens keep the valid one",
            mixed != null && mixed.getBlockMatches().size() == 1
                && "conquest:stone_full_1".equals(mixed.getBlockMatches().get(0).getKey()));
    }

    private static void check(String name, boolean condition) {
        if (condition) passed++;
        else failures.add(name);
    }
}
