package team.celeritasctm.client.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import team.celeritasctm.client.resource.CtmProperties;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CTM精灵注册表 - 管理CTM瓦片精灵（tile 是与 .properties 同目录的独立 PNG）
 *
 * <p>瓦片精灵由 vanilla 缝合进图集（见 MixinTextureMapExtended），
 * 这里懒加载地从图集（mapUploadedSprites）获取最终的精灵对象，保证 UV 正确。
 */
public class CTMSpriteRegistry {

    // 图标名 → 缝合后的精灵
    private static final Map<String, TextureAtlasSprite> SPRITE_CACHE = new ConcurrentHashMap<>();

    private CTMSpriteRegistry() {}

    /**
     * 构建 tile 的 ResourceLocation：与 .properties 同目录 + tile 标记（数字或名称）
     */
    public static ResourceLocation tileLoc(CtmProperties props, String tileToken) {
        if (tileToken == null) return null;
        String propsPath = props.getResourceId().getPath();
        int lastSlash = propsPath.lastIndexOf('/');
        String baseDir = lastSlash >= 0 ? propsPath.substring(0, lastSlash + 1) : "";
        return new ResourceLocation(props.getResourceId().getNamespace(), baseDir + tileToken);
    }

    /**
     * 获取CTM瓦片精灵（tile 标记可为数字 "3" 或名称 "x"）
     */
    public static TextureAtlasSprite getTileSprite(CtmProperties props, String tileToken) {
        ResourceLocation loc = tileLoc(props, tileToken);
        if (loc == null) return null;
        String icon = loc.toString();

        TextureAtlasSprite cached = SPRITE_CACHE.get(icon);
        if (cached != null) return cached;

        try {
            TextureAtlasSprite sprite =
                Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(icon);
            // getAtlasSprite 对缺失纹理返回 missingno，需校验图标名
            if (sprite != null && icon.equals(sprite.getIconName())) {
                SPRITE_CACHE.put(icon, sprite);
                return sprite;
            }
        } catch (Throwable ignored) {
            // 图集尚未就绪
        }
        return null;
    }

    public static void clear() {
        SPRITE_CACHE.clear();
    }
}
