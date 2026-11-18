package team.celeritasctm.client.resource;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.BlockRenderLayer;

import java.util.*;

/**
 * 从OptiFine .properties文件解析出的CTM属性
 */
public class CtmProperties implements Comparable<CtmProperties> {

    private final ResourceLocation resourceId;
    private final String method;
    private final int packPriority;

    // matchTiles / matchBlocks - 匹配的方块/纹理
    private final List<String> matchTiles = new ArrayList<>();
    private final List<String> matchTilesClean = new ArrayList<>();
    // 与 matchTilesClean 平行的“显式命名空间键”（仅当资源包作者写了 ns:path 时非 null）。
    // 显式命名空间的匹配项要求纹理命名空间一致，避免 conquest:blocks/foo 误配 minecraft:blocks/foo。
    private final List<String> matchTilesExplicitNs = new ArrayList<>();
    private final List<String> matchBlocks = new ArrayList<>();
    // matchBlocks 的规范化结果（数字 ID 解析为注册名后也放这里；可与 matchBlocks 数量不一致）
    private final List<CtmRuleSupport.BlockMatch> blockMatches = new ArrayList<>();
    // 数字 ID 条目（注册表不可用时保留，待延迟解析；不当作空气方块）
    private final List<Integer> deferredNumericIds = new ArrayList<>();
    private final List<String> connectTiles = new ArrayList<>();

    // tiles - 纹理索引（支持数字、范围、名称；null 表示 <skip>/<default>）
    private String tiles;
    private String[] tileTokens = new String[] { null };

    // faces - 哪些面需要CTM
    private final Set<String> faces = new HashSet<>();

    // metadata（-1 表示所有；支持多个值，如 "1 5 9"）
    private int[] metadata = null;

    // 随机纹理权重
    private int[] weights;

    // repeat参数
    private int width = 1;
    private int height = 1;

    // 环境过滤（OptiFine height/heights/minHeight/maxHeight + biomes）。
    // 为空/null 表示不限制；非空时若没有世界/坐标上下文则 fail-closed。
    private List<CtmRuleSupport.HeightRange> heightRanges = null;
    private Set<String> biomes = null;
    // 以 ! 前缀给出的 biome 排除项（命中即不应用）
    private Set<String> biomeExclusions = null;

    // 连接检测
    private String connect = "block"; // block | tile | material | state
    private boolean connectExplicit = false;
    // 是否 tile-based（有 matchTiles）：OptiFine 对 tile-based 规则默认 connect=tile
    private boolean tileBased = false;
    // 随机对称性：none | opposite | all（OptiFine random/repeat）
    private String symmetry = "none";

    public CtmProperties(ResourceLocation resourceId, String method, int packPriority) {
        this.resourceId = resourceId;
        this.method = method;
        this.packPriority = packPriority;
    }

    // ===== Getters =====

    public ResourceLocation getResourceId() { return resourceId; }
    public String getMethod() { return method; }
    public int getPackPriority() { return packPriority; }
    public List<String> getMatchTiles() { return matchTiles; }
    public List<String> getMatchTilesClean() { return matchTilesClean; }
    public List<String> getMatchBlocks() { return matchBlocks; }
    /** matchBlocks 的规范化条目（含数字 ID 解析结果与状态谓词） */
    public List<CtmRuleSupport.BlockMatch> getBlockMatches() { return blockMatches; }
    /** 待延迟解析的数字 block ID（注册表尚不可用时保留） */
    public List<Integer> getDeferredNumericIds() { return deferredNumericIds; }
    public boolean hasDeferredNumericIds() { return !deferredNumericIds.isEmpty(); }
    public List<String> getConnectTiles() { return connectTiles; }
    public String getTiles() { return tiles; }
    public int getTileCount() { return tileTokens == null ? 1 : tileTokens.length; }
    public String[] getTileTokens() { return tileTokens; }

