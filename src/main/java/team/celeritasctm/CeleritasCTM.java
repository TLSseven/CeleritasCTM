package team.celeritasctm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import team.celeritasctm.client.resource.CtmPropertiesLoader;
import team.celeritasctm.client.util.CTMPackReloadListener;
import team.celeritasctm.config.CeleritasConfigEntryPoint;
import team.celeritasctm.config.ModConfig;

@Mod(
    name = CeleritasCTM.MOD_NAME,
    modid = CeleritasCTM.MOD_ID,
    version = CeleritasCTM.VERSION,
    dependencies = "required-after:actinium",
    clientSideOnly = true
)
public class CeleritasCTM {

    public static final String MOD_ID = "celeritasctm";
    public static final String MOD_NAME = "CeleritasCTM";
    public static final String VERSION = "1.0.0";

    public static final org.apache.logging.log4j.Logger logger =
        org.apache.logging.log4j.LogManager.getLogger("CeleritasCTM");

    @Mod.Instance(MOD_ID)
    public static CeleritasCTM instance;

    /** 用户配置（四个开关；热路径直接读 public 字段） */
    private static ModConfig config = new ModConfig();

    public static ModConfig config() { return config; }

    /** 配置变更后待执行的重载标志（客户端 tick 执行一次，避免在 apply 事务中途重载） */
    private static volatile boolean reloadPending = false;

    /** 请求重建区块（配置 Apply 后调用；图集无变化，不需要重缝合） */
    public static void requestReload() {
        reloadPending = true;
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger.info("=== CeleritasCTM pre-init ===");

        // 加载用户配置（必须在 CTM 属性加载之前，玻璃补丁注入依赖 connectedGlass 开关）
        config = ModConfig.load();
        logger.info("  config: betterGrass={} betterSnow={} connectedGlass={} betterFoliageGrass={}",
            config.betterGrass, config.betterSnow, config.connectedGlass, config.betterFoliageGrass);

        // 向 Actinium 配置菜单注册本 mod 分页（冻结前调用；未装 Actinium 时跳过）
        try {
            com.dhj.actinium.compat.sodium.ActiniumConfigBootstrap.shared()
                .registerConfigEntryPoint(MOD_ID, new CeleritasConfigEntryPoint());
            logger.info("  registered config entry point in Actinium settings menu");
        } catch (Throwable t) {
            logger.warn("  failed to register Actinium config entry point (Actinium missing?): {}", t.toString());
        }

        MinecraftForge.EVENT_BUS.register(this);

        // 在精灵注册前就加载CTM属性
        try {
            if (Minecraft.getMinecraft().getResourceManager() != null) {
                CtmPropertiesLoader.loadAll(Minecraft.getMinecraft().getResourceManager());
            }
        } catch (Exception e) {
            logger.error("Failed to load CTM properties: {}", e.getMessage());
        }

        logger.info("  CTM properties loaded: {}", CtmPropertiesLoader.getProperties().size());
    }

    @EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        logger.info("=== CeleritasCTM post-init ===");

        // 注册资源重载监听器
        if (Minecraft.getMinecraft().getResourceManager() instanceof SimpleReloadableResourceManager) {
            ((SimpleReloadableResourceManager) Minecraft.getMinecraft()
                .getResourceManager()).registerReloadListener(CTMPackReloadListener.INSTANCE);
        }

        logger.info("=== CeleritasCTM ready. {} CTM properties loaded ===",
            CtmPropertiesLoader.getProperties().size());
    }

    /**
     * 配置 Apply 后延迟到客户端 tick 执行重载。
     *
     * <p>四个开关都只是 chunk 构建时的运行时判断（CtmBakedModel/BF 桥读取 ModConfig
     * 字段），图集没有任何变化：草地/雪地用的 grass_top/mycelium_top/snow 本就已缝合，
     * 玻璃 tile 在启动时已无条件注入缝合（injectVanillaGlassPatch 不依赖开关）。
     * 因此只调用 renderGlobal.loadRenderers()（经 Celeritas MixinRenderGlobalProxy 级联
     * 全量重建区块），不需要 refreshResources() 重缝合图集——那才是重载慢的根源。
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!reloadPending) return;
        reloadPending = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return;
            mc.renderGlobal.loadRenderers();
            logger.info("config changed - chunks reloaded");
        } catch (Throwable t) {
            logger.warn("reload after config change failed: {}", t.toString());
        }
    }
}
