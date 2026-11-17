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

        // 向 Actinium 视频设置的选项 GUI 注册本 mod 分页。
        //
        // 显式能力探测（不做 catch-Throwable 兜底）：Actinium 是 required-after 依赖，
        // 缺失时 FML 会拒载；这里唯一要诊断的是「Actinium 存在但版本不含该选项 API」。
        // 因此先探测关键类是否可加载，再调用注册（注册阶段再抛异常即为真实不兼容）。
        registerOptionPage();

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

    /**
     * 探测 Actinium 的选项 GUI API 是否可用，可用时注册分页。
     *
     * <p>只用字符串类名探测，避免在方法体外常量化引用新 API 类型（否则类加载期
     * 就可能 NoClassDefFoundError，探测失去意义）。探测通过后由
     * {@link CeleritasConfigEntryPoint#register()} 建立真实链接；该阶段抛出的
     * 异常不再被吞掉——那才是「Actinium 存在但 API 不匹配」的真实信号。
     */
    private void registerOptionPage() {
        try {
            Class.forName("dhj.embeddedt.embeddium.api.OptionGUIConstructionEvent", false,
                CeleritasCTM.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError t) {
            logger.warn("  Actinium option GUI unavailable (API version mismatch, page not registered): {}",
                t.toString());
            return;
        }
        CeleritasConfigEntryPoint.register();
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
