package team.celeritasctm.config;

import dhj.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import dhj.embeddedt.embeddium.api.eventbus.EventHandlerRegistrar;
import dhj.embeddedt.embeddium.api.options.OptionIdentifier;
import dhj.embeddedt.embeddium.api.options.control.TickBoxControl;
import dhj.embeddedt.embeddium.api.options.structure.OptionFlag;
import dhj.embeddedt.embeddium.api.options.structure.OptionGroup;
import dhj.embeddedt.embeddium.api.options.structure.OptionImpact;
import dhj.embeddedt.embeddium.api.options.structure.OptionImpl;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;
import dhj.embeddedt.embeddium.api.options.structure.OptionStorage;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import team.celeritasctm.CeleritasCTM;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registers the CeleritasCTM page in Actinium's video settings
 * (Actinium alpha-0.0.13 option GUI).
 *
 * <p>Registration path: a handler is appended to
 * {@link OptionGUIConstructionEvent#BUS}; Actinium's
 * {@code OptionGUIConstructionBridge} dispatches that event while it collects
 * pages and appends whatever page the handler adds. Nothing is frozen
 * beforehand, so registering during {@code preInit} is safe.
 *
 * <p>Persistence and apply semantics: each option binds directly to the public
 * fields of {@link ModConfig}; all five options share one
 * {@link OptionStorage} whose {@code save} writes {@code config/celeritasctm.json}
 * and requests a chunk rebuild through {@link CeleritasCTM#requestReload()}.
 * The rebuild happens on the next client tick (no atlas re-stitch: the five
 * switches only affect per-quad decisions at chunk-build time). No option
 * declares {@code REQUIRES_ASSET_RELOAD} / {@code REQUIRES_RENDERER_RELOAD}, so
 * Actinium's own apply sweep will not trigger a second, slower reload.
 */
public final class CeleritasConfigEntryPoint
        implements EventHandlerRegistrar.Handler<OptionGUIConstructionEvent> {

    public static final String MOD_ID = "celeritasctm";

    private static final OptionIdentifier<Void> PAGE_ID =
        OptionIdentifier.create(MOD_ID, "options");
    private static final OptionIdentifier<Void> GROUP_ID =
        OptionIdentifier.create(MOD_ID, "general");

    /** Single challenge: one storage instance per option makes dirty-tracking de-duplicate to one save. */
    private static final OptionStorage<ModConfig> STORAGE = new OptionStorage<ModConfig>() {
        @Override
        public ModConfig getData() {
            return CeleritasCTM.config();
        }

        @Override
        public void save() {
            persist();
        }

        @Override
        public void save(Set<OptionFlag> flags) {
            persist();
        }
    };

    private CeleritasConfigEntryPoint() {
    }

    private static void persist() {
        CeleritasCTM.config().save();
        // Only chunk geometry depends on these switches; the atlas is unchanged.
        CeleritasCTM.requestReload();
    }

    /**
     * Attaches the page contribution to Actinium's option GUI event.
     *
     * <p>Must only be called after the caller verified that the embeddium option
     * GUI types are present (this class is then linkable).
     */
    public static void register() {
        OptionGUIConstructionEvent.BUS.addListener(new CeleritasConfigEntryPoint());
        CeleritasCTM.logger.info("  registered CeleritasCTM page in Actinium video settings");
    }

    @Override
    public void acceptEvent(OptionGUIConstructionEvent event) {
        List<OptionPage> pages = event.getPages();
        // The GUI may be constructed more than once per session; add the page once.
        for (OptionPage page : pages) {
            if (page != null && PAGE_ID.equals(page.getId())) {
                return;
            }
        }
        event.addPage(buildPage());
    }

    private static OptionPage buildPage() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
            .setId(GROUP_ID)
            .setName(TextComponent.literal("CeleritasCTM"))
            .add(option("better_grass", "更好的草地",
                "草方块侧面沿对角下侧同种方块方向延伸草皮（连接草皮）。关闭后恢复原版双层侧面。",
                OptionImpact.LOW,
                (config, value) -> config.betterGrass = value,
                config -> config.betterGrass))
            .add(option("better_snow", "更好的雪地",
                "雪层下方的方块侧面显示雪纹理（如草方块积雪侧面）。",
                OptionImpact.LOW,
                (config, value) -> config.betterSnow = value,
                config -> config.betterSnow))
            .add(option("connected_glass", "无缝玻璃",
                "原版玻璃与染色玻璃：相邻玻璃之间去掉边框（连接纹理，参考 Continuity）。",
                OptionImpact.MEDIUM,
                (config, value) -> config.connectedGlass = value,
                config -> config.connectedGlass))
            .add(option("internal_face_culling", "连接方块内部面剔除",
                "仅在相邻连接方块的模型完整覆盖当前面时，删除该面。关闭后仍使用连接纹理和渲染器的常规剔面。",
                OptionImpact.MEDIUM,
                (config, value) -> config.internalFaceCulling = value,
                config -> config.internalFaceCulling))
            .add(option("better_foliage_grass", "BetterFoliage 草地",
                "允许 BetterFoliage 处理草方块（BF 的连接草地样式）。若出现紫色/白色草方块问题请关闭。",
                OptionImpact.LOW,
                (config, value) -> config.betterFoliageGrass = value,
                config -> config.betterFoliageGrass))
            .build());

        return new OptionPage(PAGE_ID, TextComponent.literal("CeleritasCTM"), groups);
    }

    private static OptionImpl<ModConfig, Boolean> option(
            String path, String name, String tooltip, OptionImpact impact,
            java.util.function.BiConsumer<ModConfig, Boolean> setter,
            java.util.function.Function<ModConfig, Boolean> getter) {
        return OptionImpl.createBuilder(boolean.class, STORAGE)
            .setId(OptionIdentifier.create(MOD_ID, path, boolean.class))
            .setName(TextComponent.literal(name))
            .setTooltip(TextComponent.literal(tooltip))
            .setControl(TickBoxControl::new)
            .setImpact(impact)
            .setBinding(setter, getter)
            .setDefaultValue(true)
            .build();
    }
}
