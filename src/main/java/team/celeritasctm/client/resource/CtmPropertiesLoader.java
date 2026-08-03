package team.celeritasctm.client.resource;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.*;
import net.minecraft.util.EnumFacing;
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
 */
public class CtmPropertiesLoader {

    private static final List<CtmProperties> PROPERTIES = new ArrayList<>();
    // 清理后的纹理名 → 属性列表（含 matchTiles 的规则；列表按 packPriority 顺序）
    private static final Map<String, List<CtmProperties>> TILE_INDEX = new HashMap<>();
    // 方块注册名 → 属性列表（纯 matchBlocks 的规则）
    private static final Map<String, List<CtmProperties>> BLOCK_INDEX = new HashMap<>();
    // 已加载的 resourceId（用于跨来源去重：mod jar 与资源包 zip 内容相同）
    private static final Set<String> SEEN_IDS = new HashSet<>();
    private static boolean loaded = false;

    // 内置连接玻璃规则（mod jar 内 tiles 0-46，见 assets/celeritasctm/mcpatcher/ctm/connected_glass/）。
    // 无色玻璃（blocks/glass）经 findMatch 命中；染色玻璃在 CtmBakedModel 手动走此规则。
    public static CtmProperties GLASS_RULE = null;

    public static void loadAll(IResourceManager resourceManager) {
        PROPERTIES.clear();
        TILE_INDEX.clear();
        BLOCK_INDEX.clear();
        SEEN_IDS.clear();
        CeleritasCTM.logger.info("=== CTM Properties Loader: Scanning resource packs ===");

        int totalLoaded = 0;

        try {
            // 只扫描已启用的资源包（禁用包的 CTM 内容不应加载）
            Set<String> enabledPacks = getEnabledPackNames();

            // 方法1: 直接扫描resourcepacks目录（最可靠）
            File resourcepacksDir = new File(Minecraft.getMinecraft().gameDir, "resourcepacks");
            if (resourcepacksDir.isDirectory()) {
                int packPriority = 0;
                File[] files = resourcepacksDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        String name = file.getName();
                        // 包名匹配：zip 文件名或文件夹名
                        if (!enabledPacks.isEmpty() && !enabledPacks.contains(name)) {
                            continue;
                        }
                        if (file.isFile() && file.getName().endsWith(".zip")) {
                            totalLoaded += scanZipFile(file, packPriority);
                            packPriority++;
                        } else if (file.isDirectory()) {
                            totalLoaded += scanDirectory(file, packPriority);
                            packPriority++;
                        }
                    }
                }
            }

