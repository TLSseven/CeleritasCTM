package team.celeritasctm.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import team.celeritasctm.CeleritasCTM;

/**
 * 向 Actinium 配置菜单注册 CeleritasCTM 分页（视频设置 → CeleritasCTM）。
 *
 * <p>在 ActiniumConfigBootstrap 冻结前调用 registerConfigEntryPoint（本 mod preInit
 * 时用户尚未打开视频设置，安全）。四个布尔开关绑定 ModConfig 字段：
 * setBinding 读写字段，setStorageHandler 持久化，setApplyHook 触发区块重建（图集无变化）。
 *
 * <p>设置属性：id 用 {@code celeritasctm:...} 命名空间；name/tooltip 为中文+英文。
 */
public final class CeleritasConfigEntryPoint implements ConfigEntryPoint {

    public static final String MOD_ID = "celeritasctm";

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder owner = builder.registerModOptions(MOD_ID, "CeleritasCTM", CeleritasCTM.VERSION);

        // 分页：CeleritasCTM
        net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder page =
            builder.createOptionPage().setName(new TextComponentString("CeleritasCTM"));

        // 更好的草地
        page.addOption(builder.createBooleanOption(id("better_grass"))
            .setName(new TextComponentString("更好的草地"))
            .setTooltip(new TextComponentString("草方块侧面沿对角下侧同种方块方向延伸草皮（连接草皮）。关闭后恢复原版双层侧面。"))
            .setBinding(v -> CeleritasCTM.config().betterGrass = v,
                        () -> CeleritasCTM.config().betterGrass)
            .setDefaultValue(true)
            .setStorageHandler(() -> CeleritasCTM.config().save())
            .setApplyHook(state -> CeleritasCTM.requestReload()));

        // 更好的雪地
        page.addOption(builder.createBooleanOption(id("better_snow"))
            .setName(new TextComponentString("更好的雪地"))
            .setTooltip(new TextComponentString("雪层下方的方块侧面显示雪纹理（如草方块积雪侧面）。"))
            .setBinding(v -> CeleritasCTM.config().betterSnow = v,
                        () -> CeleritasCTM.config().betterSnow)
            .setDefaultValue(true)
            .setStorageHandler(() -> CeleritasCTM.config().save())
            .setApplyHook(state -> CeleritasCTM.requestReload()));

        // 无缝玻璃
        page.addOption(builder.createBooleanOption(id("connected_glass"))
            .setName(new TextComponentString("无缝玻璃"))
            .setTooltip(new TextComponentString("原版玻璃与染色玻璃：相邻玻璃之间去掉边框（连接纹理，参考 Continuity）。"))
            .setBinding(v -> CeleritasCTM.config().connectedGlass = v,
                        () -> CeleritasCTM.config().connectedGlass)
            .setDefaultValue(true)
            .setStorageHandler(() -> CeleritasCTM.config().save())
            .setApplyHook(state -> CeleritasCTM.requestReload()));

        // BetterFoliage 草地
        page.addOption(builder.createBooleanOption(id("better_foliage_grass"))
            .setName(new TextComponentString("BetterFoliage 草地"))
            .setTooltip(new TextComponentString("允许 BetterFoliage 处理草方块（BF 的连接草地样式）。若出现紫色/白色草方块问题请关闭。"))
            .setBinding(v -> CeleritasCTM.config().betterFoliageGrass = v,
                        () -> CeleritasCTM.config().betterFoliageGrass)
            .setDefaultValue(true)
            .setStorageHandler(() -> CeleritasCTM.config().save())
            .setApplyHook(state -> CeleritasCTM.requestReload()));

        owner.addPage(page);
    }
}
