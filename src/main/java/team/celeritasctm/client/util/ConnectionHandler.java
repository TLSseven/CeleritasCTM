package team.celeritasctm.client.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import team.celeritasctm.client.model.CtmBakedModel;
import team.celeritasctm.client.resource.CtmProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OptiFine 兼容的连接检测器。
 *
 * <p>方向约定（纹理空间，与 OptiFine/Continuity 一致）：
 * 每个面定义 [左, 下, 右, 上] 四个纹理方向（map[0] 布局）。
 * CTM 掩码位：bit0=左, bit1=左下, bit2=下, bit3=右下, bit4=右, bit5=右上, bit6=上, bit7=左上
 * （斜角只在相邻正交方向都连接时才检查）。
 */
public final class ConnectionHandler {

    /**
     * 每个面的纹理方向 [左, 下, 右, 上]（与 vanilla 方块模型 UV 约定一致）。
     * 已对照 OptiFine 1.12.2 字节码（SIDES_Y_NEG_DOWN/SIDES_Y_POS_UP/SIDES_Z_NEG_NORTH 等表，
     * 顺序 [left, right, down, up]）与 Continuity DirectionMaps map[0] 双重验证：
     * NORTH=[EAST,DOWN,WEST,UP]、SOUTH=[WEST,DOWN,EAST,UP]（注意左右与直觉相反——
     * 看向北面时纹理右方向是 -X，左方向是 +X，与 vanilla quad UV 一致）。
     */
    public static final EnumFacing[][] FACE_DIRS = new EnumFacing[6][];

    private static final ThreadLocal<Map<IBlockState, String>> NEIGHBOR_SPRITE_CACHE =
        ThreadLocal.withInitial(HashMap::new);

    /** 缓存世代：资源重载时自增，所有工作线程在下次访问时惰性丢弃旧缓存。 */
    private static final AtomicLong GENERATION = new AtomicLong();

    private static final ThreadLocal<Long> SPRITE_CACHE_GENERATION = ThreadLocal.withInitial(() -> -1L);

    // 重入保护：获取邻居精灵时若触发 CtmBakedModel 递归，返回 null 终止
    private static final ThreadLocal<Boolean> TILE_LOOKUP_GUARD = ThreadLocal.withInitial(() -> false);

