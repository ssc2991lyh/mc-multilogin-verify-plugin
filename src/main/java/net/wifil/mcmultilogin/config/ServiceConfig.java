package net.wifil.mcmultilogin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MC-MultiLogin-service 的服务端配置（config.json）。
 *
 * <p>这是把原 Node 版 {@code MC-MultiLogin-service} 合并进插件后的「服务端配置」载体。
 * 原服务用 {@code config.json}（含 {@code port}/{@code manage_port} 监听端口），
 * 合并进插件后验证逻辑在插件进程内直接跑、不再对外起 HTTP 服务，
 * 因此 <b>刻意去掉所有 port 字段</b>（用户要求），只保留验证语义相关的配置。</p>
 *
 * <p>首次运行 / 配置文件缺失时，按内置模板自动写出 {@code config.json}，路径在
 * {@code plugins/MCMultiLoginCompat/config.json}。解析采用 Gson 的
 * {@link JsonObject} 动态读取，与原 Node 版 {@code globleConfig.get(key, def)} 行为一致。</p>
 */
public final class ServiceConfig {

    /** 内置默认模板（不含任何 port 字段）。 */
    private static final String DEFAULT_CONFIG_JSON =
            "{\n"
            + "  \"log_remaining_number\": 5,\n"
            + "  \"login_cooldown\": 5000,\n"
            + "  \"skinDomains\": [\n"
            + "    \"littleskin.cn\"\n"
            + "  ],\n"
            + "  \"apis\": [\n"
            + "    {\n"
            + "      \"id\": \"littleskin\",\n"
            + "      \"name\": \"LittleSkin\",\n"
            + "      \"root\": \"https://littleskin.cn/api/yggdrasil\"\n"
            + "    },\n"
            + "    {\n"
            + "      \"id\": \"original\",\n"
            + "      \"name\": \"Official\"\n"
            + "    }\n"
            + "  ],\n"
            + "  \"default\": \"original\",\n"
            + "  \"method\": [],\n"
            + "  \"_method_comment\": \"默认 method[] 为空：首次安装安全 fail-open，插件不会接管登录。如需自包含验证，在此填入 method 对象，例如 { url: '/login/my', name: 'myserver', secret: 'your_secret_key_here', handles: ['littleskin','original'] }\",\n"
            + "  \"push\": {\n"
            + "    \"handles\": {}\n"
            + "  },\n"
            + "  \"errorMessages\": {\n"
            + "    \"DUPLICATE_NAME\": \"该玩家名已被来自 \\\"{from}\\\" 的账号占用，不允许其他皮肤站的同名玩家登录\",\n"
            + "    \"DUPLICATE_UUID\": \"该账号的 UUID 与已有玩家 \\\"{name}\\\"（来自 \\\"{from}\\\"）冲突\",\n"
            + "    \"BANNED_FOREVER\": \"您已被永久封禁\",\n"
            + "    \"BANNED\": \"您已被封禁\",\n"
            + "    \"NOT_FOUND\": \"玩家未在任何已配置的皮肤站找到\",\n"
            + "    \"UNSUPPORTED_SKIN_SITE\": \"该玩家注册的皮肤站不在此服务器支持列表中\",\n"
            + "    \"FETCH_ERROR\": \"连接验证服务器失败\",\n"
            + "    \"VERIFY_FAILED\": \"验证失败，你应当通过 {name} 进入\",\n"
            + "    \"LOGIN_TOO_FAST\": \"你的登录过快，请稍后再试\",\n"
            + "    \"BAN_UNTIL\": \"解封时间: \"\n"
            + "  },\n"
            + "  \"verify_data_file\": \"verify.json\",\n"
            + "  \"cache_dir\": \"cache\"\n"
            + "}\n";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JsonObject root;
    private final File file;

    private ServiceConfig(File file, JsonObject root) {
        this.file = file;
        this.root = root;
    }

