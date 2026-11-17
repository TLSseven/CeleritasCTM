package team.celeritasctm.client.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CtmRuleSupport 的直接行为测试（无 JUnit、无反射、不读源码文本）。
 *
 * <p>Run with {@code ./gradlew testCtmRuleSupport} or compile/run this class directly.
 *
 * <p>覆盖 A08–A12：
 * <ul>
 *   <li>A08 优先级方向：{@link CtmRuleSupport#userPackPriority} 单调递增，
 *       {@link CtmRuleSupport#modPackPriority} 恒低于用户包</li>
 *   <li>A09 matchBlocks 归一化：数字 ID、短名、命名空间、Conquest 谓词</li>
 *   <li>A11 sides 取代默认 faces、与 faces 并集</li>
 *   <li>A12 metadata 范围/越界、connect 合法性</li>
 * </ul>
 */
public final class CtmRuleSupportTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        testNumericBlockId();
        testShortAndNamespacedName();
        testConquestPredicate();
        testPredicateMatching();
        testIndexAndQueryUseSameKey();
        testInvalidTokens();
        testSidesAloneReplacesDefaultFaces();
        testFacesAndSidesUnion();
        testFacesAllSemantics();
        testMetadata();
        testConnectValidation();
        testPackPriorityDirection();
        testEnabledStackPriorityMapping();
        testZeroEnabledPackSelection();
        testRepositoryNotReadySelection();
        testDedupeReplacementRule();
        testEffectivePackOverrideDetection();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("FAIL: " + f);
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ---------------- A09 ----------------

    private static void testNumericBlockId() {
        CtmRuleSupport.BlockMatch m = CtmRuleSupport.parseMatchToken("171");
        check("numeric id parsed", m != null && m.isNumeric() && m.getNumericId() == 171);
        check("numeric id has no predicate", m != null && m.getProperties().isEmpty());

        // 解析结果可键化（模拟 Block.getBlockById(171) -> minecraft:carpet）
        check("numeric id resolves to registry key",
            m != null && "minecraft:carpet".equals(m.resolvedTo("minecraft:carpet").getKey()));
    }

    private static void testShortAndNamespacedName() {
        CtmRuleSupport.BlockMatch shortName = CtmRuleSupport.parseMatchToken("carpet");
        check("short name gets minecraft namespace",
            shortName != null && "minecraft:carpet".equals(shortName.getKey()));

        CtmRuleSupport.BlockMatch fullName = CtmRuleSupport.parseMatchToken("conquest:stone_stairs_3");
        check("namespaced name kept",
            fullName != null && "conquest:stone_stairs_3".equals(fullName.getKey()));
        check("namespaced name has no predicate",
            fullName != null && fullName.getProperties().isEmpty());
    }

    private static void testConquestPredicate() {
        // 实例证据：Conquest 包 leaves_cherry_a.properties:7
        CtmRuleSupport.BlockMatch m = CtmRuleSupport.parseMatchToken("conquest:Leaves_cherry:age=7");
        check("predicate key normalized to lowercase",
            m != null && "conquest:leaves_cherry".equals(m.getKey()));
        check("predicate parsed",
            m != null && "7".equals(m.getProperties().get("age")));

        // 无命名空间 + 谓词
        CtmRuleSupport.BlockMatch noNs = CtmRuleSupport.parseMatchToken("stone:variant=granite");
        check("no-namespace predicate gets minecraft prefix",
            noNs != null && "minecraft:stone".equals(noNs.getKey())
                && "granite".equals(noNs.getProperties().get("variant")));
    }

    private static void testPredicateMatching() {
        CtmRuleSupport.BlockMatch m = CtmRuleSupport.parseMatchToken("conquest:Leaves_cherry:age=7");
        Map<String, String> age7 = new LinkedHashMap<>();
        age7.put("age", "7");
        Map<String, String> age3 = new LinkedHashMap<>();
        age3.put("age", "3");
        check("predicate satisfied", m != null && m.matchesProperties(age7));
        check("predicate rejected on mismatch", m != null && !m.matchesProperties(age3));
        check("predicate rejected when property absent", m != null && !m.matchesProperties(null));

        CtmRuleSupport.BlockMatch plain = CtmRuleSupport.parseMatchToken("carpet");
        check("empty predicate matches anything", plain != null && plain.matchesProperties(null));
    }

    private static void testIndexAndQueryUseSameKey() {
        // 索引端与查询端必须得到同一个键（旧实现：索引拼 minecraft:、查询比短名）
        check("index/query key agree on short name",
            "minecraft:carpet".equals(CtmRuleSupport.canonicalBlockKey("minecraft:carpet"))
                && "minecraft:carpet".equals(CtmRuleSupport.canonicalBlockKey("carpet")));
        check("key is case-insensitive",
            "conquest:leaves_cherry".equals(CtmRuleSupport.canonicalBlockKey("Conquest:Leaves_cherry")));
        check("key strips predicate tail",
            "conquest:leaves_cherry".equals(
                CtmRuleSupport.canonicalBlockKey("conquest:Leaves_cherry:age=7")));
        check("empty namespace defaults to minecraft",
            "minecraft:stone".equals(CtmRuleSupport.canonicalBlockKey(":stone")));
        check("null key for blank input", CtmRuleSupport.canonicalBlockKey("  ") == null);
    }

    private static void testInvalidTokens() {
        check("out-of-range id rejected", CtmRuleSupport.parseMatchToken("4096") == null);
        check("bad predicate rejected", CtmRuleSupport.parseMatchToken("conquest:foo:age=") == null);
        check("empty token rejected", CtmRuleSupport.parseMatchToken("   ") == null);
        check("null token rejected", CtmRuleSupport.parseMatchToken(null) == null);
    }

    // ---------------- A11 ----------------

    private static void testSidesAloneReplacesDefaultFaces() {
        // 只有 sides 时，旧实现退化成 faces=all；现在 sides 生效
        Set<String> faces = CtmRuleSupport.resolveFaces(null, "top bottom");
        check("sides alone restricts to up/down",
            faces.size() == 2 && faces.contains("up") && faces.contains("down"));
        check("sides alone does not include sides",
            !faces.contains("north") && !faces.contains("east"));

        // sides 简写
        Set<String> sides = CtmRuleSupport.resolveFaces(null, "sides");
        check("sides shorthand expands to four sides",
            sides.size() == 4 && sides.contains("north") && sides.contains("south")
                && sides.contains("east") && sides.contains("west"));
    }

    private static void testFacesAndSidesUnion() {
        Set<String> faces = CtmRuleSupport.resolveFaces("top", "north");
        check("faces+sides union",
            faces.size() == 2 && faces.contains("up") && faces.contains("north"));
    }

    private static void testFacesAllSemantics() {
        check("faces=all means unrestricted (empty set)",
            CtmRuleSupport.resolveFaces("all", null).isEmpty());
        check("no faces and no sides means unrestricted",
            CtmRuleSupport.resolveFaces(null, null).isEmpty());
        check("faces=all with sides=top keeps sides restriction",
            CtmRuleSupport.resolveFaces("all", "top").contains("up"));
    }

    // ---------------- A12 ----------------

    private static void testMetadata() {
        int[] single = CtmRuleSupport.parseMetadata("7");
        check("single metadata", single != null && single.length == 1 && single[0] == 7);

        int[] range = CtmRuleSupport.parseMetadata("0-3");
        check("metadata range", range != null && Arrays.equals(range, new int[]{0, 1, 2, 3}));

        int[] mixed = CtmRuleSupport.parseMetadata("0 2-4 8");
        check("mixed metadata", mixed != null && Arrays.equals(mixed, new int[]{0, 2, 3, 4, 8}));

        check("bad metadata rejected (fail-closed)", CtmRuleSupport.parseMetadata("abc") == null);
        check("out-of-range metadata rejected", CtmRuleSupport.parseMetadata("32") == null);
        check("range beyond 31 rejected", CtmRuleSupport.parseMetadata("25-40") == null);
        check("'-1' means unrestricted (null)", CtmRuleSupport.parseMetadata("-1") == null);

        // 坏 metadata 必须让规则被跳过，而不是放宽为全匹配
        // （加载器 parseProperties 在该情形返回 null；此处校验判定本身）
        CtmRuleSupport.setWarnSink(msg -> { });
    }

    private static void testConnectValidation() {
        check("connect=block valid", CtmRuleSupport.isValidConnect("block"));
        check("connect=tile valid", CtmRuleSupport.isValidConnect("tile"));
        check("connect=material valid", CtmRuleSupport.isValidConnect("material"));
        check("connect=state valid", CtmRuleSupport.isValidConnect("state"));
        check("connect empty valid (default)", CtmRuleSupport.isValidConnect(""));
        check("connect null valid (default)", CtmRuleSupport.isValidConnect(null));
        check("unknown connect rejected", !CtmRuleSupport.isValidConnect("blocks"));
    }

    // ---------------- A08 ----------------

    private static void testPackPriorityDirection() {
        // getRepositoryEntries() 索引越大越优先（FallbackResourceManager 末尾优先）
        check("later enabled index wins",
            CtmRuleSupport.userPackPriority(1) > CtmRuleSupport.userPackPriority(0));
        check("third pack beats second",
            CtmRuleSupport.userPackPriority(2) > CtmRuleSupport.userPackPriority(1));

        // mod 自带资源排在 defaultResourcePacks（用户包之前），因此优先级低于任何用户包
        check("mod pack lower than first user pack",
            CtmRuleSupport.modPackPriority(0) < CtmRuleSupport.userPackPriority(0));
        check("more mod packs do not outrank user packs",
            CtmRuleSupport.modPackPriority(50) < CtmRuleSupport.userPackPriority(0));
        check("mod pack priority strictly decreasing",
            CtmRuleSupport.modPackPriority(1) < CtmRuleSupport.modPackPriority(0));
    }

    // ---------------- A08/A10 选择与去重 ----------------

    private static void testEnabledStackPriorityMapping() {
        List<CtmRuleSupport.PackSourceInfo> stack = CtmRuleSupport.priorityForEnabledStack(
            Arrays.asList("LowPack.zip", "HighPack.zip"));
        check("stack mapping keeps both packs", stack.size() == 2);
        check("later pack in stack has higher priority",
            stack.get(1).priority > stack.get(0).priority);
        check("stack preserves pack names",
            "LowPack.zip".equals(stack.get(0).name) && "HighPack.zip".equals(stack.get(1).name));

        // 反向启用顺序：生效来源必须反转（A08 的反向启用顺序测试）
        List<CtmRuleSupport.PackSourceInfo> reversed = CtmRuleSupport.priorityForEnabledStack(
            Arrays.asList("HighPack.zip", "LowPack.zip"));
        check("reversing order reverses which pack wins",
            reversed.get(1).priority > reversed.get(0).priority
                && "LowPack.zip".equals(reversed.get(1).name));

        check("empty stack maps to empty list",
            CtmRuleSupport.priorityForEnabledStack(new ArrayList<String>()).isEmpty());
        check("null stack maps to empty list",
            CtmRuleSupport.priorityForEnabledStack(null).isEmpty());
    }

    private static void testZeroEnabledPackSelection() {
        // 仓库就绪 + 零启用：不扫描任何 resourcepacks 文件（A10）
        check("zero enabled never scans a pack file",
            !CtmRuleSupport.shouldScanPackFile(true, new HashSet<String>(), "SomePack.zip"));
        check("zero enabled with null set never scans",
            !CtmRuleSupport.shouldScanPackFile(true, null, "SomePack.zip"));

        // 启用一个包：只扫它
        Set<String> enabled = new HashSet<>(Arrays.asList("SomePack.zip"));
        check("enabled pack is scanned",
            CtmRuleSupport.shouldScanPackFile(true, enabled, "SomePack.zip"));
        check("other pack is skipped",
            !CtmRuleSupport.shouldScanPackFile(true, enabled, "OtherPack.zip"));
    }

    private static void testRepositoryNotReadySelection() {
        // 仓库未就绪才退化为扫描目录
        check("not-ready repository falls back to scanning",
            CtmRuleSupport.shouldScanPackFile(false, new HashSet<String>(), "AnyPack.zip"));
        check("not-ready repository scans even with null set",
            CtmRuleSupport.shouldScanPackFile(false, null, "AnyPack.zip"));
    }

    private static void testDedupeReplacementRule() {
        // 同 resourceId：仅当新来源优先级严格更高才替换
        check("higher priority replaces", CtmRuleSupport.shouldReplace(0, 1));
        check("equal priority keeps first", !CtmRuleSupport.shouldReplace(1, 1));
        check("lower priority does not replace", !CtmRuleSupport.shouldReplace(2, 1));
        // mod 包（负优先级）永远压不过用户包
        check("mod pack never replaces user pack",
            !CtmRuleSupport.shouldReplace(CtmRuleSupport.userPackPriority(0),
                CtmRuleSupport.modPackPriority(0)));
    }

    private static void testEffectivePackOverrideDetection() {
        Set<String> candidates = new HashSet<>(Arrays.asList("PackA.zip", "PackB.zip"));
        // 生效来源是本包 → 不跳过
        check("same pack is not treated as override",
            !CtmRuleSupport.isOverriddenByAnotherKnownPack("PackA.zip", "PackA.zip", candidates));
        // 生效来源是另一个已知候选包 → 跳过
        check("different known pack is an override",
            CtmRuleSupport.isOverriddenByAnotherKnownPack("PackA.zip", "PackB.zip", candidates));
        // 生效来源未知 / 查询失败 → 不跳过（避免因包名映射差异误丢规则）
        check("unknown effective source never skips",
            !CtmRuleSupport.isOverriddenByAnotherKnownPack(null, "PackB.zip", candidates));
        // 生效名不属于候选集合（如 "Default"）→ 不跳过
        check("non-candidate effective source never skips",
            !CtmRuleSupport.isOverriddenByAnotherKnownPack("Default", "PackB.zip", candidates));
    }

    // ---------------- harness ----------------
    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(name);
        }
    }
}