    /** 高度限制（null 表示不限制） */
    public List<CtmRuleSupport.HeightRange> getHeightRanges() { return heightRanges; }
    public void setHeightRanges(List<CtmRuleSupport.HeightRange> ranges) {
        this.heightRanges = (ranges == null || ranges.isEmpty()) ? null : ranges;
    }
    /** 是否要求世界高度上下文 */
    public boolean hasHeightRestriction() { return heightRanges != null && !heightRanges.isEmpty(); }

    /** biome 限制（规范化键集合，null 表示不限制） */
    public Set<String> getBiomes() { return biomes; }
    public void setBiomes(Set<String> biomeKeys) {
        this.biomes = (biomeKeys == null || biomeKeys.isEmpty()) ? null : biomeKeys;
    }
    public void setBiomeExclusions(Set<String> exclusions) {
        this.biomeExclusions = (exclusions == null || exclusions.isEmpty()) ? null : exclusions;
    }
    public Set<String> getBiomeExclusions() { return biomeExclusions; }

    /** 只有排除项（{@code !biome}）时同样构成限制 */
    public boolean hasBiomeRestriction() {
        return (biomes != null && !biomes.isEmpty())
            || (biomeExclusions != null && !biomeExclusions.isEmpty());
    }

    /** 第 index 个 tile 的标记（可为 null 表示跳过），越界返回 null */
    public String tileAt(int index) {
        if (tileTokens == null || index < 0 || index >= tileTokens.length) return null;
        return tileTokens[index];
    }