    /**
     * 载入 config.json；缺失则用默认模板写出后再解析。
     *
     * @param dataFolder 插件数据目录（plugins/MCMultiLoginCompat）
     * @param logger     用于提示日志
     * @return 配置实例（永远非 null）
     */
    public static ServiceConfig load(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "config.json");
        if (!file.exists()) {
            try {
                dataFolder.mkdirs();
                Files.write(file.toPath(),
                        DEFAULT_CONFIG_JSON.getBytes(StandardCharsets.UTF_8));
                if (logger != null) {
                    logger.info("[ServiceConfig] 未找到 config.json，已按默认模板生成："
                            + file.getAbsolutePath());
                }
            } catch (IOException e) {
                if (logger != null) {
                    logger.warning("[ServiceConfig] 生成默认 config.json 失败："
                            + e.getMessage());
                }
            }
        }

        JsonObject parsed = parse(file);
        if (parsed == null) {
            // 解析失败（例如损坏）：回退到内置模板，避免插件直接崩。
            parsed = JsonParser.parseString(DEFAULT_CONFIG_JSON).getAsJsonObject();
            if (logger != null) {
                logger.warning("[ServiceConfig] config.json 解析失败，使用内置默认配置。");
            }
        }
        return new ServiceConfig(file, parsed);
    }

    private static JsonObject parse(File file) {
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) {
                return null;
            }
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 通用读取

    public String getString(String key, String def) {
        JsonElement e = root.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : def;
    }

    public int getInt(String key, int def) {
        JsonElement e = root.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        return def;
    }

    public JsonObject getObject(String key) {
        JsonElement e = root.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
    }

    public JsonArray getArray(String key) {
        JsonElement e = root.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : new JsonArray();
    }

    public JsonElement get(String key) {
        return root.get(key);
    }

    /** 写入磁盘（供未来热重载/管理指令使用）。 */
    public void save() throws IOException {
        Files.write(file.toPath(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 验证语义访问

    /** 登录冷却时间（毫秒），对应原版 login_cooldown。 */
    public long loginCooldownMs() {
        return getInt("login_cooldown", 5000);
    }

    /** 皮肤域名白名单（对应 skinDomains，仅用于 root 响应里的 skinDomains 字段）。 */
    public JsonArray skinDomains() {
        return getArray("skinDomains");
    }

    /** 配置的 API 数组（id/name/root）。 */
    public JsonArray apis() {
        return getArray("apis");
    }

    /** 默认皮肤站 id（对应 default）。 */
    public String defaultApi() {
        return getString("default", "original");
    }

    /**
     * 登录方式数组（method）。每个元素含 url/name/secret/handles。
     * 合并进插件后，每个 method 对应一个独立的玩家缓存目录。
     */
    public JsonArray methods() {
        return getArray("method");
    }

    /** push.handles：玩家名 -> 强制使用的皮肤站 id。 */
    public Map<String, String> pushHandles() {
        JsonObject push = getObject("push");
        JsonObject handles = push.has("handles") && push.get("handles").isJsonObject()
                ? push.getAsJsonObject("handles") : new JsonObject();
        Map<String, String> map = new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<String, JsonElement> en : handles.entrySet()) {
            map.put(en.getKey(), en.getValue().getAsString());
        }
        return map;
    }

    /** 自定义错误文案（cause -> 模板），支持 {from}/{name} 占位符。 */
    public JsonObject errorMessages() {
        return getObject("errorMessages");
    }

    /** 缓存根目录名（对应 cache_dir），相对插件数据目录。 */
    public String cacheDir() {
        return getString("cache_dir", "cache");
    }

    /** 共享验证状态文件相对/绝对路径（默认 verify.json，落在插件数据目录）。 */
    public String verifyDataFile() {
        return getString("verify_data_file", "verify.json");
    }

    /** 是否已配置至少一个 method（没有则验证服务无入口，应当 fail-open）。 */
    public boolean hasAnyMethod() {
        return methods().size() > 0;
    }

    @Override
    public String toString() {
        return "ServiceConfig{methods=" + methods().size()
                + ", apis=" + apis().size() + ", default='" + defaultApi() + "'}";
    }
}
