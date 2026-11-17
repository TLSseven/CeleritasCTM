package team.celeritasctm.client.resource;

import java.util.*;

/**
 * CTM 规则解析的纯逻辑（不依赖 Minecraft 类，可独立编译测试）。
 *
 * <p>这里集中三件容易出错、需要单测覆盖的事：
 * <ul>
 *   <li>{@code matchBlocks} 归一化（数字 ID / 短名 / {@code ns:name} /
 *       Conquest 扩展谓词 {@code ns:name:property=value}）</li>
 *   <li>{@code faces} 与 {@code sides} 的合并语义</li>
 *   <li>{@code metadata} / {@code connect} 的合法性校验（fail-closed）</li>
 * </ul>
 *
 * <h3>资源包优先级方向的实证（Minecraft 1.12.2）</h3>
 * <pre>
 * Minecraft.refreshResources()            // 1.12.2 反编译源码
 *   list = new ArrayList(defaultResourcePacks);        // 内置包/Forge 的 mod 包先加入
 *   for (Entry e : resourcePackRepository.getRepositoryEntries()) list.add(e.getResourcePack());
 *   resourceManager.reloadResources(list);             // 按该顺序 addResourcePack（追加到末尾）
 *
 * FallbackResourceManager.getResource()
 *   for (int i = resourcePacks.size() - 1; i &gt;= 0; --i)  // 从末尾往前找
 *       if (resourceExists) return ...;                 // 第一个命中者生效
 * </pre>
 * 结论（实测源码，非文档猜测）：
 * <ol>
 *   <li>{@code getRepositoryEntries()} 中<b>索引越大优先级越高</b>（末尾最后加入，倒序扫描时最先命中）。
 *       {@code GuiScreenResourcePacks.actionPerformed()} 先按界面列表顺序收集再
 *       {@code Collections.reverse(list)} 后 {@code setRepositories(...)}，界面最上方的包即末位。</li>
 *   <li>内置默认包与 Forge 追加的 mod 资源包位于用户包之前，因此<b>优先级低于任何用户资源包</b>。
 *       旧实现的 {@code totalLoaded > 0 ? 1000 : 0} 让 mod 包压过用户包，与实际相反。</li>
 * </ol>
 * 本类把该方向固化为 {@link #userPackPriority(int)} / {@link #modPackPriority(int)}，
 * 加载器仍会用 {@code IResourceManager#getResource} 对重复 resourceId 做一次实证校验。
 */
public final class CtmRuleSupport {

    /** 四个侧面（OptiFine 用 north/south/east/west，与 EnumFacing#getName 一致） */
    public static final List<String> SIDE_FACES =
        Collections.unmodifiableList(Arrays.asList("north", "south", "east", "west"));

