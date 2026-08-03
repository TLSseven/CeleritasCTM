package team.celeritasctm.client.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.celeritasctm.client.model.CtmBakedModel;

@Mixin(Block.class)
public class MixinBlock {

    // 复用上下文对象，避免每帧分配
    private static final ThreadLocal<CtmBakedModel.RenderContext> ctm$ctx =
        ThreadLocal.withInitial(() -> new CtmBakedModel.RenderContext(null, null));

    @Inject(method = "getExtendedState", at = @At("HEAD"))
    private void ctm$beforeGetExtendedState(IBlockState state, IBlockAccess world, BlockPos pos,
                                             CallbackInfoReturnable<IBlockState> cir) {
        CtmBakedModel.RenderContext ctx = ctm$ctx.get();
        ctx.world = world;
        ctx.pos = pos;
        CtmBakedModel.CURRENT_CONTEXT.set(ctx);
    }
}
