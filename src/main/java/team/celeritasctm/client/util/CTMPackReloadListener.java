package team.celeritasctm.client.util;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import team.celeritasctm.CeleritasCTM;
import team.celeritasctm.client.resource.CtmPropertiesLoader;
import team.celeritasctm.client.texture.CTMSpriteRegistry;
import team.celeritasctm.client.util.ConnectionHandler;

public enum CTMPackReloadListener implements IResourceManagerReloadListener {
    INSTANCE;

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        CeleritasCTM.logger.info("Resource reload - clearing CTM caches...");
        CTMSpriteRegistry.clear();
        ConnectionHandler.clearCaches();
        CtmPropertiesLoader.reload(resourceManager);
    }
}
