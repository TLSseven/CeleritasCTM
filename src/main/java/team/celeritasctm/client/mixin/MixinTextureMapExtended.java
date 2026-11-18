package team.celeritasctm.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.PngSizeInfo;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.ProgressManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import team.celeritasctm.CeleritasCTM;
import team.celeritasctm.client.resource.CtmProperties;
import team.celeritasctm.client.resource.CtmPropertiesLoader;
import team.celeritasctm.client.texture.CTMSpriteRegistry;

import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;

/**
 * Mixin: TextureMap - 将CTM瓦片注入缝合器
 *
 * <p>Conquest 的 CTM 瓦片位于 mcpatcher/ctm/ 目录（不在 textures/ 下），
 * vanilla 的 registerSprite/loadTextureAtlas 无法直接加载它们。
 * 因此这里在 finishLoading（缝合前）用 ImageIO 手动读取 PNG，
 * 构造精灵并 addSprite 进 Stitcher —— 瓦片会被正常缝合、分配 UV、
 * 并写入 mapUploadedSprites，之后 getAtlasSprite 可直接取用。
 *
 * <p>命名注意：finishLoading / loadTexture 是 Forge 新增的私有方法，
 * 运行时保持 MCP 名（不参与 SRG 映射）；Stitcher.addSprite 是 vanilla
 * 方法（SRG func_110934_a），由 refmap 处理。
 * 不使用 @Shadow 字段（本 Forge 版本的 TextureMap 没有 resourceManager 字段）。
 */
@Mixin(TextureMap.class)
public abstract class MixinTextureMapExtended {

    private static Constructor<TextureAtlasSprite> spriteConstructor;

    static {
        try {
            spriteConstructor = TextureAtlasSprite.class.getDeclaredConstructor(String.class);
            spriteConstructor.setAccessible(true);
        } catch (Exception e) {
            CeleritasCTM.logger.error("Failed to get TextureAtlasSprite constructor", e);
        }
    }

    @Inject(method = "finishLoading", at = @At("HEAD"), remap = true)
    private void ctm$beforeFinishLoading(Stitcher stitcher, ProgressManager.ProgressBar bar,
                                         int minSpriteSize, int maxSpriteSize, CallbackInfo ci) {
        // 只处理 Block 图集；自定义图集（GUI/粒子等）不承载 CTM 瓦片，必须整体跳过。
        // 规则由 TextureStitchEvent.Pre 在本方法之前重建（CeleritasCTM#onTextureStitchPre）。
        if (((TextureMap) (Object) this) != Minecraft.getMinecraft().getTextureMapBlocks()) return;
        int added = addCTMTiles(stitcher);
        CeleritasCTM.logger.info("CTM: added {} tile sprites to stitcher", added);
    }

    private int addCTMTiles(Stitcher stitcher) {
        if (spriteConstructor == null) return 0;

        IResourceManager resourceManager;
        try {
            resourceManager = Minecraft.getMinecraft().getResourceManager();
        } catch (Throwable t) {
            return 0;
        }
        if (resourceManager == null) return 0;

        int added = 0;
        Set<String> seen = new HashSet<>();
        for (CtmProperties props : CtmPropertiesLoader.getProperties()) {
            String[] tokens = props.getTileTokens();
            if (tokens == null) continue;
            for (String token : tokens) {
                if (token == null) continue; // <skip>/<default>
                ResourceLocation tileLoc = CTMSpriteRegistry.tileLoc(props, token);
                if (tileLoc == null) continue;
                String icon = tileLoc.toString();
                if (!seen.add(icon)) continue; // 同一瓦片只处理一次

                try {
                    TextureAtlasSprite sprite = loadTile(resourceManager, tileLoc);
                    if (sprite != null) {
                        stitcher.addSprite(sprite);
                        added++;
                    }
                } catch (Exception e) {
                    CeleritasCTM.logger.debug("CTM: tile load failed for {}: {}", tileLoc, e.toString());
                }
            }
        }
        return added;
    }

    private TextureAtlasSprite loadTile(IResourceManager resourceManager,
                                        ResourceLocation tileLoc) throws Exception {
        ResourceLocation pngLoc =
            new ResourceLocation(tileLoc.getNamespace(), tileLoc.getPath() + ".png");

        // mipmap 级别必须来自 TextureMap（vanilla 的 loadTexture 传 getMipmapLevels()+1
        // 给 loadSpriteFrames；注意 finishLoading 的参数不是 mipmap 级别！）
        int mipmapLevels = ((TextureMap) (Object) this).getMipmapLevels();

        // 完全复刻 vanilla TextureMap.loadTexture 的精灵加载序列，
        // 一次性处理：iconWidth/Height、动画帧（.mcmeta）、mipmap 链。
        // makeFromResource 内部会关闭传入的 IResource；这里不再持有该句柄。
        PngSizeInfo sizeInfo = PngSizeInfo.makeFromResource(resourceManager.getResource(pngLoc));
        if (sizeInfo.pngWidth < 1 || sizeInfo.pngHeight < 1) return null;

        TextureAtlasSprite sprite = spriteConstructor.newInstance(tileLoc.toString());

        // 第二个资源句柄必须无条件关闭：loadSprite / loadSpriteFrames 抛出时
        // 若不关闭会随每次 F3+T 泄漏 zip 句柄（资源包是 zip 时尤为明显）。
        IResource resource = resourceManager.getResource(pngLoc);
        try {
            boolean hasAnim = resource.getMetadata("animation") != null;
            sprite.loadSprite(sizeInfo, hasAnim);                // 设置 iconWidth/Height + UV 比例
            sprite.loadSpriteFrames(resource, mipmapLevels + 1); // 填充帧 + 切分动画帧
        } finally {
            resource.close();
        }

        if (mipmapLevels > 0) {
            sprite.generateMipmaps(mipmapLevels);           // 生成 mip 链（vanilla loadTexture 同样如此）
        }
        return sprite;
    }
}
