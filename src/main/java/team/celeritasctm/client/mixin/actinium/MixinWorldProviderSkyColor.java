package team.celeritasctm.client.mixin.actinium;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 保护 WorldProvider.getSkyColor 的 null 实体。
 *
 * <p>aperture 的 ASM hook 把 World.getSkyColor 改写为 AsmRenderingHandler.getSkyColor，
 * 世界加载初期（玩家实体未就绪时 getRenderViewEntity() 返回 null）会把 null 实体
 * 传给 WorldProvider.getSkyColor -> World.getSkyColorBody，vanilla 原本的 null
 * 安全分支被 aperture 的替换破坏，导致 NPE 崩溃（无法进入世界）。
 *
 * <p>此 mixin 在 getSkyColor 入口拦截 null 实体，返回默认天空色，让游戏正常进入。
 */
@Mixin(WorldProvider.class)
public abstract class MixinWorldProviderSkyColor {

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void ctm$guardNullEntity(Entity entityIn, float partialTicks,
                                     CallbackInfoReturnable<Vec3d> cir) {
        if (entityIn == null) {
            // 默认淡蓝天空（fallback），仅在世界加载早期实体未就绪时命中
            cir.setReturnValue(new Vec3d(0.6D, 0.65D, 0.9D));
        }
    }
}
