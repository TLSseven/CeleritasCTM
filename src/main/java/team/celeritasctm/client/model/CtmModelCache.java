package team.celeritasctm.client.model;

import net.minecraft.client.renderer.block.model.IBakedModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CtmBakedModel} 包装缓存。
 *
 * <p>独立于 Mixin 类持有静态状态：Mixin 类的静态成员在运行时会被合并进目标类，
 * 直接在别处静态调用 `MixinXxx.clear()` 并不可靠，因此缓存放在普通类中，
 * 供 {@code MixinBlockModelShapes} 与 {@code CTMPackReloadListener} 共同使用。
 *
 * <p>缓存键为 vanilla 烘焙出的原始模型。资源重载（F3+T / 切换资源包）后
 * {@code BlockModelShapes} 会产生新的模型实例，旧键若不清理会被静态 map 永久
 * 强引用（连同其 parent 模型），导致内存随重载次数线性增长（A15）。
 */
public final class CtmModelCache {

    private static final Map<IBakedModel, CtmBakedModel> CACHE = new ConcurrentHashMap<>(1024);

    private CtmModelCache() {
    }

    /** 取已缓存的包装模型；不存在返回 null。 */
    public static CtmBakedModel get(IBakedModel parent) {
        return CACHE.get(parent);
    }

    /** 取或创建包装模型（并发安全，保证同一 parent 只对应一个 wrapper）。 */
    public static CtmBakedModel getOrCreate(IBakedModel parent) {
        CtmBakedModel cached = CACHE.get(parent);
        if (cached != null) return cached;
        CtmBakedModel created = new CtmBakedModel(parent);
        CtmBakedModel prev = CACHE.putIfAbsent(parent, created);
        return prev != null ? prev : created;
    }

    /** 当前缓存条目数（诊断用）。 */
    public static int size() {
        return CACHE.size();
    }

    /**
     * 清空缓存。资源重载时调用：重烘焙后所有旧 parent 模型都已失效。
     *
     * @return 清理前的条目数，便于记录重载前后增长
     */
    public static int clear() {
        int previous = CACHE.size();
        CACHE.clear();
        return previous;
    }
}
