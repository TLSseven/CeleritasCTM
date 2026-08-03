package team.celeritasctm.client.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import team.celeritasctm.client.resource.CtmProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final ThreadLocal<Map<String, String>> NEIGHBOR_FACE_CACHE =
        ThreadLocal.withInitial(HashMap::new);

    // 重入保护：获取邻居精灵时若触发 CtmBakedModel 递归，返回 null 终止
    private static final ThreadLocal<Boolean> TILE_LOOKUP_GUARD = ThreadLocal.withInitial(() -> false);

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
        if (otherPos == null) return false;
        IBlockState other = world.getBlockState(otherPos);
        if (other == null || other.getBlock() == null) return false;

        String connect = props.getConnect(); // 含 OptiFine 默认（tile-based→tile, block-based→block）
        boolean connected;
        if ("material".equals(connect)) {
            connected = self.getMaterial() == other.getMaterial();
        } else if ("state".equals(connect)) {
            connected = self.equals(other);
        } else if ("tile".equals(connect)) {
            // OptiFine connect=tile：邻居在当前面的纹理与当前 quad 纹理相同才连接
            // 注意：传入当前 quad 的 sprite 名——对 verticalslab 等无 cullface 模型，
            // 邻居指定面查不到 quad 时会回退到 general quads，必须按纹理匹配正确的 quad
            // （否则拿到顶/底面 plank12 等错误纹理，比较永远失败）
            connected = quadSprite != null && face != null
                && quadSprite.getIconName().equals(tileOfFace(other, face, quadSprite.getIconName()));
        } else {
            // block：同方块 ID
            connected = self.getBlock() == other.getBlock();
        }
        if (connected) return true;

        // connectTiles 附加条件：与使用了指定纹理的方块也连接（如 plank ↔ slab）
        List<String> connectTiles = props.getConnectTiles();
        if (!connectTiles.isEmpty()) {
            String name = spriteNameOf(other);
            if (name != null && connectTiles.contains(name)) return true;
        }
        return false;
    }

    /**
     * 获取方块指定面的 quad 精灵名（缓存；重入时返回 null）。
     *
     * <p>关键：verticalslab 等无 cullface 的模型（半砖/台阶侧面）烘焙后所有面
     * 都进入 general quads，getQuads(state, face) 返回空。此时必须在 general quads
     * 中按 selfSpriteName 找到与当前 quad 纹理相同的那个 quad（即邻居同位置的侧面），
     * 而不是盲目取第一个 quad（那是顶/底面）。否则 connect=tile 比较永远失败，
     * 表现为「上下同种方块却不连接」。
     */
    private static String tileOfFace(IBlockState state, EnumFacing side, String selfSpriteName) {
        if (TILE_LOOKUP_GUARD.get()) return null;
        String key = System.identityHashCode(state) + "|" + side.ordinal() + "|" + selfSpriteName;
        Map<String, String> cache = NEIGHBOR_FACE_CACHE.get();
        if (cache.containsKey(key)) {
            String v = cache.get(key);
            return v.isEmpty() ? null : v;
        }
        TILE_LOOKUP_GUARD.set(true);
        String name = "";
        try {
            BlockModelShapes shapes = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes();
            IBakedModel model = shapes.getModelForState(state);
            if (model != null) {
                // 先查指定面；无 cullface 模型该面为空
                name = firstQuadIcon(model, state, side);
                if (name.isEmpty()) {
                    // 在 general quads 中找与自身纹理相同的 quad（邻居同位置侧面）
                    name = findQuadIconBySprite(model, state, selfSpriteName);
                }
                if (name.isEmpty()) {
                    // 兜底：第一个 general quad
                    name = firstQuadIcon(model, state, null);
                }
            }
        } catch (Throwable t) {
            name = "";
        } finally {
            TILE_LOOKUP_GUARD.set(false);
        }
        cache.put(key, name);
        return name.isEmpty() ? null : name;
    }

    private static String firstQuadIcon(IBakedModel model, IBlockState state, EnumFacing side) {
        List<BakedQuad> quads = model.getQuads(state, side, 0L);
        for (BakedQuad q : quads) {
            if (q != null && q.getSprite() != null) {
                return q.getSprite().getIconName();
            }
        }
        return "";
    }

    /** 在 general quads（side=null）中查找 sprite 名与 selfSpriteName 相同的 quad */
    private static String findQuadIconBySprite(IBakedModel model, IBlockState state, String selfSpriteName) {
        if (selfSpriteName == null) return "";
        List<BakedQuad> quads = model.getQuads(state, null, 0L);
        for (BakedQuad q : quads) {
            if (q != null && q.getSprite() != null
                && selfSpriteName.equals(q.getSprite().getIconName())) {
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
        Map<IBlockState, String> cache = NEIGHBOR_SPRITE_CACHE.get();
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

    public static void clearCaches() {
        NEIGHBOR_SPRITE_CACHE.remove();
        NEIGHBOR_FACE_CACHE.remove();
        TILE_LOOKUP_GUARD.remove();
    }
}
