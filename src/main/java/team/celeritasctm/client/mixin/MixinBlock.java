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
        // 内部面剔除查询邻居几何时会再次进入 getExtendedState；此时不得改写
        // 当前渲染上下文（复用对象 + 复用 MutableBlockPos），否则会污染正在渲染的方块。
        if (Boolean.TRUE.equals(CtmBakedModel.EXTENDED_STATE_SUPPRESS.get())) {
            return;
        }
        CtmBakedModel.RenderContext ctx = ctm$ctx.get();
        ctx.world = world;
        ctx.pos = pos;
        // 记录注入时的坐标值（不复制对象、不分配）：chunk 构建线程会复用
        // MutableBlockPos，若下游读取时坐标已被移动，消费端据此判定上下文失效。
        ctx.packedPos = pos == null ? Long.MIN_VALUE : pos.toLong();
        CtmBakedModel.CURRENT_CONTEXT.set(ctx);
    }
}
