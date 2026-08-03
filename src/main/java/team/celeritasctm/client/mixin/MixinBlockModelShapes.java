package team.celeritasctm.client.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.IBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.celeritasctm.client.model.CtmBakedModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(BlockModelShapes.class)
public class MixinBlockModelShapes {

    // 缓存：避免每次调用都创建新的CtmBakedModel
    private static final Map<IBakedModel, CtmBakedModel> ctm$modelCache = new ConcurrentHashMap<>(1024);

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

        CtmBakedModel cached = ctm$modelCache.get(model);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }

        CtmBakedModel ctmModel = new CtmBakedModel(model);
        CtmBakedModel prev = ctm$modelCache.putIfAbsent(model, ctmModel);
        if (prev != null) {
            ctmModel = prev;
        }
        cir.setReturnValue(ctmModel);
    }
}
