package team.celeritasctm.client.mixin;

import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.celeritasctm.client.resource.CtmProperties;
import team.celeritasctm.client.resource.CtmPropertiesLoader;

/**
 * Mixin: TextureMap.registerSprite()
 * 触发CTM属性加载（确保在精灵注册前完成）
 */
@Mixin(TextureMap.class)
public class MixinTextureMap {

    private static boolean ctm$loaded = false;

    @Inject(method = "registerSprite", at = @At("HEAD"))
    private void ctm$onRegisterSprite(ResourceLocation location, CallbackInfoReturnable<Boolean> cir) {
        if (!ctm$loaded) {
            ctm$loaded = true;
            // CTM属性已在preInit中加载，这里不需要再加载
        }
    }
}
