package team.celeritasctm.client.util;

import net.minecraft.client.resources.IResourceManager;
import team.celeritasctm.CeleritasCTM;
import team.celeritasctm.client.model.CtmModelCache;
import team.celeritasctm.client.resource.CtmPropertiesLoader;
import team.celeritasctm.client.texture.CTMSpriteRegistry;

/**
 * 方块图集（block atlas）重载入口，由
 * {@code CeleritasCTM#onTextureStitchPre} 在 {@code TextureStitchEvent.Pre} 时调用。
 *
 * <p>时机：该事件在 {@code TextureMap.loadSprites} 内、{@code loadTextureAtlas}
 * （即 {@code finishLoading} 的 CTM tile 注入）之前触发，因此规则必然先于 tile
 * 注入就绪；启动、F3+T、切换资源包都会经过此处。这里<b>不</b>按 {@code IResourceManager}
 * 实例去重——重载会复用同一个资源管理器实例。
 */
public final class CTMPackReloadListener {

    private CTMPackReloadListener() {}

    /**
     * 方块图集缝合前：清缓存（精灵 → 连接缓存 → 模型缓存），最后发布新规则快照。
     *
     * <p>顺序保证：tile 注入读到的是本次重载的规则；旧图集精灵与旧模型不被静态引用。
     */
    public static void onBlockAtlasStitchPre(IResourceManager resourceManager) {
        if (resourceManager == null) return;

        CTMSpriteRegistry.clear();
        ConnectionHandler.clearCaches();
        int clearedModels = CtmModelCache.clear();
        CtmPropertiesLoader.loadAll(resourceManager);
        CeleritasCTM.logger.info(
            "  block atlas pre-stitch: cleared {} cached CTM models; {} CTM properties loaded",
            clearedModels, CtmPropertiesLoader.getProperties().size());
    }
}