    /** 合法 connect 值（OptiFine 1.12.2 doc/ctm.properties: block | tile | material | state） */
    public static final Set<String> VALID_CONNECT =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("block", "tile", "material", "state")));

    private CtmRuleSupport() {}

    // ======================= 诊断输出（可注入，保持本类不依赖 Minecraft） =======================

    /** 诊断输出接口；生产环境由 {@link CtmPropertiesLoader} 安装为日志输出 */
    public interface WarnSink {
        void warn(String message);
    }

    private static volatile WarnSink warnSink = message -> { };

    public static void setWarnSink(WarnSink sink) {
        warnSink = sink == null ? message -> { } : sink;
    }

    /** 未知状态属性的 warn（同一属性只报一次由 sink 侧去重） */
    public static void warnUnknownProperty(String canonicalKey, String propertyName) {
        warnSink.warn("unknown block state property '" + propertyName + "' while matching "
            + canonicalKey + " (rule will not apply to this block)");
    }

    // ======================= matchBlocks 归一化 =======================

    /**
     * 一条 {@code matchBlocks} 条目：规范化的方块键 + 可选状态谓词。
     *
     * <p>谓词写法是 Conquest 资源包扩展（非 OptiFine 标准），例如
     * {@code matchBlocks=conquest:Leaves_cherry:age=7}。
     */
    public static final class BlockMatch {
        private final String key;                    // 规范化键：数字 ID 为 "#<id>"，否则 "namespace:path"
        private final Integer numericId;             // 纯数字 ID 时非 null
        private final Map<String, String> properties;

        BlockMatch(String key, Integer numericId, Map<String, String> properties) {
            this.key = key;
            this.numericId = numericId;
            this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        /** 规范化键（数字 ID 条目为 {@code #<id>}） */
        public String getKey() { return key; }

        /** 纯数字 ID（0-4095 的 OptiFine 写法），否则 null */
        public Integer getNumericId() { return numericId; }

        public boolean isNumeric() { return numericId != null; }

        /** 状态谓词（可能为空） */
        public Map<String, String> getProperties() { return properties; }

        /**
         * 谓词是否被给定状态属性满足。空谓词恒真。
         *
         * @param stateProperties 方块状态属性（键=属性名，值=属性值字符串）
         */
        public boolean matchesProperties(Map<String, String> stateProperties) {
            if (properties.isEmpty()) return true;
            if (stateProperties == null) return false;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                String actual = stateProperties.get(e.getKey());
                if (actual == null) return false;
                if (!actual.equalsIgnoreCase(e.getValue())) return false;
            }
            return true;
        }

        /** 与规范化方块键比较 */
        public boolean matchesKey(String canonicalKey) {
            return key != null && key.equals(canonicalKey);
        }

        /** 数字 ID 解析成功后生成同谓词的键化条目（供加载器使用） */
        public BlockMatch resolvedTo(String registryName) {
            return new BlockMatch(canonicalBlockKey(registryName), null, properties);
        }

        @Override
        public String toString() {
            if (isNumeric()) return "#" + numericId;
            StringBuilder sb = new StringBuilder(key);
            for (Map.Entry<String, String> e : properties.entrySet()) {
                sb.append(':').append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /**
     * 解析单条 {@code matchBlocks} 条目。
     *
     * <p>支持的写法：
     * <ul>
     *   <li>{@code 171} —— 纯数字，返回 {@link BlockMatch#getNumericId()}，由调用方查注册表</li>
     *   <li>{@code carpet} / {@code minecraft:carpet} —— 短名或全名</li>
     *   <li>{@code conquest:Leaves_cherry:age=7} —— 名字后跟 {@code prop=value} 谓词</li>
     *   <li>{@code stone:variant=granite} —— 无命名空间 + 谓词</li>
     * </ul>
     * 空条目返回 null。谓词段以 {@code :} 分隔，段内可用 {@code ,} 分隔多个谓词。
     */
    public static BlockMatch parseMatchToken(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.isEmpty()) return null;

        // 纯数字 = OptiFine block ID 写法
        if (isAllDigits(t)) {
            try {
                int id = Integer.parseInt(t);
                if (id < 0 || id > 4095) return null; // 文档范围 0-4095
                return new BlockMatch("#" + id, id, Collections.<String, String>emptyMap());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String[] parts = t.split(":");
        String namespace;
        String path;
        int predStart;
        if (parts.length >= 2 && parts[1].contains("=")) {
            // 形如 stone:variant=granite —— 第一段就是方块名
            namespace = "minecraft";
            path = parts[0];
            predStart = 1;
        } else if (parts.length >= 2) {
            namespace = parts[0];
            path = parts[1];
            predStart = 2;
        } else {
            namespace = "minecraft";
            path = parts[0];
            predStart = 1;
        }

        Map<String, String> predicates = new LinkedHashMap<>();
        for (int i = predStart; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) continue;
            for (String piece : seg.split(",")) {
                String p = piece.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                if (eq <= 0 || eq == p.length() - 1) {
                    // 无法解析的谓词段：整条视为无效，交由调用方 warn
                    return null;
                }
                predicates.put(p.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                    p.substring(eq + 1).trim());
            }
        }

        return new BlockMatch(canonicalBlockKey(namespace + ":" + path), null, predicates);
    }

    /**
     * 方块标识规范化：小写、缺省命名空间补 {@code minecraft:}、去掉首尾空白。
     * 索引构建与匹配查询必须共用本函数，避免“写索引用一套、查询用另一套”。
     */
    public static String canonicalBlockKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int colon = s.indexOf(':');
        String ns;
        String path;
        if (colon < 0) {
            ns = "minecraft";
            path = s;
        } else {
            ns = s.substring(0, colon);
            path = s.substring(colon + 1);
            if (ns.isEmpty()) ns = "minecraft";
            // 命名空间后的第一个 ':' 之后若还有谓词段，取到 ':' 前的路径
            int next = path.indexOf(':');
            if (next >= 0) path = path.substring(0, next);
        }
        if (path.isEmpty()) return null;
        return ns.toLowerCase(Locale.ROOT) + ":" + path.toLowerCase(Locale.ROOT);
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return !s.isEmpty();
    }

    // ======================= faces / sides =======================
    /**
     * 解析面限制，返回规范化面名集合；<b>空集表示“所有面”</b>（与
     * {@link CtmProperties#appliesTo} 的约定一致）。
     *
     * <p>语义：
     * <ul>
     *   <li>只写 {@code sides=top bottom}：{@code sides} <b>取代</b>默认的 {@code faces=all}
     *       （旧实现直接丢掉 sides，退化成所有面）</li>
     *   <li>同时写 {@code faces} 与 {@code sides}：并集</li>
     *   <li>{@code sides} 不是 OptiFine 标准属性，仅为 Conquest 等扩展资源包兼容</li>
     * </ul>
     *
     * @param facesRaw {@code faces} 原始值，属性缺失时传 null
     * @param sidesRaw {@code sides} 原始值，属性缺失时传 null
     */
    public static Set<String> resolveFaces(String facesRaw, String sidesRaw) {
        String faces = normalizeRaw(facesRaw);
        String sides = normalizeRaw(sidesRaw);
        Set<String> out = new LinkedHashSet<>();

        boolean facesRestricts = faces != null && !"all".equals(faces);
        boolean sidesRestricts = sides != null && !"all".equals(sides);

        if (facesRestricts) addFaceTokens(out, faces);
        if (sidesRestricts) addFaceTokens(out, sides);

        return out;
    }

    private static String normalizeRaw(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static void addFaceTokens(Set<String> out, String raw) {
        boolean sidesSeen = false;
        for (String token : raw.split("\\s+")) {
            String face = token.trim();
            if (face.isEmpty()) continue;
            switch (face) {
                case "sides":
                    if (sidesSeen) continue;
                    sidesSeen = true;
                    out.addAll(SIDE_FACES);
                    break;
                case "top":
                    out.add("up");
                    break;
                case "bottom":
                    out.add("down");
                    break;
                case "all":
                    // all 只在单项出现时表示“无限制”，由调用方以空集表达
                    break;
                default:
                    out.add(face);
                    break;
            }
        }
    }

    // ======================= metadata =======================

    /**
     * 解析 metadata 列表：单值 / 空格分隔 / 范围（{@code 0-5}）/ 混合。
     * OptiFine 1.12.2 文档规定取值 0-31；越界或语法错误返回 null（fail-closed）。
     */
    public static int[] parseMetadata(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "-1".equals(s)) return null; // 调用方按“不限制”处理
        List<Integer> out = new ArrayList<>();
        for (String part : s.split("\\s+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash > 0) {
                Integer a = parseIntOrNull(part.substring(0, dash));
                Integer b = parseIntOrNull(part.substring(dash + 1));
                if (a == null || b == null) return null;
                if (a > b) { int t = a; a = b; b = t; }
                if (a < 0 || b > 31) return null;
                for (int i = a; i <= b; i++) out.add(i);
            } else {
                Integer v = parseIntOrNull(part);
                if (v == null || v < 0 || v > 31) return null;
                out.add(v);
            }
        }
        if (out.isEmpty()) return null;
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ======================= connect =======================

    /** {@code connect} 是否为空（未指定）或合法值。非法值应 fail-closed。 */
    public static boolean isValidConnect(String raw) {
        if (raw == null) return true;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() || VALID_CONNECT.contains(s);
    }

    // ======================= 资源包优先级 =======================

    /**
     * 用户资源包优先级：{@code getRepositoryEntries()} 的索引，索引越大优先级越高。
     * 依据见类注释（{@code FallbackResourceManager} 倒序扫描）。
     */
    public static int userPackPriority(int enabledIndex) {
        return enabledIndex;
    }

    /**
     * mod 自带资源包优先级：恒低于任何用户资源包。
     * 依据：{@code Minecraft.refreshResources()} 把 {@code defaultResourcePacks}
     * （含 Forge 追加的 mod 包）排在 {@code getRepositoryEntries()} 之前。
     */
    public static int modPackPriority(int modIndex) {
        return -(modIndex + 1);
    }

    // ======================= 去重与来源判定 =======================

    /**
     * 同一 resourceId 出现多次时，是否用新来源替换已有规则。
     * 只有新来源优先级<b>严格更高</b>才替换（同优先级保留先到的，保证结果确定）。
     */
    public static boolean shouldReplace(int existingPriority, int newPriority) {
        return newPriority > existingPriority;
    }

    /**
     * 候选包来源：名字 + 优先级。纯数据，供选择逻辑与测试使用。
     */
    public static final class PackSourceInfo {
        public final String name;
        public final int priority;

        public PackSourceInfo(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String toString() {
            return name + "@" + priority;
        }
    }

    /**
     * 由「启用栈顺序」计算来源优先级列表。
     *
     * <p>入参按 {@code getRepositoryEntries()} 原样顺序给出启用包名；
     * 返回值索引越大优先级越高（见类注释的 1.12.2 源码证据）。
     * 空列表表示<b>零启用</b>：调用方据此不扫描 {@code resourcepacks/}。
     */
    public static List<PackSourceInfo> priorityForEnabledStack(List<String> enabledPackNamesInOrder) {
        List<PackSourceInfo> out = new ArrayList<>();
        if (enabledPackNamesInOrder == null) return out;
        for (int i = 0; i < enabledPackNamesInOrder.size(); i++) {
            String name = enabledPackNamesInOrder.get(i);
            if (name == null) continue;
            out.add(new PackSourceInfo(name, userPackPriority(i)));
        }
        return out;
    }

    /**
     * 判断某文件是否应被扫描。零启用（enabled 为空且仓库就绪）时一律不扫。
     *
     * @param repositoryReady 资源包仓库是否可用（null 视为未就绪）
     * @param enabledNames    启用包名集合（可为空）
     * @param fileName        磁盘上的包文件名或文件夹名
     */
    public static boolean shouldScanPackFile(boolean repositoryReady, Set<String> enabledNames, String fileName) {
        if (!repositoryReady) return true;              // 仓库未就绪：退化扫描
        if (enabledNames == null || enabledNames.isEmpty()) return false; // 零启用：不扫
        return enabledNames.contains(fileName);
    }

    /**
     * 资源管理器报告的「实际生效来源」是否表明当前扫描源被覆盖。
     *
     * <p>只在能<b>明确判定</b>时返回 true：生效包名已知且属于本次扫描的候选集合、
     * 且不等于当前源。否则（生效名未知、不属于候选集合、或查询失败）返回 false，
     * 避免因包名映射差异而误丢规则。
     */
    public static boolean isOverriddenByAnotherKnownPack(String effectivePackName, String currentPackName,
                                                         Set<String> candidatePackNames) {
        if (effectivePackName == null || currentPackName == null) return false;
        if (effectivePackName.equals(currentPackName)) return false;
        return candidatePackNames != null && candidatePackNames.contains(effectivePackName);
    }
}
