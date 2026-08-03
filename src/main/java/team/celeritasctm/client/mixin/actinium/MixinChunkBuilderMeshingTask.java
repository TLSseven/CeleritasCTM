package team.celeritasctm.client.mixin.actinium;

import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.render.terrain.compile.task.ChunkBuilderMeshingTask;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.celeritasctm.client.foliage.BetterFoliageCompat;

import java.lang.reflect.Field;

/**
 * Better Foliage（BF）与 Sodium/Celeritas 的桥接。
 *
 * <p>BF 的 transformer 注入的是 vanilla RenderChunk.rebuildChunk，被 Celeritas 完全绕过。
 * 这里重定向 Celeritas ChunkBuilderMeshingTask.execute 里的 dispatcher.renderBlock 到
 * BF Hooks（base + overlay 几何）。
 *
 * <p>芦苇/珊瑚等附加几何只在 CUTOUT 层渲染（RenderReeds/RenderCoral.render 的
 * {@code if (!isCutout(layer)) return base}）：SOLID 方块（沙/泥等）在 CUTOUT 层
 * can=false，BF 经 handler 的 {@code getAddToCutout()} 分支画附加几何。Celeritas 对
 * SOLID 方块只调一次 renderBlock，因此这里在 SOLID 层渲染后额外在 CUTOUT_MIPPED 层
 * 调一次 BF 桥——BF 会画芦苇/珊瑚，不会重复画本体（can=false）。
 */
@Mixin(value = ChunkBuilderMeshingTask.class)
public abstract class MixinChunkBuilderMeshingTask {

    @Unique
    private static final Field FOLIAGE$RENDER_LAYER_FIELD = foliage$findRenderLayerField();

    // 当前 chunk 构建上下文（execute HEAD 捕获；ChunkBuilder 线程池每线程独立）
    @Unique
    private static final ThreadLocal<VintageChunkBuildContext> FOLIAGE$BUILD_CONTEXT = new ThreadLocal<>();

    // BF 假植物写入光影 context 用的方块 state：优先 conquest:crop_corn（光影 1.12 段
    // block.80=SSS_STRONG，不摇动+SSS，与 cypress/corn 同类处理），fallback
    // minecraft:grass（block.85=GRASS，不摇动+SSS）。
    @Unique
    private static final IBlockState FOLIAGE$PLANT_STATE = foliage$resolvePlantState();

    @Unique
    private static IBlockState foliage$resolvePlantState() {
        try {
            Block corn = Block.getBlockFromName("conquest:crop_corn");
            if (corn != null) {
                return corn.getDefaultState();
            }
        } catch (Throwable ignored) {}
        return Blocks.GRASS.getDefaultState();
    }

    @Unique
    private static Field foliage$findRenderLayerField() {
        try {
            Field f = net.minecraftforge.client.ForgeHooksClient.class.getDeclaredField("renderLayer");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private static BlockRenderLayer foliage$currentLayer() {
        try {
            if (FOLIAGE$RENDER_LAYER_FIELD != null) {
                Object tl = FOLIAGE$RENDER_LAYER_FIELD.get(null);
                if (tl instanceof ThreadLocal) {
                    Object val = ((ThreadLocal<?>) tl).get();
                    if (val instanceof BlockRenderLayer) {
                        return (BlockRenderLayer) val;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Inject(method = "execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",
            at = @At("HEAD"))
    private void ctm$captureBuildContext(ChunkBuildContext context, CancellationToken token,
                                         CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (context instanceof VintageChunkBuildContext) {
            FOLIAGE$BUILD_CONTEXT.set((VintageChunkBuildContext) context);
        }
    }

    /**
     * 重定向 dispatcher.renderBlock：
     * - 先让 BF 处理（base + overlay）；BF 不可用或无 BF 几何时回退原 renderBlock。
     * - SOLID 方块渲染后，额外在 CUTOUT_MIPPED 层调一次 BF 桥（芦苇/珊瑚等附加几何）。
     */
    @Redirect(method = "execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BlockRendererDispatcher;renderBlock(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/client/renderer/BufferBuilder;)Z"))
    private boolean ctm$renderWithFoliage(BlockRendererDispatcher dispatcher, IBlockState state,
                                          BlockPos pos, IBlockAccess access, BufferBuilder buffer) {
        BlockRenderLayer layer = foliage$currentLayer();
        boolean bf = BetterFoliageCompat.isAvailable();
        boolean rendered;
        if (layer != null && bf && BetterFoliageCompat.renderWorldBlock(dispatcher, state, pos, access, buffer, layer)) {
            rendered = true;
        } else {
            rendered = dispatcher.renderBlock(state, pos, access, buffer);
        }

        // BF 附加几何（芦苇/珊瑚/海藻等 getAddToCutout handler）：SOLID 方块额外在
        // CUTOUT_MIPPED 层调 BF——该层 can=false，BF 只画附加几何，不重复画本体。
        //
        // 光影兼容：假植物写入的 quad context 默认带宿主方块 id（沙/泥→固体材质）。
        // 用 minecraft:reeds（block.54 GROUND_WAVING）会让两格高的芦苇按顶点世界坐标
        // 分段摇动（光影摇动相位=顶点位置，跨两格即两段独立相位）。因此改用不摇动的
        // 植物材质（conquest:crop_corn→block.80 SSS_STRONG，fallback grass→block.85
        // GRASS）：整株静止 + 次表面散射，与 cypress/corn 的处理一致。
        // 附加几何是 BF 画出的假植物（沙/泥本身不在 CUTOUT 层渲染），整段 buffer
        // 内容都是植物，用植物 id 不会影响宿主方块本体。
        if (bf && layer == BlockRenderLayer.SOLID && BetterFoliageCompat.mayHaveFoliage(state)) {
            VintageChunkBuildContext buildContext = FOLIAGE$BUILD_CONTEXT.get();
            if (buildContext != null) {
                try {
                    BufferBuilder cutout = buildContext.getBufferForLayer(BlockRenderLayer.CUTOUT_MIPPED);
                    buildContext.beginVanillaBlockRender(cutout, pos, FOLIAGE$PLANT_STATE);
                    try {
                        BetterFoliageCompat.renderWorldBlock(dispatcher, state, pos, access, cutout,
                            BlockRenderLayer.CUTOUT_MIPPED);
                    } finally {
                        buildContext.endVanillaRender(cutout);
                    }
                } catch (Throwable t) {
                    team.celeritasctm.CeleritasCTM.logger.warn("CTM BF cutout pass failed for {}: {}",
                        state.getBlock(), t.toString());
                }
            }
        }
        return rendered;
    }
}