    public Set<String> getFaces() { return faces; }
    /** 面信息（诊断用）：空集显示 "all" */
    public String getFacesInfo() {
        return faces.isEmpty() ? "all" : String.join(" ", faces);
    }
    public int[] getMetadata() { return metadata; }
    public int[] getWeights() { return weights; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getConnect() {
        if (!connectExplicit) {
            // 有显式方块约束（matchBlocks 或待解析数字 ID）时默认 connect=block；
            // 纯 matchTiles 规则默认 connect=tile。
            return hasBlockConstraint() ? "block" : (tileBased ? "tile" : "block");
        }
        return connect;
    }

    // ===== Setters (builder pattern) =====

    public void addMatchTile(String tile) {
        if (tile == null || tile.isEmpty()) return;
        matchTiles.add(tile);
        matchTilesClean.add(cleanTextureName(tile));
        matchTilesExplicitNs.add(explicitNamespaceOf(tile));
    }

    /**
     * 若纹理名显式带命名空间（{@code ns:path}）则返回该命名空间，否则 null。
     * 用于 namespace-aware 匹配；相对路径 {@code ./x.png} 视为无命名空间。
     */
    private static String explicitNamespaceOf(String tile) {
        if (tile == null) return null;
        String t = tile.trim();
        if (t.startsWith("./")) return null;
        int colon = t.indexOf(':');
        if (colon <= 0) return null;
        return t.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** 清理后的纹理名（与 matchTilesClean 元素同一规范）是否命中显式命名空间条目 */

    public void addMatchBlock(String block) {
        if (block == null || block.isEmpty()) return;
        String token = block.trim();
        if (token.isEmpty()) return;
        matchBlocks.add(token);
        CtmRuleSupport.BlockMatch parsed = CtmRuleSupport.parseMatchToken(token);
        if (parsed != null) blockMatches.add(parsed);
    }

    /**
     * 只登记原始 {@code matchBlocks} 文本，<b>不做</b>归一化。
     * 归一化条目由调用方通过 {@link #addBlockMatch} 单独加入（数字 ID 需要注册表解析，
     * 必须由加载器负责），避免同一 token 被解析两次产生重复条目。
     */
    public void addMatchBlockRecord(String block) {
        if (block == null) return;
        String token = block.trim();
        if (!token.isEmpty()) matchBlocks.add(token);
    }

    /** 直接加入已规范化的匹配条目（数字 ID 由加载器查注册表解析后使用） */
    public void addBlockMatch(CtmRuleSupport.BlockMatch match) {
        if (match == null) return;
        blockMatches.add(match);
    }

    /** 是否没有任何可用的方块匹配条目（含尚未解析的数字 ID） */
    public boolean hasBlockMatches() {
        return !blockMatches.isEmpty();
    }

    /** 规则是否可参与匹配（有 matchTiles 或有可解析的 matchBlocks，含延迟数字 ID） */
    public boolean hasAnyMatch() {
        return !matchTilesClean.isEmpty() || !blockMatches.isEmpty() || !deferredNumericIds.isEmpty();
    }

    /** 是否存在“显式方块约束”（matchBlocks 写出过内容，或仍有待解析数字 ID） */
    public boolean hasBlockConstraint() {
        return !matchBlocks.isEmpty() || !blockMatches.isEmpty() || !deferredNumericIds.isEmpty();
    }

    /** 登记延迟解析的数字 ID（注册表不可用时保留，避免被当作空气方块或整条丢弃） */
    public void addDeferredNumericId(int id) {
        // 保留 0 等边界值：保留约束比“悄悄放弃约束”安全；解析期会拒绝把未注册 ID
        // 映射成空气（见 CtmPropertiesLoader#resolveNumericKey）。
        if (id >= 0 && id <= 4095) deferredNumericIds.add(id);
    }

    public void addConnectTile(String tile) {
        if (tile == null || tile.isEmpty()) return;
        connectTiles.add(cleanTextureName(tile));
    }

    public void setTiles(String tiles) {
        this.tiles = tiles;
        this.tileTokens = parseTiles(tiles);
    }

    public void addFace(String face) { faces.add(face); }
    public void setMetadata(int[] metadata) { this.metadata = metadata; }

    public void setWeights(int[] weights) { this.weights = weights; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
    public void setConnect(String connect) {
        this.connect = (connect == null || connect.isEmpty()) ? "block" : connect;
        this.connectExplicit = connect != null && !connect.isEmpty();
    }

    /** 标记为 tile-based 规则（有 matchTiles） */
    public void setTileBased(boolean tileBased) {
        this.tileBased = tileBased;
    }

    public String getSymmetry() { return symmetry; }
    public void setSymmetry(String symmetry) {
        this.symmetry = (symmetry == null || symmetry.isEmpty()) ? "none" : symmetry;
    }

    // ===== 工具方法 =====

    /**
     * 方块匹配：没有显式方块约束时匹配任意方块；
     * 有约束时要求「规范化方块键相同」且「状态谓词全部满足」。
     *
     * <p>fail-closed：写了 matchBlocks 但没有任何可用条目（全部无效或数字 ID
     * 尚未解析）时返回 false，<b>绝不</b>因为 matchTiles 存在就放宽为任意方块。
     *
     * <p>键比较统一走 {@link CtmRuleSupport#canonicalBlockKey}，与索引构建同一函数，
     * 不再有“索引拼 {@code minecraft:} 前缀、匹配端比短名”的两条路径。
     */
    public boolean matchesBlock(net.minecraft.block.state.IBlockState state) {
        if (!hasBlockConstraint()) return true;
        if (state == null) return false;
        // 延迟数字 ID：直接比较状态方块的数字 ID，不经过“查不到就映射为空气”的路径。
        if (!deferredNumericIds.isEmpty()) {
            try {
                int stateId = net.minecraft.block.Block.getIdFromBlock(state.getBlock());
                for (Integer id : deferredNumericIds) {
                    if (id != null && id == stateId) return true;
                }
            } catch (Throwable ignored) {
                // 注册表不可用：无法判定，继续走规范化键路径
            }
        }
        if (blockMatches.isEmpty()) return false;
        String key = canonicalKeyOf(state);
        if (key == null) return false;
        Map<String, String> props = statePropertyMap(state);
        for (CtmRuleSupport.BlockMatch m : blockMatches) {
            if (m.isNumeric()) continue; // 数字 ID 在加载期已解析为键，未解析成功的条目不参与
            if (!m.matchesKey(key)) continue;
            if (m.matchesProperties(props)) return true;
            // 属性名在方块状态里根本不存在 → 大概率是规则写错了，warn 一次便于定位
            for (String propName : m.getProperties().keySet()) {
                if (!props.containsKey(propName)) {
                    CtmRuleSupport.warnUnknownProperty(key, propName);
                }
            }
        }
        return false;
    }

    /** 方块状态的规范化注册键（{@code namespace:path}，小写） */
    public static String canonicalKeyOf(net.minecraft.block.state.IBlockState state) {
        if (state == null) return null;
        try {
            net.minecraft.block.Block block = state.getBlock();
            if (block == null) return null;
            ResourceLocation rl = block.getRegistryName();
            if (rl == null) return null;
            return CtmRuleSupport.canonicalBlockKey(rl.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方块状态属性 → 字符串值（谓词比较用） */
    public static Map<String, String> statePropertyMap(net.minecraft.block.state.IBlockState state) {
        if (state == null) return Collections.emptyMap();
        Map<String, String> out = new HashMap<>();
        try {
            for (Map.Entry<net.minecraft.block.properties.IProperty<?>, Comparable<?>> e
                    : state.getProperties().entrySet()) {
                out.put(e.getKey().getName().toLowerCase(Locale.ROOT),
                    String.valueOf(e.getValue()));
            }
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
        return out;
    }

    /**
     * 元数据匹配：metadata 为 null（未限制）时匹配所有；
     * 有明确 metadata 限制但拿不到状态（state == null）时 fail-closed，
     * 绝不因为缺少上下文就放宽为“全部匹配”。
     */
    public boolean matchesMetadata(net.minecraft.block.state.IBlockState state) {
        if (metadata == null || metadata.length == 0) return true;
        if (state == null) return false;
        int meta;
        try {
            meta = state.getBlock().getMetaFromState(state);
        } catch (Throwable t) {
            return false; // 读取失败无法判定：保守不匹配
        }
        for (int m : metadata) {
            if (m == meta) return true;
        }
        return false;
    }

    /**
     * 纹理匹配：matchTiles 为空时匹配任意纹理（纯 matchBlocks 的规则）；
     * 否则要求清理后的纹理名在 matchTiles 中；若条目显式带命名空间，
     * 还要求纹理命名空间一致（namespace-aware）。
     */
    public boolean matchesTile(String textureName) {
        if (matchTilesClean.isEmpty()) return true;
        if (textureName == null) return false;
        String clean = cleanTextureName(textureName);
        String ns = namespaceOf(textureName);
        for (int i = 0; i < matchTilesClean.size(); i++) {
            if (!matchTilesClean.get(i).equals(clean)) continue;
            String explicitNs = i < matchTilesExplicitNs.size() ? matchTilesExplicitNs.get(i) : null;
            if (explicitNs == null || ns == null || explicitNs.equalsIgnoreCase(ns)) return true;
        }
        return false;
    }

    /** 纹理名的命名空间：{@code ns:path} 取 ns，否则默认 {@code minecraft} */
    public static String namespaceOf(String textureName) {
        if (textureName == null) return null;
        int colon = textureName.indexOf(':');
        if (colon > 0) return textureName.substring(0, colon).toLowerCase(Locale.ROOT);
        return "minecraft";
    }

    /**
     * 高度限制：无限制恒真；有限制时必须提供 y 坐标且在任一区间内（fail-closed）。
     */
    public boolean matchesHeight(Integer y) {
        if (!hasHeightRestriction()) return true;
        if (y == null) return false;
        for (CtmRuleSupport.HeightRange r : heightRanges) {
            if (r.contains(y)) return true;
        }
        return false;
    }

    /**
     * biome 限制：无限制恒真；有限制时必须提供规范化 biome 键并命中（fail-closed）。
     */
    public boolean matchesBiome(String canonicalBiomeKey) {
        if (canonicalBiomeKey == null) return matchesBiomeKeys(null);
        return matchesBiomeKeys(Collections.singletonList(canonicalBiomeKey));
    }

    /**
     * biome 限制（支持同一 biome 的多条候选键：注册名 + 旧版显示名）。
     *
     * <ul>
     *   <li>无限制 → 恒真；</li>
     *   <li>有限制但没有任何候选键（无世界/坐标上下文，或注册表无法给出名字）→ false，绝不放宽；</li>
     *   <li>任一候选键命中排除项（{@code !biome}）→ false；</li>
     *   <li>只有排除项时，未被排除即为真；</li>
     *   <li>有包含项时，任一候选键命中即真。</li>
     * </ul>
     */
    public boolean matchesBiomeKeys(Collection<String> biomeKeys) {
        if (!hasBiomeRestriction()) return true;
        if (biomeKeys == null || biomeKeys.isEmpty()) return false;
        if (biomeExclusions != null) {
            for (String key : biomeKeys) {
                if (key != null && biomeExclusions.contains(key)) return false;
            }
        }
        if (biomes == null || biomes.isEmpty()) return true; // 只写了排除项
        for (String key : biomeKeys) {
            if (key != null && biomes.contains(key)) return true;
        }
        return false;
    }

    /**
     * 是否应用于指定面。
     * - faces 为空 → 应用于所有面（含无朝向 quad）
     * - side == null（无朝向 quad，如 cross 模型/部分 mod quad）：
     *   仅当 faces 为空时才应用。OptiFine 只对面向 quad 应用 CTM，
     *   无朝向 quad 遇到明确限定面的规则（faces=sides 等）应跳过，
     *   否则 trapdoor 等模型的内部 quad 会被错误替换。
     */
    public boolean appliesTo(net.minecraft.util.EnumFacing side) {
        if (faces.isEmpty()) return true;
        if (side == null) return false;
        return faces.contains(side.getName());
    }

    /**
     * 清理纹理名称：去命名空间、./ 前缀、blocks/ 前缀、.png 后缀
     */
    public static String cleanTextureName(String name) {
        if (name == null) return "";
        String n = name;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        if (n.startsWith("./")) n = n.substring(2);
        if (n.endsWith(".png")) n = n.substring(0, n.length() - 4);
        if (n.startsWith("blocks/")) n = n.substring(7);
        return n;
    }

    /**
     * 解析 tiles 列表：
     * - 数字与范围（1-4 → 1,2,3,4）
     * - 名称（x、73、name、name.png、full/path/name）
     * - <skip> / <default> → null（使用默认纹理）
     * - 分隔符：空白与逗号均可（OptiFine 作者两种写法都有）
     */
    static String[] parseTiles(String tiles) {
        if (tiles == null || tiles.trim().isEmpty()) return new String[] { null };
        List<String> out = new ArrayList<>();
        String[] parts = tiles.trim().split("[\\s,]+");
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if ("<skip>".equals(p) || "<default>".equals(p)) {
                out.add(null);
                continue;
            }
            int dash = p.indexOf('-');
            if (dash > 0) {
                try {
                    int a = Integer.parseInt(p.substring(0, dash).trim());
                    int b = Integer.parseInt(p.substring(dash + 1).trim());
                    if (a > b) { int t = a; a = b; b = t; }
                    if (a >= 0 && b - a < 4096) {
                        for (int i = a; i <= b; i++) out.add(String.valueOf(i));
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    // 不是数字范围，按名称处理
                }
            }
            if (p.endsWith(".png")) p = p.substring(0, p.length() - 4);
            if (p.startsWith("./")) p = p.substring(2);
            out.add(p);
        }
        if (out.isEmpty()) out.add(null);
        return out.toArray(new String[0]);
    }

    @Override
    public int compareTo(CtmProperties o) {
        // 高优先级排前面
        int c = Integer.compare(o.packPriority, this.packPriority);
        if (c != 0) return c;
        // 同优先级按文件名字母序（OptiFine 规则：同包内按文件名顺序处理，首个匹配生效）
        return this.resourceId.getPath().compareTo(o.resourceId.getPath());
    }

    @Override
    public String toString() {
        return "CtmProperties{" +
            "method='" + method + '\'' +
            ", matchTiles=" + matchTiles +
            ", tiles='" + tiles + '\'' +
            '}';
    }
}
