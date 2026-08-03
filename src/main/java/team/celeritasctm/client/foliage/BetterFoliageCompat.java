package team.celeritasctm.client.foliage;

import team.celeritasctm.CeleritasCTM;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.lang.reflect.Method;

/**
 * Better Foliage（BF）与 Sodium/Celeritas 渲染管线的桥接。
 *
 * <p>BF 的 ASM 注入点（RenderChunk.rebuildChunk / BlockRendererDispatcher.renderBlock）被
 * Sodium 的区块渲染管线完全绕过，导致更多树叶/芦苇/水草等功能失效（仅粒子仍走实体系统）。
 *
 * <p>此桥接把 Sodium 的 {@code dispatcher.renderBlock} 调用重定向到 BF 的
 * {@code Hooks.renderWorldBlock}，等价于 BF 对 vanilla RenderChunk 做的替换：
 * BF 会渲染 base（内部调用真 dispatcher）并追加 overlay（更多树叶/芦苇等）。
 *
 * <p>草方块（BlockGrass）例外（可配置）：BF 的 RenderGrass.connectedGrass 分支会用 BF 运行时
 * 生成的纹理全方块接管渲染（不调用 dispatcher），在 Iris/Sodium 环境下该纹理不在主
 * 图集 → 草地呈粉紫白色且丢失 CTM 连接纹理。默认关闭「BetterFoliage 草地」时跳过草方块
 * 本体，走 Sodium 的 dispatcher.renderBlock（CTM 连接纹理 + vanilla 生物群系着色）；
 * 开启该配置后允许 BF 处理草方块（用户选择）。
 */
public final class BetterFoliageCompat {

    private static final Method RENDER_WORLD_BLOCK;
    private static final boolean AVAILABLE;

    static {
        Method render = null;
        try {
            Class<?> hooks = Class.forName("mods.betterfoliage.client.Hooks");
            render = hooks.getMethod("renderWorldBlock", BlockRendererDispatcher.class,
                    IBlockState.class, BlockPos.class, IBlockAccess.class,
                    BufferBuilder.class, BlockRenderLayer.class);
        } catch (Throwable t) {
            // BF 未安装或版本不兼容：桥接禁用
            render = null;
        }
        RENDER_WORLD_BLOCK = render;
        AVAILABLE = render != null;
    }

    private BetterFoliageCompat() {}

    /** BF 是否已安装且可桥接 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * 让 BF 渲染当前方块（base + overlay），等价于 BF 对 vanilla RenderChunk 做的
     * renderWorldBlock 替换。BF 内部会调用真 dispatcher 渲染 base，并追加 overlay。
     *
     * @return BF 是否处理了该方块（true 表示 BF 接管渲染）
     */
    public static boolean renderWorldBlock(BlockRendererDispatcher dispatcher, IBlockState state,
                                           BlockPos pos, IBlockAccess access,
                                           BufferBuilder buffer, BlockRenderLayer layer) {
        if (!isAvailable() || !mayHaveFoliage(state)) return false;
        try {
            Boolean rendered = (Boolean) RENDER_WORLD_BLOCK.invoke(null,
                    dispatcher, state, pos, access, buffer, layer);
            return Boolean.TRUE.equals(rendered);
        } catch (Throwable t) {
            CeleritasCTM.logger.warn("CTM BF bridge: renderWorldBlock failed for {}: {}", state.getBlock(), t.toString());
            return false;
        }
    }

    /** 快速预检：BF 只处理草/菌丝/树叶/仙人掌/睡莲/原木/沙/泥/下界岩/水域相关方块，其余直接跳过（性能） */
    public static boolean mayHaveFoliage(IBlockState state) {
        try {
            net.minecraft.block.Block b = state.getBlock();
            // 草方块：由「BetterFoliage 草地」配置控制。BF 的 connectedGrass 分支会全方块
            // 接管草方块渲染，Iris 环境下可能破坏 CTM 连接纹理与生物群系着色（粉紫白色），
            // 故默认关闭；开启时放行给 BF Hooks 处理（BF 内部决定是否真正接管）。
            if (b instanceof BlockGrass) {
                return team.celeritasctm.CeleritasCTM.config().betterFoliageGrass;
            }
            net.minecraft.block.material.Material mat = b.getMaterial(state);
            return b instanceof net.minecraft.block.BlockLeaves
                    || b instanceof net.minecraft.block.BlockMycelium
                    || b instanceof net.minecraft.block.BlockCactus
                    || b instanceof net.minecraft.block.BlockLilyPad
                    || b instanceof net.minecraft.block.BlockLog
                    || b instanceof net.minecraft.block.BlockSand
                    || b instanceof net.minecraft.block.BlockDirt
                    || b instanceof net.minecraft.block.BlockGravel
                    || b instanceof net.minecraft.block.BlockSoulSand
                    || b == net.minecraft.init.Blocks.NETHERRACK
                    || mat == net.minecraft.block.material.Material.WATER
                    || mat == net.minecraft.block.material.Material.SAND;
        } catch (Throwable t) {
            return true; // 不确定时尝试
        }
    }
}
