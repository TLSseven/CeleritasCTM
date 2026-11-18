package team.celeritasctm.client.util;

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
import team.celeritasctm.client.model.CtmBakedModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ConnectionHandler} 邻居面纹理查询（connect=tile 证据源）的可执行测试。
 *
 * <p>直接驱动生产方法 {@link ConnectionHandler#tileOfFaceWith}（包内可见的模型解析
 * 注入点），验证：
 * <ul>
 *   <li>顺序：actual → 用 actual 取模型 → extended → {@code getQuads(extended, side, seed)}；</li>
 *   <li>种子是 {@link MathHelper#getPositionRandom}（不是 0）；</li>
 *   <li>指定面内按目标纹理精确查找（首个 quad 纹理不同也要继续找）；</li>
 *   <li>general quads 只接受朝向与查询面一致的 quad；</li>
 *   <li>actual/extended 查询失败或模型抛异常 → null（保守不连接）；</li>
 *   <li>生产代码必须自行恢复 {@link CtmBakedModel#CURRENT_CONTEXT}、抑制标志、
 *       原始 quad 守卫 — 由回调主动篡改后检查；</li>
 *   <li>connect=tile 端到端：{@code connectsToPrimary} 使用该纹理证据。</li>
 * </ul>
 *
 * <p>运行：{@code java team.celeritasctm.client.util.ConnectionHandlerTileLookupTest}。
 */
public final class ConnectionHandlerTileLookupTest {

    private static int passed = 0;
    private static int failed = 0;

    private ConnectionHandlerTileLookupTest() {
    }

    public static void main(String[] args) {
        actualThenExtendedOrderAndSeed();
        sideSearchSkipsNonMatchingFirstQuad();
        generalRequiresMatchingFace();
        failuresReturnNull();
        productionRestoresContextAndGuards();
        connectedToPrimaryUsesTileEvidence();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            throw new AssertionError("failed=" + failed);
        }
    }

    // ------------------------------------------------------------------ cases

    /** 精确顺序：actual → 模型解析(actual) → extended → getQuads(extended, side, seed)。 */
    private static void actualThenExtendedOrderAndSeed() {
        BlockPos pos = new BlockPos(11, 64, -7);
        FakeBlock block = newFakeBlock();
        IBlockState raw = buildState(block, false);
        IBlockState actual = buildState(block, false);
        IBlockState extended = buildState(block, false);
        block.actual = actual;
        block.extended = extended;

        final List<String> order = new ArrayList<>();
        final List<IBlockState> lookupInput = new ArrayList<>();
        block.onActual = p -> order.add("actual");
        block.onExtended = p -> order.add("extended");

        FakeModel model = new FakeModel();
        model.side(EnumFacing.NORTH, quad("minecraft:blocks/planks_oak"));
        model.onQuads = () -> order.add("quads");

        String name = ConnectionHandler.tileOfFaceWith(raw, world(raw), pos, EnumFacing.NORTH,
            "minecraft:blocks/planks_oak", a -> {
                lookupInput.add(a);
                order.add("lookup");
                return model;
            });

        // 期望前四项：actual → lookup → extended → quads（之后 general 查询可能再记 quads）
        check("exact order actual -> lookup -> extended -> quads",
            order.size() >= 4
                && "actual".equals(order.get(0))
                && "lookup".equals(order.get(1))
                && "extended".equals(order.get(2))
                && "quads".equals(order.get(3)));
        check("model lookup received actual state", lookupInput.get(0) == actual);
        check("model lookup did not receive raw or extended state",
            lookupInput.get(0) != raw && lookupInput.get(0) != extended);

        check("side query state is extended", model.sideCalls.size() == 1
            && model.sideCalls.get(0).state == extended);
        check("side query uses position random seed",
            model.sideCalls.get(0).rand == MathHelper.getPositionRandom(pos));
        check("side query seed is not fixed 0", model.sideCalls.get(0).rand != 0L);
        check("returns matching sprite name", "minecraft:blocks/planks_oak".equals(name));
    }

    /** 指定面混纹理：第一个 quad 纹理不同，第二个匹配 → 必须找到第二个。 */
    private static void sideSearchSkipsNonMatchingFirstQuad() {
        BlockPos pos = new BlockPos(1, 2, 3);
        FakeBlock block = newFakeBlock();
        IBlockState raw = buildState(block, false);

        FakeModel model = new FakeModel();
        model.side(EnumFacing.UP, quad("minecraft:blocks/stone"));
        model.side(EnumFacing.UP, quad("minecraft:blocks/glass"));

        String name = ConnectionHandler.tileOfFaceWith(raw, world(raw), pos, EnumFacing.UP,
            "minecraft:blocks/glass", a -> model);
        check("side list searched by exact sprite (second quad matched)",
            "minecraft:blocks/glass".equals(name));

        String missing = ConnectionHandler.tileOfFaceWith(raw, world(raw), pos, EnumFacing.UP,
            "minecraft:blocks/dirt", a -> model);
        check("no matching sprite in side list -> null (no arbitrary first-quad fallback)",
            missing == null);
    }

    /** general quads 仅在朝向等于查询面时作证（无朝向 quad 不参与）。 */
    private static void generalRequiresMatchingFace() {
        BlockPos pos = new BlockPos(4, 5, 6);
        FakeBlock block = newFakeBlock();
        IBlockState raw = buildState(block, false);

        FakeModel oriented = new FakeModel();
        oriented.general(new BakedQuad(new int[28], 0, EnumFacing.WEST,
            new TestSprite("minecraft:blocks/glass")));
        check("general quad with matching face used",
            "minecraft:blocks/glass".equals(ConnectionHandler.tileOfFaceWith(
                raw, world(raw), pos, EnumFacing.WEST, "minecraft:blocks/glass", a -> oriented)));

        FakeModel unoriented = new FakeModel();
        unoriented.general(new BakedQuad(new int[28], 0, null,
            new TestSprite("minecraft:blocks/glass")));
        check("general quad without face not used",
            ConnectionHandler.tileOfFaceWith(raw, world(raw), pos, EnumFacing.WEST,
                "minecraft:blocks/glass", a -> unoriented) == null);
    }

    /** 取态失败 / 模型异常 → null。 */
    private static void failuresReturnNull() {
        BlockPos pos = new BlockPos(0, 0, 0);

        FakeBlock failing = newFakeBlock();
        failing.failActual = true;
        IBlockState failingState = buildState(failing, false);
        check("actual state exception -> null",
            ConnectionHandler.tileOfFaceWith(failingState, world(failingState), pos,
                EnumFacing.NORTH, "minecraft:blocks/glass", a -> new FakeModel()) == null);

        FakeBlock extendedFailing = newFakeBlock();
        extendedFailing.failExtended = true;
        IBlockState extState = buildState(extendedFailing, false);
        check("extended state exception -> null",
            ConnectionHandler.tileOfFaceWith(extState, world(extState), pos,
                EnumFacing.NORTH, "minecraft:blocks/glass", a -> new FakeModel()) == null);

        FakeBlock ok = newFakeBlock();
        IBlockState okState = buildState(ok, false);
        FakeModel throwing = new FakeModel();
        throwing.throwOnQuads = true;
        check("model exception -> null",
            ConnectionHandler.tileOfFaceWith(okState, world(okState), pos,
                EnumFacing.NORTH, "minecraft:blocks/glass", a -> throwing) == null);

        check("missing model -> null",
            ConnectionHandler.tileOfFaceWith(okState, world(okState), pos,
                EnumFacing.NORTH, "minecraft:blocks/glass", a -> null) == null);
    }

    /**
     * 生产代码必须恢复上下文：让模型回调主动改写 CURRENT_CONTEXT / 抑制标志，
     * 并进入原始 quad 守卫；返回后全部应回到调用前的值。
     */
    private static void productionRestoresContextAndGuards() {
        BlockPos pos = new BlockPos(9, 9, 9);
        FakeBlock block = newFakeBlock();
        IBlockState raw = buildState(block, false);

        CtmBakedModel.RenderContext outer = new CtmBakedModel.RenderContext(null, null);
        outer.packedPos = 77L;
        CtmBakedModel.CURRENT_CONTEXT.set(outer);
        boolean outerSuppress = CtmBakedModel.EXTENDED_STATE_SUPPRESS.get();

        FakeModel mutating = new FakeModel();
        mutating.side(EnumFacing.NORTH, quad("minecraft:blocks/glass"));
        mutating.onQuads = () -> {
            // 模拟 MixinBlock 在邻居 getExtendedState 中改写复用上下文
            CtmBakedModel.RenderContext clobbered = new CtmBakedModel.RenderContext(null, null);
            clobbered.packedPos = 999L;
            CtmBakedModel.CURRENT_CONTEXT.set(clobbered);
            CtmBakedModel.EXTENDED_STATE_SUPPRESS.set(!outerSuppress);
            check("raw quad guard active during nested query",
                ConnectionHandler.isTileLookupActive());
        };

        String name = ConnectionHandler.tileOfFaceWith(raw, world(raw), pos, EnumFacing.NORTH,
            "minecraft:blocks/glass", a -> mutating);

        check("query returned", "minecraft:blocks/glass".equals(name));
        check("CURRENT_CONTEXT restored by production",
            CtmBakedModel.CURRENT_CONTEXT.get() == outer);
        check("EXTENDED_STATE_SUPPRESS restored by production",
            CtmBakedModel.EXTENDED_STATE_SUPPRESS.get() == outerSuppress);
        check("raw quad guard released after query",
            !ConnectionHandler.isTileLookupActive());
        CtmBakedModel.CURRENT_CONTEXT.remove();
    }

    /**
     * connect 主语义（不含 connectTiles 放行）：
     * state/block 直接可判；tile 在无图集模型时保守为 false（不是「同 state 即连接」）。
     */
    private static void connectedToPrimaryUsesTileEvidence() {
        BlockPos selfPos = new BlockPos(0, 0, 0);
        BlockPos otherPos = new BlockPos(0, 0, 1);

        FakeBlock block = newFakeBlock();
        IBlockState sameState = buildState(block, false);
        IBlockState otherState = buildState(block, false);

        team.celeritasctm.client.resource.CtmProperties stateProps =
            new team.celeritasctm.client.resource.CtmProperties(
                new net.minecraft.util.ResourceLocation("test", "mcpatcher/ctm/state.properties"), "ctm", 0);
        stateProps.setConnect("state");
        check("connect=state primary true for identical state",
            ConnectionHandler.connectsToPrimary(worldWith(otherPos, sameState), selfPos, sameState,
                stateProps, new TestSprite("minecraft:blocks/glass"), EnumFacing.NORTH, otherPos));
        check("connect=state primary false for different state instance",
            !ConnectionHandler.connectsToPrimary(worldWith(otherPos, otherState), selfPos, sameState,
                stateProps, new TestSprite("minecraft:blocks/glass"), EnumFacing.NORTH, otherPos));

        team.celeritasctm.client.resource.CtmProperties blockProps =
            new team.celeritasctm.client.resource.CtmProperties(
                new net.minecraft.util.ResourceLocation("test", "mcpatcher/ctm/block.properties"), "ctm", 0);
        blockProps.setConnect("block");
        check("connect=block primary true for same block",
            ConnectionHandler.connectsToPrimary(worldWith(otherPos, otherState), selfPos, sameState,
                blockProps, new TestSprite("minecraft:blocks/glass"), EnumFacing.NORTH, otherPos));

        team.celeritasctm.client.resource.CtmProperties tileProps =
            new team.celeritasctm.client.resource.CtmProperties(
                new net.minecraft.util.ResourceLocation("test", "mcpatcher/ctm/glass.properties"), "ctm", 0);
        tileProps.setConnect("tile");
        // 无图集/模型上下文：纹理证据不可得 → 保守 false（绝不以同 state 代替纹理）
        check("connect=tile primary conservative without model context",
            !ConnectionHandler.connectsToPrimary(worldWith(otherPos, otherState), selfPos, sameState,
                tileProps, new TestSprite("minecraft:blocks/glass"), EnumFacing.NORTH, otherPos));
    }

    // ------------------------------------------------------------------ fixtures

    private static final class Call {
        final IBlockState state;
        final long rand;

        Call(IBlockState state, long rand) {
            this.state = state;
            this.rand = rand;
        }
    }

    private static final class FakeModel implements IBakedModel {
        final Map<EnumFacing, List<BakedQuad>> sides = new EnumMap<>(EnumFacing.class);
        final List<BakedQuad> general = new ArrayList<>();
        final List<Call> sideCalls = new ArrayList<>();
        boolean throwOnQuads = false;
        Runnable onQuads;

        void side(EnumFacing side, BakedQuad quad) {
            sides.computeIfAbsent(side, k -> new ArrayList<>()).add(quad);
        }

        void general(BakedQuad quad) {
            general.add(quad);
        }

        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            if (onQuads != null) onQuads.run();
            if (throwOnQuads) throw new IllegalStateException("simulated model failure");
            if (side == null) {
                return general;
            }
            sideCalls.add(new Call(state, rand));
            List<BakedQuad> list = sides.get(side);
            return list == null ? Collections.<BakedQuad>emptyList() : list;
        }

        @Override public boolean isAmbientOcclusion() { return true; }
        @Override public boolean isGui3d() { return true; }
        @Override public boolean isBuiltInRenderer() { return false; }
        @Override public TextureAtlasSprite getParticleTexture() {
            return new TestSprite("minecraft:blocks/stone");
        }
        @Override public ItemOverrideList getOverrides() { return ItemOverrideList.NONE; }
    }

    /** 只做状态/偏移分派，不跑 Block 构造器（避免 Bootstrap 依赖）。 */
    static class FakeBlock extends Block {
        /** 仅用于通过 Java 编译（Unsafe.allocateInstance 不会执行该构造器）。 */
        FakeBlock() {
            super(Material.ROCK);
        }

        IBlockState actual;
        IBlockState extended;
        boolean failActual = false;
        boolean failExtended = false;
        java.util.function.Consumer<BlockPos> onActual;
        java.util.function.Consumer<BlockPos> onExtended;

        @Override
        public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
            if (onActual != null) onActual.accept(pos);
            if (failActual) throw new IllegalStateException("actual failure");
            return actual == null ? state : actual;
        }

        @Override
        public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos) {
            if (onExtended != null) onExtended.accept(pos);
            if (failExtended) throw new IllegalStateException("extended failure");
            return extended == null ? state : extended;
        }

        @Override
        public Material getMaterial(IBlockState state) {
            return Material.ROCK;
        }

        @Override
        public Vec3d getOffset(IBlockState state, IBlockAccess world, BlockPos pos) {
            return Vec3d.ZERO;
        }
    }

    /**
     * 不触发 Block 构造器地创建 FakeBlock。
     * 通过 Class.forName + 反射调用 Unsafe，避免在 {@code --release 8} 下引用
     * sun.misc.Unsafe 这一 JDK 内部类型（编译期不可用）。
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

    private static IBlockState buildState(Block block, boolean unused) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getBlock": return block;
                case "getMaterial": return Material.ROCK;
                case "getActualState": return block.getActualState((IBlockState) proxy,
                    (IBlockAccess) args[0], (BlockPos) args[1]);
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
            ConnectionHandlerTileLookupTest.class.getClassLoader(),
            new Class<?>[] { IBlockState.class }, h);
    }

    private static IBlockAccess world(IBlockState state) {
        return (IBlockAccess) Proxy.newProxyInstance(
            ConnectionHandlerTileLookupTest.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> "getBlockState".equals(method.getName()) ? state : null);
    }

    private static IBlockAccess worldWith(BlockPos target, IBlockState state) {
        return (IBlockAccess) Proxy.newProxyInstance(
            ConnectionHandlerTileLookupTest.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> {
                if ("getBlockState".equals(method.getName())) {
                    return target.equals(args[0]) ? state : state;
                }
                return null;
            });
    }

    private static final class TestSprite extends TextureAtlasSprite {
        TestSprite(String name) {
            super(name);
        }
    }

    private static BakedQuad quad(String icon) {
        return new BakedQuad(new int[28], 0, EnumFacing.NORTH, new TestSprite(icon));
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
