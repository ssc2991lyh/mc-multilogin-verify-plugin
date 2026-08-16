package net.wifil.mcmultilogin.tracking;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 持久化「原始玩家名 → 自动改名后的玩家名」映射，落盘为
 * {@code plugins/MCMultiLoginCompat/renames.json}。
 *
 * <p><b>与 Fabric 版的差异</b>：
 * <ul>
 *   <li>路径来源从 {@code FabricLoader.getInstance().getConfigDir()} 换成 Bukkit 的
 *       {@code plugin.getDataFolder()}（由构造参数传入），彻底摘掉 Fabric 依赖。</li>
 *   <li>日志从 SLF4J 换成 JUL（{@code java.util.logging}）—— Bukkit 插件的标准日志门面，
 *       且 JDK 自带，不引入任何额外依赖。</li>
 *   <li>{@code Files.readString/writeString}（Java 11+）与 {@code Map.copyOf}（Java 10+）
 *       换成 Java 8 可用的流式读写与 {@code Collections.unmodifiableMap}。</li>
 * </ul>
 * 全部方法加 synchronized，保证并发登录时不会写坏文件（与原版行为一致）。</p>
 */
public class PlayerNameTracker {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final java.lang.reflect.Type MAP_TYPE =
            new TypeToken<LinkedHashMap<String, String>>() {
            }.getType();

    private final File file;
    private final Logger logger;
    private final Map<String, String> renames;

    /**
     * @param dataFolder 插件数据目录（{@code plugin.getDataFolder()}）
     * @param logger     插件日志器
     */
    public PlayerNameTracker(File dataFolder, Logger logger) {
        this.logger = logger;
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.warning("[MultiLogin] 无法创建数据目录: " + dataFolder);
        }
        this.file = new File(dataFolder, "renames.json");
        this.renames = load();
    }

    /** 记录一次自动改名并立即落盘。 */
    public synchronized void track(String originalName, String newName) {
        renames.put(originalName, newName);
        save();
        logger.info("[MultiLogin] 已记录改名: " + originalName + " -> " + newName);
    }

    /** 查询某个原始名对应的改名结果；没有记录则返回 null。 */
    public synchronized String getRenamed(String originalName) {
        return renames.get(originalName);
    }

    /** 返回全部改名记录的只读快照。 */
    public synchronized Map<String, String> getAllRenames() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(renames));
    }

    /** 记录条数。 */
    public synchronized int size() {
        return renames.size();
    }

    /** 落盘文件路径，供状态命令展示。 */
    public File file() {
        return file;
    }

    // ------------------------------------------------------------------

    private Map<String, String> load() {
        if (!file.exists()) {
            return new LinkedHashMap<String, String>();
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            String json = new String(out.toByteArray(), "UTF-8");
            Map<String, String> loaded = GSON.fromJson(json, MAP_TYPE);
            return loaded != null ? loaded : new LinkedHashMap<String, String>();
        } catch (IOException e) {
            logger.warning("[MultiLogin] 读取改名记录失败: " + e.getMessage());
            return new LinkedHashMap<String, String>();
        } catch (RuntimeException e) {
            // JSON 损坏时不能让插件起不来，重置为空表并保留原文件供人工排查。
            logger.warning("[MultiLogin] 改名记录 JSON 解析失败，按空表处理: " + e.getMessage());
            return new LinkedHashMap<String, String>();
        } finally {
            closeQuietly(in);
        }
    }

    private void save() {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(GSON.toJson(renames).getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            logger.warning("[MultiLogin] 保存改名记录失败: " + e.getMessage());
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
                // 忽略：关闭失败不影响业务。
            }
        }
    }
}
