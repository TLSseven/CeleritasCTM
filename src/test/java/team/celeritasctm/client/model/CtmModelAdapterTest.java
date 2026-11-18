package team.celeritasctm.client.model;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import team.celeritasctm.client.model.QuadCoverage.Rect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 生产适配层的可执行测试（无 JUnit，直接 {@code java} 运行）。
 *
 * <p>覆盖点（全部走生产代码路径，不复制判定逻辑）：
 * <ul>
 *   <li>六个面方向：{@link CtmBakedModel#collectNeighborRects} 收集邻居实际 quad，
 *       交给 {@link InternalFaceCulling#shouldCull(Rect, List, boolean, float)} 判定；</li>
 *   <li>side quads 与 general quads 都参与覆盖；</li>
 *   <li>同一面混纹理时，connect=tile 的 tileFilter 只接受同纹理 quad；</li>
 *   <li>动态模型收到 {@link MathHelper#getPositionRandom} 种子（不是 rand=0）；</li>
 *   <li>查询异常 / 取态失败 → 无证据（保留面）；</li>
 *   <li>嵌套查询后 {@link CtmBakedModel#CURRENT_CONTEXT} 与抑制标志恢复；</li>
 *   <li>{@link CtmBakedModel#sameFiniteOffset} 的偏移一致性要求；</li>
 *   <li>小于几何 eps 的真实细缝（1e-5）在 U/V 两方向都不被合并。</li>
 * </ul>
 *
 * <p>运行：{@code ./gradlew testCtmModelAdapter}（或直接 java 运行本类）。
 */
public final class CtmModelAdapterTest {

    private static final int STRIDE = 7;
    private static final float EPS = QuadCoverage.GEOMETRY_EPSILON;

    private static int passed = 0;
    private static int failed = 0;

    private CtmModelAdapterTest() {
    }

    public static void main(String[] args) {
        sixDirectionsCull();
        sideAndGeneralQuadsContribute();
        mixedTextureRequiresMatchingTileEvidence();
        seededDynamicModelUsesPositionRandom();
        queryFailureAndStateFailureKeepFace();
        nestedContextRestoration();
        offsetConsistencyGuard();
        subEpsilonSlitsKept();
        productionFrameConfig();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            throw new AssertionError("failed=" + failed);
        }
    }

    // ------------------------------------------------------------------ cases

    /** 六个方向：邻居朝向本方块的面完整覆盖 → 剔除。 */
    private static void sixDirectionsCull() {
        EnumFacing[] faces = {
            EnumFacing.UP, EnumFacing.DOWN, EnumFacing.EAST,
            EnumFacing.WEST, EnumFacing.SOUTH, EnumFacing.NORTH
        };
        for (EnumFacing face : faces) {
            int axis = axisOf(face);
            boolean positive = face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE;
            float plane = positive ? 1.0f : 0.0f;
            float localPlane = plane - InternalFaceCulling.planeShift(positive);
            int neighborSign = positive ? -1 : 1;

            int[] targetData = rectQuad(axis, plane, 0f, 1f, 0f, 1f, 0);
            Rect target = QuadCoverage.rect(targetData, STRIDE, 0, axis, plane, EPS);
            check(face + " target rect extracted",
                target != null && target.isNonDegenerate(EPS));

            int[] neighborData = rectQuad(axis, localPlane, 0f, 1f, 0f, 1f, neighborSign);
            FakeModel model = new FakeModel();
            model.side(face.getOpposite(), quad(neighborData, face.getOpposite(), sprite("minecraft:blocks/glass")));

            List<Rect> rects = CtmBakedModel.collectNeighborRects(
                model, stateOf(block()), new BlockPos(3, 4, 5), face.getOpposite(),
                localPlane, axis, neighborSign, null, EPS);

            check(face + " neighbor quad collected", rects.size() == 1);
            check(face + " culled when fully covered",
                InternalFaceCulling.shouldCull(target, rects, true, EPS));
        }
    }

    /** side quads 与 general quads 都可以独立提供覆盖证据。 */
    private static void sideAndGeneralQuadsContribute() {
        int axis = 1;
        float plane = 1.0f;
        float localPlane = 0.0f;
        Rect target = QuadCoverage.rect(rectQuad(axis, plane, 0f, 1f, 0f, 1f, 0), STRIDE, 0,
            axis, plane, EPS);
        BlockPos npos = new BlockPos(1, 2, 3);
        IBlockState state = stateOf(block());

        FakeModel sideOnly = new FakeModel();
        sideOnly.side(EnumFacing.DOWN, quad(rectQuad(axis, localPlane, 0f, 1f, 0f, 1f, -1),
            EnumFacing.DOWN, sprite("minecraft:blocks/glass")));
        List<Rect> fromSide = CtmBakedModel.collectNeighborRects(sideOnly, state, npos,
            EnumFacing.DOWN, localPlane, axis, -1, null, EPS);
        check("side-only quad covers", InternalFaceCulling.shouldCull(target, fromSide, true, EPS));

        FakeModel generalOnly = new FakeModel();
        generalOnly.general(quad(rectQuad(axis, localPlane, 0f, 1f, 0f, 1f, -1),
            EnumFacing.DOWN, sprite("minecraft:blocks/glass")));
        List<Rect> fromGeneral = CtmBakedModel.collectNeighborRects(generalOnly, state, npos,
            EnumFacing.DOWN, localPlane, axis, -1, null, EPS);
        check("general-only quad covers", InternalFaceCulling.shouldCull(target, fromGeneral, true, EPS));

        // 无朝向 quad（getFace()==null）不得作为指定面的覆盖证据
        FakeModel unoriented = new FakeModel();
        unoriented.side(EnumFacing.DOWN, quad(rectQuad(axis, localPlane, 0f, 1f, 0f, 1f, -1),
            null, sprite("minecraft:blocks/glass")));
        List<Rect> fromUnoriented = CtmBakedModel.collectNeighborRects(unoriented, state, npos,
            EnumFacing.DOWN, localPlane, axis, -1, null, EPS);
        check("unoriented quad in side list ignored",
            !InternalFaceCulling.shouldCull(target, fromUnoriented, true, EPS));
    }

    /**
     * 同一面混纹理：tileFilter 只接受与当前 quad 同纹理的 quad。
     * 邻居侧面有一条错误纹理的完整平面 + 一条正确纹理但只覆盖一半的平面
     * → 用 tileFilter 时不得被错误纹理补齐（保留面）；不设 filter 时会误判覆盖。
     */
    private static void mixedTextureRequiresMatchingTileEvidence() {
        int axis = 2;
        float plane = 1.0f;
        float localPlane = 0.0f;
        Rect target = QuadCoverage.rect(rectQuad(axis, plane, 0f, 1f, 0f, 1f, 0), STRIDE, 0,
            axis, plane, EPS);
        BlockPos npos = new BlockPos(7, 8, 9);
        IBlockState state = stateOf(block());

        FakeModel model = new FakeModel();
        model.side(EnumFacing.NORTH, quad(rectQuad(axis, localPlane, 0f, 1f, 0f, 1f, -1),
            EnumFacing.NORTH, sprite("minecraft:blocks/stone")));
        model.side(EnumFacing.NORTH, quad(rectQuad(axis, localPlane, 0f, 1f, 0f, 0.5f, -1),
            EnumFacing.NORTH, sprite("minecraft:blocks/glass")));

        List<Rect> filtered = CtmBakedModel.collectNeighborRects(model, state, npos,
            EnumFacing.NORTH, localPlane, axis, -1, "minecraft:blocks/glass", EPS);
        check("mixed texture: only matching tile counts (half coverage kept)",
            !InternalFaceCulling.shouldCull(target, filtered, true, EPS));

        List<Rect> unfiltered = CtmBakedModel.collectNeighborRects(model, state, npos,
            EnumFacing.NORTH, localPlane, axis, -1, null, EPS);
        check("mixed texture without filter is fully covered (shows why filter is required)",
            InternalFaceCulling.shouldCull(target, unfiltered, true, EPS));
    }

    /** 动态模型必须收到 MathHelper.getPositionRandom(pos)，side 与 general 查询同种子。 */
    private static void seededDynamicModelUsesPositionRandom() {
        int axis = 1;
        float localPlane = 0.0f;
        BlockPos npos = new BlockPos(-5, 70, 12);
        long expected = MathHelper.getPositionRandom(npos);

        FakeModel model = new FakeModel();
        model.side(EnumFacing.DOWN, quad(rectQuad(axis, localPlane, 0f, 1f, 0f, 1f, -1),
            EnumFacing.DOWN, sprite("minecraft:blocks/glass")));
        CtmBakedModel.collectNeighborRects(model, stateOf(block()), npos,
            EnumFacing.DOWN, localPlane, axis, -1, null, EPS);

        check("dynamic model got position seed for side query",
            model.sideCalls.size() == 1 && model.sideCalls.get(0).rand == expected);
        check("dynamic model got position seed for general query",
            model.generalCalls.size() == 1 && model.generalCalls.get(0).rand == expected);
        check("seed is not the old fixed rand=0", expected != 0L);
    }

    /** 查询异常 / 取态失败一律无证据。 */
    private static void queryFailureAndStateFailureKeepFace() {
        int axis = 1;
        Rect target = QuadCoverage.rect(rectQuad(axis, 1f, 0f, 1f, 0f, 1f, 0), STRIDE, 0,
            axis, 1f, EPS);
        BlockPos npos = new BlockPos(0, 0, 0);
        IBlockState state = stateOf(block());

        FakeModel throwing = new FakeModel();
        throwing.throwOnQuads = true;
        List<Rect> rects;
        try {
            rects = CtmBakedModel.collectNeighborRects(throwing, state, npos,
                EnumFacing.DOWN, 0f, axis, -1, null, EPS);
        } catch (Throwable t) {
            rects = Collections.emptyList();
        }
        check("throwing model yields no evidence",
            rects.isEmpty() && !InternalFaceCulling.shouldCull(target, rects, true, EPS));

        FakeBlock failing = newFakeBlock();
        failing.failActual = true;
        failing.failExtended = true;
        IBlockState failingState = buildState(failing);
        check("actual state failure returns null (no evidence)",
            CtmBakedModel.safeActualState(world(failingState), npos, failingState) == null);
        check("extended state failure returns null (no evidence)",
            CtmBakedModel.safeExtendedState(failingState, world(failingState), npos) == null);
        check("null fallback returns null",
            CtmBakedModel.safeActualState(world(failingState), npos, null) == null);
        check("null world returns null",
            CtmBakedModel.safeActualState(null, npos, failingState) == null);
    }

    /**
     * 状态查询可嵌套且不受外层 ThreadLocal 影响。
     *
     * <p>生产代码对上下文/抑制标志的「自动恢复」由
     * {@code ConnectionHandlerTileLookupTest#productionRestoresContextAndGuards}
     * 直接驱动生产方法验证；这里只验证取态在嵌套调用下仍返回正确结果，
     * 不重复手工重置 ThreadLocal 充作生产证据。
     */
    private static void nestedContextRestoration() {
        CtmBakedModel.RenderContext outer = new CtmBakedModel.RenderContext(null, null);
        outer.packedPos = 42L;
        CtmBakedModel.CURRENT_CONTEXT.set(outer);

        IBlockState nestedState = stateOf(block());
        IBlockState outerResult = CtmBakedModel.safeActualState(world(nestedState),
            new BlockPos(0, 0, 0), nestedState);
        IBlockState innerResult = CtmBakedModel.safeActualState(world(nestedState),
            new BlockPos(1, 0, 0), nestedState);

        check("nested state query returned a state", outerResult != null && innerResult != null);
        check("nested query does not replace outer render context",
            CtmBakedModel.CURRENT_CONTEXT.get() == outer);
        CtmBakedModel.CURRENT_CONTEXT.remove();
    }

    /** 模型偏移：一致（含同为 0）才允许比较；非零不等/异常/NaN → 保留面。 */
    private static void offsetConsistencyGuard() {
        Block a = offsetBlock(Vec3d.ZERO);
        Block b = offsetBlock(Vec3d.ZERO);
        Block c = offsetBlock(new Vec3d(0.0, 0.25, 0.0));
        Block nan = offsetBlock(new Vec3d(Double.NaN, 0.0, 0.0));
        IBlockAccess world = world(null);
        BlockPos p0 = new BlockPos(0, 0, 0);
        BlockPos p1 = new BlockPos(1, 0, 0);

        check("equal zero offsets comparable",
            CtmBakedModel.sameFiniteOffset(stateOf(a), p0, stateOf(b), p1, world));
        check("nonzero unequal offsets not comparable",
            !CtmBakedModel.sameFiniteOffset(stateOf(b), p0, stateOf(c), p1, world));
        check("identical nonzero offsets comparable",
            CtmBakedModel.sameFiniteOffset(stateOf(c), p0, stateOf(c), p1, world));
        check("NaN offset not comparable",
            !CtmBakedModel.sameFiniteOffset(stateOf(nan), p0, stateOf(nan), p1, world));
        check("null world not comparable",
            !CtmBakedModel.sameFiniteOffset(stateOf(a), p0, stateOf(b), p1, null));
    }

    /** 小于 eps 的真实细缝（1e-5）在 U 和 V 方向都不得被覆盖。 */
    private static void subEpsilonSlitsKept() {
        Rect target = new Rect(0f, 1f, 0f, 1f);
        float slit = 1e-5f;

        List<Rect> uGap = new ArrayList<>();
        uGap.add(new Rect(0f, 0.5f, 0f, 1f));
        uGap.add(new Rect(0.5f + slit, 1f, 0f, 1f));
        check("1e-5 U gap not covered",
            !InternalFaceCulling.shouldCull(target, uGap, true, EPS));

        List<Rect> vGap = new ArrayList<>();
        vGap.add(new Rect(0f, 1f, 0f, 0.5f));
        vGap.add(new Rect(0f, 1f, 0.5f + slit, 1f));
        check("1e-5 V gap not covered",
            !InternalFaceCulling.shouldCull(target, vGap, true, EPS));

        // 精确相接 → 覆盖（确认上面的失败来自真实间隙，而非判定过严）
        List<Rect> exact = new ArrayList<>();
        exact.add(new Rect(0f, 0.5f, 0f, 1f));
        exact.add(new Rect(0.5f, 1f, 0f, 1f));
        check("exact seam covered",
            InternalFaceCulling.shouldCull(target, exact, true, EPS));
    }

    /** 生产判定重载的基本契约：未连接/空证据/退化目标一律保留。 */
    private static void productionFrameConfig() {
        Rect target = new Rect(0f, 1f, 0f, 1f);
        List<Rect> cover = Collections.singletonList(new Rect(0f, 1f, 0f, 1f));
        check("connected + cover culls",
            InternalFaceCulling.shouldCull(target, cover, true, EPS));
        check("not connected keeps",
            !InternalFaceCulling.shouldCull(target, cover, false, EPS));
        check("empty evidence keeps",
            !InternalFaceCulling.shouldCull(target, new ArrayList<Rect>(), true, EPS));
        check("degenerate target keeps",
            !InternalFaceCulling.shouldCull(new Rect(0.5f, 0.5f, 0f, 1f), cover, true, EPS));
    }

    // ------------------------------------------------------------------ fixtures

    /** 记录调用（含 rand）的假模型；可按面或 general 提供 quad。 */
    private static final class FakeModel implements IBakedModel {
        final Map<EnumFacing, List<BakedQuad>> sides = new EnumMap<>(EnumFacing.class);
        final List<BakedQuad> general = new ArrayList<>();
        final List<Call> sideCalls = new ArrayList<>();
        final List<Call> generalCalls = new ArrayList<>();
        boolean throwOnQuads = false;

        void side(EnumFacing side, BakedQuad quad) {
            sides.computeIfAbsent(side, k -> new ArrayList<>()).add(quad);
        }

        void general(BakedQuad quad) {
            general.add(quad);
        }

        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            if (throwOnQuads) throw new IllegalStateException("simulated model failure");
            if (side == null) {
                generalCalls.add(new Call(state, null, rand));
                return general;
            }
            sideCalls.add(new Call(state, side, rand));
            List<BakedQuad> list = sides.get(side);
            return list == null ? Collections.<BakedQuad>emptyList() : list;
        }

        @Override public boolean isAmbientOcclusion() { return true; }
        @Override public boolean isGui3d() { return true; }
        @Override public boolean isBuiltInRenderer() { return false; }
        @Override public TextureAtlasSprite getParticleTexture() { return sprite("minecraft:blocks/stone"); }
        @Override public ItemOverrideList getOverrides() { return ItemOverrideList.NONE; }
    }

    private static final class Call {
        final IBlockState state;
        final EnumFacing side;
        final long rand;

        Call(IBlockState state, EnumFacing side, long rand) {
            this.state = state;
            this.side = side;
            this.rand = rand;
        }
    }

    /**
     * 可配置偏移、可抛异常、带 actual/extended 的 Block 子类。
     *
     * <p>用 {@code Unsafe.allocateInstance} 创建：{@code Block} 构造器会访问
     * {@code SoundType}/{@code SoundEvents} 静态初始化，脱离游戏进程会抛
     * 「Accessed Sounds before Bootstrap」。这里只需要虚方法分派，不需要构造器副作用。
     */
    static class FakeBlock extends Block {
        private Vec3d offset = Vec3d.ZERO;
        private boolean failActual = false;
        private boolean failExtended = false;
        private IBlockState extendedOverride;

        FakeBlock() {
            super(Material.ROCK);
        }

        @Override
        public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
            if (failActual) throw new IllegalStateException("actual failure");
            return state;
        }

        @Override
        public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos) {
            if (failExtended) throw new IllegalStateException("extended failure");
            return extendedOverride == null ? state : extendedOverride;
        }

        @Override
        public Material getMaterial(IBlockState state) {
            return Material.ROCK;
        }

        @Override
        public Vec3d getOffset(IBlockState state, IBlockAccess world, BlockPos pos) {
            return offset;
        }
    }

    /**
     * 不触发 Block 构造器副作用地创建 FakeBlock（Block 构造器会访问 SoundType/
     * SoundEvents 静态初始化，脱离游戏进程不可用）。
     *
     * <p>通过 {@code Class.forName + 反射} 调用 Unsafe，避免在 {@code --release 8}
     * 下引用 JDK 内部类型 {@code sun.misc.Unsafe}（编译期不可用）。
     */
    private static FakeBlock newFakeBlock() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            return (FakeBlock) allocate.invoke(unsafe, FakeBlock.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate FakeBlock", e);
        }
    }

    private static Block block() {
        return newFakeBlock();
    }

    private static Block offsetBlock(Vec3d offset) {
        FakeBlock b = newFakeBlock();
        b.offset = offset;
        return b;
    }

    private static IBlockState stateOf(Block block) {
        return buildState(block);
    }

    private static IBlockState buildState(Block block) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBlock": return block;
                case "getMaterial": return Material.ROCK;
                case "getActualState": {
                    Block b = block;
                    return b.getActualState((IBlockState) proxy, (IBlockAccess) args[0], (BlockPos) args[1]);
                }
                case "isOpaqueCube": return false;
                case "toString": return "teststate";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "getProperties":
                case "getPropertiesMap":
                    return com.google.common.collect.ImmutableMap.of();
                default: return null;
            }
        };
        return (IBlockState) Proxy.newProxyInstance(
            CtmModelAdapterTest.class.getClassLoader(), new Class<?>[] { IBlockState.class }, h);
    }

    private static IBlockAccess world(IBlockState state) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBlockState": return state;
                case "isAirBlock": return false;
                default: return null;
            }
        };
        return (IBlockAccess) Proxy.newProxyInstance(
            CtmModelAdapterTest.class.getClassLoader(), new Class<?>[] { IBlockAccess.class }, h);
    }

    private static final class Sprite extends TextureAtlasSprite {
        Sprite(String name) {
            super(name);
        }
    }

    private static TextureAtlasSprite sprite(String name) {
        return new Sprite(name);
    }

    private static BakedQuad quad(int[] data, EnumFacing face, TextureAtlasSprite sprite) {
        return new BakedQuad(data, 0, face, sprite);
    }

    private static int axisOf(EnumFacing face) {
        switch (face.getAxis()) {
            case X: return 0;
            case Y: return 1;
            default: return 2;
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    /**
     * 生成轴对齐矩形 quad；{@code sign} 非 0 时按需反转顶点序，使 Newell 法线符号等于
     * {@code sign}（0 表示不关心）。位置在每顶点前 3 个 int（float xyz）。
     */
    private static int[] rectQuad(int axis, float plane, float u0, float u1, float v0, float v1, int sign) {
        int[] data = quadData(axis, plane,
            new float[] { u0, v0 }, new float[] { u1, v0 },
            new float[] { u1, v1 }, new float[] { u0, v1 });
        if (sign == 0) return data;
        if (QuadCoverage.axisNormalSign(data, STRIDE, 0, axis) == sign) return data;
        int[] reversed = new int[data.length];
        int[] order = { 0, 3, 2, 1 };
        for (int i = 0; i < 4; i++) {
            System.arraycopy(data, order[i] * STRIDE, reversed, i * STRIDE, STRIDE);
        }
        return reversed;
    }

    private static int[] quadData(int axis, float plane, float[]... uv) {
        int[] data = new int[STRIDE * 4];
        int a = axis == 0 ? 1 : 0;
        int b = axis == 2 ? 1 : 2;
        for (int i = 0; i < 4; i++) {
            float[] p = new float[3];
            p[axis] = plane;
            p[a] = uv[i][0];
            p[b] = uv[i][1];
            int base = i * STRIDE;
            data[base] = Float.floatToIntBits(p[0]);
            data[base + 1] = Float.floatToIntBits(p[1]);
            data[base + 2] = Float.floatToIntBits(p[2]);
        }
        return data;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
