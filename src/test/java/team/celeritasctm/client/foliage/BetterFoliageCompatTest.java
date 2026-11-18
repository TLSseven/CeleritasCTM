package team.celeritasctm.client.foliage;

import java.util.ArrayList;
import java.util.List;

/**
 * BetterFoliageCompat 草地让出判定的行为测试（无 JUnit、不读源码文本）。
 *
 * <p>Run with {@code ./gradlew testBetterFoliageCompat} or compile/run directly.
 *
 * <p>覆盖「BF 草地开关」真值表：非草方块不受开关影响；草方块在开关关闭时
 * 必须让出给常规渲染（CTM 连接纹理 + 原版生物群系着色），开关开启时允许 BF 接管。
 */
public final class BetterFoliageCompatTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        nonGrassNeverDefers();
        grassDefersWhenDisabled();
        grassDoesNotDeferWhenEnabled();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failures.size());
        for (String f : failures) System.out.println("FAIL: " + f);
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void nonGrassNeverDefers() {
        check("non-grass + toggle off does not defer",
            !BetterFoliageCompat.deferGrassToVanilla(false, false));
        check("non-grass + toggle on does not defer",
            !BetterFoliageCompat.deferGrassToVanilla(false, true));
    }

    private static void grassDefersWhenDisabled() {
        check("grass + toggle off defers to vanilla/regular rendering",
            BetterFoliageCompat.deferGrassToVanilla(true, false));
    }

    private static void grassDoesNotDeferWhenEnabled() {
        check("grass + toggle on keeps BF takeover",
            !BetterFoliageCompat.deferGrassToVanilla(true, true));
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
        } else {
            failures.add(name);
        }
    }
}