            // 方法2: 也扫描mods目录（某些mod自带CTM资源）
            File modsDir = new File(Minecraft.getMinecraft().gameDir, "mods");
            if (modsDir.isDirectory()) {
                File[] modFiles = modsDir.listFiles();
                if (modFiles != null) {
                    int packPriority = totalLoaded > 0 ? 1000 : 0;
                    for (File file : modFiles) {
                        if (file.isFile() && (file.getName().endsWith(".jar") || file.getName().endsWith(".zip"))) {
                            totalLoaded += scanZipFile(file, packPriority);
                            packPriority++;
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

    /** 获取已启用的资源包名称集合（zip 文件名或文件夹名） */
    private static Set<String> getEnabledPackNames() {
        Set<String> names = new HashSet<>();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getResourcePackRepository() != null) {
                for (net.minecraft.client.resources.ResourcePackRepository.Entry entry :
                        mc.getResourcePackRepository().getRepositoryEntries()) {
                    names.add(entry.getResourcePackName());
                }
            }
        } catch (Throwable t) {
            // 仓库未就绪时返回空集合，调用方将扫描全部（回退）
        }
        return names;
    }

    /** 构建双索引：纹理名 → 规则列表（含 matchTiles），方块注册名 → 规则列表（纯 matchBlocks） */
    private static void rebuildIndex() {
        TILE_INDEX.clear();
        BLOCK_INDEX.clear();
        // PROPERTIES 已按 packPriority 排序（高优先级在前），索引列表保持该顺序
        for (CtmProperties prop : PROPERTIES) {
            if (!prop.getMatchTilesClean().isEmpty()) {
                for (String clean : prop.getMatchTilesClean()) {
                    TILE_INDEX.computeIfAbsent(clean, k -> new ArrayList<>()).add(prop);
                }
            } else if (!prop.getMatchBlocks().isEmpty()) {
                for (String mb : prop.getMatchBlocks()) {
                    String key = mb.contains(":") ? mb : "minecraft:" + mb;
                    BLOCK_INDEX.computeIfAbsent(key, k -> new ArrayList<>()).add(prop);
                }
            }
        }
    }

    /**
     * 添加属性：按 resourceId 去重（保留首个/最高优先级来源），
     * 并跳过完全无法匹配的属性（无 matchTiles 也无 matchBlocks）。
     * method=overlay* 的连接过渡规则：本 mod 已移除 overlay 功能（CUTOUT 二次渲染），
     * 直接跳过，避免其 17 张 tile 被当作普通 ctm 规则误用。
     */
    private static boolean addProperty(CtmProperties props) {
        String method = props.getMethod();
        if (method != null && method.startsWith("overlay")) {
            return false;
        }
        String id = props.getResourceId().toString();
        if (!SEEN_IDS.add(id)) return false;
        if (props.getMatchTilesClean().isEmpty() && props.getMatchBlocks().isEmpty()) return false;
        // 有 matchTiles 的规则视为 tile-based（OptiFine 默认 connect=tile）
        props.setTileBased(!props.getMatchTilesClean().isEmpty());
        PROPERTIES.add(props);
        return true;
    }

    /**
     * 匹配规则（按优先级顺序）：
     * 1. 含 matchTiles 的规则：纹理名命中，且方块/元数据（若指定）匹配
     * 2. 纯 matchBlocks 的规则：方块注册名命中，且元数据（若指定）匹配
     *
     * 注意：OptiFine 遍历规则直到找到能应用于当前面的规则，不能只返回第一个匹配——
     * 若其 faces 不含当前面应继续尝试下一条（如 stone8_side 的 sides_ctm 只用于侧面，
     * 顶面应落到 top_bottom 规则）。
     */
    public static CtmProperties findMatch(String textureName, IBlockState state) {
        return findMatch(textureName, state, null);
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
    public static CtmProperties findMatch(String textureName, IBlockState state, EnumFacing side) {
        return findMatchInternal(textureName, state, side);
    }

    private static CtmProperties findMatchInternal(String textureName, IBlockState state, EnumFacing side) {
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
        // 2) matchBlocks 规则（无 matchTiles 时按方块匹配）
        if (state != null) {
            net.minecraft.block.Block block = state.getBlock();
            if (block != null) {
                try {
                    ResourceLocation rl = block.getRegistryName();
                    if (rl != null) {
                        List<CtmProperties> list = BLOCK_INDEX.get(rl.toString());
                        if (list != null) {
                            for (CtmProperties p : list) {
                                if (p.matchesMetadata(state) && p.appliesTo(side)) {
                                    return p;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
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

                String ctmPath = null;
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
                            count += scanPropertiesDir(ctmDir, namespace, ctmPath, packPriority);
                        }
                    }
                }
            }
        }
        return count;
    }

    private static int scanPropertiesDir(File dir, String namespace, String ctmPath, int packPriority) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += scanPropertiesDir(file, namespace, ctmPath, packPriority);
            } else if (file.getName().endsWith(".properties")) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    String relativePath = file.getAbsolutePath().replace('\\', '/');
                    int idx = relativePath.indexOf(ctmPath);
                    if (idx >= 0) {
                        String resourcePath = relativePath.substring(idx);
                        ResourceLocation resourceId = new ResourceLocation(namespace, resourcePath);
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
     * 解析单个.properties文件
     */
    public static CtmProperties parseProperties(InputStream stream, ResourceLocation resourceId,
                                                  int packPriority) {
        try {
            Properties props = new Properties();
            props.load(stream);

            String method = props.getProperty("method", "ctm").trim().toLowerCase();
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

            // matchBlocks
            String matchBlocksRaw = props.getProperty("matchBlocks", "");
            if (!matchBlocksRaw.isEmpty()) {
                for (String block : matchBlocksRaw.split("\\s+")) {
                    ctm.addMatchBlock(block.trim());
                }
            }

            // OptiFine block<ID> 文件名机制：matchTiles/matchBlocks 都为空时，
            // 若文件名是 block<数字>[后缀]（如 block171a.properties），自动匹配方块 ID。
            // Conquest 的地毯规则（block171*.properties）依赖此机制。
            if (ctm.getMatchTilesClean().isEmpty() && ctm.getMatchBlocks().isEmpty()) {
                addBlockIdMatch(ctm);
            }

            // tiles
            String tilesRaw = props.getProperty("tiles", "");
            if (!tilesRaw.isEmpty()) {
                ctm.setTiles(tilesRaw.trim());
            }

            // faces（OptiFine 支持 faces= 与 sides= 两个键，如 Conquest 的 "sides=top bottom"；
            // faces=all 或无 faces 视为无面限制——含无朝向 quad）
            String facesRaw = props.getProperty("faces", "all").trim().toLowerCase();
            String sidesRaw = props.getProperty("sides", "").trim().toLowerCase();
            if (sidesRaw.isEmpty() || "all".equals(facesRaw)) {
                parseFaces(ctm, facesRaw);
            } else {
                parseFaces(ctm, facesRaw + " " + sidesRaw);
            }

            // metadata（支持列表与范围，如 "1 5 9"、"0-5"、"0 2-4 8"；默认 -1 表示所有）
            String metadataRaw = props.getProperty("metadata", "-1").trim();
            if (!metadataRaw.isEmpty() && !"-1".equals(metadataRaw)) {
                int[] meta = parseMetadata(metadataRaw);
                if (meta != null) ctm.setMetadata(meta);
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

            // connect
            ctm.setConnect(props.getProperty("connect", "").trim());

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
     * 解析 metadata 列表：支持单个值、空格分隔列表、范围（0-5）与混合。
     * 返回 null 表示解析失败（不设置）。
     */
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
        // 提取 block 后的数字前缀
        int digitStart = 5;
        int i = digitStart;
        while (i < base.length() && Character.isDigit(base.charAt(i))) i++;
        if (i == digitStart) return; // block 后无数字
        try {
            int blockId = Integer.parseInt(base.substring(digitStart, i));
            net.minecraft.block.Block block = net.minecraft.block.Block.getBlockById(blockId);
            if (block == null) return;
            net.minecraft.util.ResourceLocation rl = block.getRegistryName();
            if (rl == null) return;
            ctm.addMatchBlock(rl.toString());
        } catch (NumberFormatException ignored) {}
    }

    /**
     * 解析 metadata 列表：支持单个值、空格分隔列表、范围（0-5）与混合。
     * 返回 null 表示解析失败（不设置）。
     */
    private static int[] parseMetadata(String raw) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String part : raw.split("\\s+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash > 0) {
                try {
                    int a = Integer.parseInt(part.substring(0, dash).trim());
                    int b = Integer.parseInt(part.substring(dash + 1).trim());
                    if (a > b) { int t = a; a = b; b = t; }
                    if (b - a < 256) {
                        for (int i = a; i <= b; i++) out.add(i);
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (out.isEmpty()) return null;
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static void parseFaces(CtmProperties ctm, String facesRaw) {
        // 注意：EnumFacing 的 getName() 是 up/down/north/south/east/west，
        // OptiFine 文档用 top/bottom，需要归一化。
        // faces=all 或无 faces：保持 faces 集为空（无面限制，含无朝向 quad）
        if (facesRaw == null || facesRaw.isEmpty() || "all".equals(facesRaw)) {
            return;
        }
        boolean sidesSeen = false;
        for (String face : facesRaw.split("\\s+")) {
            face = face.trim();
            if (face.isEmpty()) continue;
            if ("sides".equals(face)) {           // sides 可与其他面组合：如 "sides bottom"（Patrix 圆石）
                if (sidesSeen) continue;
                sidesSeen = true;
                ctm.addFace("north"); ctm.addFace("south");
                ctm.addFace("east"); ctm.addFace("west");
            } else {
                if ("top".equals(face)) face = "up";
                else if ("bottom".equals(face)) face = "down";
                ctm.addFace(face);
            }
        }
    }

    public static List<CtmProperties> getProperties() {
        return Collections.unmodifiableList(PROPERTIES);
    }

    public static void reload(IResourceManager resourceManager) {
        loaded = false;
        loadAll(resourceManager);
    }

    public static boolean isLoaded() { return loaded; }
}
