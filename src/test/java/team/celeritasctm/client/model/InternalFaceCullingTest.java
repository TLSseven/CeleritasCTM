package team.celeritasctm.client.model;

import team.celeritasctm.client.model.QuadCoverage.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link InternalFaceCulling} / {@link QuadCoverage} 的可执行单测
 * （无第三方依赖，直接 {@code java} 运行）。
 *
 * <p>所有几何用例都从<b>真实顶点 int[] 数据</b>出发，经由生产提取路径
 * （{@link QuadCoverage#rect} / {@link QuadCoverage#bounds} /
 * {@link QuadCoverage#axisNormalSign}）得到矩形，再交给生产判定
 * {@link InternalFaceCulling#shouldCull}。不使用「手工传入 solid 布尔值」的替代证据。
 *
 * <p>Run with {@code ./gradlew testInternalFaceCulling} or compile/run this class directly.
 */
public final class InternalFaceCullingTest {

    /** 位置在顶点数据前 3 个 int（float xyz），每顶点 7 个 int（模拟 BLOCK 布局）。 */
    private static final int STRIDE = 7;
    private static final int POS = 0;
    private static final float EPS = QuadCoverage.GEOMETRY_EPSILON;

    private static int passed = 0;
    private static int failed = 0;

    private InternalFaceCullingTest() {
    }

    public static void main(String[] args) {
        connectKindIsParsed();
        fullBlockAllSixDirectionsCulled();
        neighborPartialCoverageIsKept();
        verticalSlabCoverage();
        stairLShapeUnion();
        splitFaceUnionAndGap();
        diagonalAndBowTieRejected();
        nonPlanarAndNonRectangularQuadsKept();
        degnerateAndBadDataKept();
        thinSlitIsKept();
        windingNormalSignFiltering();
        differingStatesWithStateConnect();
        materialAndBlockSemantics();
        tileRequiresRealTextureEvidence();
        exceptionFallbackIsRetained();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            throw new AssertionError("failed=" + failed);
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
        check("connect=null -> unknown", InternalFaceCulling.connectKind(null)
            == InternalFaceCulling.ConnectKind.UNKNOWN);
        check("connect=bogus -> unknown", InternalFaceCulling.connectKind("bogus")
            == InternalFaceCulling.ConnectKind.UNKNOWN);
    }

    /** 满方块六个方向的内部面都应被剔除（覆盖与方向无关）。 */
    private static void fullBlockAllSixDirectionsCulled() {
        for (int axis = 0; axis < 3; axis++) {
            for (int dir = 0; dir < 2; dir++) {
                boolean positive = dir == 1;
                float plane = positive ? 1f : 0f;
                int[] targetData = rectQuad(axis, plane, 0f, 1f, 0f, 1f);
                Rect target = QuadCoverage.rect(targetData, STRIDE, POS, axis, plane, EPS);
                // 邻居朝向本方块的面：正方向邻居是它的 0 面，负方向邻居是它的 1 面
                float neighborPlane = positive ? 0f : 1f;
                int[] neighborData = rectQuad(axis, neighborPlane, 0f, 1f, 0f, 1f);
                Rect neighbor = QuadCoverage.rect(neighborData, STRIDE, POS, axis, neighborPlane, EPS);
                check("full block axis=" + axis + " positive=" + positive + " culled",
                    cull(target, neighbor, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
            }
        }
    }

    /** 目标整面 vs 邻居只有一半（半砖）→ 必须保留，否则露洞。 */
    private static void neighborPartialCoverageIsKept() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        Rect halfLow = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS);
        Rect halfHigh = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0.5f, 1f), STRIDE, POS, axis, 0f, EPS);
        check("half slab (low) does not cull full face",
            !cull(target, halfLow, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
        check("half slab (high) does not cull full face",
            !cull(target, halfHigh, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));

        // 目标本身只有 z 0..0.5 且被完整覆盖 → 剔除
        Rect halfTarget = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 1f, EPS);
        check("half target fully covered -> culled",
            cull(halfTarget, halfLow, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** 竖半砖：邻居在 z 方向只占半格 → 完整面保留，半面对应部分可剔。 */
    private static void verticalSlabCoverage() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        Rect verticalSlab = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS);
        check("vertical slab partial coverage kept",
            !cull(target, verticalSlab, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
        Rect matching = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 1f, EPS);
        check("vertical slab matching half culled",
            cull(matching, verticalSlab, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
    }

    /** 楼梯 L 形面：两块矩形并集仍缺一角 → 完整目标面必须保留。 */
    private static void stairLShapeUnion() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        List<Rect> lShape = new ArrayList<>();
        // 下半格整条 + 上半格前半条 = L 形（缺右上角）
        lShape.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 0.5f, 0f, 1f), STRIDE, POS, axis, 0f, EPS));
        lShape.add(QuadCoverage.rect(rectQuad(axis, 0f, 0.5f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS));
        check("stair L-shape does not cull full face",
            !InternalFaceCulling.shouldCull(target, lShape, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));

        // 补齐缺角后（楼梯上层实际完整）应剔除
        List<Rect> complete = new ArrayList<>(lShape);
        complete.add(QuadCoverage.rect(rectQuad(axis, 0f, 0.5f, 1f, 0.5f, 1f), STRIDE, POS, axis, 0f, EPS));
        check("stair full side coverage culled",
            InternalFaceCulling.shouldCull(target, complete, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));
    }

    /** 一个面被拆成两个 quad（左右两半）：并集覆盖 → 剔除；中间有缝 → 保留。 */
    private static void splitFaceUnionAndGap() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        List<Rect> split = new ArrayList<>();
        split.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS));
        split.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0.5f, 1f), STRIDE, POS, axis, 0f, EPS));
        check("split face union culled",
            InternalFaceCulling.shouldCull(target, split, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));

        List<Rect> gapped = new ArrayList<>();
        gapped.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.4f), STRIDE, POS, axis, 0f, EPS));
        gapped.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0.6f, 1f), STRIDE, POS, axis, 0f, EPS));
        check("split face with gap kept",
            !InternalFaceCulling.shouldCull(target, gapped, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));
    }

    /** 对角/蝶形顶点顺序必须被拒绝（角点集合正确也不代表三角形覆盖矩形）。 */
    private static void diagonalAndBowTieRejected() {
        int axis = 0;
        int[] diagonal = quadData(axis, 1f,
            new float[] { 0f, 0f }, new float[] { 1f, 1f }, new float[] { 0f, 1f }, new float[] { 1f, 0f });
        check("diagonal corner ordering rejected",
            QuadCoverage.rect(diagonal, STRIDE, POS, axis, 1f, EPS) == null);

        int[] bowTie = quadData(axis, 1f,
            new float[] { 0f, 0f }, new float[] { 1f, 1f }, new float[] { 0f, 1f }, new float[] { 1f, 0f });
        check("bow-tie rejected", QuadCoverage.rect(bowTie, STRIDE, POS, axis, 1f, EPS) == null);
    }

    /** 非共面/非矩形（梯形）quad 不得作为覆盖证据。 */
    private static void nonPlanarAndNonRectangularQuadsKept() {
        int axis = 0;
        int[] slanted = quadData(axis, 1f,
            new float[] { 0f, 0f }, new float[] { 1f, 0f }, new float[] { 1f, 1f }, new float[] { 0f, 1f });
        // 把最后一个顶点抬离平面
        setPosition(slanted, 3, 0.9f, 0f, 1f);
        check("non-coplanar quad rejected",
            QuadCoverage.rect(slanted, STRIDE, POS, axis, 1f, EPS) == null);

        int[] trapezoid = quadData(axis, 1f,
            new float[] { 0f, 0f }, new float[] { 1f, 0f }, new float[] { 1f, 1f }, new float[] { 0f, 0.5f });
        check("trapezoid rejected",
            QuadCoverage.rect(trapezoid, STRIDE, POS, axis, 1f, EPS) == null);

        int axis2 = 2;
        int[] northFacing = quadData(axis2, 0f,
            new float[] { 0f, 0f }, new float[] { 1f, 0f }, new float[] { 1f, 1f }, new float[] { 0f, 1f });
        Rect target = QuadCoverage.rect(northFacing, STRIDE, POS, axis2, 0f, EPS);
        check("valid rect extracted", target != null && target.isNonDegenerate(EPS));
    }

    /** NaN/Inf、零宽/零高、格式不足 → 一律不作为证据（保留面）。 */
    private static void degnerateAndBadDataKept() {
        int axis = 0;
        int[] nan = rectQuad(axis, 1f, 0f, 1f, 0f, 1f);
        setPositionRaw(nan, 2, Float.NaN, 0f, 0.5f);
        check("NaN vertex rejected", QuadCoverage.rect(nan, STRIDE, POS, axis, 1f, EPS) == null);

        int[] zeroWidth = rectQuad(axis, 1f, 0f, 0f, 0f, 1f);
        check("zero-width rect rejected",
            QuadCoverage.rect(zeroWidth, STRIDE, POS, axis, 1f, EPS) == null);

        int[] shortData = new int[STRIDE * 3];
        check("short vertex data rejected", QuadCoverage.rect(shortData, STRIDE, POS, axis, 1f, EPS) == null);
        check("null data rejected", QuadCoverage.rect(null, STRIDE, POS, axis, 1f, EPS) == null);
        check("bad stride rejected", QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), 2, POS, axis, 1f, EPS) == null);
    }

    /** 真实细缝 1/4096 大于几何 epsilon，必须保留（不能被容差缝补）。 */
    private static void thinSlitIsKept() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        float slit = 1f / 4096f;
        List<Rect> two = new ArrayList<>();
        two.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS));
        two.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0.5f + slit, 1f), STRIDE, POS, axis, 0f, EPS));
        check("1/4096 slit is not covered",
            !InternalFaceCulling.shouldCull(target, two, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));

        // 同样大小但位置精确相接 → 剔除
        List<Rect> seamless = new ArrayList<>();
        seamless.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS));
        seamless.add(QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0.5f, 1f), STRIDE, POS, axis, 0f, EPS));
        check("seamless halves covered",
            InternalFaceCulling.shouldCull(target, seamless, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));
    }

    /** 顶点环绕方向决定面朝向；提取可给出法线符号以过滤反向 quad。 */
    private static void windingNormalSignFiltering() {
        int axis = 0;
        int[] ccw = rectQuad(axis, 1f, 0f, 1f, 0f, 1f);
        int sign = QuadCoverage.axisNormalSign(ccw, STRIDE, POS, axis);
        check("winding normal sign is +/-1", sign == 1 || sign == -1);

        int[] reversed = new int[ccw.length];
        // 反转顶点顺序 0,3,2,1
        for (int i = 0; i < 4; i++) {
            int src = (i == 0) ? 0 : (4 - i);
            System.arraycopy(ccw, src * STRIDE, reversed, i * STRIDE, STRIDE);
        }
        check("reversed winding flips normal sign",
            QuadCoverage.axisNormalSign(reversed, STRIDE, POS, axis) == -sign);
    }

    /** connect=state：同 state 才剔除；不同 state 即使几何完整也保留。 */
    private static void differingStatesWithStateConnect() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        Rect neighbor = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 0f, EPS);
        check("state same -> culled", cull(target, neighbor,
            InternalFaceCulling.ConnectKind.STATE, true, true, true, true));
        check("state differs -> kept", !cull(target, neighbor,
            InternalFaceCulling.ConnectKind.STATE, true, false, true, false));
    }

    /** block / material 语义。 */
    private static void materialAndBlockSemantics() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        Rect neighbor = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 0f, EPS);
        check("block same -> culled even if state differs", cull(target, neighbor,
            InternalFaceCulling.ConnectKind.BLOCK, true, false, false, false));
        check("block differs -> kept", !cull(target, neighbor,
            InternalFaceCulling.ConnectKind.BLOCK, false, false, true, true));
        check("material same -> culled", cull(target, neighbor,
            InternalFaceCulling.ConnectKind.MATERIAL, false, false, true, false));
        check("material differs -> kept", !cull(target, neighbor,
            InternalFaceCulling.ConnectKind.MATERIAL, false, false, false, false));
        check("unknown differs state -> kept", !cull(target, neighbor,
            InternalFaceCulling.ConnectKind.UNKNOWN, true, false, true, true));
    }

    /** connect=tile：必须同时有连接证据与几何证据；几何不合格时即使连接也不剔除。 */
    private static void tileRequiresRealTextureEvidence() {
        int axis = 0;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 1f, EPS);
        Rect neighbor = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 1f), STRIDE, POS, axis, 0f, EPS);
        check("tile matched + geometry -> culled", cull(target, neighbor,
            InternalFaceCulling.ConnectKind.TILE, true, true, true, true));
        check("tile not matched -> kept", !cull(target, neighbor,
            InternalFaceCulling.ConnectKind.TILE, true, true, true, false));

        Rect partial = QuadCoverage.rect(rectQuad(axis, 0f, 0f, 1f, 0f, 0.5f), STRIDE, POS, axis, 0f, EPS);
        check("tile matched but partial geometry -> kept", !cull(target, partial,
            InternalFaceCulling.ConnectKind.TILE, true, true, true, true));
    }

    /** 异常/无效输入必须保留面（不抛异常）。 */
    private static void exceptionFallbackIsRetained() {
        Rect target = QuadCoverage.rect(rectQuad(0, 1f, 0f, 1f, 0f, 1f), STRIDE, POS, 0, 1f, EPS);
        check("null target kept", !InternalFaceCulling.shouldCull(
            null, new ArrayList<Rect>(), InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
        check("null neighbor list kept", !InternalFaceCulling.shouldCull(
            target, null, InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));
        check("empty neighbor list kept", !InternalFaceCulling.shouldCull(
            target, new ArrayList<Rect>(), InternalFaceCulling.ConnectKind.BLOCK, true, true, true, true));

        List<Rect> withNulls = new ArrayList<>();
        withNulls.add(null);
        withNulls.add(new Rect(0f, 0f, 0f, 0f));
        check("null/degenerate neighbor entries kept",
            !InternalFaceCulling.shouldCull(target, withNulls, InternalFaceCulling.ConnectKind.BLOCK,
                true, true, true, true));

        // 已知异常语义：非共面平面比较返回 false
        check("coplanar rejects NaN", !InternalFaceCulling.coplanar(Float.NaN, 0f, 1f, EPS));
    }

    // ------------------------------------------------------------------ geometry helpers

    private static boolean cull(Rect target, Rect neighbor, InternalFaceCulling.ConnectKind kind,
                                boolean sameBlock, boolean sameState, boolean sameMaterial,
                                boolean tileMatch) {
        List<Rect> list = new ArrayList<>(1);
        if (neighbor != null) list.add(neighbor);
        return InternalFaceCulling.shouldCull(target, list, kind, sameBlock, sameState,
            sameMaterial, tileMatch);
    }

    /**
     * 生成一个轴对齐矩形 quad 的顶点数据。
     *
     * @param axis  面法线轴
     * @param plane 法线轴坐标
     * @param u0/u1/v0/v1 平面内两轴范围（轴序号升序）
     */
    private static int[] rectQuad(int axis, float plane, float u0, float u1, float v0, float v1) {
        return quadData(axis, plane,
            new float[] { u0, v0 }, new float[] { u1, v0 },
            new float[] { u1, v1 }, new float[] { u0, v1 });
    }

    /** 按给定顺序把平面内 (u,v) 坐标写入四顶点。 */
    private static int[] quadData(int axis, float plane, float[]... uv) {
        int[] data = new int[STRIDE * 4];
        for (int i = 0; i < 4; i++) {
            float[] p = new float[3];
            int a = axis == 0 ? 1 : 0;
            int b = axis == 2 ? 1 : 2;
            p[axis] = plane;
            p[a] = uv[i][0];
            p[b] = uv[i][1];
            setPosition(data, i, p[0], p[1], p[2]);
        }
        return data;
    }

    private static void setPosition(int[] data, int vertex, float x, float y, float z) {
        int base = vertex * STRIDE + POS;
        data[base] = Float.floatToIntBits(x);
        data[base + 1] = Float.floatToIntBits(y);
        data[base + 2] = Float.floatToIntBits(z);
    }

    private static void setPositionRaw(int[] data, int vertex, float x, float y, float z) {
        setPosition(data, vertex, x, y, z);
    }

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
