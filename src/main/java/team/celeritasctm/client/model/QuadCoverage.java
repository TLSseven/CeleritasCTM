package team.celeritasctm.client.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 烘焙 quad 的实际几何覆盖判定（纯数据，不依赖 Minecraft 类型，可直接单测）。
 *
 * <p>本类只做两件事：
 * <ol>
 *   <li>从真实顶点数据（{@code BakedQuad.getVertexData()} 的 int[]）中提取
 *       「轴向对齐、与给定平面共面」的矩形；非矩形、对角线、退化、含 NaN/Inf 的
 *       quad 一律返回 {@code null}，不构成遮挡证据；</li>
 *   <li>判断一组矩形（邻居朝向面的实际 quad）的并集是否完整覆盖目标矩形，
 *       用精确的矩形并集扫描实现，支持一个面被拆成多个 quad 的情况。</li>
 * </ol>
 *
 * <p><b>坐标单位</b>：MC/Forge 烘焙后的顶点位置已经是「方块单位」（0..1 = 一格），
 * 这里不做任何基于数值大小的单位推断。超出 0..1 的装饰几何会自然无法与目标平面/范围
 * 匹配，从而被保守地保留（不会误剔）。{@link #GEOMETRY_EPSILON} 只用于抵消浮点误差，
 * 必须保持很小——用 0.01 之类的容差会把真实存在的细缝误判为已覆盖。
 */
public final class QuadCoverage {

    /** 几何容差（方块单位）：仅抵消浮点误差，不可用于「缝补」真实间隙。 */
    public static final float GEOMETRY_EPSILON = 1e-4f;

    private QuadCoverage() {
    }

    /** 平面内两轴上的矩形（u/v 为除面法线轴外、按轴序号升序排列的两轴）。 */
    public static final class Rect {
        public final float minU;
        public final float maxU;
        public final float minV;
        public final float maxV;

        public Rect(float minU, float maxU, float minV, float maxV) {
            this.minU = minU;
            this.maxU = maxU;
            this.minV = minV;
            this.maxV = maxV;
        }

        /** 两轴尺寸都必须大于 {@code eps}（零宽/零高的退化面不算遮挡证据）。 */
        public boolean isNonDegenerate(float eps) {
            return maxU - minU > eps && maxV - minV > eps;
        }

        @Override
        public String toString() {
            return "Rect[" + minU + ".." + maxU + " x " + minV + ".." + maxV + "]";
        }
    }

    /**
     * 读取 4 个顶点位置并校验：stride/offset 合法、数据足够、数值有限。
     *
     * @return 4x3 角点数组；任何校验失败返回 {@code null}
     */
    public static float[][] corners(int[] data, int stride, int posOffsetInts) {
        if (data == null || stride < 3 || posOffsetInts < 0) return null;
        if ((long) stride * 4 > data.length) return null;
        if (posOffsetInts + 2 >= stride) return null;

        float[][] out = new float[4][3];
        for (int i = 0; i < 4; i++) {
            int base = i * stride + posOffsetInts;
            if (base + 2 >= data.length) return null;
            for (int a = 0; a < 3; a++) {
                float v = Float.intBitsToFloat(data[base + a]);
                if (Float.isNaN(v) || Float.isInfinite(v)) return null;
                out[i][a] = v;
            }
        }
        return out;
    }

    /** 4 个顶点在三轴上的范围。校验失败返回 false（调用方不得剔除）。 */
    public static boolean bounds(int[] data, int stride, int posOffsetInts,
                                 float[] outMin, float[] outMax) {
        float[][] c = corners(data, stride, posOffsetInts);
        if (c == null || outMin == null || outMax == null
                || outMin.length < 3 || outMax.length < 3) {
            return false;
        }
        for (int a = 0; a < 3; a++) {
            float lo = c[0][a];
            float hi = c[0][a];
            for (int i = 1; i < 4; i++) {
                if (c[i][a] < lo) lo = c[i][a];
                if (c[i][a] > hi) hi = c[i][a];
            }
            outMin[a] = lo;
            outMax[a] = hi;
        }
        return true;
    }

    /**
     * 提取 quad 在给定平面上的轴对齐矩形。
     *
     * <p>要求：4 个顶点都在 {@code plane ± eps} 共面；另两轴各只有两个极值，
     * 且 4 个角恰好是这两个极值的 4 种组合（排除三角形/梯形/对角线/退化线）。
     *
     * @param axis 面法线轴（0=X, 1=Y, 2=Z）
     * @param plane 期望的平面坐标（方块单位，邻居模型需先由调用方换算到同一空间）
     * @return 矩形；不合格返回 {@code null}
     */
    public static Rect rect(int[] data, int stride, int posOffsetInts, int axis,
                            float plane, float eps) {
        if (axis < 0 || axis > 2) return null;
        if (Float.isNaN(plane) || Float.isInfinite(plane)) return null;
        float[][] c = corners(data, stride, posOffsetInts);
        if (c == null) return null;

        for (int i = 0; i < 4; i++) {
            if (Math.abs(c[i][axis] - plane) > eps) return null;
        }

        int a = axis == 0 ? 1 : 0;
        int b = axis == 2 ? 1 : 2;

        float a0 = c[0][a];
        float a1 = distinctSecond(c, a, a0, eps);
        if (Float.isNaN(a1)) return null;
        float b0 = c[0][b];
        float b1 = distinctSecond(c, b, b0, eps);
        if (Float.isNaN(b1)) return null;

        float minA = Math.min(a0, a1);
        float maxA = Math.max(a0, a1);
        float minB = Math.min(b0, b1);
        float maxB = Math.max(b0, b1);
        if (maxA - minA <= eps || maxB - minB <= eps) return null;

        boolean[] seen = new boolean[4];
        for (int i = 0; i < 4; i++) {
            int ai = match(c[i][a], minA, maxA, eps);
            int bi = match(c[i][b], minB, maxB, eps);
            if (ai < 0 || bi < 0) return null;
            int idx = ai * 2 + bi;
            if (seen[idx]) return null;
            seen[idx] = true;
        }
        for (int i = 0; i < 4; i++) {
            if (!seen[i]) return null;
        }
        // 拒绝蝶形/交叉顶点顺序：四个角坐标集合正确不代表三角形覆盖矩形。
        // 合法的矩形 quad 每条边只在一个切向轴上变化；交叉顺序会出现两个轴同时变化。
        for (int i = 0; i < 4; i++) {
            float[] p = c[i];
            float[] q = c[(i + 1) % 4];
            boolean changesA = Math.abs(p[a] - q[a]) > eps;
            boolean changesB = Math.abs(p[b] - q[b]) > eps;
            if (changesA == changesB) return null; // 两个轴都变（对角）或都不变（重复点）
        }
        return new Rect(minA, maxA, minB, maxB);
    }

    /** 返回与 first 相差超过 eps 的第二个取值；所有值都与 first 相近则返回 NaN。 */
    private static float distinctSecond(float[][] c, int axis, float first, float eps) {
        for (int i = 1; i < 4; i++) {
            if (Math.abs(c[i][axis] - first) > eps) {
                return c[i][axis];
            }
        }
        return Float.NaN;
    }

    private static int match(float value, float lo, float hi, float eps) {
        if (Math.abs(value - lo) <= eps) return 0;
        if (Math.abs(value - hi) <= eps) return 1;
        return -1;
    }

    /**
     * 顶点环绕方向在给定轴上的法线符号（Newell 法，抗微小非共面误差）。
     *
     * @return +1 / -1；数据非法、退化或法线在该轴上为 0 时返回 0
     */
    public static int axisNormalSign(int[] data, int stride, int posOffsetInts, int axis) {
        if (axis < 0 || axis > 2) return 0;
        float[][] c = corners(data, stride, posOffsetInts);
        if (c == null) return 0;
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;
        for (int i = 0; i < 4; i++) {
            float[] p = c[i];
            float[] q = c[(i + 1) % 4];
            nx += (p[1] - q[1]) * (p[2] + q[2]);
            ny += (p[2] - q[2]) * (p[0] + q[0]);
            nz += (p[0] - q[0]) * (p[1] + q[1]);
        }
        double component = axis == 0 ? nx : (axis == 1 ? ny : nz);
        double magnitude = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (magnitude <= 1e-9) return 0;
        // 该轴分量必须占主导，否则不是轴向面
        if (Math.abs(component) < magnitude * 0.5) return 0;
        return component > 0 ? 1 : -1;
    }

    /**
     * 一组矩形的并集是否完整覆盖目标矩形（二维精确判定）。
     *
     * <p>做法：以所有矩形的 u 边界（及目标 u 边界）把平面切成竖条；每条竖条内
     * 把跨过该竖条的矩形 v 区间做并集，检查是否覆盖目标的 v 范围。
     *
     * <p><b>精确性要求</b>：u 边界按<b>精确值</b>去重、竖条一律处理（不做
     * 「差 <= eps 就合并/跳过」）；v 区间拼接游标也精确比较。{@code eps} 只用于
     * 校验矩形是否退化（{@link Rect#isNonDegenerate(float)}），<b>不</b>用于扩大
     * 覆盖范围、缝补间隙或合并边界。这样小于 eps 的真实细缝（如 1e-5）仍会被
     * 判为未覆盖；浮点舍入导致的边界不一致只会使结果更保守（不剔除）。
     */
    public static boolean unionCovers(List<Rect> cover, Rect target, float eps) {
        if (cover == null || target == null) return false;
        if (!target.isNonDegenerate(eps)) return false;

        List<Float> cuts = new ArrayList<>(cover.size() * 2 + 2);
        addCut(cuts, target.minU);
        addCut(cuts, target.maxU);
        for (Rect r : cover) {
            if (r == null || !r.isNonDegenerate(eps)) continue;
            if (r.maxU <= target.minU || r.minU >= target.maxU) continue;
            addCut(cuts, clamp(r.minU, target.minU, target.maxU));
            addCut(cuts, clamp(r.maxU, target.minU, target.maxU));
        }
        if (cuts.size() < 2) return false;
        cuts.sort(Float::compare);
        if (cuts.get(0) != target.minU) return false;
        if (cuts.get(cuts.size() - 1) != target.maxU) return false;

        for (int i = 0; i + 1 < cuts.size(); i++) {
            float u0 = cuts.get(i);
            float u1 = cuts.get(i + 1);
            if (u1 <= u0) continue; // 重复边界（精确相等）不构成竖条

            List<float[]> spans = new ArrayList<>(cover.size());
            for (Rect r : cover) {
                if (r == null || !r.isNonDegenerate(eps)) continue;
                if (r.minU <= u0 && r.maxU >= u1) {
                    spans.add(new float[] { r.minV, r.maxV });
                }
            }
            if (spans.isEmpty()) return false;
            if (!intervalsCover(spans, target.minV, target.maxV)) return false;
        }
        return true;
    }

    /** 精确拼接 v 区间（游标不扩张容差）；出现真实间隙即返回 false。 */
    private static boolean intervalsCover(List<float[]> spans, float minV, float maxV) {
        spans.sort((x, y) -> Float.compare(x[0], y[0]));
        float cursor = minV;
        boolean started = false;
        for (float[] s : spans) {
            if (s[0] > cursor) break; // 真实间隙（精确比较）
            if (s[1] > cursor) {
                cursor = s[1];
                started = true;
            }
            if (cursor >= maxV) return true;
        }
        return started && cursor >= maxV;
    }

    /** 按精确相等去重（不合并近似值）。 */
    private static void addCut(List<Float> cuts, float value) {
        for (float c : cuts) {
            if (c == value) return;
        }
        cuts.add(value);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
