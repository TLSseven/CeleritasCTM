package team.celeritasctm.client.model;

/**
 * {@link InternalFaceCulling} 的可执行单测（无第三方依赖，直接 {@code java} 运行）。
 *
 * <p>Run with {@code ./gradlew testInternalFaceCulling} or compile/run this class directly.
 */
public final class InternalFaceCullingTest {

    private static int passed = 0;
    private static int failed = 0;

    private InternalFaceCullingTest() {
    }

    public static void main(String[] args) {
        connectKindIsParsed();
        fullBlockEastFaceIsCulled();
        incompleteNeighborFaceIsKept();
        completeTransparentNeighborFaceIsCulled();
        completeStairFaceIsCulled();
        partialCoverageIsKept();
        wrongPlaneIsKept();
        nonPlanarQuadIsKept();
        stateSemantics();
        materialSemantics();
        tileSemantics();
        unknownConnectIsConservative();
        zeroWidthNeighborIsKept();
        toleranceBoundaries();
        scaleNormalization();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            throw new AssertionError(failed + " test(s) failed");
        }
    }

    // ------------------------------------------------------------------ cases

    private static void connectKindIsParsed() {
        check("connect=block", InternalFaceCulling.connectKind("block")
            == InternalFaceCulling.ConnectKind.BLOCK);
        check("connect=tile", InternalFaceCulling.connectKind("tile")
            == InternalFaceCulling.ConnectKind.TILE);
        check("connect=material", InternalFaceCulling.connectKind("material")
            == InternalFaceCulling.ConnectKind.MATERIAL);
        check("connect=state", InternalFaceCulling.connectKind("state")
            == InternalFaceCulling.ConnectKind.STATE);
        check("connect=null → unknown", InternalFaceCulling.connectKind(null)
            == InternalFaceCulling.ConnectKind.UNKNOWN);
        check("connect=bogus → unknown", InternalFaceCulling.connectKind("bogus")
            == InternalFaceCulling.ConnectKind.UNKNOWN);
    }

    /**
     * 基准正例：满方块 EAST 面（x=1）与正东满方块相邻 → 应剔除。
     */
    private static void fullBlockEastFaceIsCulled() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("满方块东面剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** 即使 bbox 是整格，邻居朝向面的实际形状不完整时也必须保留 quad。 */
    private static void incompleteNeighborFaceIsKept() {
        float[] qMin = { 1f, 0.5f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("整格 bbox 不足以证明邻居面完整", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.MATERIAL, false, false, true, false, false));
    }

    /** 透明块无需 isFullCube=true；完整朝向面仍可用于剔除。 */
    private static void completeTransparentNeighborFaceIsCulled() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("透明邻居的完整面仍可剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true, true));
    }

    /** 楼梯可能有完整的朝向面；不能因为不是 full cube 而一概禁用剔除。 */
    private static void completeStairFaceIsCulled() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("楼梯完整朝向面覆盖时可剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.MATERIAL, false, false, true, false, true));
    }

    /**
     * A13 反例：竖半砖只在 z∈[0,0.5] 覆盖，quad 是整面 z∈[0,1] → 必须保留，
     * 否则未被覆盖的一半会露出空洞。
     */
    private static void partialCoverageIsKept() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 0.5f };
        check("半覆盖不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));

        // 覆盖偏移（z∈[0.5,1]）同样不足
        float[] nMin2 = { 0f, 0f, 0.5f };
        float[] nMax2 = { 1f, 1f, 1f };
        check("偏半覆盖不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin2, nMax2,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));

        // 但 quad 本身也只有 z∈[0,0.5] 时，邻居完整覆盖它 → 剔除
        float[] qMin3 = { 1f, 0f, 0f };
        float[] qMax3 = { 1f, 1f, 0.5f };
        check("半面被完整覆盖则剔除", InternalFaceCulling.shouldCull(
            qMin3, qMax3, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** 平面不贴合邻居内侧边（邻居退后 0.25 格）→ 不是内部面。 */
    private static void wrongPlaneIsKept() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0.25f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("平面不贴合不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** 非平面 quad（斜面/十字花纹）不参与剔除。 */
    private static void nonPlanarQuadIsKept() {
        float[] qMin = { 0.9f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("非平面不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** connect=state：不同 state 即使同 block、几何完全贴合也不剔除。 */
    private static void stateSemantics() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };

        check("state 同 state 剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.STATE, true, true, true, true));
        check("state 异 state 不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.STATE, true, false, true, true));
        check("state 异 block 不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.STATE, false, false, true, true));
    }

    /** connect=material：同材质即可，几何覆盖和邻居面形状仍须分别成立。 */
    private static void materialSemantics() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 0.5f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("material 同材质异 block 剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.MATERIAL, false, false, true, false));
        check("material 异材质不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.MATERIAL, false, false, false, false));
    }

    /** connect=tile：只看纹理匹配证据。 */
    private static void tileSemantics() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("tile 纹理相同剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.TILE, true, true, true, true));
        check("tile 纹理不同不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.TILE, true, true, true, false));
    }

    /** 未知 connect：保守要求同 block 且同 state。 */
    private static void unknownConnectIsConservative() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("unknown 同 state 剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.UNKNOWN, true, true, true, true));
        check("unknown 异 state 不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.UNKNOWN, true, false, true, true));
    }

    /** 邻居在该轴上厚度为 0（贴面平板）→ 不是实体内部面。 */
    private static void zeroWidthNeighborIsKept() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 3f / 16f, 0f, 0f };
        float[] nMax = { 3f / 16f, 1f, 1f };
        // 平面 1.0 与内侧边 1+3/16 不贴合
        check("零厚度邻居不剔除", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** 容差边界：刚好在容差内剔除，超出容差保留。 */
    private static void toleranceBoundaries() {
        float[] qMin = { 1f, 0f, 0f };
        float[] qMax = { 1f, 1f, 1f };
        float[] nMin = { 0.005f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("误差 0.005 在容差内", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true, 0.01f));

        float[] nMin2 = { 0.02f, 0f, 0f };
        check("误差 0.02 超出容差", !InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin2, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true, 0.01f));

        // 覆盖不足 0.005 → 容差内仍视为覆盖；0.02 → 拒绝
        float[] qShort = { 1f, 0f, 0f };
        float[] qShortMax = { 1f, 1f, 1f };
        float[] nShort = { 0f, 0f, 0f };
        float[] nShortMax = { 1f, 1f, 0.995f };
        check("覆盖缺 0.005 仍剔除", InternalFaceCulling.shouldCull(
            qShort, qShortMax, 0, true, nShort, nShortMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true, 0.01f));

        float[] nShortMax2 = { 1f, 1f, 0.98f };
        check("覆盖缺 0.02 不剔除", !InternalFaceCulling.shouldCull(
            qShort, qShortMax, 0, true, nShort, nShortMax2,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true, 0.01f));
    }

    /** 0..16 与 0..1 坐标都要支持（Conquest 自定义模型用 0..16）。 */
    private static void scaleNormalization() {
        check("0..1 不缩放", InternalFaceCulling.normalizeScale(1.0f) == 1.0f);
        check("0..16 缩放 1/16", Math.abs(InternalFaceCulling.normalizeScale(16.0f) - 1f / 16f) < 1e-6f);
        check("0.5 不缩放", InternalFaceCulling.normalizeScale(0.5f) == 1.0f);
        check("坐标归一化 16→1",
            Math.abs(InternalFaceCulling.normalizeCoordinate(16.0f) - 1.0f) < 1e-6f);
        check("坐标归一化 0.5 不变",
            Math.abs(InternalFaceCulling.normalizeCoordinate(0.5f) - 0.5f) < 1e-6f);

        // 用 0..16 输入归一化后应得到与 0..1 相同的剔除结论
        float[] qMin = { 16f, 0f, 0f };
        float[] qMax = { 16f, 16f, 16f };
        for (int a = 0; a < 3; a++) {
            qMin[a] *= 1f / 16f;
            qMax[a] *= 1f / 16f;
        }
        float[] nMin = { 0f, 0f, 0f };
        float[] nMax = { 1f, 1f, 1f };
        check("归一化后剔除", InternalFaceCulling.shouldCull(
            qMin, qMax, 0, true, nMin, nMax,
            InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    // ------------------------------------------------------------------ util

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
