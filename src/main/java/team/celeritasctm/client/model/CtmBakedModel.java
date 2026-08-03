package team.celeritasctm.client.model;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakable;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockMycelium;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
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

    public static class RenderContext {
        public IBlockAccess world;
        public BlockPos pos;
        public RenderContext(IBlockAccess world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
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

        RenderContext ctx = CURRENT_CONTEXT.get();
        // 上下文有效性校验：ThreadLocal 可能残留上一块/物品渲染的陈旧上下文，
        // 当 pos 处的方块与当前 state 不是同一方块时视为无效（跳过处理，防止纹理错乱）
        if (ctx == null || ctx.world == null || ctx.pos == null) {
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

        // ===== 连接玻璃（可配置）=====
        // 染色玻璃：内置玻璃规则只匹配 blocks/glass（无色），染色玻璃（blocks/glass_<color>）
        // 在这里手动走同一套连接掩码 + tile 选择。
        if (CeleritasCTM.config().connectedGlass && isStainedGlass(blockState) && quadFace != null) {
            CtmProperties glassRule = CtmPropertiesLoader.GLASS_RULE;
            if (glassRule != null) {
                int mask = ConnectionHandler.getMask(ctx.world, ctx.pos, quadFace, glassRule, sprite);
                String tile = glassRule.tileAt(CTM_INDEX_MAP[mask & 255]);
                if (tile != null) {
                    TextureAtlasSprite ts = CTMSpriteRegistry.getTileSprite(glassRule, tile);
                    if (ts != null && ts.getIconWidth() > 1) {
                        return java.util.Collections.singletonList(remapUV(quad, sprite, ts));
                    }
                }
            }
        }

        // 普通规则（替换本体）
        CtmProperties props = CtmPropertiesLoader.findMatch(sprite.getIconName(), blockState, quadFace);
        if (props != null) {
            // 玻璃规则在配置关闭时不应用（无色玻璃走 findMatch 命中）
            if (props == CtmPropertiesLoader.GLASS_RULE && !CeleritasCTM.config().connectedGlass) {
                return java.util.Collections.singletonList(quad);
            }
            BakedQuad baseQuad = applyRule(quad, sprite, props, ctx, quadFace, blockState, 0);
            if (baseQuad == null) baseQuad = quad;
            return java.util.Collections.singletonList(baseQuad);
        }
        return java.util.Collections.singletonList(quad);
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
        Block self = ctx.world.getBlockState(ctx.pos).getBlock();
        if (self == Blocks.SNOW_LAYER || self == Blocks.SNOW || self == Blocks.ICE
                || self == Blocks.PACKED_ICE || self == Blocks.FROSTED_ICE) {
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
        CtmProperties next = CtmPropertiesLoader.findMatch(tileSprite.getIconName(), blockState, quadFace);
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
        int uvOffset = format.getUvOffsetById(0);
        if (uvOffset < 0) return quad;
        int stride = format.getIntegerSize();
        int uvIdx = uvOffset / 4;

        float oMinU = original.getMinU(), oMaxU = original.getMaxU();
        float oMinV = original.getMinV(), oMaxV = original.getMaxV();
        float du = oMaxU - oMinU;
        float dv = oMaxV - oMinV;
        if (du <= 0.0f || dv <= 0.0f) return quad;

        int[] data = quad.getVertexData();
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
