package team.celeritasctm.client.mixin.actinium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 保护 EntityRenderer.renderWorld 的 null 渲染视图实体。
 *
 * <p>aperture 的 ASM hook 破坏了 vanilla 的世界渲染时序：世界加载早期
 * （玩家实体未就绪时）getRenderViewEntity() 与 mc.player 均为 null，
 * renderWorld 的 null→player fallback 把 null 设为渲染视图实体，
 * renderWorldPass 里 entity.getEyeHeight() 直接 NPE（无法进入世界）。
 *
 * <p>此 mixin 在 renderWorld 入口拦截：渲染视图实体与玩家都为 null 时
 * 跳过世界渲染（等玩家就绪后再渲染），避免 NPE 崩溃。
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererWorldGuard {

    @Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true)
    private void ctm$guardNullRenderEntity(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getRenderViewEntity() == null && mc.player == null) {
                // 世界加载早期玩家未就绪：跳过世界渲染
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
