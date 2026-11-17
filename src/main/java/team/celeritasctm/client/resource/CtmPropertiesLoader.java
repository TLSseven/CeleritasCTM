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

    private static final List<CtmProperties> PROPERTIES = new ArrayList<>();
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
    private static boolean loaded = false;

    // 内置连接玻璃规则（mod jar 内 tiles 0-46，见 assets/celeritasctm/mcpatcher/ctm/connected_glass/）。
    // 无色玻璃（blocks/glass）经 findMatch 命中；染色玻璃在 CtmBakedModel 手动走此规则。
    public static CtmProperties GLASS_RULE = null;

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

    public static void loadAll(IResourceManager resourceManager) {
        PROPERTIES.clear();
        TILE_INDEX.clear();
        BLOCK_INDEX.clear();
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
        } catch (Exception e) {
            CeleritasCTM.logger.error("Error scanning resource packs: {}", e.getMessage(), e);
        }

        Collections.sort(PROPERTIES);
        rebuildIndex();
        loaded = true;
        // 补丁必须在 rebuildIndex 之后（否则 TILE_INDEX 被清空）
        injectVanillaIronBarsPatch();
        injectVanillaGlassPatch();
        CeleritasCTM.logger.info("=== CTM Properties Loader: Total {} CTM properties loaded ===", totalLoaded);
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
        File resourcepacksDir = new File(Minecraft.getMinecraft().gameDir, "resourcepacks");

        ResourcePackRepository repo = null;
        try {
            repo = Minecraft.getMinecraft().getResourcePackRepository();
        } catch (Throwable t) {
            repo = null;
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
            File file = byName.get(packName);
            if (file == null) file = byName.get(packName + ".zip");
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

    /** 仅用于发现文件（不参与优先级）；键为文件名与去掉 .zip 的名字 */
    private static Map<String, File> indexResourcepackFiles(File dir) {
        Map<String, File> map = new HashMap<>();
        if (dir == null || !dir.isDirectory()) return map;
        File[] files = dir.listFiles();
        if (files == null) return map;
        for (File file : files) {
            String name = file.getName();
            map.put(name, file);
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                map.put(name.substring(0, name.length() - 4), file);
            }
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
            return resource == null ? null : resource.getResourcePackName();
        } catch (Throwable t) {
            // 未找到 / 命名空间不存在 / 管理器尚未就绪：不据此否决任何规则
            return null;
        }
    }

    /**
     * 注入补丁规则：原版铁栅栏（minecraft:iron_bars）的 CTM 规则。
     *
     * <p>Conquest 资源包覆盖了原版 iron_bars.png：杆画在贴图 x9-13/x20-24
     * （对应 UV 4.5-6 / 9-10.5），而原版铁栅栏模型（iron_bars_post/cap 等）
     * 的 quad 采样 UV 7-9（贴图 x14-18）——恰好落在透明区，导致无连接时
     * 柱子完全不可见。Conquest 未给原版铁栅栏提供任何规则或模型覆盖。
     *
     * <p>补丁：给原版 iron_bars 注入 Conquest 自带 iron_fence 的 CTM 规则
     * （tiles 1-48），其贴图采样区 x14-18 有杆（Conquest ironbar 方块同款），
     * 恢复可见并获得连接纹理。规则在 rebuildIndex 之后注入索引，不影响其他规则。
     */
    private static void injectVanillaIronBarsPatch() {
        try {
            ResourceLocation patchId =
                new ResourceLocation("minecraft", "mcpatcher/ctm/3_decoration/2_metal/fence/iron_fence/ironbar_vanilla_patch.properties");
            CtmProperties props = new CtmProperties(patchId, "ctm", Integer.MAX_VALUE);
            props.addMatchTile("blocks/iron_bars");
            props.setTiles("1-48");
            props.addFace("north"); props.addFace("south");
            props.addFace("east"); props.addFace("west");
            props.setConnect("material");
            props.setTileBased(true);
            // 加入主列表 + 重建索引（prop 已 sort 后加入会乱序，这里直接补进 TILE_INDEX）
            PROPERTIES.add(props);
            TILE_INDEX.computeIfAbsent("iron_bars", k -> new ArrayList<>()).add(props);
            CeleritasCTM.logger.info("CTM: injected vanilla iron_bars CTM patch rule");
        } catch (Throwable t) {
            CeleritasCTM.logger.warn("CTM: failed to inject iron_bars patch: {}", t.toString());
        }
    }

    /**
     * 注入内置连接玻璃规则（method=ctm, tiles=0-46，位于本 mod jar 的
     * assets/celeritasctm/mcpatcher/ctm/connected_glass/）。玻璃是 CUTOUT 层，
     * 现有 getQuads 逐 quad + ConnectionHandler.getMask + remapUV 直接生效。
     * 关闭「无缝玻璃」配置后由 CtmBakedModel 跳过（规则本身保留，tile 缝合无成本）。
     */
    private static void injectVanillaGlassPatch() {
        try {
            ResourceLocation patchId =
                new ResourceLocation("celeritasctm", "mcpatcher/ctm/connected_glass/glass_vanilla_patch.properties");
            CtmProperties props = new CtmProperties(patchId, "ctm", Integer.MAX_VALUE);
            props.addMatchTile("blocks/glass");
            props.setTiles("0-46");
            props.setConnect("tile"); // 只与同种玻璃（blocks/glass）连接
            props.setTileBased(true);
            GLASS_RULE = props;
            PROPERTIES.add(props);
            TILE_INDEX.computeIfAbsent("glass", k -> new ArrayList<>()).add(props);
            CeleritasCTM.logger.info("CTM: injected vanilla connected glass CTM patch rule");
        } catch (Throwable t) {
            CeleritasCTM.logger.warn("CTM: failed to inject glass patch: {}", t.toString());
        }
    }

    /** 构建双索引：纹理名 → 规则列表（含 matchTiles），规范化方块键 → 规则列表（纯 matchBlocks） */
    private static void rebuildIndex() {
        TILE_INDEX.clear();
        BLOCK_INDEX.clear();
        // PROPERTIES 已按 packPriority 排序（高优先级在前），索引列表保持该顺序
        for (CtmProperties prop : PROPERTIES) {
            if (!prop.getMatchTilesClean().isEmpty()) {
                for (String clean : prop.getMatchTilesClean()) {
                    TILE_INDEX.computeIfAbsent(clean, k -> new ArrayList<>()).add(prop);
                }
            } else if (prop.hasBlockMatches()) {
                for (CtmRuleSupport.BlockMatch match : prop.getBlockMatches()) {
                    if (match.isNumeric() || match.getKey() == null) continue;
                    BLOCK_INDEX.computeIfAbsent(match.getKey(), k -> new ArrayList<>()).add(prop);
                }
            }
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
        return findMatchInternal(textureName, state, side);
    }

    private static CtmProperties findMatchInternal(String textureName,
                                                   net.minecraft.block.state.IBlockState state,
                                                   net.minecraft.util.EnumFacing side) {
        // 1) matchTiles 规则
        if (textureName != null) {
            List<CtmProperties> list = TILE_INDEX.get(CtmProperties.cleanTextureName(textureName));
            if (list != null) {
                for (CtmProperties p : list) {
                    if (p.matchesBlock(state) && p.matchesMetadata(state) && p.appliesTo(side)) {
                        return p;
                    }
                }
            }
        }
        // 2) matchBlocks 规则（无 matchTiles 时按规范化方块键匹配）
        if (state != null) {
            String key = CtmProperties.canonicalKeyOf(state);
            if (key != null) {
                List<CtmProperties> list = BLOCK_INDEX.get(key);
                if (list != null) {
                    for (CtmProperties p : list) {
                        if (p.matchesBlock(state) && p.matchesMetadata(state) && p.appliesTo(side)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
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

            // matchTiles（支持相对路径 ./xxx.png，基于 properties 所在目录解析）
            String matchTilesRaw = props.getProperty("matchTiles", "");
            if (!matchTilesRaw.isEmpty()) {
                String propsPath = resourceId.getPath();
                String propsDir = propsPath.contains("/")
                    ? propsPath.substring(0, propsPath.lastIndexOf('/') + 1) : "";
                for (String tile : matchTilesRaw.split("\\s+")) {
                    String t = tile.trim();
                    if (t.isEmpty()) continue;
                    // 相对路径 → 完整路径（不含命名空间），如 ./81.png → mcpatcher/ctm/.../plastered_stone/81.png
                    if (t.startsWith("./")) {
                        t = propsDir + t.substring(2);
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
                        CtmRuleSupport.BlockMatch resolved = resolveNumericId(resourceId, parsed);
                        if (resolved == null) continue; // 已在 resolveNumericId 内 warn
                        ctm.addMatchBlockRecord(resolved.getKey());
                        ctm.addBlockMatch(resolved);
                    } else {
                        ctm.addMatchBlockRecord(t);
                        ctm.addBlockMatch(parsed);
                    }
                    anyValidToken = true;
                }
                if (!anyValidToken && ctm.getMatchTilesClean().isEmpty()) {
                    CeleritasCTM.logger.warn("CTM: {} has no usable matchBlocks entries - rule skipped",
                        resourceId);
                    return null;
                }
            }

            // OptiFine block<ID> 文件名机制：matchTiles/matchBlocks 都为空时，
            // 若文件名是 block<数字>[后缀]（如 block171a.properties），自动匹配方块 ID。
            // Conquest 的地毯规则（block171*.properties）依赖此机制。
            if (ctm.getMatchTilesClean().isEmpty() && !ctm.hasBlockMatches()) {
                addBlockIdMatch(ctm);
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
     * 数字 Block ID 归一化：OptiFine 1.12.2 文档 {@code doc/ctm.properties:47}
     * 规定 {@code matchBlocks=<list of block IDs 0-4095>}。
     * 解析失败（返回 null 或注册名为 null）时 warn 并跳过该条，不得静默当成方块名。
     */
    private static CtmRuleSupport.BlockMatch resolveNumericId(ResourceLocation resourceId,
                                                              CtmRuleSupport.BlockMatch numeric) {
        int id = numeric.getNumericId();
        try {
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockById(id);
            if (block == null) {
                CeleritasCTM.logger.warn("CTM: {} matchBlocks id {} resolves to no block (ignored)",
                    resourceId, id);
                return null;
            }
            ResourceLocation rl = block.getRegistryName();
            if (rl == null) {
                CeleritasCTM.logger.warn("CTM: {} matchBlocks id {} has no registry name (ignored)",
                    resourceId, id);
                return null;
            }
            return numeric.resolvedTo(rl.toString());
        } catch (Throwable t) {
            CeleritasCTM.logger.warn("CTM: {} failed to resolve matchBlocks id {}: {}",
                resourceId, id, t.toString());
            return null;
        }
    }

    /**
     * OptiFine block(数字ID) 文件名机制：规则文件名形如 block171a.properties
     * （匹配 ID=171 的方块 minecraft:carpet）时，通过 Block.getBlockById
     * 反查 ID 得到注册名，加入 matchBlocks。
     * Conquest 的地毯规则（carpet 各颜色目录下的 block171*.properties）依赖此机制。
     */
    private static void addBlockIdMatch(CtmProperties ctm) {
        String fileName = ctm.getResourceId().getPath();
        int lastSlash = fileName.lastIndexOf('/');
        String base = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
        if (!base.startsWith("block")) return;
        int digitStart = 5;
        int i = digitStart;
        while (i < base.length() && Character.isDigit(base.charAt(i))) i++;
        if (i == digitStart) return; // block 后无数字
        try {
            int blockId = Integer.parseInt(base.substring(digitStart, i));
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockById(blockId);
            if (block == null) {
                CeleritasCTM.logger.warn("CTM: {} filename implies block id {} but no such block",
                    ctm.getResourceId(), blockId);
                return;
            }
            net.minecraft.util.ResourceLocation rl = block.getRegistryName();
            if (rl == null) return;
            ctm.addMatchBlock(rl.toString());
        } catch (NumberFormatException ignored) {
        }
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
        return Collections.unmodifiableList(PROPERTIES);
    }

    public static void reload(IResourceManager resourceManager) {
        loaded = false;
        loadAll(resourceManager);
    }

}
