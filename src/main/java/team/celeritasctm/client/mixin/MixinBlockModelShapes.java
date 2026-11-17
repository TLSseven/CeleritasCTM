package team.celeritasctm.client.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.celeritasctm.client.model.CtmBakedModel;
import team.celeritasctm.client.model.CtmModelCache;

/**
 * 为 vanilla 模型套一层 {@link team.celeritasctm.client.model.CtmBakedModel}。
 *
 * <p>缓存本体在 {@link CtmModelCache}（普通类）中，资源重载时由
 * {@code CTMPackReloadListener} 调用 {@link CtmModelCache#clear()} 清理，
 * 避免重烘焙产生的旧模型被静态 map 永久强引用（A15）。
 */
@Mixin(BlockModelShapes.class)
public class MixinBlockModelShapes {

    private static final String VANILLA_PREFIX = "net.minecraft";

    @Inject(method = "getModelForState", at = @At("RETURN"), cancellable = true)
    private void ctm$wrapModelWithCTM(IBlockState state, CallbackInfoReturnable<IBakedModel> cir) {
        IBakedModel model = cir.getReturnValue();
        if (model == null || model instanceof CtmBakedModel) return;

        // 只包装 vanilla 模型：mod 自定义模型（如 ChiselBits 的 BaseBakedBlockModel）
        // 的 quad 形状特殊，UV 重映射会导致纹理错乱，必须排除。
        // 注意：不能用粒子纹理做匹配门槛——多纹理模型（铁栅栏 bars/edge 面、
        // cube_bottom_top 的顶底侧面）的粒子纹理 ≠ 被纹理化的面，
        // OptiFine 是逐 quad 匹配的，这里统一包装后由 CtmBakedModel 逐 quad 处理。
        if (!model.getClass().getName().startsWith(VANILLA_PREFIX)) return;

        cir.setReturnValue(CtmModelCache.getOrCreate(model));
    }
}