    /**
     * 原始 quad 查询的嵌套引用计数（ThreadLocal）：内部面剔除查询邻居几何时，
     * 需要模型返回未经过 CTM 替换的原始 quads，并且不得递归进入剔除逻辑。
     * 用计数而非布尔值，保证嵌套调用退出后状态正确恢复。
     */
    private static final ThreadLocal<Integer> RAW_QUAD_LOOKUP_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 进入原始 quad 查询；返回进入前的状态（供 {@link #endRawQuadLookup} 恢复）。 */
    public static boolean beginRawQuadLookup() {
        boolean previous = isTileLookupActive();
        RAW_QUAD_LOOKUP_DEPTH.set(RAW_QUAD_LOOKUP_DEPTH.get() + 1);
        TILE_LOOKUP_GUARD.set(true);
        return previous;
    }

    /** 退出原始 quad 查询，恢复到进入前的状态。 */
    public static void endRawQuadLookup(boolean previousActive) {
        int depth = RAW_QUAD_LOOKUP_DEPTH.get() - 1;
        if (depth <= 0) {
            RAW_QUAD_LOOKUP_DEPTH.set(0);
            TILE_LOOKUP_GUARD.set(previousActive);
        } else {
            RAW_QUAD_LOOKUP_DEPTH.set(depth);
            TILE_LOOKUP_GUARD.set(true);
        }
    }

    /**
     * 连接检测是否处于「邻居纹理查询」状态。
     * CtmBakedModel 在该状态下必须返回原始 quads（parent），
     * 否则 connect=tile 拿到的会是 CTM 替换后的精灵，导致比较永远失败。
     */
    public static boolean isTileLookupActive() {
        return TILE_LOOKUP_GUARD.get();
    }

    static {
        FACE_DIRS[EnumFacing.NORTH.ordinal()] = new EnumFacing[] { EnumFacing.EAST, EnumFacing.DOWN, EnumFacing.WEST, EnumFacing.UP };
        FACE_DIRS[EnumFacing.SOUTH.ordinal()] = new EnumFacing[] { EnumFacing.WEST, EnumFacing.DOWN, EnumFacing.EAST, EnumFacing.UP };
        FACE_DIRS[EnumFacing.EAST.ordinal()]  = new EnumFacing[] { EnumFacing.SOUTH, EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.UP };
        FACE_DIRS[EnumFacing.WEST.ordinal()]  = new EnumFacing[] { EnumFacing.NORTH, EnumFacing.DOWN, EnumFacing.SOUTH, EnumFacing.UP };
        FACE_DIRS[EnumFacing.UP.ordinal()]    = new EnumFacing[] { EnumFacing.WEST, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.NORTH };
        FACE_DIRS[EnumFacing.DOWN.ordinal()]  = new EnumFacing[] { EnumFacing.WEST, EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH };
    }

    private ConnectionHandler() {}

    /** 8 方向连接掩码（用于 method=ctm） */
    public static int getMask(IBlockAccess world, BlockPos pos, EnumFacing face,
                              CtmProperties props, TextureAtlasSprite quadSprite) {
        IBlockState self = world.getBlockState(pos);
        EnumFacing[] dirs = FACE_DIRS[face.ordinal()];
        int mask = 0;
        for (int i = 0; i < 4; i++) {
            if (shouldConnect(world, pos, self, props, quadSprite, pos.offset(dirs[i]), face)) {
                mask |= 1 << (i * 2);
            }
        }
        for (int i = 0; i < 4; i++) {
            int i2 = (i + 1) % 4;
            if (((mask >>> (i * 2)) & 1) == 1 && ((mask >>> (i2 * 2)) & 1) == 1) {
                BlockPos corner = pos.offset(dirs[i]).offset(dirs[i2]);
                if (shouldConnect(world, pos, self, props, quadSprite, corner, face)) {
                    mask |= 1 << (i * 2 + 1);
                }
            }
        }
        return mask;
    }

    /** 水平掩码（用于 method=horizontal / h+v）：bit0=左, bit1=右 */
    public static int getHorizontalMask(IBlockAccess world, BlockPos pos, EnumFacing face,
                                        CtmProperties props, TextureAtlasSprite quadSprite) {
        IBlockState self = world.getBlockState(pos);
        EnumFacing[] dirs = FACE_DIRS[face.ordinal()];
        int mask = 0;
        if (shouldConnect(world, pos, self, props, quadSprite, pos.offset(dirs[0]), face)) mask |= 1;
        if (shouldConnect(world, pos, self, props, quadSprite, pos.offset(dirs[2]), face)) mask |= 2;
        return mask;
    }

    /** 垂直掩码（用于 method=vertical / v+h）：bit0=下, bit1=上 */
    public static int getVerticalMask(IBlockAccess world, BlockPos pos, EnumFacing face,
                                      CtmProperties props, TextureAtlasSprite quadSprite) {
        IBlockState self = world.getBlockState(pos);
        EnumFacing[] dirs = FACE_DIRS[face.ordinal()];
        int mask = 0;
        if (shouldConnect(world, pos, self, props, quadSprite, pos.offset(dirs[1]), face)) mask |= 1;
        if (shouldConnect(world, pos, self, props, quadSprite, pos.offset(dirs[3]), face)) mask |= 2;
        return mask;
    }

    /**
     * 世界空间垂直掩码（cross 模型如藤蔓的 quad face 为 null）：
     * bit0=下方连接, bit1=上方连接（直接检查世界 Y 方向）
     */
    public static int getVerticalMaskWorld(IBlockAccess world, BlockPos pos,
                                           CtmProperties props, TextureAtlasSprite quadSprite) {
        IBlockState self = world.getBlockState(pos);
        int mask = 0;
        if (shouldConnect(world, pos, self, props, quadSprite, pos.down(), EnumFacing.UP)) mask |= 1;
        if (shouldConnect(world, pos, self, props, quadSprite, pos.up(), EnumFacing.UP)) mask |= 2;
        return mask;
    }

    /** 单个方向连接检测（用于 method=top） */
    public static boolean isConnected(IBlockAccess world, BlockPos pos,
                                      CtmProperties props, TextureAtlasSprite quadSprite, EnumFacing facing) {
        IBlockState self = world.getBlockState(pos);
        return shouldConnect(world, pos, self, props, quadSprite, pos.offset(facing), facing);
    }

    private static boolean shouldConnect(IBlockAccess world, BlockPos pos, IBlockState self,
                                         CtmProperties props, TextureAtlasSprite quadSprite,
                                         BlockPos otherPos, EnumFacing face) {
        boolean connected = primaryConnect(world, self, props, quadSprite, otherPos, face);
        if (connected) return true;

        // connectTiles 附加条件：与使用了指定纹理的方块也连接（如 plank ↔ slab）
        List<String> connectTiles = props.getConnectTiles();
        if (!connectTiles.isEmpty()) {
            IBlockState other = otherState(world, otherPos);
            if (other == null) return false;
            String name = spriteNameOf(other);
            if (name != null && connectTiles.contains(name)) return true;
        }
        return false;
    }

    /** 邻居 state（同一入口，保证调用方与谓词看到一致语义）。 */
    private static IBlockState otherState(IBlockAccess world, BlockPos otherPos) {
        if (world == null || otherPos == null) return null;
        IBlockState other = world.getBlockState(otherPos);
        return other == null || other.getBlock() == null ? null : other;
    }

    /**
     * 只按 connect 主语义判定（不含 connectTiles 额外放行）。
     *
     * <p>用于 connect=tile 的内部面剔除几何证据：connectTiles 只是「与使用指定纹理的
     * 方块也连接」的额外放行，邻居该面纹理未必与本 quad 相同，不能在同面混纹理时
     * 互相作遮盖证据。主语义不连接时即便 connectTiles 命中也不剔面（保守）。
     */
    private static boolean primaryConnect(IBlockAccess world, IBlockState self,
                                          CtmProperties props, TextureAtlasSprite quadSprite,
                                          BlockPos otherPos, EnumFacing face) {
        if (otherPos == null || self == null || props == null || world == null) return false;
        IBlockState other = otherState(world, otherPos);
        if (other == null) return false;

        String connect = props.getConnect(); // 含 OptiFine 默认（tile-based→tile, block-based→block）
        if ("material".equals(connect)) {
            return self.getMaterial() == other.getMaterial();
        } else if ("state".equals(connect)) {
            return self.equals(other);
        } else if ("tile".equals(connect)) {
            // OptiFine connect=tile：邻居在当前面的纹理与当前 quad 纹理相同才连接。
            // 必须按真实模型/真实种子取邻居该面纹理，且 general quads 只接受朝向一致的面；
            // 不能只按 state 缓存（动态模型纹理依赖世界坐标与种子）。
            return quadSprite != null && face != null
                && quadSprite.getIconName().equals(
                    tileOfFace(other, world, otherPos, face, quadSprite.getIconName()));
        }
        // block：同方块 ID
        return self.getBlock() == other.getBlock();
    }

    /**
     * 公共连接判定入口：内部面剔除与连接掩码共用同一语义，避免两处各写一套
     * （尤其是 connect=tile：必须用邻居该面的原始烘焙纹理作证，不能以同 state 代替）。
     *
     * @param face 当前 quad 的面（mask 与 cull 都按该面方向取邻居纹理）
     */
    public static boolean connectsTo(IBlockAccess world, BlockPos pos, IBlockState self,
                                     CtmProperties props, TextureAtlasSprite quadSprite,
                                     EnumFacing face, BlockPos otherPos) {
        return shouldConnect(world, pos, self, props, quadSprite, otherPos, face);
    }

    /**
     * 只按 connect 主语义判定（不含 connectTiles 放行），供 connect=tile 的几何剔除使用。
     */
    public static boolean connectsToPrimary(IBlockAccess world, BlockPos pos, IBlockState self,
                                            CtmProperties props, TextureAtlasSprite quadSprite,
                                            EnumFacing face, BlockPos otherPos) {
        return primaryConnect(world, self, props, quadSprite, otherPos, face);
    }

    /**
     * 获取邻居方块在指定面上的 quad 精灵名（原始烘焙纹理，未经 CTM 替换）。
     *
     * <p>动态模型（依赖世界坐标/随机种子的模型）不能按 state 做无位置缓存，否则同一
     * state 在不同坐标/种子下会拿到错误纹理。因此这里<b>不做面纹理缓存</b>，
     * 每次按真实模型 + 真实 {@link MathHelper#getPositionRandom(BlockPos)} 种子查询；
     * 只有「按 state 求默认粒子纹理名」的 {@link #spriteNameOf} 缓存是安全的。
     *
     * <p>取模型顺序必须与渲染器一致：
     * <ol>
     *   <li>{@code other.getActualState(world, otherPos)} 得到 actual state；</li>
     *   <li>用 <b>actual</b> state 查 {@code BlockModelShapes#getModelForState}（extended
     *       state 通常不是模型注册键，不能拿来查模型）；</li>
     *   <li>再取 {@code block.getExtendedState(actual, world, otherPos)}，把 extended state
     *       传给 {@code model.getQuads}（连接方向等运行时属性在 extended state 上）。</li>
     * </ol>
     *
     * <p>查询邻居该面；该面为空（无 cullface 模型的半砖/台阶侧面会进入 general quads）时，
     * 只在 general quads 中找<b>朝向与 side 一致</b>、且纹理名等于 {@code selfSpriteName}
     * 的那个 quad。绝不盲目取第一个 quad（那是顶/底面，比较必然失败）。
     * 任何异常/无法确认一律返回 null（视为不连接，保守保留面）。
     */
    private static String tileOfFace(IBlockState state, IBlockAccess world, BlockPos pos,
                                     EnumFacing side, String selfSpriteName) {
        return tileOfFaceWith(state, world, pos, side, selfSpriteName,
            actualState -> {
                BlockModelShapes shapes = Minecraft.getMinecraft()
                    .getBlockRendererDispatcher().getBlockModelShapes();
                return shapes == null ? null : shapes.getModelForState(actualState);
            });
    }

    /**
     * 模型解析注入点（包内可见，供测试替换）。只负责 actual state → 模型。
     */
    interface ModelLookup {
        IBakedModel modelFor(IBlockState actualState);
    }

    /**
     * {@link #tileOfFace} 的实现，模型解析由 {@code lookup} 提供（生产用
     * {@code BlockModelShapes#getModelForState(actual)}）。
     *
     * <p>查询期间同样抑制 MixinBlock 对复用 RenderContext 的写入，并保存/恢复
     * {@link CtmBakedModel#CURRENT_CONTEXT} 引用（支持嵌套），避免 neighbor 的
     * getExtendedState 篡改正在渲染的方块上下文。
     *
     * <p>纹理匹配顺序：先在<b>指定面</b>按 {@code selfSpriteName} 精确查找，再在
     * general quads 中按「朝向一致 + 纹理相同」查找。不能先取任意第一个面纹理——
     * 同一面混纹理时第一个未必匹配，会漏掉真正相同的 quad。
     */
    static String tileOfFaceWith(IBlockState state, IBlockAccess world, BlockPos pos,
                                 EnumFacing side, String selfSpriteName, ModelLookup lookup) {
        if (TILE_LOOKUP_GUARD.get()) return null;
        if (state == null || world == null || pos == null || side == null || lookup == null) return null;

        CtmBakedModel.RenderContext savedCtx = CtmBakedModel.CURRENT_CONTEXT.get();
        boolean previousSuppress = CtmBakedModel.EXTENDED_STATE_SUPPRESS.get();
        CtmBakedModel.EXTENDED_STATE_SUPPRESS.set(true);
        boolean previousRaw = beginRawQuadLookup();
        try {
            IBlockState actual = safeActualState(world, pos, state);
            if (actual == null) return null;
            IBakedModel model = lookup.modelFor(actual);
            if (model == null || model.isBuiltInRenderer()) return null;

            IBlockState extended = safeExtendedState(actual, world, pos);
            if (extended == null) return null;
            long rand = MathHelper.getPositionRandom(pos);

            // 1) 指定面里按目标纹理精确查找；2) general quads 里按朝向+纹理查找。
            String name = findQuadIconIn(model.getQuads(extended, side, rand), selfSpriteName);
            if (name.isEmpty()) {
                name = findQuadIcon(model, extended, side, rand, selfSpriteName);
            }
            return name.isEmpty() ? null : name;
        } catch (Throwable t) {
            return null;
        } finally {
            endRawQuadLookup(previousRaw);
            CtmBakedModel.EXTENDED_STATE_SUPPRESS.set(previousSuppress);
            if (savedCtx != null) {
                CtmBakedModel.CURRENT_CONTEXT.set(savedCtx);
            } else {
                CtmBakedModel.CURRENT_CONTEXT.remove();
            }
        }
    }

    /** 取 actual state：查询失败返回 null（连接判定视为「无证据」，不连接）。 */
    private static IBlockState safeActualState(IBlockAccess world, BlockPos pos, IBlockState fallback) {
        if (fallback == null || world == null || pos == null) return null;
        try {
            return fallback.getActualState(world, pos);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 actual state 取 extended state；查询失败返回 null（连接判定视为无证据）。 */
    private static IBlockState safeExtendedState(IBlockState actual, IBlockAccess world, BlockPos pos) {
        if (actual == null) return null;
        try {
            return actual.getBlock().getExtendedState(actual, world, pos);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前线程的精灵缓存；世代变化时惰性清空。 */
    private static Map<IBlockState, String> spriteCache() {
        long generation = GENERATION.get();
        Long current = SPRITE_CACHE_GENERATION.get();
        if (current == null || current != generation) {
            Map<IBlockState, String> fresh = new HashMap<>();
            NEIGHBOR_SPRITE_CACHE.set(fresh);
            SPRITE_CACHE_GENERATION.set(generation);
            return fresh;
        }
        return NEIGHBOR_SPRITE_CACHE.get();
    }

    /** 在给定 quad 列表中找 sprite 名等于 {@code spriteName} 的第一个；无匹配返回 ""。 */
    private static String findQuadIconIn(List<BakedQuad> quads, String spriteName) {
        if (quads == null || spriteName == null) return "";
        for (BakedQuad q : quads) {
            if (q != null && q.getSprite() != null
                    && spriteName.equals(q.getSprite().getIconName())) {
                return spriteName;
            }
        }
        return "";
    }

    /**
     * 在 general quads（side=null）中查找：quad 朝向必须等于 {@code side}（无朝向 quad
     * 不参与，避免把顶/底/斜向 quad 当侧面证据），且 sprite 名等于 {@code selfSpriteName}。
     */
    private static String findQuadIcon(IBakedModel model, IBlockState state, EnumFacing side, long rand,
                                       String selfSpriteName) {
        if (selfSpriteName == null) return "";
        List<BakedQuad> quads = model.getQuads(state, null, rand);
        if (quads == null) return "";
        for (BakedQuad q : quads) {
            if (q == null || q.getFace() != side || q.getSprite() == null) continue;
            if (selfSpriteName.equals(q.getSprite().getIconName())) {
                return selfSpriteName;
            }
        }
        return "";
    }

    /**
     * 方块的默认（粒子）纹理名（clean 后）。有缓存；用于 connectTiles 匹配。
     * 自然方块（dirt/sand/gravel 等）各面纹理一致，该值即其面纹理名。
     */
    private static String spriteNameOf(IBlockState state) {
        Map<IBlockState, String> cache = spriteCache();
        String name = cache.get(state);
        if (name == null) {
            try {
                BlockModelShapes shapes = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes();
                TextureAtlasSprite sprite = shapes.getTexture(state);
                name = sprite == null ? "" : CtmProperties.cleanTextureName(sprite.getIconName());
            } catch (Throwable t) {
                name = "";
            }
            cache.put(state, name);
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * 清空缓存并推进全局世代：本线程立即清空，其他 worker 线程在下次访问时
     * 通过世代比较惰性丢弃旧缓存（资源重载后不会有旧纹理/旧 state 残留）。
     * 由 {@code CTMPackReloadListener} 在资源重载时调用。
     */
    public static void clearCaches() {
        GENERATION.incrementAndGet();
        NEIGHBOR_SPRITE_CACHE.remove();
        TILE_LOOKUP_GUARD.remove();
        SPRITE_CACHE_GENERATION.remove();
        RAW_QUAD_LOOKUP_DEPTH.remove();
    }
}
