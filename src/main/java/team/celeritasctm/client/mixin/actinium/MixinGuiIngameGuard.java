package team.celeritasctm.client.mixin.actinium;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 保护 HUD（游戏内界面）渲染的 null 玩家。
 *
 * <p>崩溃链（本环境实测）：Metamorph/mclib 在玩家实体创建前同步 morph 状态，
 * capability 未初始化 → NPE 打乱玩家实体创建时序 → Minecraft.player 保持 null
 * → 世界渲染进行时 GuiIngameForge.renderGameOverlay 访问 player.getRidingEntity()
 * 直接 NPE 崩溃（无法进入世界）。
 *
 * <p>此 mixin 在 renderGameOverlay 入口拦截：玩家为 null 时跳过 HUD 渲染
 * （等玩家就绪后再画），避免 NPE 崩溃。纯防护，不影响正常游戏。
 */
@Mixin(GuiIngameForge.class)
public abstract class MixinGuiIngameGuard {

    @Inject(method = "renderGameOverlay", at = @At("HEAD"), cancellable = true)
    private void ctm$guardNullPlayer(float partialTicks, CallbackInfo ci) {
        try {
            if (Minecraft.getMinecraft().player == null) {
                // 玩家未就绪（进世界早期 / 模组时序问题）：跳过 HUD 渲染
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
