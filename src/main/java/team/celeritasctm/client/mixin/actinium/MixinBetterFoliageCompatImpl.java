package team.celeritasctm.client.mixin.actinium;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.celeritasctm.client.foliage.BetterFoliageCompat;

/**
 * 「BetterFoliage 草地」开关：Actinium alpha 已在
 * {@code MixinChunkBuilderMeshingTaskBetterFoliage} 中把 mesher 三处调用重定向到
 * {@code com.dhj.actinium.compat.betterfoliage.BetterFoliageCompatImpl}，本 mixin
 * 只在该 impl 入口按配置让草方块回退常规渲染，不新增 mesher 桥接。
 *
 * <p>目标类缺失时 {@link Pseudo} 使本 mixin 整体跳过；类存在但签名不符时
 * 注入器按 {@code defaultRequire=1} 失败，不静默降级。非映射 mod 类故 {@code remap=false}。
 */
@Pseudo
@Mixin(targets = "com.dhj.actinium.compat.betterfoliage.BetterFoliageCompatImpl", remap = false)
public class MixinBetterFoliageCompatImpl {

    /** 草方块让出时恢复原版层语义（不再为 BF overlay 开通目标 cutout 层）。 */
    @Inject(method = "canRenderBlockInLayer", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ctm$deferGrassLayer(Block block, IBlockState state, BlockRenderLayer layer,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (BetterFoliageCompat.deferGrassToVanilla(state)) {
            cir.setReturnValue(block.canRenderInLayer(state, layer));
        }
    }

    /** 草方块让出时跳过 BF renderWorldBlock（base + overlay），直接走真 dispatcher。 */
    @Inject(method = "renderVanillaBlock", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ctm$deferGrassRender(BlockRendererDispatcher dispatcher, IBlockState state,
                                      BlockPos pos, IBlockAccess access, BufferBuilder buffer,
                                      BlockRenderLayer layer,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (BetterFoliageCompat.deferGrassToVanilla(state)) {
            cir.setReturnValue(dispatcher.renderBlock(state, pos, access, buffer));
        }
    }

    /** false = 不启用 BF 快速路径；Actinium 随后走常规 VintageBlockRenderer。 */
    @Inject(method = "tryRenderFastBlock", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ctm$deferGrassFastBlock(IBlockState state, BlockPos pos,
                                         com.dhj.actinium.world.cloned.ActiniumBlockAccess access,
                                         com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext ctx,
                                         BlockRenderLayer layer,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (BetterFoliageCompat.deferGrassToVanilla(state)) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
