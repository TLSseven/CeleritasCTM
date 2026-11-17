package team.celeritasctm.client.util;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import team.celeritasctm.CeleritasCTM;
import team.celeritasctm.client.model.CtmModelCache;
import team.celeritasctm.client.resource.CtmPropertiesLoader;
import team.celeritasctm.client.texture.CTMSpriteRegistry;

public enum CTMPackReloadListener implements IResourceManagerReloadListener {
    INSTANCE;

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        CeleritasCTM.logger.info("Resource reload - clearing CTM caches...");
        CTMSpriteRegistry.clear();
        ConnectionHandler.clearCaches();
        // 模型包装缓存必须在规则/精灵之前或之后清都可以，但必须清：
        // 重烘焙后 BlockModelShapes 产生新的 IBakedModel 实例，旧键（及其 parent）
        // 若被静态 map 强引用会随每次 F3+T 线性增长（A15）。
        int clearedModels = CtmModelCache.clear();
        CtmPropertiesLoader.reload(resourceManager);
        CeleritasCTM.logger.info("  cleared {} cached CTM models; {} CTM properties loaded",
            clearedModels, CtmPropertiesLoader.getProperties().size());
    }
}
