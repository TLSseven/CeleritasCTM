package team.celeritasctm.config;

import team.celeritasctm.CeleritasCTM;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * CeleritasCTM 用户配置。
 *
 * <p>五个开关全部默认开启：
 * <ul>
 *   <li>betterGrass        — 更好的草地（草方块侧面沿对角下侧同种方块延伸草皮）</li>
 *   <li>betterSnow         — 更好的雪地（雪层下的方块侧面显示雪纹理）</li>
 *   <li>connectedGlass     — 无缝玻璃（原版玻璃/染色玻璃连接去边框）</li>
 *   <li>internalFaceCulling — 连接方块内部面剔除（仅当邻居模型几何完整覆盖时删除内部面）</li>
 *   <li>betterFoliageGrass — BetterFoliage 草地（允许 BF 处理草方块，BF 的 connectedGrass）</li>
 * </ul>
 *
 * <p>持久化：GSON 写入 {@code config/celeritasctm.json}。
 * 读取为每 quad 热路径，字段直接 public 访问（无 getter 开销）。
 */
public final class ModConfig {

    public boolean betterGrass = true;
    public boolean betterSnow = true;
    public boolean connectedGlass = true;
    /**
     * 连接方块内部面剔除。volatile：仅保证 chunk 构建线程读到最新值（字段本身仍是
     * 构建期一次性判断）；Gson 缺失该键时沿用字段初值 true。
     */
    public volatile boolean internalFaceCulling = true;
    public boolean betterFoliageGrass = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File file() {
        return new File(Minecraft.getMinecraft().gameDir, "config/celeritasctm.json");
    }

    public static ModConfig load() {
        ModConfig config = new ModConfig();
        File f = file();
        if (f.isFile()) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                ModConfig parsed = GSON.fromJson(reader, ModConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } catch (Exception e) {
                team.celeritasctm.CeleritasCTM.logger.warn("Failed to load config, using defaults: {}", e.toString());
            }
        }
        return config;
    }

    public void save() {
        File f = file();
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            team.celeritasctm.CeleritasCTM.logger.warn("Failed to save config: {}", e.toString());
        }
    }
}
