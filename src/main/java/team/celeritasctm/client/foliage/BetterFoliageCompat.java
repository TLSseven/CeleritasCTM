package team.celeritasctm.client.foliage;

import net.minecraft.block.BlockGrass;
import net.minecraft.block.state.IBlockState;

/**
 * BetterFoliage 草地开关判定。
 *
 * <p>Actinium alpha 已自带 BF 桥接（{@code BetterFoliageCompatImpl} 包装
 * {@code mods.betterfoliage.client.Hooks}），本类只提供「草方块是否让出给常规渲染」
 * 的纯函数；实际拦截由 {@code MixinBetterFoliageCompatImpl} 完成。
 */
public final class BetterFoliageCompat {

    private BetterFoliageCompat() {}

    /** 是否为原版草方块（含子类）。 */
    public static boolean isGrassBlock(IBlockState state) {
        return state != null && state.getBlock() instanceof BlockGrass;
    }

    /**
     * 草方块且用户关闭「BetterFoliage 草地」时让出常规渲染；
     * 非草方块不受开关影响。
     */
    public static boolean deferGrassToVanilla(boolean isGrassBlock, boolean betterFoliageGrass) {
        return isGrassBlock && !betterFoliageGrass;
    }

    /** 结合当前配置的便捷重载。 */
    public static boolean deferGrassToVanilla(IBlockState state) {
        return deferGrassToVanilla(isGrassBlock(state),
            team.celeritasctm.CeleritasCTM.config().betterFoliageGrass);
    }
}
