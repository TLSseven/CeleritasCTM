package team.celeritasctm.client.model;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakable;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockMycelium;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import org.apache.commons.lang3.tuple.Pair;
import team.celeritasctm.CeleritasCTM;
import team.celeritasctm.client.resource.CtmProperties;
import team.celeritasctm.client.resource.CtmPropertiesLoader;
import team.celeritasctm.client.texture.CTMSpriteRegistry;
import team.celeritasctm.client.util.ConnectionHandler;

import javax.annotation.Nullable;
import javax.vecmath.Matrix4f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CtmBakedModel implements IBakedModel {

    private final IBakedModel parent;

    public static final ThreadLocal<RenderContext> CURRENT_CONTEXT = new ThreadLocal<>();

    /**
     * 抑制 MixinBlock 写入复用上下文：查询邻居 extended state 时会再次进入
     * {@code Block.getExtendedState}，若不抑制会把当前渲染上下文改成邻居坐标。
     * 查询结束后由调用方按保存值恢复。
     */
    public static final ThreadLocal<Boolean> EXTENDED_STATE_SUPPRESS = ThreadLocal.withInitial(() -> false);

    public static class RenderContext {
        public IBlockAccess world;
        public BlockPos pos;
        /**
         * 注入时的坐标打包值（{@link BlockPos#toLong()}）。<b>不分配对象</b>，
         * 用于发现 {@code pos} 是可变的 {@code MutableBlockPos} 且已被移动到别处：
         * chunk 构建线程复用一个 MutableBlockPos，若它在 getExtendedState 之后又被
         * 移动，读 {@code pos} 会得到错误方块的数据（A14/R03）。此时视为上下文失效，
         * 直接走 vanilla fallback（宁可少做 CTM，也不按错误坐标取态）。
         */
        public long packedPos = Long.MIN_VALUE;

        public RenderContext(IBlockAccess world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }

        /** 坐标自注入后是否未被移动。 */
        public boolean isPosUnchanged() {
            return pos != null && pos.toLong() == packedPos;
        }
    }

    // OptiFine 兼容的 47 子纹理索引映射（与 Continuity 一致）
    // 掩码位: 128 64 32 / 1 * 16 / 2 4 8（bit0=左, bit1=左下, bit2=下, bit3=右下, bit4=右, bit5=右上, bit6=上, bit7=左上）
    private static final int[] CTM_INDEX_MAP = new int[] {
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
        1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
        1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43,
        16, 18, 16, 18, 6, 46, 6, 21, 16, 18, 16, 18, 28, 9, 28, 22,
        36, 17, 36, 17, 24, 19, 24, 43, 36, 17, 36, 17, 24, 19, 24, 43,
        37, 40, 37, 40, 30, 8, 30, 34, 37, 40, 37, 40, 25, 23, 25, 45,
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
        1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        0, 3, 0, 3, 12, 5, 12, 15, 0, 3, 0, 3, 12, 5, 12, 15,
        1, 2, 1, 2, 4, 7, 4, 29, 1, 2, 1, 2, 13, 31, 13, 14,
        36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27,
        16, 42, 16, 42, 6, 20, 6, 10, 16, 42, 16, 42, 28, 35, 28, 44,
        36, 39, 36, 39, 24, 41, 24, 27, 36, 39, 36, 39, 24, 41, 24, 27,
        37, 38, 37, 38, 30, 11, 30, 32, 37, 38, 37, 38, 25, 33, 25, 26,
    };

    // horizontal: [无连接, 左连, 右连, 都连] → tile 索引（0-based）
    private static final int[] HORIZONTAL_INDEX_MAP = new int[] { 3, 2, 0, 1 };
    // vertical: [无连接, 下连, 上连, 都连] → tile 索引（0-based）
    private static final int[] VERTICAL_INDEX_MAP = new int[] { 3, 2, 0, 1 };

    public CtmBakedModel(IBakedModel parent) {
        this.parent = parent;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable IBlockState state, @Nullable EnumFacing side, long rand) {
        // 缺失模型防护：Forge 的 FancyMissingModel 会渲染「missing model」文本（调用字体
        // 渲染），在 Sodium/Celeritas 的 chunk 构建线程没有 OpenGL 上下文 → 直接崩溃。
        // 缺失模型块直接返回空 quads（不可见），避免崩溃；合法方块模型不受影响。
        String parentCls = parent.getClass().getName();
        if (parentCls.contains("FancyMissingModel")) {
            return java.util.Collections.emptyList();
        }

        // 连接检测（connect=tile）查询邻居纹理时，必须返回原始 quads，
        // 否则拿到的会是替换后的精灵，导致纹理比较永远失败
        if (ConnectionHandler.isTileLookupActive()) {
            return parent.getQuads(state, side, rand);
        }

        // 无状态（物品/无上下文渲染）一律交还 parent：CTM 需要世界坐标才能取连接信息
        if (state == null) {
            return parent.getQuads(state, side, rand);
        }

        RenderContext ctx = CURRENT_CONTEXT.get();
        // 上下文有效性校验：ThreadLocal 可能残留上一块/物品渲染的陈旧上下文，
        // 当 pos 处的方块与当前 state 不是同一方块时视为无效（跳过处理，防止纹理错乱）。
        // 另外校验坐标自注入后未被移动（MutableBlockPos 复用，A14/R03）。
        if (ctx == null || ctx.world == null || ctx.pos == null) {
            return parent.getQuads(state, side, rand);
        }
        if (ctx.packedPos != Long.MIN_VALUE && !ctx.isPosUnchanged()) {
            return parent.getQuads(state, side, rand);
        }
        if (state != null && state.getBlock() != null) {
            try {
                IBlockState worldState = ctx.world.getBlockState(ctx.pos);
                if (worldState == null || worldState.getBlock() != state.getBlock()) {
                    return parent.getQuads(state, side, rand);
                }
            } catch (Exception e) {
                return parent.getQuads(state, side, rand);
            }
        }

        List<BakedQuad> original = parent.getQuads(state, side, rand);
        if (original.isEmpty()) return original;

        // 逐 quad 处理：替换 / 删除（更好的草地/雪地/连接玻璃/CTM 规则）
        boolean changed = false;
        List<BakedQuad> result = new ArrayList<>(original.size());
        for (BakedQuad quad : original) {
            List<BakedQuad> processed = processQuadList(quad, ctx, side);
            if (processed.size() != 1 || processed.get(0) != quad) {
                changed = true;
            }
            result.addAll(processed);
        }
        return changed ? result : original;
    }

    private List<BakedQuad> processQuadList(BakedQuad quad, RenderContext ctx, EnumFacing side) {
        TextureAtlasSprite sprite = quad.getSprite();
        if (sprite == null) return java.util.Collections.singletonList(quad);

        // 传入方块状态：matchBlocks / metadata 需要它
        IBlockState blockState = null;
        try {
            blockState = ctx.world.getBlockState(ctx.pos);
        } catch (Exception ignored) {}

        // 面感知：用 quad 的实际朝向匹配 faces 规则。
        EnumFacing quadFace = quad.getFace();

        // ===== 更好的草地（可配置）=====
        if (CeleritasCTM.config().betterGrass && quadFace != null && quadFace.getAxis().isHorizontal()
                && blockState != null && isGrassLike(blockState)) {
            // 去掉暗色 overlay 草皮条（相连面由 grass_top 替代）
            if (isGrassSideOverlaySprite(sprite) && betterGrassConnects(ctx, blockState, quadFace)) {
                return java.util.Collections.emptyList();
            }
            // 草皮沿对角下侧同种方块方向延伸
            if (isGrassSideSprite(sprite)) {
                BakedQuad betterGrassQuad = tryBetterGrass(quad, ctx, blockState, quadFace);
                if (betterGrassQuad != null) {
                    quad = betterGrassQuad;
                    sprite = quad.getSprite();
                }
            }
        }

        // ===== 更好的雪地（可配置）=====
        // 雪层下方的方块侧面显示雪纹理（草方块积雪侧面、普通方块侧面覆盖雪）
        if (CeleritasCTM.config().betterSnow && quadFace != null && quadFace.getAxis().isHorizontal()
                && blockState != null && isSnowCovered(ctx)) {
            TextureAtlasSprite snow = Minecraft.getMinecraft().getTextureMapBlocks()
                .getAtlasSprite("minecraft:blocks/snow");
            if (snow != null && "minecraft:blocks/snow".equals(snow.getIconName())) {
                return java.util.Collections.singletonList(remapUV(quad, sprite, snow));
            }
        }

        // 普通规则（替换本体）先匹配：资源包规则（含资源包提供的 glass 规则）永远优先，
        // 不受 connectedGlass 开关影响。
        CtmProperties builtinGlass = CtmPropertiesLoader.getGlassRule();
        CtmProperties props = CtmPropertiesLoader.findMatch(
            sprite.getIconName(), blockState, quadFace, ctx.world, ctx.pos);

        // 没有任何普通规则命中时的内置染色玻璃回退：内置规则只声明 matchTiles=blocks/glass
        // （无色玻璃），染色玻璃（blocks/glass_<color>）走同一套连接掩码 + tile 选择。
        // 仅当内置规则存在、开关开启、且当前方块确实是原版染色玻璃时启用；
        // 资源包自带 glass 规则会先在 findMatch 命中，因此不会被此回退覆盖。
        if (props == null && CeleritasCTM.config().connectedGlass
                && builtinGlass != null && isStainedGlass(blockState) && quadFace != null) {
            int mask = ConnectionHandler.getMask(ctx.world, ctx.pos, quadFace, builtinGlass, sprite);
            String tile = builtinGlass.tileAt(CTM_INDEX_MAP[mask & 255]);
            if (tile != null) {
                TextureAtlasSprite ts = CTMSpriteRegistry.getTileSprite(builtinGlass, tile);
                if (ts != null && ts.getIconWidth() > 1) {
                    return java.util.Collections.singletonList(remapUV(quad, sprite, ts));
                }
            }
        }

        if (props != null) {
            // 只有「内置玻璃回退规则」受 connectedGlass 开关约束；
            // 资源包提供的 glass 规则是普通 CTM 规则，开关关闭时也必须生效。
            if (props == builtinGlass && !CeleritasCTM.config().connectedGlass) {
                return java.util.Collections.singletonList(quad);
            }
            // ===== 连接透明方块内部面剔除（还原 OptiFine 机制）=====
            // 只有同时具备三类证据才删除面（缺一不可，宁可少剔不留洞）：
            //   1. 规则面约束：props.appliesTo(face)（findMatch 已按面过滤，此处显式复核）；
            //   2. 连接谓词：与连接掩码共用 ConnectionHandler 的同一套 connect 语义
            //      （block/tile/material/state/connectTiles；tile 用邻居该面原始烘焙纹理作证）；
            //   3. 几何证据：邻居实际烘焙 quad（朝向面 + 同朝向 general quads）在目标平面上的
            //      轴对齐矩形并集必须完整覆盖当前 quad —— 不用 bbox / BlockFaceShape.SOLID。
            // 任何证据不足（几何非共面/非矩形/部分覆盖/异常/重入/模型偏移不一致）都保留面。
            if (isConnectingMethod(props.getMethod())
                    && quadFace != null && blockState != null && !blockState.isOpaqueCube()
                    && props.appliesTo(quadFace)
                    && isFullyCoveredInternalFace(quad, quadFace, props, ctx)) {
                return java.util.Collections.emptyList();
            }
            BakedQuad baseQuad = applyRule(quad, sprite, props, ctx, quadFace, blockState, 0);
            if (baseQuad == null) baseQuad = quad;
            return java.util.Collections.singletonList(baseQuad);
        }
        return java.util.Collections.singletonList(quad);
    }

    /**
     * 是否是「连接类」CTM 方法（通过连接检测选 tile）。
     * random/repeat/fixed 等非连接方法不参与内部面剔除（树叶等无连接规则的透明方块
     * 依赖 repeat 等规则时也不应被剔除，避免内部面消失）。
     */
    private static boolean isConnectingMethod(String method) {
        if (method == null) return false;
        switch (method) {
            case "ctm":
            case "ctm_compact":
            case "horizontal":
            case "vertical":
            case "horizontal+vertical":
            case "h+v":
            case "vertical+horizontal":
            case "v+h":
            case "top":
                return true;
            default:
                return false;
        }
    }

    /**
     * 内部面剔除判定（证据驱动的薄适配层）：把 Minecraft 对象抽取成纯数据，
     * 交给 {@link InternalFaceCulling#shouldCull} 做几何 + 连接谓词判定。
     *
     * <p>几何证据来自邻居<b>实际烘焙 quad</b>（对侧面 + 朝向一致的 general quads），
     * 而不是 bbox 或 {@code BlockFaceShape.SOLID}——楼梯、竖半砖的 bbox/面形状可能与
     * 实际渲染几何不同。任何一步不确定（取态/取模型/取 quad 异常、CTM 重入、几何不合格、
     * 模型偏移不一致）都保留面。
     *
     * <p>状态与模型顺序与渲染器一致：先 {@code getActualState}，用 actual 查
     * {@code getModelForState}，再 {@code getExtendedState(actual,...)} 传给
     * {@code model.getQuads}（extended 通常不是模型注册键）。
     *
     * <p>成本顺序：最廉价的共面/矩形校验最先执行；偏移/连接谓词其次；最后才查询邻居模型。
     */
    private static boolean isFullyCoveredInternalFace(BakedQuad quad, EnumFacing face,
                                                      CtmProperties props, RenderContext ctx) {
        if (quad == null || face == null || props == null || ctx == null) return false;
        if (ctx.pos == null || ctx.world == null) return false;

        int axis;
        switch (face) {
            case DOWN: case UP: axis = 1; break;
            case NORTH: case SOUTH: axis = 2; break;
            default: axis = 0; // EAST / WEST
        }
        boolean positive = face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE;
        float eps = QuadCoverage.GEOMETRY_EPSILON;

        // ---- 目标 quad：必须是共面的轴对齐矩形 ----
        float[] min = new float[3];
        float[] max = new float[3];
        if (!quadBounds(quad, min, max)) return false;
        if (Math.abs(max[axis] - min[axis]) > eps) return false;
        float plane = min[axis];
        if (plane < -eps || plane > 1.0f + eps) return false;

        QuadCoverage.Rect target = quadRect(quad, axis, plane, eps);
        if (target == null) return false;

        // ---- 邻居 / 自身状态 ----
        BlockPos npos = ctx.pos.offset(face);
        IBlockState neighborRaw;
        IBlockState selfRaw;
        try {
            neighborRaw = ctx.world.getBlockState(npos);
            selfRaw = ctx.world.getBlockState(ctx.pos);
        } catch (Exception e) {
            return false;
        }
        if (neighborRaw == null || neighborRaw.getBlock() == null) return false;
        if (selfRaw == null || selfRaw.getBlock() == null) return false;
        // 取态会再次进入 MixinBlock#getExtendedState：查询期间抑制其对复用上下文的写入，
        // 并保存/恢复 CURRENT_CONTEXT 引用（支持嵌套）。
        IBlockState neighborActual;
        IBlockState selfActual;
        IBlockState neighborExtended;
        boolean previousSuppress = EXTENDED_STATE_SUPPRESS.get();
        RenderContext savedCtx = CURRENT_CONTEXT.get();
        EXTENDED_STATE_SUPPRESS.set(true);
        try {
            neighborActual = safeActualState(ctx.world, npos, neighborRaw);
            selfActual = safeActualState(ctx.world, ctx.pos, selfRaw);
            // model registry 键：必须用 actual state；extended 只用于 getQuads。
            neighborExtended = safeExtendedState(neighborActual, ctx.world, npos);
        } finally {
            EXTENDED_STATE_SUPPRESS.set(previousSuppress);
            if (savedCtx != null) {
                CURRENT_CONTEXT.set(savedCtx);
            } else {
                CURRENT_CONTEXT.remove();
            }
        }

        // 取态失败 = 无证据：保留面（不得用 raw fallback 冒充 actual/extended）。
        if (neighborActual == null || selfActual == null || neighborExtended == null) return false;
        if (neighborActual.getBlock() == null || selfActual.getBlock() == null) return false;

        // ---- 模型偏移：渲染器会把 state.getOffset 加到顶点上 ----
        // 本地坐标比较只在两边偏移一致时成立；偏移非零且不相等、或无法读取 → 保留面。
        if (!sameFiniteOffset(selfActual, ctx.pos, neighborActual, npos, ctx.world)) return false;

        // ---- 连接谓词证据（与连接掩码同一实现）----
        // 注意 self 语义：连接掩码内部用 world.getBlockState(pos)（raw state），
        // 这里同样传 selfRaw，保证掩码与剔除对 state 的比较基准一致（actual/extended
        // 只用于模型选择与几何证据）。connect=tile 的几何覆盖必须与「主语义」同源：
        // connectTiles 只是额外放行，若连接仅由 connectTiles 成立，邻居该面纹理与
        // 本 quad 未必相同，不能作为几何遮盖证据（同一面混纹理时不得互相作证）。
        InternalFaceCulling.ConnectKind kind =
            InternalFaceCulling.connectKind(props.getConnect());
        boolean connects;
        try {
            connects = kind == InternalFaceCulling.ConnectKind.TILE
                ? ConnectionHandler.connectsToPrimary(ctx.world, ctx.pos, selfRaw, props,
                    quad.getSprite(), face, npos)
                : ConnectionHandler.connectsTo(ctx.world, ctx.pos, selfRaw, props,
                    quad.getSprite(), face, npos);
        } catch (Throwable t) {
            return false;
        }
        if (!connects) return false;

        // ---- 邻居实际 quad 在目标平面上的矩形并集 ----
        // connect=tile 时覆盖证据必须与连接证据同源：只接受原始纹理与当前 quad 相同的面。
        String tileFilter = kind == InternalFaceCulling.ConnectKind.TILE && quad.getSprite() != null
            ? quad.getSprite().getIconName() : null;
        // 邻居朝向本方块的方向在法线轴上的符号：EAST 面的邻居面朝西（-1）
        int requiredNormalSign = positive ? -1 : 1;
        List<QuadCoverage.Rect> neighborRects = neighborFaceRects(
            neighborActual, neighborExtended, npos, face.getOpposite(),
            plane - InternalFaceCulling.planeShift(positive),
            axis, requiredNormalSign, tileFilter, eps);
        if (neighborRects == null || neighborRects.isEmpty()) return false;

        // 连接谓词已由上面的统一入口证明；不要再用 connectAllows 二次分派，
        // 否则 connectTiles-only 连接会被否决。
        return InternalFaceCulling.shouldCull(target, neighborRects, true, eps);
    }

    /**
     * 两方块的模型偏移是否可安全比较：都必须有限且逐分量相等（容差仅抵消浮点误差）。
     * 读取异常视为不可比较（保留面）。
     */
    static boolean sameFiniteOffset(IBlockState self, BlockPos selfPos,
                                    IBlockState neighbor, BlockPos neighborPos,
                                    IBlockAccess world) {
        if (self == null || neighbor == null || world == null
                || selfPos == null || neighborPos == null) {
            return false;
        }
        try {
            net.minecraft.util.math.Vec3d a = self.getBlock().getOffset(self, world, selfPos);
            net.minecraft.util.math.Vec3d b = neighbor.getBlock().getOffset(neighbor, world, neighborPos);
            if (a == null || b == null) return false;
            if (!finite(a.x) || !finite(a.y) || !finite(a.z)
                    || !finite(b.x) || !finite(b.y) || !finite(b.z)) {
                return false;
            }
            float eps = QuadCoverage.GEOMETRY_EPSILON;
            return Math.abs(a.x - b.x) <= eps
                && Math.abs(a.y - b.y) <= eps
                && Math.abs(a.z - b.z) <= eps;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /** 读取 quad 三轴范围（方块单位，不做单位推断）；数据不合法返回 false。 */
    private static boolean quadBounds(BakedQuad quad, float[] min, float[] max) {
        VertexLayout layout = VertexLayout.of(quad);
        return layout != null && QuadCoverage.bounds(
            quad.getVertexData(), layout.stride, layout.positionOffset, min, max);
    }

    /** 提取 quad 在给定平面上的轴对齐矩形；不是合格矩形返回 null。 */
    private static QuadCoverage.Rect quadRect(BakedQuad quad, int axis, float plane, float eps) {
        VertexLayout layout = VertexLayout.of(quad);
        if (layout == null) return null;
        return QuadCoverage.rect(quad.getVertexData(), layout.stride, layout.positionOffset,
            axis, plane, eps);
    }

    /**
     * 查询邻居模型在指定面上的实际 quad，提取与给定平面共面的轴对齐矩形。
     *
     * <p>模型按 <b>actual</b> state 解析（extended state 通常不是模型注册键），
     * quads 用 <b>extended</b> state 查询（连接方向等运行时属性在其中），
     * 种子用 {@code MathHelper.getPositionRandom(npos)}——与渲染器一致。
     *
     * <p>同时查询指定面与 general quads（side=null）：竖半砖等无 cullface 模型
     * 的面全部进入 general quads。查询期间：
     * <ul>
     *   <li>设置 CTM 原始 quad 查询标志，阻止 CtmBakedModel 递归替换（再次进入时返回
     *       parent 原始 quads）；</li>
     *   <li>抑制 MixinBlock 对复用 RenderContext 的写入，避免邻居 getExtendedState
     *       篡改当前渲染上下文；</li>
     *   <li>保存并恢复 {@link #CURRENT_CONTEXT} 引用与抑制标志（支持嵌套）。</li>
     * </ul>
     * 任何异常返回 null（调用方保留面）。
     */
    private static List<QuadCoverage.Rect> neighborFaceRects(IBlockState neighborActual,
                                                             IBlockState neighborExtended,
                                                             BlockPos npos,
                                                             EnumFacing neighborFace, float localPlane,
                                                             int axis, int requiredNormalSign,
                                                             String tileFilter, float eps) {
        RenderContext savedCtx = CURRENT_CONTEXT.get();
        boolean previousGuard = ConnectionHandler.beginRawQuadLookup();
        boolean previousSuppress = EXTENDED_STATE_SUPPRESS.get();
        EXTENDED_STATE_SUPPRESS.set(true);
        try {
            BlockModelShapes shapes = Minecraft.getMinecraft()
                .getBlockRendererDispatcher().getBlockModelShapes();
            if (shapes == null) return null;
            IBakedModel model = shapes.getModelForState(neighborActual);
            if (model == null) return null;
            if (model.isBuiltInRenderer()) return null;
            return collectNeighborRects(model, neighborExtended, npos, neighborFace,
                localPlane, axis, requiredNormalSign, tileFilter, eps);
        } catch (Throwable t) {
            return null;
        } finally {
            EXTENDED_STATE_SUPPRESS.set(previousSuppress);
            ConnectionHandler.endRawQuadLookup(previousGuard);
            if (savedCtx != null) {
                CURRENT_CONTEXT.set(savedCtx);
            } else {
                CURRENT_CONTEXT.remove();
            }
        }
    }

    /**
     * 从已解析的邻居模型收集覆盖矩形（包内可见的测试注入点）：
     * 同时查询指定面与 general quads，种子用 {@code MathHelper.getPositionRandom(npos)}。
     * 查询期间的原始 quad 守卫由调用方持有。
     */
    static List<QuadCoverage.Rect> collectNeighborRects(IBakedModel model, IBlockState extended,
                                                        BlockPos npos, EnumFacing neighborFace,
                                                        float localPlane, int axis,
                                                        int requiredNormalSign,
                                                        String tileFilter, float eps) {
        long rand = MathHelper.getPositionRandom(npos);
        List<QuadCoverage.Rect> rects = new ArrayList<>(4);
        collectRects(model, extended, neighborFace, rand, axis, localPlane,
            requiredNormalSign, tileFilter, eps, rects);
        collectRects(model, extended, null, rand, axis, localPlane,
            requiredNormalSign, tileFilter, eps, rects);
        return rects;
    }

    /**
     * 收集指定面上的合格覆盖矩形。筛选条件：
     * <ul>
     *   <li>共面（在 {@code localPlane ± eps}）；</li>
     *   <li>轴对齐矩形（{@link QuadCoverage#rect} 会拒绝对角/蝶形/退化 quad）；</li>
     *   <li>朝向与目标面相反（邻居面朝本方块），避免把背面/侧向 quad 当作遮盖证据；
     *       side != null 时还要求 quad 的 face 精确等于 side，无朝向 quad 不作证；</li>
     *   <li>connect=tile 时原始纹理名与当前 quad 相同（同一面混纹理时不得互相作证）。</li>
     * </ul>
     */
    static void collectRects(IBakedModel model, IBlockState state, EnumFacing side, long rand,
                             int axis, float localPlane, int requiredNormalSign,
                             String tileFilter, float eps,
                             List<QuadCoverage.Rect> out) {
        List<BakedQuad> quads = model.getQuads(state, side, rand);
        if (quads == null || quads.isEmpty()) return;
        for (BakedQuad q : quads) {
            if (q == null) continue;
            // 由 side 查询到的 quad 必须是该朝向；general 查询则靠下方法线符号校验。
            if (side != null && q.getFace() != side) continue;
            if (tileFilter != null) {
                TextureAtlasSprite s = q.getSprite();
                if (s == null || !tileFilter.equals(s.getIconName())) continue;
            }
            VertexLayout layout = VertexLayout.of(q);
            if (layout == null) continue;
            if (QuadCoverage.axisNormalSign(q.getVertexData(), layout.stride,
                layout.positionOffset, axis) != requiredNormalSign) {
                continue;
            }
            QuadCoverage.Rect r = QuadCoverage.rect(q.getVertexData(), layout.stride,
                layout.positionOffset, axis, localPlane, eps);
            if (r != null) out.add(r);
        }
    }

    /** 顶点格式布局（stride 与位置偏移，单位 int）。 */
    private static final class VertexLayout {
        final int stride;
        final int positionOffset;

        private VertexLayout(int stride, int positionOffset) {
            this.stride = stride;
            this.positionOffset = positionOffset;
        }

        static VertexLayout of(BakedQuad quad) {
            if (quad == null) return null;
            int[] data = quad.getVertexData();
            VertexFormat format = quad.getFormat();
            if (data == null || format == null) return null;
            try {
                if (format.getElementCount() == 0) return null;
                VertexFormatElement pos = format.getElement(0);
                if (pos.getUsage() != VertexFormatElement.EnumUsage.POSITION) return null;
                // 位置必须是 3 个 float（不能用 short/其他类型，否则 intBitsToFloat 会读错）
                if (pos.getType() != VertexFormatElement.EnumType.FLOAT) return null;
                if (pos.getElementCount() != 3) return null;
                if (pos.getIndex() != 0) return null;
                int stride = format.getIntegerSize();
                int offset = format.getOffset(0) / 4;
                if (stride < 3 || offset < 0 || offset + 2 >= stride) return null;
                if ((long) stride * 4 > data.length) return null;
                return new VertexLayout(stride, offset);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * 取 extended state（连接方向等运行时属性在其中；扩展状态可能依赖世界）。
     *
     * <p>顺序固定为：<b>先 actual，再 extended(actual)</b>，与渲染器一致
     * （{@code VintageBlockRenderer}：actual → shapes.getModelForState(actual) →
     * extended(actual) → model.getQuads(extended,...)）。extended state 通常不是
     * 模型注册键，因此绝不能用 extended 去查模型。
     *
     * <p>查询失败返回 {@code null}（调用方必须视为「无证据」并保留面）：
     * 不能用 fallback state 冒充 extended state 作为几何证据。
     */
    static IBlockState safeExtendedState(IBlockState actual, IBlockAccess world, BlockPos pos) {
        if (actual == null || world == null || pos == null) return null;
        try {
            return actual.getBlock().getExtendedState(actual, world, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 取 actual state。查询失败/无世界坐标/返回 null 时返回 {@code null}
     * （调用方必须视为「无证据」并保留面，绝不把异常当空气或沿用 raw state）。
     */
    static IBlockState safeActualState(IBlockAccess world, BlockPos pos, IBlockState fallback) {
        if (fallback == null || world == null || pos == null) return null;
        try {
            return fallback.getActualState(world, pos);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否草类方块（BetterGrass 判定用） */
    private static boolean isGrassLike(IBlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        return b instanceof BlockGrass || b instanceof BlockMycelium;
    }

    /** 是否是草侧面纹理（grass_side，不含 overlay/snowed 层） */
    private static boolean isGrassSideSprite(TextureAtlasSprite sprite) {
        String icon = sprite.getIconName();
        return icon != null && icon.endsWith("grass_side")
            && !icon.endsWith("grass_side_overlay") && !icon.endsWith("grass_side_snowed");
    }

    /** 是否是草侧面 overlay 草皮条纹理（grass_side_overlay） */
    private static boolean isGrassSideOverlaySprite(TextureAtlasSprite sprite) {
        String icon = sprite.getIconName();
        return icon != null && icon.endsWith("grass_side_overlay");
    }

    /**
     * 更好的雪地判定：当前方块上方是雪层（且当前方块本身不是雪/冰，避免自覆盖）。
     */
    private static boolean isSnowCovered(RenderContext ctx) {
        IBlockState selfState = ctx.world.getBlockState(ctx.pos);
        Block self = selfState.getBlock();
        if (self == Blocks.SNOW_LAYER || self == Blocks.SNOW || self == Blocks.ICE
                || self == Blocks.PACKED_ICE || self == Blocks.FROSTED_ICE) {
            return false;
        }
        // 只对不透明实心方块生效（OptiFine better snow 语义）：树叶等透明方块
        // （isOpaqueCube=false，CUTOUT 层镂空）的侧面不能被雪覆盖，否则会变成
        // 实心外观（如云杉树叶盖雪后看似绿色羊毛）。
        if (!selfState.isOpaqueCube()) {
            return false;
        }
        IBlockState up = ctx.world.getBlockState(ctx.pos.up());
        return up != null && up.getBlock() instanceof BlockSnow;
    }

    /** 是否是染色玻璃方块（minecraft:stained_glass；无色玻璃走内置规则） */
    private static boolean isStainedGlass(IBlockState state) {
        if (state == null || !(state.getBlock() instanceof BlockBreakable)) return false;
        net.minecraft.util.ResourceLocation rl = state.getBlock().getRegistryName();
        return rl != null && "minecraft".equals(rl.getNamespace()) && "stained_glass".equals(rl.getPath());
    }

    /**
     * BetterGrass 连接判定（LambdaBetterGrass canFullyConnect 语义）：
     * 草方块侧面「对角下侧」（selfPos.relative(face).below()）是同种草方块时，
     * 草皮沿此方向延伸 —— 相连面显示草皮（grass_top）而非泥土。
     */
    private static boolean betterGrassConnects(RenderContext ctx, IBlockState blockState, EnumFacing face) {
        BlockPos diag = ctx.pos.offset(face).down();
        IBlockState diagState = ctx.world.getBlockState(diag);
        return isGrassLike(diagState) && diagState.getBlock() == blockState.getBlock();
    }

    /**
     * BetterGrass：草方块侧面连接。
     * 若草方块侧面的「对角下侧」也是同种草方块，则草皮沿此方向延伸——
     * 相连面显示草皮（grass_top）而非泥土。
     *
     * <p>不满足时返回 null，保留 vanilla 双层面（base 泥土 + overlay 草皮条 tint）。
     */
    private BakedQuad tryBetterGrass(BakedQuad quad, RenderContext ctx, IBlockState blockState, EnumFacing face) {
        if (!betterGrassConnects(ctx, blockState, face)) {
            return null;
        }

        TextureAtlasSprite grassTop;
        if (blockState.getBlock() instanceof BlockMycelium) {
            grassTop = Minecraft.getMinecraft().getTextureMapBlocks()
                .getAtlasSprite("minecraft:blocks/mycelium_top");
            if (grassTop == null || !"minecraft:blocks/mycelium_top".equals(grassTop.getIconName())) {
                return null;
            }
        } else {
            grassTop = Minecraft.getMinecraft().getTextureMapBlocks()
                .getAtlasSprite("minecraft:blocks/grass_top");
            if (grassTop == null || !"minecraft:blocks/grass_top".equals(grassTop.getIconName())) {
                return null;
            }
        }

        // UV 重映射到 grass_top 全图，并强制 tintindex=0（草顶靠 biome 染色变绿）
        BakedQuad remapped = remapUV(quad, quad.getSprite(), grassTop);
        return new BakedQuad(remapped.getVertexData(), 0, remapped.getFace(), grassTop,
            remapped.shouldApplyDiffuseLighting(), remapped.getFormat());
    }

    /**
     * 应用规则并支持 OptiFine 多层规则链：
     * Conquest 用 1x1 占位瓦片作为中间信号（如 plastered_stone 的 77-81.png），
     * ctm 规则先输出占位瓦片，后续规则再用 matchTiles 匹配这些瓦片名并替换成真正的纹理。
     * 遇到 1x1 占位瓦片时，用其图标名继续匹配下一层规则。
     *
     * @param quadFace quad 的实际朝向（quad.getFace()；真正的无朝向 quad 为 null）
     */
    private BakedQuad applyRule(BakedQuad quad, TextureAtlasSprite sprite, CtmProperties props,
                                RenderContext ctx, EnumFacing quadFace, IBlockState blockState, int depth) {
        if (depth > 4) return quad;

        String method = props.getMethod();
        if (method == null) return quad;

        String tile;
        switch (method) {
            case "ctm":
            case "ctm_compact": {
                if (quadFace == null) return quad;
                int mask = ConnectionHandler.getMask(ctx.world, ctx.pos, quadFace, props, sprite);
                tile = props.tileAt(CTM_INDEX_MAP[mask & 255]);
                break;
            }
            case "horizontal": {
                if (quadFace == null) return quad;
                int mask = ConnectionHandler.getHorizontalMask(ctx.world, ctx.pos, quadFace, props, sprite);
                tile = props.tileAt(HORIZONTAL_INDEX_MAP[mask]);
                break;
            }
            case "vertical": {
                // 标准 vertical：VERTICAL_INDEX_MAP={3,2,0,1}，tiles=[上连,都连,下连,无连]
                //   mask=0(无连)→tiles[3]  mask=1(下连)→tiles[2]  mask=2(上连)→tiles[0]  mask=3(都连)→tiles[1]
                // 连续堆叠序列（下→上）：5,3,3,...,3,1 —— 中间全部是「都连」完整 V 图案，
                // 保证任意数量堆叠中间都连续相连；3 格=下段(5+3)+上段(1)，
                // 4 格=下 2 格(5+3)+上 2 格(3+1)=「2 段 2 格高」。
                // （不要改成 2 周期 tiles[0]/tiles[2] 交替——那会让 V 顶与 V 底在组间断裂，
                //   表现为中间夹 1 格高的断裂图像。）
                if (quadFace == null) {
                    // 真正的无朝向 quad（如部分 mod 自定义 quad）：用世界空间上下方向判断连接
                    int mask = ConnectionHandler.getVerticalMaskWorld(ctx.world, ctx.pos, props, sprite);
                    tile = props.tileAt(VERTICAL_INDEX_MAP[mask]);
                } else {
                    int mask = ConnectionHandler.getVerticalMask(ctx.world, ctx.pos, quadFace, props, sprite);
                    tile = props.tileAt(VERTICAL_INDEX_MAP[mask]);
                }
                break;
            }
            case "horizontal+vertical":
            case "h+v": {
                if (quadFace == null) return quad;
                int hm = ConnectionHandler.getHorizontalMask(ctx.world, ctx.pos, quadFace, props, sprite);
                if (hm != 0) {
                    tile = props.tileAt(HORIZONTAL_INDEX_MAP[hm]);
                } else {
                    int vm = ConnectionHandler.getVerticalMask(ctx.world, ctx.pos, quadFace, props, sprite);
                    tile = props.tileAt(VERTICAL_INDEX_MAP[vm]);
                }
                break;
            }
            case "vertical+horizontal":
            case "v+h": {
                if (quadFace == null) return quad;
                int vm = ConnectionHandler.getVerticalMask(ctx.world, ctx.pos, quadFace, props, sprite);
                if (vm != 0) {
                    tile = props.tileAt(VERTICAL_INDEX_MAP[vm]);
                } else {
                    int hm = ConnectionHandler.getHorizontalMask(ctx.world, ctx.pos, quadFace, props, sprite);
                    tile = props.tileAt(HORIZONTAL_INDEX_MAP[hm]);
                }
                break;
            }
            case "top": {
                if (quadFace == null) return quad;
                if (ConnectionHandler.isConnected(ctx.world, ctx.pos, props, sprite, EnumFacing.UP)) {
                    tile = props.tileAt(0);
                } else {
                    return quad;
                }
                break;
            }
            case "repeat": {
                int width = Math.max(1, props.getWidth());
                int height = Math.max(1, props.getHeight());
                int count = props.getTileCount();
                // OptiFine 按面映射重复坐标（对照 OptiFine 1.12.2 字节码 getConnectedTextureRepeat）：
                //   DOWN=(x,-z-1)  UP=(x,z)  NORTH=(-x-1,-y)  SOUTH=(x,-y)  WEST=(z,-y)  EAST=(-z-1,-y)
                // 侧面用 y 作为「下→上」行、水平轴作列；顶底用 x/z。无朝向 quad 退化为 (x,y)。
                int tileX, tileY;
                if (quadFace == null) {
                    tileX = ctx.pos.getX();
                    tileY = ctx.pos.getY();
                } else {
                    int x = ctx.pos.getX(), y = ctx.pos.getY(), z = ctx.pos.getZ();
                    switch (quadFace) {
                        case DOWN:  tileX = x; tileY = -z - 1; break;
                        case UP:    tileX = x; tileY = z; break;
                        case NORTH: tileX = -x - 1; tileY = -y; break;
                        case SOUTH: tileX = x; tileY = -y; break;
                        case WEST:  tileX = z; tileY = -y; break;
                        case EAST:  tileX = -z - 1; tileY = -y; break;
                        default:    tileX = x; tileY = y; break;
                    }
                }
                int idx = (Math.floorMod(tileY, height) * width + Math.floorMod(tileX, width)) % count;
                tile = props.tileAt(idx);
                break;
            }
            case "random": {
                tile = props.tileAt(pickRandomTile(props, ctx.pos, quadFace));
                break;
            }
            case "fixed": {
                tile = props.tileAt(0);
                break;
            }
            default:
                return quad;
        }

        if (tile == null) {
            return quad;
        }
        TextureAtlasSprite tileSprite = CTMSpriteRegistry.getTileSprite(props, tile);
        if (tileSprite == null) {
            return quad;
        }

        // OptiFine 多层规则链：Conquest 用「信号瓦片」作为中间层（如 plastered_stone 77-81、
        // 藤蔓 1-4、cobblestone 500/503），输出后由后续规则用 matchTiles 匹配瓦片名并替换成真纹理。
        // 信号瓦片可能是 1x1 或声明 32x32 的透明/单色 PNG，无法可靠按尺寸判断，
        // 因此总是尝试用瓦片图标名匹配下一层规则；有则继续链，无则作为最终瓦片。
        // 注意：只有 matchTiles 规则参与链式匹配（信号瓦片靠 matchTiles 命中）。
        // 纯 matchBlocks 规则（如 Conquest block208 匹配 minecraft:grass_path 方块）
        // 不应劫持瓦片名——否则 Patrix 等 matchTiles 规则输出的瓦片会被 block-based
        // 规则覆盖（土径显示 Conquest 纹理的根因）。
        CtmProperties next = CtmPropertiesLoader.findMatch(tileSprite.getIconName(), blockState, quadFace,
            ctx.world, ctx.pos);
        if (next != null && next != props && !next.getMatchTilesClean().isEmpty()) {
            BakedQuad chained = applyRule(quad, sprite, next, ctx, quadFace, blockState, depth + 1);
            if (chained != quad) return chained;
        }

        // 1x1 占位瓦片且无后续规则：透明黑，直接渲染会变黑，跳过保留默认纹理
        if (tileSprite.getIconWidth() <= 1 && tileSprite.getIconHeight() <= 1) {
            return quad;
        }

        return remapUV(quad, sprite, tileSprite);
    }

    private int pickRandomTile(CtmProperties props, BlockPos pos, EnumFacing side) {
        int count = props.getTileCount();
        if (count <= 1) return 0;
        long hash = MathHelper.getPositionRandom(pos);
        int h = (int) hash;
        if (h < 0) h = -h;

        // symmetry 处理：all 所有面同种子；opposite 相对面同种子；none 每面独立
        String symmetry = props.getSymmetry();
        if ("all".equals(symmetry)) {
            // 只用方块位置，所有面一致
        } else if ("opposite".equals(symmetry) && side != null) {
            // 相对面共享种子：up/down、north/south、east/west
            int group;
            switch (side) {
                case UP: case DOWN: group = 0; break;
                case NORTH: case SOUTH: group = 1; break;
                default: group = 2; // EAST/WEST
            }
            h = Math.floorMod(h + group * 7919, 2147483647);
        } else if (side != null) {
            // none：每面独立
            h = Math.floorMod(h + side.ordinal() * 104729, 2147483647);
        }

        int[] weights = props.getWeights();
        if (weights != null && weights.length >= count) {
            int total = 0;
            for (int i = 0; i < count; i++) total += weights[i];
            if (total <= 0) return Math.floorMod(h, count);
            int r = Math.floorMod(h, total);
            int cumulative = 0;
            for (int i = 0; i < count; i++) {
                cumulative += weights[i];
                if (r < cumulative) return i;
            }
            return Math.floorMod(h, count);
        }
        return Math.floorMod(h, count);
    }

    /**
     * 将四边形的 UV 从原始精灵区域重映射到替换精灵区域（保持相对位置）。
     *
     * <p>关键：getInterpolatedU/V 的参数是「模型空间 UV」（固定 0..16），
     * 内部实现为 minU + (maxU-minU) * x / 16.0f（除数硬编码 16）。
     * 因此传 relU * 16.0f 即可映射到替换精灵的完整区域，与精灵像素尺寸无关。
     * 若按 iconWidth 缩放（如 32），会过采样 2 倍，扫到图集里相邻精灵的贴图。
     */
    private BakedQuad remapUV(BakedQuad quad, TextureAtlasSprite original, TextureAtlasSprite replacement) {
        VertexFormat format = quad.getFormat();
        if (format == null) return quad;
        int uvOffset = format.getUvOffsetById(0);
        if (uvOffset < 0) return quad;
        int stride = format.getIntegerSize();
        int uvIdx = uvOffset / 4;
        int[] data = quad.getVertexData();
        // 格式/数据长度防护：UV 必须是 2 个 float 且 4 个顶点都在数据范围内
        if (stride < 1 || uvIdx < 0 || uvIdx + 1 >= stride || data == null) return quad;
        if ((long) stride * 4 > data.length) return quad;

        float oMinU = original.getMinU(), oMaxU = original.getMaxU();
        float oMinV = original.getMinV(), oMaxV = original.getMaxV();
        float du = oMaxU - oMinU;
        float dv = oMaxV - oMinV;
        if (du <= 0.0f || dv <= 0.0f) return quad;

        int[] copy = Arrays.copyOf(data, data.length);
        for (int i = 0; i < 4; i++) {
            int idx = i * stride + uvIdx;
            float u = Float.intBitsToFloat(copy[idx]);
            float v = Float.intBitsToFloat(copy[idx + 1]);
            float relU = (u - oMinU) / du;
            float relV = (v - oMinV) / dv;
            copy[idx] = Float.floatToIntBits(replacement.getInterpolatedU(relU * 16.0f));
            copy[idx + 1] = Float.floatToIntBits(replacement.getInterpolatedV(relV * 16.0f));
        }
        return new BakedQuad(copy, quad.getTintIndex(), quad.getFace(), replacement,
            quad.shouldApplyDiffuseLighting(), format);
    }

    @Override public boolean isAmbientOcclusion() { return parent.isAmbientOcclusion(); }
    @Override public boolean isGui3d() { return parent.isGui3d(); }
    @Override public boolean isBuiltInRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleTexture() { return parent.getParticleTexture(); }
    @Override public ItemCameraTransforms getItemCameraTransforms() { return parent.getItemCameraTransforms(); }
    @Override public ItemOverrideList getOverrides() { return parent.getOverrides(); }
    @Override public Pair<? extends IBakedModel, Matrix4f> handlePerspective(ItemCameraTransforms.TransformType t) {
        return parent.handlePerspective(t);
    }
}
