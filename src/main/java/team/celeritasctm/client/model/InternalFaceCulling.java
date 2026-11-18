package team.celeritasctm.client.model;

import team.celeritasctm.client.model.QuadCoverage.Rect;

import java.util.List;

/**
 * 内部面剔除的纯几何/连接谓词（不依赖 Minecraft 类型，可直接单测）。
 *
 * <p>剔除一个面必须同时具备三类证据（缺一不可，证据不足即保留面）：
 * <ol>
 *   <li><b>连接谓词</b>：按 {@code connect=block|tile|material|state} 判定邻居确实
 *       应该与本方块连接；未知取值退化为「同 state + 同 block」的保守判定。</li>
 *   <li><b>目标面可度量</b>：当前 quad 在面法线轴上共面，且在另两轴上构成轴对齐矩形
 *       （对角/梯形/退化 quad 无法度量，直接保留）。</li>
 *   <li><b>邻居真实几何覆盖</b>：邻居<b>实际烘焙 quad</b> 在该平面上的矩形并集必须完整
 *       覆盖目标矩形（支持一个面拆成多个 quad）。只用 bbox 或
 *       {@code BlockFaceShape.SOLID} 不足以为证：楼梯、竖半砖的 bbox/面形状都可能
 *       与渲染出的面不同，会误剔出空洞。</li>
 * </ol>
 *
 * <p>坐标约定：所有坐标都是烘焙后的方块单位（0..1 = 一格，MC/Forge 的 baked 位置
 * 即为方块单位，不做基于数值大小的单位推断）。邻居 quad 的坐标相对邻居自身原点，
 * 换算到本方块空间时只需平移法线轴：正方向邻居（EAST/SOUTH/UP）+1，负方向 -1；
 * 平面内另两轴不做变换。
 */
public final class InternalFaceCulling {

    /** connect 属性语义 */
    public enum ConnectKind {
        BLOCK,
        TILE,
        MATERIAL,
        STATE,
        /** 未知/未声明：保守处理，要求同 block 且同 state */
        UNKNOWN
    }

    private InternalFaceCulling() {
    }

    /** 解析 {@code connect=} 取值；未知值返回 {@link ConnectKind#UNKNOWN}。 */
    public static ConnectKind connectKind(String connect) {
        if (connect == null) return ConnectKind.UNKNOWN;
        switch (connect) {
            case "block":
                return ConnectKind.BLOCK;
            case "tile":
                return ConnectKind.TILE;
            case "material":
                return ConnectKind.MATERIAL;
            case "state":
                return ConnectKind.STATE;
            default:
                return ConnectKind.UNKNOWN;
        }
    }

    /** 连接谓词是否满足（证据由调用方收集，本方法只做语义分派）。 */
    public static boolean connectAllows(ConnectKind kind, boolean sameBlock, boolean sameState,
                                        boolean sameMaterial, boolean tileMatch) {
        if (kind == null) return sameBlock && sameState;
        switch (kind) {
            case BLOCK:
                return sameBlock;
            case STATE:
                return sameState;
            case MATERIAL:
                return sameMaterial;
            case TILE:
                return tileMatch;
            default:
                // 未知 connect：宁可少剔，不留洞
                return sameBlock && sameState;
        }
    }

    /** 法线轴方向：正方向邻居（EAST/SOUTH/UP）平移 +1，负方向 -1。 */
    public static float planeShift(boolean positive) {
        return positive ? 1.0f : -1.0f;
    }

    /**
     * 完整剔除判定（默认几何容差）。
     *
     * @param target        当前 quad 在其平面内的轴对齐矩形（方块单位）
     * @param neighborRects 邻居实际烘焙 quad 的矩形（已由调用方筛选为与目标平面共面，
     *                      平面内两轴与目标同坐标系）
     */
    public static boolean shouldCull(Rect target, List<Rect> neighborRects,
                                     ConnectKind kind, boolean sameBlock, boolean sameState,
                                     boolean sameMaterial, boolean tileMatch) {
        return shouldCull(target, neighborRects, kind, sameBlock, sameState,
                sameMaterial, tileMatch, QuadCoverage.GEOMETRY_EPSILON);
    }

    /**
     * 完整剔除判定。
     *
     * @param target        当前 quad 在面法线轴上共面后的轴对齐矩形；{@code null} 保留
     * @param neighborRects 邻居实际 quad 的轴对齐矩形（已共面筛选；可含退化项，会被忽略）
     * @param eps           几何容差（只抵消浮点误差，不可用于缝补真实间隙）
     */
    public static boolean shouldCull(Rect target, List<Rect> neighborRects,
                                     ConnectKind kind, boolean sameBlock, boolean sameState,
                                     boolean sameMaterial, boolean tileMatch, float eps) {
        if (target == null || neighborRects == null) return false;
        if (eps < 0.0f) eps = QuadCoverage.GEOMETRY_EPSILON;
        if (!target.isNonDegenerate(eps)) return false;

        // 1) 连接谓词不连接就不可能形成内部面
        if (!connectAllows(kind, sameBlock, sameState, sameMaterial, tileMatch)) return false;

        return covered(target, neighborRects, eps);
    }

    /**
     * 生产路径使用的重载：连接证据由调用方以<b>统一谓词</b>
     * {@code ConnectionHandler.connectsTo} 计算后直接传入。
     *
     * <p>为什么不在这里再按 {@link ConnectKind} 分派一次：{@code connectTiles}
     * 是连接谓词里的额外放行条件（例如 plank ↔ slab），而 {@link #connectAllows}
     * 只认识 connect 主语义，会在 {@code connectsTo=true} 时再次否决，导致规则
     * 明明连接却不剔面。生产路径因此只保留一次判定（与掩码同源），避免两套语义漂移。
     *
     * @param connected 已由统一连接谓词证明的连接结果
     */
    public static boolean shouldCull(Rect target, List<Rect> neighborRects,
                                     boolean connected, float eps) {
        if (!connected) return false;
        if (target == null || neighborRects == null) return false;
        if (eps < 0.0f) eps = QuadCoverage.GEOMETRY_EPSILON;
        if (!target.isNonDegenerate(eps)) return false;
        return covered(target, neighborRects, eps);
    }

    /** 邻居实际 quad 的平面内并集是否完整覆盖目标（已通过连接谓词后调用）。 */
    private static boolean covered(Rect target, List<Rect> neighborRects, float eps) {
        // QuadCoverage.unionCovers 自身会跳 null/退化项，且不要求预过滤副本：
        // 直接传入原列表，避免每个面额外复制一次矩形列表。
        return QuadCoverage.unionCovers(neighborRects, target, eps);
    }

    /**
     * 邻居矩形平移到本方块空间后是否与给定平面共面。
     *
     * <p>平面不在 {@link Rect} 中存储，因此由调用方在提取邻居矩形时按
     * {@code localPlane = targetPlane - planeShift} 过滤；本方法保留给需要显式
     * 复核共面性的调用方。
     */
    public static boolean coplanar(float targetPlane, float neighborLocalPlane,
                                   float planeShift, float eps) {
        if (Float.isNaN(targetPlane) || Float.isNaN(neighborLocalPlane)
                || Float.isInfinite(targetPlane) || Float.isInfinite(neighborLocalPlane)) {
            return false;
        }
        return Math.abs((neighborLocalPlane + planeShift) - targetPlane) <= eps;
    }
}
