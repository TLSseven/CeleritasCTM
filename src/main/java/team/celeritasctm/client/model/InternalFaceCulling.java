package team.celeritasctm.client.model;

/**
 * 内部面剔除的纯几何/连接谓词（不依赖 Minecraft 类型，可直接单测）。
 *
 * <p>剔除一个面必须同时具备四项证据（缺一不可）：
 * <ol>
 *   <li><b>邻居面完整</b>：朝向当前方块的邻居面必须是完整实面；</li>
 *   <li><b>同轴共面贴合</b>：quad 在面法线轴上是一个平面，且该平面与邻居 bbox 的
 *       内侧边精确贴合（相差不超过容差）；</li>
 *   <li><b>二维完整覆盖</b>：邻居 bbox 在 quad 平面内的另两轴投影必须完整覆盖 quad
 *       的另两轴范围 —— 只贴合一条边、只覆盖一部分时不得剔除，否则会露出空洞；</li>
 *   <li><b>连接谓词</b>：按 {@code connect=block|tile|material|state} 判定邻居确实
 *       应该与本方块连接；未知取值退化为「同 state + 同 block」的保守判定。</li>
 * </ol>
 *
 * <p>坐标约定：所有 quad/bbox 坐标均已归一化到 0..1 的方块模型空间。邻居 bbox 的
 * 「内侧边」是相对邻居自身原点的偏移，换算到本方块模型空间时：正方向邻居
 * （EAST/SOUTH/UP）的内侧边 = {@code 1 + neighborMin[axis]}，负方向邻居
 * （WEST/NORTH/DOWN）的内侧边 = {@code -1 + neighborMax[axis]}。这样对旋转/切面
 * 模型（竖半砖等）都成立。
 */
public final class InternalFaceCulling {

    /** 默认容差（方块模型空间，1.0 = 一格） */
    public static final float DEFAULT_TOLERANCE = 0.01f;

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

    /**
     * 顶点坐标归一化：部分 mod 模型（如 Conquest 竖半砖）烘焙时使用 0..16 单位，
     * vanilla FaceBakery 使用 0..1。以整体最大绝对值为判据统一缩放。
     */
    public static float normalizeScale(float maxAbsCoordinate) {
        return maxAbsCoordinate > 1.5f ? 1.0f / 16.0f : 1.0f;
    }

    /** 单轴坐标归一化（供调用方逐个换算时使用）。 */
    public static float normalizeCoordinate(float value) {
        return Math.abs(value) > 1.5f ? value / 16.0f : value;
    }

