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
        // 属性名 → 允许值列表。单个值与多值（prop=a,b）统一为列表，匹配时任一相等即可。
        private final Map<String, List<String>> properties;

        BlockMatch(String key, Integer numericId, Map<String, List<String>> properties) {
            this.key = key;
            this.numericId = numericId;
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (properties != null) {
                for (Map.Entry<String, List<String>> e : properties.entrySet()) {
                    copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
                }
            }
            this.properties = Collections.unmodifiableMap(copy);
        }

        /** 规范化键（数字 ID 条目为 {@code #<id>}） */
        public String getKey() { return key; }

        /** 纯数字 ID（0-4095 的 OptiFine 写法），否则 null */
        public Integer getNumericId() { return numericId; }

        public boolean isNumeric() { return numericId != null; }

        /**
         * 状态谓词（可能为空）。多值谓词以逗号连接后返回（例如 {@code age=7,8}），
         * 仅用于展示/兼容；判定请使用 {@link #matchesProperties}。
         */
        public Map<String, String> getProperties() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : properties.entrySet()) {
                out.put(e.getKey(), String.join(",", e.getValue()));
            }
            return out;
        }

        /** 每个属性允许的取值列表（多值谓词；空谓词时为空 Map） */
        public Map<String, List<String>> getPropertyValues() { return properties; }

        /**
         * 是否带状态属性谓词。热路径用它在取状态属性表之前先短路：
         * 没有谓词的匹配条目完全不需要构建属性 HashMap。
         */
        public boolean hasProperties() { return !properties.isEmpty(); }

        /**
         * 谓词是否被给定状态属性满足。空谓词恒真。
         *
         * <p>多值谓词（{@code prop=a,b}）语义为 OR：实际值匹配任一允许值即可。
         *
         * @param stateProperties 方块状态属性（键=属性名，值=属性值字符串）
         */
        public boolean matchesProperties(Map<String, String> stateProperties) {
            if (properties.isEmpty()) return true;
            if (stateProperties == null) return false;
            for (Map.Entry<String, List<String>> e : properties.entrySet()) {
                String actual = stateProperties.get(e.getKey());
                if (actual == null) return false;
                boolean any = false;
                for (String allowed : e.getValue()) {
                    if (actual.equalsIgnoreCase(allowed)) { any = true; break; }
                }
                if (!any) return false;
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
            for (Map.Entry<String, List<String>> e : properties.entrySet()) {
                sb.append(':').append(e.getKey()).append('=').append(String.join(",", e.getValue()));
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
        // 尾部/连续冒号、尾部/连续逗号一定是写坏了；不能靠 split 丢弃空段后当作合法
        if (t.endsWith(":") || t.contains("::") || t.endsWith(",") || t.contains(",,")) return null;

        // 纯数字 = OptiFine block ID 写法
        if (isAllDigits(t)) {
            try {
                int id = Integer.parseInt(t);
                if (id < 0 || id > 4095) return null; // 文档范围 0-4095
                return new BlockMatch("#" + id, id, Collections.<String, List<String>>emptyMap());
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
        if (!isValidIdentifier(namespace) || !isValidIdentifier(path)) return null;

        Map<String, List<String>> predicates = new LinkedHashMap<>();
        for (int i = predStart; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) return null;
            for (String piece : seg.split(",")) {
                String p = piece.trim();
                if (p.isEmpty()) return null; // "age=7," / "age=,7" 是写坏了，不能静默忽略
                int eq = p.indexOf('=');
                if (eq > 0 && eq < p.length() - 1) {
                    // 新谓词：prop=value
                    String name = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String value = p.substring(eq + 1).trim();
                    if (!isValidIdentifier(name) || !isValidPredicateValue(value)) return null;
                    predicates.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                } else if (eq < 0) {
                    // 逗号后的裸值：追加到上一个谓词的允许值列表（age=7,8）
                    if (!isValidPredicateValue(p)) return null;
                    Map.Entry<String, List<String>> last = null;
                    for (Map.Entry<String, List<String>> e : predicates.entrySet()) last = e;
                    if (last == null) return null; // 没有可归属的谓词名 → 整条无效
                    last.getValue().add(p);
                } else {
                    // 缺名或缺值 / 多个等号：整条视为无效，交由调用方 warn
                    return null;
                }
            }
        }

        String key = canonicalBlockKey(namespace + ":" + path);
        return key == null ? null : new BlockMatch(key, null, predicates);
    }

    /** 方块/属性名允许字符；空串或含冒号、等号、逗号、空白的标识符一律无效。 */
    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/')) return false;
        }
        return true;
    }

    /** 谓词取值：非空，且不能含会改变语法的字符（= , : 空白）。 */
    private static boolean isValidPredicateValue(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == ',' || c == ':' || Character.isWhitespace(c)) return false;
        }
        return true;
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
        for (String token : raw.split("[\\s,]+")) {
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
        for (String part : s.split("[\\s,]+")) {
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

    // ======================= 高度 / biome 环境过滤 =======================

    /** 高度区间（含端点），用于 {@code height/heights/minHeight/maxHeight} 过滤。 */
    public static final class HeightRange {
        public final int min;
        public final int max;

        public HeightRange(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public boolean contains(int y) {
            return y >= min && y <= max;
        }

        @Override
        public String toString() {
            return min + "-" + max;
        }
    }

    /**
     * 解析 OptiFine 高度过滤：{@code height=0-63}、{@code heights=0-63 100-127}、
     * {@code minHeight=0}、{@code maxHeight=63}。
     *
     * <p>返回 {@code null} 表示“无高度限制”（属性缺失/空白）。语法错误也返回 null，
     * 但加载器对语法错误采取 fail-closed（整条规则跳过），见
     * {@link CtmPropertiesLoader}。多个区间取并集。
     *
     * @param raw {@code height}/{@code heights}/{@code minHeight-maxHeight} 合并后的原始值
     */
    public static List<HeightRange> parseHeightRanges(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        List<HeightRange> out = new ArrayList<>();
        for (String part : s.split("[\\s,]+")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash > 0) {
                Integer a = parseIntOrNull(part.substring(0, dash));
                Integer b = parseIntOrNull(part.substring(dash + 1));
                if (a == null || b == null) return null;
                if (a > b) { int t = a; a = b; b = t; }
                out.add(new HeightRange(a, b));
            } else {
                Integer v = parseIntOrNull(part);
                if (v == null) return null;
                out.add(new HeightRange(v, v));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 由独立的 minHeight / maxHeight 属性合并出高度区间（任一缺失按该侧无限制）。 */
    public static List<HeightRange> parseMinMaxHeight(String minRaw, String maxRaw) {
        Integer min = minRaw == null || minRaw.trim().isEmpty() ? null : parseIntOrNull(minRaw);
        Integer max = maxRaw == null || maxRaw.trim().isEmpty() ? null : parseIntOrNull(maxRaw);
        if (minRaw != null && !minRaw.trim().isEmpty() && min == null) return null; // 语法错误
        if (maxRaw != null && !maxRaw.trim().isEmpty() && max == null) return null;
        if (min == null && max == null) return null;
        int lo = min == null ? Integer.MIN_VALUE : min;
        int hi = max == null ? Integer.MAX_VALUE : max;
        if (lo > hi) return null;
        return Collections.singletonList(new HeightRange(lo, hi));
    }

    /**
     * biome 过滤器：包含项（普通 token）与排除项（{@code !} 前缀）。
     * 两者都规范化到同一套键（小写、缺省命名空间、去 {@code .png}）。
     */
    public static final class BiomeFilter {
        public final Set<String> included;
        public final Set<String> excluded;

        BiomeFilter(Set<String> included, Set<String> excluded) {
            this.included = Collections.unmodifiableSet(included);
            this.excluded = Collections.unmodifiableSet(excluded);
        }

        public boolean isEmpty() {
            return included.isEmpty() && excluded.isEmpty();
        }
    }

    /**
     * 解析 biome 名称列表（空格或逗号分隔），规范化为小写、去 {@code .png}，
     * 缺省命名空间补 {@code minecraft:}。返回 null 表示“无 biome 限制”；
     * 空 token 忽略。
     */
    public static Set<String> parseBiomes(String raw) {
        BiomeFilter filter = parseBiomeFilter(raw);
        return filter == null ? null : new LinkedHashSet<>(filter.included);
    }

    /**
     * 解析 biome 过滤器。语义与 OptiFine 一致：<b>前导 {@code !} 作用于整个列表</b>——
     * {@code !desert savanna} 表示同时排除 desert 与 savanna，而不是“包含 savanna、
     * 排除 desert”的混合写法（不发明混合语法）。没有前导 {@code !} 时全部为包含项。
     *
     * @return null 表示无有效条目（属性缺失/空白/仅有一个裸 {@code !}）
     */
    public static BiomeFilter parseBiomeFilter(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        boolean negateAll = s.startsWith("!");
        if (negateAll) s = s.substring(1).trim();
        if (s.isEmpty()) return null;
        Set<String> included = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        for (String part : s.split("[\\s,]+")) {
            String b = part.trim();
            if (b.isEmpty()) continue;
            // token 内的 ! 既不是合法 biome 名，也不是前缀语法：按写坏处理
            if (b.indexOf('!') >= 0) return null;
            String key = canonicalBlockKey(b.toLowerCase(Locale.ROOT));
            if (key == null) return null;
            (negateAll ? excluded : included).add(key);
        }
        if (included.isEmpty() && excluded.isEmpty()) return null;
        return new BiomeFilter(included, excluded);
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
