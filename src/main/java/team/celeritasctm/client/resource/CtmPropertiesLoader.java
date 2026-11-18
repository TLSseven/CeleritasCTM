package team.celeritasctm.client.resource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;
import team.celeritasctm.CeleritasCTM;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从资源包中加载OptiFine格式的CTM .properties文件
 *
 * 搜索路径:
 * - assets/&lt;namespace&gt;/mcpatcher/ctm/ 下的所有子目录中的 .properties
 * - assets/&lt;namespace&gt;/optifine/ctm/ 下的所有子目录中的 .properties
 *
 * <h3>优先级来源（实证，不预设方向）</h3>
 * 1.12.2 的 {@code FallbackResourceManager#getResource} 从 {@code resourcePacks} 列表<b>末尾往前</b>
 * 查找，命中即返回；而 {@code Minecraft#refreshResources} 按
 * 「内置包 → {@code getRepositoryEntries()} 顺序 → 服务器包」追加到该列表。
 * 因此：
 * <ul>
 *   <li>{@code getRepositoryEntries()} 的<b>索引越大优先级越高</b>；</li>
 *   <li>mod 自带资源（经 Forge 加入 {@code defaultResourcePacks}）优先级<b>低于</b>任何用户资源包。</li>
 * </ul>
 * 加载时还会用 {@code IResourceManager#getResource} 对同一 resourceId 的实际生效包名做一次校验，
 * 不一致时以资源管理器结果为准（见 {@link #effectivePackName}），避免仅凭推算。
 *
 * <h3>零启用包（A10）</h3>
 * 仓库可用但启用列表为空时，<b>不扫描</b> {@code resourcepacks/}；mod 自带资源照常加载。
 * 仅当仓库未就绪（null）时才退化为扫描目录。
 */
public class CtmPropertiesLoader {

    /**
     * 不可变快照：规则列表与两个索引作为一个整体发布。
     * 重载期间只构建新快照，绝不半途修改正在被渲染线程读取的旧快照。
     */
    public static final class RuleSnapshot {
        public final List<CtmProperties> properties;
        public final Map<String, List<CtmProperties>> tileIndex;
        public final Map<String, List<CtmProperties>> blockIndex;
        public final CtmProperties glassRule;
        /** 含未解析数字 ID 的规则：索引构建时注册表尚不可用时在此兜底（查询期按方块 ID 比较） */
        final List<CtmProperties> deferredNumericRules;

        RuleSnapshot(List<CtmProperties> properties,
                     Map<String, List<CtmProperties>> tileIndex,
                     Map<String, List<CtmProperties>> blockIndex,
                     CtmProperties glassRule,
                     List<CtmProperties> deferredNumericRules) {
            this.properties = Collections.unmodifiableList(new ArrayList<>(properties));
            this.tileIndex = Collections.unmodifiableMap(deepFreeze(tileIndex));
            this.blockIndex = Collections.unmodifiableMap(deepFreeze(blockIndex));
            this.glassRule = glassRule;
            this.deferredNumericRules = Collections.unmodifiableList(new ArrayList<>(deferredNumericRules));
        }

        private static Map<String, List<CtmProperties>> deepFreeze(Map<String, List<CtmProperties>> src) {
            Map<String, List<CtmProperties>> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<CtmProperties>> e : src.entrySet()) {
                out.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
            }
            return out;
        }

        static RuleSnapshot empty() {
            return new RuleSnapshot(Collections.<CtmProperties>emptyList(),
                Collections.<String, List<CtmProperties>>emptyMap(),
                Collections.<String, List<CtmProperties>>emptyMap(), null,
                Collections.<CtmProperties>emptyList());
        }
    }

    /** 当前已发布的不可变快照（volatile：重载线程写、渲染线程读） */
    private static volatile RuleSnapshot snapshot = RuleSnapshot.empty();

    // 构建期的工作副本（仅重载线程访问；发布后即丢弃）
    private static final List<CtmProperties> PROPERTIES = new ArrayList<>();
    // 索引构建时仍有未解析数字 ID 的规则（查询期作为兜底）
    private static final List<CtmProperties> DEFERRED_NUMERIC_RULES = new ArrayList<>();
    // 清理后的纹理名 → 属性列表（含 matchTiles 的规则；列表按 packPriority 顺序）
    private static final Map<String, List<CtmProperties>> TILE_INDEX = new HashMap<>();
    // 规范化方块键（namespace:path，小写）→ 属性列表（纯 matchBlocks 的规则）
    private static final Map<String, List<CtmProperties>> BLOCK_INDEX = new HashMap<>();
    // 已加载的 resourceId → 实际生效来源的优先级（取最高优先级者）
    private static final Map<String, Integer> SEEN_IDS = new HashMap<>();
    // 未知状态属性的 warn 去重
    private static final Set<String> WARNED_PROPERTIES = new HashSet<>();
    // 本次扫描涉及的所有包名（用于判定“实际生效来源是否是另一个已知候选包”）
    private static final Set<String> CANDIDATE_PACK_NAMES = new HashSet<>();
    // 当前正在扫描的包名（zip 文件名或文件夹名），供去重时确认实际生效来源
    private static String currentPackName;
    // 当前重载使用的资源管理器（仅在重复 resourceId 时用于实证查询）
    private static IResourceManager activeResourceManager;

    // 内置连接玻璃规则（mod jar 内 tiles 0-46，见 assets/celeritasctm/mcpatcher/ctm/connected_glass/）。
    // 只作为<b>普通规则全部未命中后的回退</b>注入；也绝不把资源包提供的 glass 规则
    // 当作全局内置模板暴露（资源包规则由 findMatch 正常命中）。
    // 通过 {@link #getGlassRule()} 读取，保证与同一份快照一致。
    /**
     * @deprecated 仅为兼容旧调用点保留的只读别名，值恒等于 {@link #getGlassRule()}
     *             （永远是内置回退对象，绝不会是资源包规则）。新代码请用 {@link #getGlassRule()}。
     */
    @Deprecated
    public static volatile CtmProperties GLASS_RULE = null;

    /** 一次扫描的来源描述 */
    private static final class PackSource {
        final String name;
        final File file;
        final int priority;

        PackSource(String name, File file, int priority) {
            this.name = name;
            this.file = file;
            this.priority = priority;
        }
    }

    /**
     * 扫描并构建规则快照。方法整体同步：重载线程串行执行，避免工作副本被并发清空；
     * 渲染线程只读 {@link #snapshot}，不受构建过程影响。构建/发布期间若抛异常，
     * 已发布的旧快照保持有效（绝不中途发布半成品）。
     */
    public static synchronized void loadAll(IResourceManager resourceManager) {
        // 工作副本只在重载线程使用；已发布的旧快照继续服务渲染线程，直到新快照构建完成。
        PROPERTIES.clear();
        TILE_INDEX.clear();
        BLOCK_INDEX.clear();
        DEFERRED_NUMERIC_RULES.clear();
        SEEN_IDS.clear();
        WARNED_PROPERTIES.clear();
        CANDIDATE_PACK_NAMES.clear();
        currentPackName = null;
        activeResourceManager = resourceManager;
        CtmRuleSupport.setWarnSink(CtmPropertiesLoader::warnOnceForProperty);
        CeleritasCTM.logger.info("=== CTM Properties Loader: Scanning resource packs ===");

        int totalLoaded = 0;

        try {
            List<PackSource> sources = collectPackSources();
            List<String> scanned = new ArrayList<>();
            for (PackSource source : sources) {
                scanned.add(source.name + "@" + source.priority);
                CANDIDATE_PACK_NAMES.add(source.name);
                CANDIDATE_PACK_NAMES.add(source.file.getName());
                if (source.file.isFile() && source.file.getName().endsWith(".zip")) {
                    totalLoaded += scanZipFile(source.file, source.priority);
                } else if (source.file.isDirectory()) {
                    totalLoaded += scanDirectory(source.file, source.priority);
                }
                currentPackName = null;
            }
            CeleritasCTM.logger.info("CTM: scanned packs (low→high priority): {}", scanned);

            // mods 目录：mod 自带资源（Forge 将其排在用户包之前，故优先级更低）
            File modsDir = new File(Minecraft.getMinecraft().gameDir, "mods");
            if (modsDir.isDirectory()) {
                File[] modFiles = modsDir.listFiles();
                if (modFiles != null) {
                    int modIndex = 0;
                    for (File file : modFiles) {
                        if (file.isFile() && (file.getName().endsWith(".jar") || file.getName().endsWith(".zip"))) {
                            CANDIDATE_PACK_NAMES.add(file.getName());
                            totalLoaded += scanZipFile(file, CtmRuleSupport.modPackPriority(modIndex));
                            modIndex++;
                        }
                    }
                }
            }

            Collections.sort(PROPERTIES);
            rebuildIndex();
            // 内置回退：只补充普通规则未覆盖的场景，绝不覆盖资源包自带规则。
            CtmProperties glass = injectVanillaGlassPatch();
            // 原子发布：此刻起渲染线程看到的是完整的新规则+索引。
            publishSnapshot(glass);
        } catch (Throwable t) {
            // 构建失败：保留上一个有效快照，不做任何发布。
            CeleritasCTM.logger.error("CTM: rule build failed - keeping previous snapshot: {}", t.toString(), t);
            return;
        }
        CeleritasCTM.logger.info("=== CTM Properties Loader: Total {} CTM properties loaded ===", totalLoaded);
    }

    /** 把工作副本冻结为新快照并原子发布 */
    private static void publishSnapshot(CtmProperties glassRule) {
        RuleSnapshot next =
            new RuleSnapshot(PROPERTIES, TILE_INDEX, BLOCK_INDEX, glassRule, DEFERRED_NUMERIC_RULES);
        snapshot = next;
        GLASS_RULE = next.glassRule; // 兼容别名：与快照同一对象，且只可能是内置回退
    }

    /**
     * 收集要扫描的包来源。
     *
     * <p>优先级：
     * <ul>
     *   <li>仓库就绪：按 {@code getRepositoryEntries()} 顺序，索引即优先级（越大越优先）；</li>
     *   <li>仓库就绪且列表为空：<b>零用户包</b>，资源包目录一个都不扫（A10），只留 mod 资源；</li>
     *   <li>仓库未就绪：退化扫描 {@code resourcepacks/}，按文件名字母序，并 warn。</li>
     * </ul>
     * {@code listFiles()} 只用于把启用包名映射到文件，不参与排序。
     */
    private static List<PackSource> collectPackSources() {
        List<PackSource> out = new ArrayList<>();

        ResourcePackRepository repo = null;
        try {
            repo = Minecraft.getMinecraft().getResourcePackRepository();
        } catch (Throwable t) {
            repo = null;
        }
        // 优先使用仓库自身的 resourcepacks 目录（与 vanilla 完全一致）；
        // 仓库不可用时退回 gameDir/resourcepacks。
        File resourcepacksDir;
        try {
            resourcepacksDir = repo == null ? new File(Minecraft.getMinecraft().gameDir, "resourcepacks")
                : repo.getDirResourcepacks();
        } catch (Throwable t) {
            resourcepacksDir = new File(Minecraft.getMinecraft().gameDir, "resourcepacks");
        }

        if (repo == null) {
            // 仓库未就绪：退化路径（无法判断启用栈，只能尽力而为）
            CeleritasCTM.logger.warn(
                "CTM: ResourcePackRepository not ready - falling back to scanning resourcepacks/ (priority order unspecified)");
            out.addAll(scanAllResourcepacks(resourcepacksDir));
            return out;
        }

        List<ResourcePackRepository.Entry> entries;
        try {
            entries = repo.getRepositoryEntries();
        } catch (Throwable t) {
            CeleritasCTM.logger.warn("CTM: failed to read enabled resource pack stack: {}", t.toString());
            entries = Collections.emptyList();
        }

        if (entries == null || entries.isEmpty()) {
            // 零启用：不扫描 resourcepacks/（A10）。mod 自带资源仍会加载。
            CeleritasCTM.logger.info("CTM: no user resource packs enabled - resourcepacks/ will not be scanned");
            return out;
        }

        Map<String, File> byName = indexResourcepackFiles(resourcepacksDir);
        List<String> enabledNames = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String packName;
            try {
                packName = entries.get(i).getResourcePackName();
            } catch (Throwable t) {
                continue;
            }
            if (packName == null) continue;
            enabledNames.add(packName);
        }

        List<CtmRuleSupport.PackSourceInfo> stack =
            CtmRuleSupport.priorityForEnabledStack(enabledNames);
        for (CtmRuleSupport.PackSourceInfo info : stack) {
            String packName = info.name;
            // 1.12.2 getResourcePackName() 就是文件/文件夹的 File.getName()：
            // 精确匹配，不做前缀/后缀猜测（避免 Foo 目录与 Foo.zip 互相别名）。
            File file = byName.get(packName);
            if (file == null) {
                CeleritasCTM.logger.warn(
                    "CTM: enabled pack '{}' not found under resourcepacks/ (ignored)", packName);
                continue;
            }
            out.add(new PackSource(packName, file, info.priority));
        }
        CeleritasCTM.logger.info("CTM: enabled packs (low→high priority): {}", enabledNames);
        return out;
    }

    /** 仅用于发现文件（不参与优先级）；键为精确文件名 */
    private static Map<String, File> indexResourcepackFiles(File dir) {
        Map<String, File> map = new HashMap<>();
        if (dir == null || !dir.isDirectory()) return map;
        File[] files = dir.listFiles();
        if (files == null) return map;
        for (File file : files) {
            map.put(file.getName(), file);
        }
        return map;
    }

    /** 仓库未就绪时的退化扫描：文件名字母序，优先级按索引递增（可能不正确，仅尽力而为） */
    private static List<PackSource> scanAllResourcepacks(File dir) {
        List<PackSource> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        int i = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".zip")) {
                out.add(new PackSource(file.getName(), file, CtmRuleSupport.userPackPriority(i++)));
            } else if (file.isDirectory()) {
                out.add(new PackSource(file.getName(), file, CtmRuleSupport.userPackPriority(i++)));
            }
        }
        return out;
    }

    /**
     * 用资源管理器确认某个 resourceId 的<b>实际生效包名</b>；无法确认时返回 null。
     * 资源管理器从包列表末尾往前找（1.12.2 {@code FallbackResourceManager}），
     * 因此这里返回的就是最终覆盖来源。
     */
    private static String effectivePackName(IResourceManager resourceManager, ResourceLocation resourceId) {
        if (resourceManager == null || resourceId == null) return null;
        try {
            IResource resource = resourceManager.getResource(resourceId);
            if (resource == null) return null;
            try {
                return resource.getResourcePackName();
            } finally {
                resource.close(); // 修复资源泄漏：查询后必须关闭
            }
        } catch (Throwable t) {
            // 未找到 / 命名空间不存在 / 管理器尚未就绪：不据此否决任何规则
            return null;
        }
    }

    /**
     * 注入内置连接玻璃规则（method=ctm, tiles=0-46，位于本 mod jar 的
     * assets/celeritasctm/mcpatcher/ctm/connected_glass/）。
     *
     * <p>这是<b>通用回退</b>，不是资源包兼容补丁：
     * <ul>
     *   <li>只匹配原版精确纹理名 {@code blocks/glass}（不加通配，不碰染色玻璃或
     *       其它 mod 的玻璃类方块）；染色玻璃由渲染端用同一内置规则单独合成。</li>
     *   <li>内置规则在 {@link #findMatch} 中排在所有资源包规则之后（普通规则优先）。</li>
     *   <li>返回的对象只用于回退（内置瓦片）；资源包自己的 glass 规则永远不会被
     *       提升为全局模板，也不会在此函数返回。</li>
     *   <li>瓦片资产不存在时不注入，返回 null。</li>
     * </ul>
     */
    private static CtmProperties injectVanillaGlassPatch() {
        try {
            if (!resourceExists(new ResourceLocation("celeritasctm",
                    "mcpatcher/ctm/connected_glass/0.png"))) {
                CeleritasCTM.logger.info(
                    "CTM: built-in connected glass tiles not present - skipped glass fallback");
                return null;
            }
            ResourceLocation patchId =
                new ResourceLocation("celeritasctm", "mcpatcher/ctm/connected_glass/glass_vanilla_patch.properties");
            CtmProperties props = new CtmProperties(patchId, "ctm", Integer.MIN_VALUE);
            // 精确原版纹理名（显式命名空间）：不带命名空间的 blocks/glass 会命中
            // 其它 mod 的同名纹理，必须避免。
            props.addMatchTile("minecraft:blocks/glass");
            props.setTiles("0-46");
            props.setConnect("tile"); // 只与同种玻璃（minecraft:blocks/glass）连接
            props.setTileBased(true);
            // 规则列表供瓦片精灵缝合（MixinTextureMapExtended）；tile 索引里也登记，
            // 但 findMatch 的普通循环会显式跳过它，只在最后作为回退使用。
            PROPERTIES.add(props);
            TILE_INDEX.computeIfAbsent("glass", k -> new ArrayList<>()).add(props);
            CeleritasCTM.logger.info("CTM: built-in connected glass fallback rule available");
            return props;
        } catch (Throwable t) {
            CeleritasCTM.logger.warn("CTM: failed to build glass fallback: {}", t.toString());
            return null;
        }
    }

    /** 当前资源管理器是否能解析该资源（重载时不可用则返回 false，保守跳过补丁） */
    private static boolean resourceExists(ResourceLocation loc) {
        IResourceManager rm = activeResourceManager;
        if (rm == null) return false;
        try {
            IResource res = rm.getResource(loc);
            if (res != null) {
                res.close(); // 修复资源泄漏：探测后必须关闭
                return true;
            }
        } catch (Throwable ignored) {
            // 不存在 / 管理器未就绪
        }
        return false;
    }

    /** 构建双索引：纹理名 → 规则列表（含 matchTiles），规范化方块键 → 规则列表（纯 matchBlocks） */
    private static void rebuildIndex() {
        rebuildIndex(PROPERTIES);
    }

    private static void rebuildIndex(List<CtmProperties> source) {
        TILE_INDEX.clear();
        BLOCK_INDEX.clear();
        DEFERRED_NUMERIC_RULES.clear();
        // source 已按 packPriority 排序（高优先级在前），索引列表保持该顺序
        for (CtmProperties prop : source) {
            if (!prop.getMatchTilesClean().isEmpty()) {
                for (String clean : prop.getMatchTilesClean()) {
                    TILE_INDEX.computeIfAbsent(clean, k -> new ArrayList<>()).add(prop);
                }
                // matchTiles 规则也可能带未解析数字 ID 的 matchBlocks：
                // 这类约束必须仍被尊重（不能因为走了纹理索引就跳过）。
                if (prop.hasDeferredNumericIds()) DEFERRED_NUMERIC_RULES.add(prop);
            } else if (prop.hasBlockMatches() || prop.hasDeferredNumericIds()) {
                for (CtmRuleSupport.BlockMatch match : prop.getBlockMatches()) {
                    if (match.isNumeric() || match.getKey() == null) continue;
                    BLOCK_INDEX.computeIfAbsent(match.getKey(), k -> new ArrayList<>()).add(prop);
                }
                // 延迟数字 ID：若注册表此刻可用则解析并补入索引（只读查询，不改规则对象）
                for (Integer id : prop.getDeferredNumericIds()) {
                    String key = resolveNumericKey(id);
                    if (key != null) {
                        BLOCK_INDEX.computeIfAbsent(key, k -> new ArrayList<>()).add(prop);
                    }
                }
                // 数字 ID 无法解析（注册表未就绪）时查询期按方块 ID 兜底
                if (prop.hasDeferredNumericIds()) DEFERRED_NUMERIC_RULES.add(prop);
            }
        }
    }

    /** 只读地把数字 ID 解析为规范化键；注册表不可用/无映射时返回 null（绝不当作空气方块） */
    private static String resolveNumericKey(int id) {
        try {
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockById(id);
            if (block == null) return null;
            ResourceLocation rl = block.getRegistryName();
            if (rl == null) return null;
            // 注册表默认值（空气）不代表真实映射：ID 未注册时 getBlockById 返回空气，必须排除
            if (block == net.minecraft.init.Blocks.AIR) return null;
            return CtmRuleSupport.canonicalBlockKey(rl.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 添加属性：按 resourceId 去重，保留<b>优先级最高</b>的来源（不是先到先得），
     * 并跳过完全无法匹配的属性（无 matchTiles 也无可用 matchBlocks）。
     * method=overlay* 的连接过渡规则：本 mod 已移除 overlay 功能（CUTOUT 二次渲染），
     * 直接跳过，避免其 17 张 tile 被当作普通 ctm 规则误用。
     */
    private static boolean addProperty(CtmProperties props) {
        String method = props.getMethod();
        if (method != null && method.startsWith("overlay")) {
            return false;
        }
        if (!props.hasAnyMatch()) return false;
        String id = props.getResourceId().toString();
        Integer existing = SEEN_IDS.get(id);
        if (existing != null) {
            if (!CtmRuleSupport.shouldReplace(existing, props.getPackPriority())) {
                return false; // 已有更高或同优先级的来源
            }
            // 只在重复 resourceId 时做一次实证：以资源管理器报告的生效包名为准
            if (isShadowedByEffectiveSource(props, existing)) {
                return false;
            }
            // 新来源优先级更高：移除旧规则
            for (Iterator<CtmProperties> it = PROPERTIES.iterator(); it.hasNext(); ) {
                if (id.equals(it.next().getResourceId().toString())) it.remove();
            }
        }
        SEEN_IDS.put(id, props.getPackPriority());
        // 有 matchTiles 的规则视为 tile-based（OptiFine 默认 connect=tile）
        props.setTileBased(!props.getMatchTilesClean().isEmpty());
        PROPERTIES.add(props);
        return true;
    }

    /**
     * 仅在重复 resourceId 时调用：向资源管理器确认该文件的实际生效包，
     * 若生效包是另一个已知候选包则说明当前来源被覆盖，不应替换。
     * 查询失败或包名无法对应时返回 false（保守：不否决优先级推算）。
     */
    private static boolean isShadowedByEffectiveSource(CtmProperties props, int existingPriority) {
        String effective = effectivePackName(activeResourceManager, props.getResourceId());
        if (!CtmRuleSupport.isOverriddenByAnotherKnownPack(effective, currentPackName, CANDIDATE_PACK_NAMES)) {
            return false;
        }
        CeleritasCTM.logger.info(
            "CTM: {} has override '{}' which is not the high-priority source; keeping existing rule",
            props.getResourceId(), effective);
        return true;
    }

    /**
     * 面感知匹配：返回第一条「纹理+方块+元数据+面」都匹配的规则。
     *
     * <p>面为 null（真正无朝向的 quad）时，faces 限定的规则必须跳过
     * （CtmProperties.appliesTo(null)=false），否则 trapdoor 内部面等
     * 无朝向 quad 会被错误替换为 faces=sides 的规则纹理。
     * 注意：这里必须无条件调用 appliesTo——调用方应传入 quad.getFace()
     * （unculled 面进入 general quads 时 getQuads 的 side 参数为 null，
     * 但 quad 元素面的真实朝向保存在 BakedQuad.getFace() 中）。
     */
    public static CtmProperties findMatch(String textureName, net.minecraft.block.state.IBlockState state,
                                          net.minecraft.util.EnumFacing side) {
        return findMatch(textureName, state, side, null, null);
    }

    /**
     * 带世界上下文的面感知匹配。若规则含 biome/height 限制，必须由调用方提供
     * {@code world}/{@code pos}，否则这类规则 fail-closed（不匹配）。
     * 无环境限制的规则行为与会话 3 参数版本完全一致。
     */
    public static CtmProperties findMatch(String textureName,
                                          net.minecraft.block.state.IBlockState state,
                                          net.minecraft.util.EnumFacing side,
                                          net.minecraft.world.IBlockAccess world,
                                          net.minecraft.util.math.BlockPos pos) {
        return findMatchInSnapshot(snapshot, textureName, state, side, world, pos);
    }

    /**
     * 在给定快照上执行匹配（包可见，供行为测试直接构造快照）。
     *
     * <p>优先级严格为：普通 matchTiles 规则 → 普通 matchBlocks 规则 →
     * 延迟数字 ID 规则 → 最后才是内置玻璃回退。内置回退在 tile 索引里也会出现，
     * 因此普通循环必须显式跳过它，否则它会抢在资源包方块规则之前命中。
     */
    static CtmProperties findMatchInSnapshot(RuleSnapshot snap,
                                             String textureName,
                                             net.minecraft.block.state.IBlockState state,
                                             net.minecraft.util.EnumFacing side,
                                             net.minecraft.world.IBlockAccess world,
                                             net.minecraft.util.math.BlockPos pos) {
        if (snap == null) return null;
        // ---- 一次请求内的惰性上下文（不跨调用缓存，避免脏状态）----
        // 清理后的纹理名与命名空间只算一次，索引查找与每条候选共用；
        // 规范化方块键只算一次（首次真正需要时）。
        String cleanTexture = textureName == null ? null : CtmProperties.cleanTextureName(textureName);
        String textureNamespace = textureName == null ? null : CtmProperties.namespaceOf(textureName);
        EnvContext env = null;          // 仅在候选规则带 height/biome 限制时创建
        String canonicalKey = null;     // 仅在候选规则带方块约束时计算
        boolean canonicalKeyReady = false;
        // 状态属性表：仅当候选规则真的带属性谓词时才构建，且一次请求内只构建一次
        Map<String, String> stateProps = null;
        boolean statePropsReady = false;

        // 1) matchTiles 规则（按 packPriority 排序，高优先级在前）。
        //    跳过内置玻璃回退：它不是普通规则，只能在所有普通规则之后使用。
        if (textureName != null) {
            List<CtmProperties> list = snap.tileIndex.get(cleanTexture);
            if (list != null) {
                for (CtmProperties p : list) {
                    if (p == snap.glassRule) continue;
                    if (!p.matchesTileClean(cleanTexture, textureNamespace)) continue;
                    if (p.needsEnvironment() && env == null) env = new EnvContext(world, pos);
                    if (state != null && p.hasBlockConstraint() && !canonicalKeyReady) {
                        canonicalKey = CtmProperties.canonicalKeyOf(state);
                        canonicalKeyReady = true;
                    }
                    if (p.hasBlockPropertyPredicates() && state != null && !statePropsReady) {
                        stateProps = CtmProperties.statePropertyMap(state);
                        statePropsReady = true;
                    }
                    if (matchesContext(p, state, side, env, canonicalKey, stateProps)) {
                        return p;
                    }
                }
            }
        }
        // 2) matchBlocks 规则（无 matchTiles 时按规范化方块键匹配）
        if (state != null) {
            if (!canonicalKeyReady) {
                canonicalKey = CtmProperties.canonicalKeyOf(state);
                canonicalKeyReady = true;
            }
            if (canonicalKey != null) {
                List<CtmProperties> list = snap.blockIndex.get(canonicalKey);
                if (list != null) {
                    for (CtmProperties p : list) {
                        if (p == snap.glassRule) continue;
                        if (p.needsEnvironment() && env == null) env = new EnvContext(world, pos);
                        if (p.hasBlockPropertyPredicates() && !statePropsReady) {
                            stateProps = CtmProperties.statePropertyMap(state);
                            statePropsReady = true;
                        }
                        if (matchesContext(p, state, side, env, canonicalKey, stateProps)) {
                            return p;
                        }
                    }
                }
            }
        }
        // 3) 注册表尚未就绪时未能进入方块索引的数字 ID 规则：查询期按方块 ID 兜底
        for (CtmProperties p : snap.deferredNumericRules) {
            if (p == snap.glassRule) continue;
            if (!p.hasDeferredNumericIds()) continue;
            if (!p.getMatchTilesClean().isEmpty() && !p.matchesTileClean(cleanTexture, textureNamespace)) continue;
            if (p.needsEnvironment() && env == null) env = new EnvContext(world, pos);
            if (state != null && p.hasBlockConstraint() && !canonicalKeyReady) {
                canonicalKey = CtmProperties.canonicalKeyOf(state);
                canonicalKeyReady = true;
            }
            if (p.hasBlockPropertyPredicates() && state != null && !statePropsReady) {
                stateProps = CtmProperties.statePropertyMap(state);
                statePropsReady = true;
            }
            if (matchesContext(p, state, side, env, canonicalKey, stateProps)) {
                return p;
            }
        }
        // 4) 内置玻璃回退：只有全部普通规则都未命中、且纹理确实是内置规则声明的那一张时才使用
        CtmProperties glass = snap.glassRule;
        if (glass != null
                && textureName != null
                && glass.matchesTileClean(cleanTexture, textureNamespace)) {
            if (glass.needsEnvironment() && env == null) env = new EnvContext(world, pos);
            if (state != null && glass.hasBlockConstraint() && !canonicalKeyReady) {
                canonicalKey = CtmProperties.canonicalKeyOf(state);
                canonicalKeyReady = true;
            }
            if (glass.hasBlockPropertyPredicates() && state != null && !statePropsReady) {
                stateProps = CtmProperties.statePropertyMap(state);
                statePropsReady = true;
            }
            if (matchesContext(glass, state, side, env, canonicalKey, stateProps)) {
                return glass;
            }
        }
        return null;
    }

    /**
     * 方块/元数据/高度/biome/面 的统一判定（不含纹理判定）。
     *
     * @param env         惰性环境上下文；仅当规则带 height/biome 限制时才由调用方创建
     * @param canonicalKey 调用方已计算好的规范化方块键；规则无方块约束时不使用
     * @param stateProps  调用方已构建的状态属性表；规则无属性谓词时为 null
     */
    private static boolean matchesContext(CtmProperties p, net.minecraft.block.state.IBlockState state,
                                          net.minecraft.util.EnumFacing side, EnvContext env,
                                          String canonicalKey, Map<String, String> stateProps) {
        // 不变量保护：规则需要环境上下文时调用方必须已创建 env。
        // 若漏建则 fail-closed（绝不把 height/biome 限制当作“无限制”）。
        if (p.needsEnvironment() && env == null) return false;
        return p.matchesBlock(state, canonicalKey, stateProps)
            && p.matchesMetadata(state)
            && p.matchesHeight(env == null ? null : env.height())
            // 无 biome 限制的规则绝不触发 biome 查询
            && (!p.hasBiomeRestriction() || p.matchesBiomeKeys(env.biomeKeys()))
            && p.appliesTo(side);
    }

    /** 一次匹配内的惰性环境上下文：height 总是可用，biome 只在必要时查询。 */
    private static final class EnvContext {
        private final net.minecraft.world.IBlockAccess world;
        private final net.minecraft.util.math.BlockPos pos;
        private List<String> biomeKeys;
        private boolean biomeResolved;

        EnvContext(net.minecraft.world.IBlockAccess world, net.minecraft.util.math.BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }

        Integer height() {
            return pos == null ? null : pos.getY();
        }

        List<String> biomeKeys() {
            if (!biomeResolved) {
                biomeKeys = biomeKeysAt(world, pos);
                biomeResolved = true;
            }
            return biomeKeys;
        }
    }

    /**
     * 当前位置 biome 的候选键：注册名 + 实际显示名（旧版资源包常写显示名）。
     * 两者都规范化后返回；无世界/坐标上下文或无法取得名字时返回 null。
     * 不做任何“显示名 → 注册名”的硬编码映射，只比较两端实际存在的字符串。
     */
    private static List<String> biomeKeysAt(net.minecraft.world.IBlockAccess world,
                                            net.minecraft.util.math.BlockPos pos) {
        if (world == null || pos == null) return null;
        try {
            net.minecraft.world.biome.Biome biome = world.getBiome(pos);
            if (biome == null) return null;
            List<String> keys = new ArrayList<>(2);
            ResourceLocation rl = biome.getRegistryName();
            if (rl != null) {
                String key = CtmRuleSupport.canonicalBlockKey(rl.toString());
                if (key != null) keys.add(key);
            }
            String name = biome.getBiomeName();
            if (name != null && !name.trim().isEmpty()) {
                String key = CtmRuleSupport.canonicalBlockKey(name);
                if (key != null && !keys.contains(key)) keys.add(key);
            }
            return keys.isEmpty() ? null : keys;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int scanZipFile(File zipFile, int packPriority) {
        int count = 0;
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".properties")) continue;

                String ctmPath;
                if (name.contains("mcpatcher/ctm/")) {
                    ctmPath = "mcpatcher/ctm";
                } else if (name.contains("optifine/ctm/")) {
                    ctmPath = "optifine/ctm";
                } else {
                    continue;
                }

                int assetsIdx = name.indexOf("assets/");
                if (assetsIdx < 0) continue;
                String afterAssets = name.substring(assetsIdx + 7);
                int slashIdx = afterAssets.indexOf('/');
                if (slashIdx < 0) continue;
                String namespace = afterAssets.substring(0, slashIdx);

                try (InputStream is = zf.getInputStream(entry)) {
                    int ctmIdx = name.indexOf(ctmPath);
                    String resourcePath = name.substring(ctmIdx);
                    ResourceLocation resourceId = new ResourceLocation(namespace, resourcePath);
                    currentPackName = zipFile.getName();
                    CtmProperties props = parseProperties(is, resourceId, packPriority);
                    if (props != null && addProperty(props)) {
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            CeleritasCTM.logger.debug("Failed to scan zip '{}': {}", zipFile.getName(), e.getMessage());
        }
        return count;
    }

    private static int scanDirectory(File dir, int packPriority) {
        int count = 0;
        File assetsDir = new File(dir, "assets");
        if (assetsDir.isDirectory()) {
            File[] nsDirs = assetsDir.listFiles();
            if (nsDirs != null) {
                for (File namespaceDir : nsDirs) {
                    if (!namespaceDir.isDirectory()) continue;
                    String namespace = namespaceDir.getName();
                    for (String ctmPath : new String[] { "mcpatcher/ctm", "optifine/ctm" }) {
                        File ctmDir = new File(namespaceDir, ctmPath);
                        if (ctmDir.isDirectory()) {
                            count += scanPropertiesDir(ctmDir, namespace, ctmPath, packPriority, dir.getName());
                        }
                    }
                }
            }
        }
        return count;
    }

    private static int scanPropertiesDir(File dir, String namespace, String ctmPath, int packPriority,
                                         String packName) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += scanPropertiesDir(file, namespace, ctmPath, packPriority, packName);
            } else if (file.getName().endsWith(".properties")) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    String relativePath = file.getAbsolutePath().replace('\\', '/');
                    int idx = relativePath.indexOf(ctmPath);
                    if (idx >= 0) {
                        String resourcePath = relativePath.substring(idx);
                        ResourceLocation resourceId = new ResourceLocation(namespace, resourcePath);
                        currentPackName = packName;
                        CtmProperties props = parseProperties(fis, resourceId, packPriority);
                        if (props != null && addProperty(props)) {
                            count++;
                        }
                    }
                } catch (Exception e) {
                    CeleritasCTM.logger.debug("Failed to parse '{}': {}", file.getAbsolutePath(), e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * 解析单个.properties文件。返回 null 表示该规则应被跳过（坏 metadata / 坏 connect /
     * 无可用匹配目标），调用方会把 null 视为“不加载”。
     */
    public static CtmProperties parseProperties(InputStream stream, ResourceLocation resourceId,
                                                 int packPriority) {
        try {
            Properties props = new Properties();
            props.load(stream);

            String method = props.getProperty("method", "ctm").trim().toLowerCase(Locale.ROOT);
            CtmProperties ctm = new CtmProperties(resourceId, method, packPriority);

            // properties 所在目录（相对路径解析与文件名回退共用）
            String propsPath = resourceId.getPath();
            String pathDir = propsPath.contains("/")
                ? propsPath.substring(0, propsPath.lastIndexOf('/') + 1) : "";

            // matchTiles（支持相对路径 ./xxx.png，基于 properties 所在目录解析）
            String matchTilesRaw = props.getProperty("matchTiles", "");
            if (!matchTilesRaw.isEmpty()) {
                for (String tile : matchTilesRaw.split("\\s+")) {
                    String t = tile.trim();
                    if (t.isEmpty()) continue;
                    // 相对路径 → 完整路径（不含命名空间），如 ./81.png → mcpatcher/ctm/.../plastered_stone/81.png
                    if (t.startsWith("./")) {
                        t = pathDir + t.substring(2);
                    }
                    ctm.addMatchTile(t);
                }
            }

            // matchBlocks：统一走 CtmRuleSupport 归一化（数字 ID / 短名 / ns:name / ns:name:prop=value）
            String matchBlocksRaw = props.getProperty("matchBlocks", "");
            if (!matchBlocksRaw.isEmpty()) {
                boolean anyValidToken = false;
                for (String token : matchBlocksRaw.split("\\s+")) {
                    String t = token.trim();
                    if (t.isEmpty()) continue;
                    CtmRuleSupport.BlockMatch parsed = CtmRuleSupport.parseMatchToken(t);
                    if (parsed == null) {
                        CeleritasCTM.logger.warn("CTM: {} has invalid matchBlocks entry '{}' (ignored)",
                            resourceId, t);
                        continue;
                    }
                    if (parsed.isNumeric()) {
                        // 数字 ID 延迟解析：注册表不可用时保留裸 ID，绝不映射为空气；
                        // 可用时立即解析为键。两条路径都由 hasBlockConstraint() 保证不从“有约束”退化为“无约束”。
                        ctm.addMatchBlockRecord(t);
                        ctm.addDeferredNumericId(parsed.getNumericId());
                        String resolvedKey = resolveNumericKey(parsed.getNumericId());
                        if (resolvedKey != null) {
                            ctm.addBlockMatch(parsed.resolvedTo(resolvedKey));
                        }
                    } else {
                        ctm.addMatchBlockRecord(t);
                        ctm.addBlockMatch(parsed);
                    }
                    anyValidToken = true;
                }
                if (!anyValidToken) {
                    // 显式写了 matchBlocks 但无一有效：整条规则跳过。
                    // 关键：即使 matchTiles 存在也不得放宽为“任意方块”，否则会把规则
                    // 扩散到所有使用该纹理的方块（引入行为不属于该资源包）。
                    CeleritasCTM.logger.warn(
                        "CTM: {} has no usable matchBlocks entries - rule skipped (not widened)", resourceId);
                    return null;
                }
            }

            // OptiFine 文件名机制（通用回退，matchBlocks 缺省时才生效）：
            // 1) block<数字>[后缀].properties → 匹配该数字 ID 的方块。即使文件里写了
            //    matchTiles 也要加上这个方块约束（两条约束取交，不能因文件名被忽略而放宽）；
            // 2) 没有 block<id> 文件名、也没有 matchTiles 时，用“文件名主干”精确作为纹理名
            //    （如 planks.properties → 纹理 planks；planks2.properties → planks2）。
            boolean hasExplicitBlockMatch = ctm.hasBlockMatches() || ctm.hasDeferredNumericIds();
            if (!hasExplicitBlockMatch) {
                if (!addBlockIdMatch(ctm) && ctm.getMatchTilesClean().isEmpty()) {
                    addFilenameTileMatch(ctm, pathDir);
                }
            }

            // tiles
            String tilesRaw = props.getProperty("tiles", "");
            if (!tilesRaw.isEmpty()) {
                ctm.setTiles(tilesRaw.trim());
            }

            // faces / sides：
            // - sides 单独出现时取代默认 faces=all（A11）
            // - faces 与 sides 同时出现时取并集
            // - sides 不是 OptiFine 标准属性，仅为 Conquest 等扩展包兼容
            Set<String> faces = CtmRuleSupport.resolveFaces(
                props.getProperty("faces"), props.getProperty("sides"));
            for (String face : faces) ctm.addFace(face);

            // metadata（支持列表与范围，如 "1 5 9"、"0-5"；默认 -1 表示所有）
            String metadataRaw = props.getProperty("metadata", "-1").trim();
            if (!metadataRaw.isEmpty() && !"-1".equals(metadataRaw)) {
                int[] meta = CtmRuleSupport.parseMetadata(metadataRaw);
                if (meta == null) {
                    // fail-closed：坏 metadata 不得放宽为“全部匹配”
                    CeleritasCTM.logger.warn("CTM: {} has invalid metadata '{}' - rule skipped",
                        resourceId, metadataRaw);
                    return null;
                }
                ctm.setMetadata(meta);
            }

            // weights
            String weightsRaw = props.getProperty("weights", "");
            if (!weightsRaw.isEmpty()) {
                String[] parts = weightsRaw.trim().split("\\s+");
                int[] weights = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    try { weights[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException e) { weights[i] = 1; }
                }
                ctm.setWeights(weights);
            }

            // width / height
            try { ctm.setWidth(Integer.parseInt(props.getProperty("width", "1").trim())); } catch (NumberFormatException ignored) {}
            try { ctm.setHeight(Integer.parseInt(props.getProperty("height", "1").trim())); } catch (NumberFormatException ignored) {}

            // 环境过滤：heights / minHeight / maxHeight / biomes（通用 OptiFine 键）。
            // 注意：height= 是 repeat 的尺寸参数（见 width/height），此处不作为选择器解析。
            // heights 优先：写了 heights 就不再合并 minHeight/maxHeight，
            // 否则两组区间并集会把范围放宽（不应扩大规则命中面）。
            String heightsRaw = props.getProperty("heights");
            List<CtmRuleSupport.HeightRange> ranges = null;
            if (heightsRaw != null && !heightsRaw.trim().isEmpty()) {
                ranges = CtmRuleSupport.parseHeightRanges(heightsRaw);
                if (ranges == null) {
                    CeleritasCTM.logger.warn("CTM: {} has invalid heights '{}' - rule skipped",
                        resourceId, heightsRaw);
                    return null;
                }
            } else {
                String minH = props.getProperty("minHeight");
                String maxH = props.getProperty("maxHeight");
                if ((minH != null && !minH.trim().isEmpty()) || (maxH != null && !maxH.trim().isEmpty())) {
                    ranges = CtmRuleSupport.parseMinMaxHeight(minH, maxH);
                    if (ranges == null) {
                        CeleritasCTM.logger.warn("CTM: {} has invalid minHeight/maxHeight '{}/{}' - rule skipped",
                            resourceId, minH, maxH);
                        return null;
                    }
                }
            }
            ctm.setHeightRanges(ranges);

            String biomesRaw = props.getProperty("biomes");
            if (biomesRaw != null && !biomesRaw.trim().isEmpty()) {
                CtmRuleSupport.BiomeFilter filter = CtmRuleSupport.parseBiomeFilter(biomesRaw);
                if (filter == null) {
                    CeleritasCTM.logger.warn("CTM: {} has invalid biomes '{}' - rule skipped",
                        resourceId, biomesRaw);
                    return null;
                }
                ctm.setBiomes(filter.included);
                ctm.setBiomeExclusions(filter.excluded);
            }

            // connect（合法值 block|tile|material|state；其余 fail-closed）
            String connectRaw = props.getProperty("connect", "").trim();
            if (!CtmRuleSupport.isValidConnect(connectRaw)) {
                CeleritasCTM.logger.warn("CTM: {} has unknown connect value '{}' - rule skipped",
                    resourceId, connectRaw);
                return null;
            }
            ctm.setConnect(connectRaw);

            // symmetry（random/repeat 的面对称性）
            ctm.setSymmetry(props.getProperty("symmetry", "none").trim());

            // connectTiles
            String connectTilesRaw = props.getProperty("connectTiles", "");
            if (!connectTilesRaw.isEmpty()) {
                for (String tile : connectTilesRaw.split("\\s+")) {
                    ctm.addConnectTile(tile.trim());
                }
            }

            return ctm;
        } catch (Exception e) {
            CeleritasCTM.logger.error("Failed to parse '{}': {}", resourceId, e.getMessage());
            return null;
        }
    }

    /**
     * OptiFine block(数字ID) 文件名机制：规则文件名形如 block171a.properties
     * （匹配 ID=171 的方块）时，通过 Block.getBlockById 反查 ID 得到注册名。
     * 注册表不可用时保留裸数字 ID（延迟解析），不映射为空气。
     *
     * @return 是否识别出 block<id> 文件名（无论是否立即解析成功）
     */
    private static boolean addBlockIdMatch(CtmProperties ctm) {
        Integer blockId = parseBlockIdFromFileName(ctm.getResourceId().getPath());
        if (blockId == null) return false;
        String key = resolveNumericKey(blockId);
        if (key != null) {
            ctm.addMatchBlock(key);
        } else {
            // 注册表未就绪：保留裸 ID，重载后（注册表可用）再解析
            ctm.addDeferredNumericId(blockId);
        }
        return true;
    }

    /**
     * 从规则文件名解析 OptiFine {@code block<id>} 形式（纯函数，不查注册表）。
     * 仅识别 {@code block} 前缀 + 至少一位数字，且数字必须落在 1-4095。
     * 例如 {@code block171a.properties} → 171；{@code block.properties}/null → null。
     */
    static Integer parseBlockIdFromFileName(String path) {
        if (path == null) return null;
        int lastSlash = path.lastIndexOf('/');
        String base = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (!base.startsWith("block")) return null;
        int i = 5;
        while (i < base.length() && Character.isDigit(base.charAt(i))) i++;
        if (i == 5) return null;
        try {
            int id = Integer.parseInt(base.substring(5, i));
            // 保留 0：显式 ID 0 仍是“有约束”（解析期拒绝映射为空气），不能退回文件名纹理匹配
            return id >= 0 && id <= 4095 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 文件名主干（去掉目录、{@code .properties}）；不做任何后缀剥离。 */
    static String filenameStem(String path) {
        if (path == null) return "";
        int lastSlash = path.lastIndexOf('/');
        String stem = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        if (stem.endsWith(".properties")) stem = stem.substring(0, stem.length() - 11);
        return stem;
    }

    /**
     * 通用文件名匹配回退：规则既无 matchTiles 也无 matchBlocks 时，
     * 用文件名主干（仅去掉 {@code .properties}）精确作为纹理名。
     * 例如 {@code ctm/planks.properties} → {@code blocks/planks}，
     * {@code ctm/planks2.properties} → {@code blocks/planks2}（不再剥掉数字后缀，
     * 否则会把 planks2 误配到 planks 的所有方块上）。
     *
     * <p>仅当该纹理名确实存在于图集（资源管理器可解析对应 PNG）时才采用，
     * 避免凭空扩大匹配面。
     */
    private static void addFilenameTileMatch(CtmProperties ctm, String pathDir) {
        String stem = filenameStem(ctm.getResourceId().getPath());
        if (stem.isEmpty()) return;

        // 候选纹理名：显式带命名空间、无命名空间（属主命名空间）
        String ns = ctm.getResourceId().getNamespace();
        String bare = "blocks/" + stem;
        ResourceLocation probeOwn = new ResourceLocation(ns, "textures/" + bare + ".png");
        ResourceLocation probeMc = new ResourceLocation("minecraft", "textures/" + bare + ".png");
        if (resourceExists(probeOwn)) {
            ctm.addMatchTile(ns.equals("minecraft") ? bare : ns + ":" + bare);
        } else if (resourceExists(probeMc)) {
            ctm.addMatchTile("minecraft:" + bare);
        }
        // 都不存在：不猜测，保持无匹配 → addProperty 会跳过该规则
    }

    /** 同一个未知属性只 warn 一次（跨规则、跨重载去重由 WARNED_PROPERTIES 保证） */
    private static void warnOnceForProperty(String message) {
        String key = message;
        synchronized (WARNED_PROPERTIES) {
            if (!WARNED_PROPERTIES.add(key)) return;
        }
        CeleritasCTM.logger.warn("CTM: {}", message);
    }

    public static List<CtmProperties> getProperties() {
        return snapshot.properties;
    }

    /** 当前已发布的不可变快照（渲染线程每次重载后应重新获取） */
    public static RuleSnapshot getSnapshot() {
        return snapshot;
    }

    /** 当前玻璃规则（来自同一份快照，避免与规则列表不一致） */
    public static CtmProperties getGlassRule() {
        return snapshot.glassRule;
    }

    public static void reload(IResourceManager resourceManager) {
        loadAll(resourceManager);
    }

}