    /** 同一组数值在容差内是否相等（用于共面判定）。 */
    public static boolean isPlanar(float[] values, float tolerance) {
        if (values == null || values.length == 0) return false;
        float first = values[0];
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - first) > tolerance) return false;
        }
        return true;
    }

    /** 邻居在一轴上的投影是否完整覆盖 quad 的该轴范围。 */
    public static boolean covers(float quadMin, float quadMax,
                                 float neighborMin, float neighborMax,
                                 float tolerance) {
        return neighborMin <= quadMin + tolerance && neighborMax >= quadMax - tolerance;
    }

    /**
     * quad 平面是否与邻居内侧边贴合。
     *
     * @param axis     面法线轴（0=X, 1=Y, 2=Z）
     * @param positive 邻居位于正方向（EAST/SOUTH/UP）时为 true
     * @param plane    quad 在 axis 上的坐标（已归一化）
     */
    public static boolean planeTouchesNeighbor(int axis, boolean positive, float plane,
                                               float[] neighborMin, float[] neighborMax,
                                               float tolerance) {
        if (axis < 0 || axis > 2) return false;
        if (plane < -tolerance || plane > 1.0f + tolerance) return false;
        float inner = positive ? 1.0f + neighborMin[axis] : -1.0f + neighborMax[axis];
        return Math.abs(plane - inner) <= tolerance;
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

    /**
     * 完整剔除判定。
     *
     * @param quadMin      quad 三轴最小坐标（0..1 模型空间）
     * @param quadMax      quad 三轴最大坐标（0..1 模型空间）
     * @param axis         面法线轴
     * @param positive     邻居是否在位正方向
     * @param neighborMin  邻居 bbox 最小角（相对邻居原点）
     * @param neighborMax  邻居 bbox 最大角（相对邻居原点）
     * @param kind         connect 语义
     * @param sameBlock    邻居与本方块是否同一 Block
     * @param sameState    邻居与本方块（actual state）是否完全相等
     * @param sameMaterial 邻居与本方块材质是否相同
     * @param tileMatch    connect=tile 时邻居该面纹理是否与当前 quad 纹理一致
     */
    public static boolean shouldCull(float[] quadMin, float[] quadMax, int axis, boolean positive,
                                     float[] neighborMin, float[] neighborMax,
                                     ConnectKind kind, boolean sameBlock, boolean sameState,
                                     boolean sameMaterial, boolean tileMatch) {
        return shouldCull(quadMin, quadMax, axis, positive, neighborMin, neighborMax,
                kind, sameBlock, sameState, sameMaterial, tileMatch, true, DEFAULT_TOLERANCE);
    }

    public static boolean shouldCull(float[] quadMin, float[] quadMax, int axis, boolean positive,
                                     float[] neighborMin, float[] neighborMax,
                                     ConnectKind kind, boolean sameBlock, boolean sameState,
                                     boolean sameMaterial, boolean tileMatch, float tolerance) {
        return shouldCull(quadMin, quadMax, axis, positive, neighborMin, neighborMax,
                kind, sameBlock, sameState, sameMaterial, tileMatch, true, tolerance);
    }

    public static boolean shouldCull(float[] quadMin, float[] quadMax, int axis, boolean positive,
                                     float[] neighborMin, float[] neighborMax,
                                     ConnectKind kind, boolean sameBlock, boolean sameState,
                                     boolean sameMaterial, boolean tileMatch,
                                     boolean neighborFaceSolid) {
        return shouldCull(quadMin, quadMax, axis, positive, neighborMin, neighborMax,
                kind, sameBlock, sameState, sameMaterial, tileMatch,
                neighborFaceSolid, DEFAULT_TOLERANCE);
    }

    public static boolean shouldCull(float[] quadMin, float[] quadMax, int axis, boolean positive,
                                     float[] neighborMin, float[] neighborMax,
                                     ConnectKind kind, boolean sameBlock, boolean sameState,
                                     boolean sameMaterial, boolean tileMatch,
                                     boolean neighborFaceSolid, float tolerance) {
        if (quadMin == null || quadMax == null || neighborMin == null || neighborMax == null) return false;
        if (quadMin.length < 3 || quadMax.length < 3 || neighborMin.length < 3 || neighborMax.length < 3) {
            return false;
        }
        if (axis < 0 || axis > 2) return false;
        if (tolerance < 0.0f) tolerance = DEFAULT_TOLERANCE;

        // 1) 邻居朝向当前方块的面必须完整；默认 AABB 不能证明楼梯等模型的实际遮挡形状
        if (!neighborFaceSolid) return false;

        // 2) 连接谓词：不连接就不可能形成内部面
        if (!connectAllows(kind, sameBlock, sameState, sameMaterial, tileMatch)) return false;

        // 3) quad 在该轴上必须是平面
        float plane = quadMin[axis];
        if (Math.abs(quadMax[axis] - plane) > tolerance) return false;

        // 3) 平面与邻居内侧边贴合
        if (!planeTouchesNeighbor(axis, positive, plane, neighborMin, neighborMax, tolerance)) return false;

        // 4) 另两轴二维完整覆盖
        for (int a = 0; a < 3; a++) {
            if (a == axis) continue;
            if (!covers(quadMin[a], quadMax[a], neighborMin[a], neighborMax[a], tolerance)) return false;
        }
        return true;
    }
}
