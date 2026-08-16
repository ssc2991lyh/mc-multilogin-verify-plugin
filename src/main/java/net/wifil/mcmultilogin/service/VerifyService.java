package net.wifil.mcmultilogin.service;

import net.wifil.mcmultilogin.api.HasJoinedClient;
import net.wifil.mcmultilogin.api.LoginApiClient;
import net.wifil.mcmultilogin.config.ServiceConfig;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 合并进插件的内嵌验证服务（忠实移植原 Node 版 {@code index.js} 的
 * {@code urlHandle_joinServer} + 缓存/封禁/皮肤站对接/错误文案逻辑）。
 *
 * <p>原 MC-MultiLogin-service 是一个独立 HTTP 服务；合并后不再对外监听端口，
 * 而是直接在插件进程内被会话拦截器调用，返回与 {@link LoginApiClient.ApiResult}
 * 同形状的验证结果（200/403/204），由 {@code LoginSessionHandler} 复用既有判定分支。</p>
 *
 * <p>Java 8 兼容：网络用 {@link HttpURLConnection}，时间用 {@code System.currentTimeMillis()}，
 * 不引入任何 9+ API。</p>
 */
public final class VerifyService implements HasJoinedClient {

    private final ServiceConfig cfg;
    private final Logger logger;
    private final int timeoutMillis;
    private final File dataFolder;

    /** 每个 method（登录方式）一个缓存目录。 */
    private final Map<String, PlayerCache> caches = new LinkedHashMap<String, PlayerCache>();

    public VerifyService(ServiceConfig cfg, File dataFolder, Logger logger, int timeoutSeconds) {
        this.cfg = cfg;
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        buildCaches();
    }

    private void buildCaches() {
        File root = new File(dataFolder, cfg.cacheDir());
        for (JsonElement e : cfg.methods()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject m = e.getAsJsonObject();
            String name = m.has("name") ? m.get("name").getAsString() : "default";
            File dir = new File(root, name);
            caches.put(name, new PlayerCache(dir, logger));
        }
    }

    // ------------------------------------------------------------------ 公开入口

    /**
     * 执行 hasJoined 验证。返回 null 表示「未配置任何 method，验证服务不启用」，
     * 调用方应 fail-open 退回原版验证。
     */
    @Override
    public LoginApiClient.ApiResult hasJoined(String username, String serverId, InetAddress address,
                                               boolean detail) throws IOException {
        if (!cfg.hasAnyMethod()) {
            return null;
        }
        // 选用第一个 method 作为本服主登录方式（单 method 部署的典型情况）。
        JsonObject method = cfg.methods().get(0).getAsJsonObject();
        String methodName = method.has("name") ? method.get("name").getAsString() : "default";
        PlayerCache cache = caches.get(methodName);
        if (cache == null) {
            cache = new PlayerCache(new File(new File(dataFolder, cfg.cacheDir()), methodName), logger);
            caches.put(methodName, cache);
        }

        String ip = (address != null) ? address.getHostAddress() : null;
        return decide(method, methodName, cache, username, serverId, ip, detail);
    }

    // ------------------------------------------------------------------ 核心判定（移植 urlHandle_joinServer）

    private LoginApiClient.ApiResult decide(JsonObject method, String methodName, PlayerCache cache,
                                             String username, String serverId, String ip, boolean detail) {
        long now = System.currentTimeMillis();

        // 1) 查缓存：封禁判定
        JsonObject info = cache.lookup(username);
        if (info != null && info.has("ban") && info.get("ban").getAsBoolean()) {
            long banTime = info.has("banTime") ? info.get("banTime").getAsLong() : 0;
            if (banTime == 0) {
                return reject(detail, "BANNED_FOREVER", banReason(info), null, null, null);
            } else if (banTime <= now) {
                cache.newBan(username, -1, null); // 到期自动解封
            } else {
                String msg = getMsg("BANNED", null);
                if (banReason(info) != null) {
                    msg += "\n" + banReason(info);
                }
                msg += "\n" + getMsg("BAN_UNTIL", null) + formatTime(banTime);
                return reject(detail, "BANNED", msg, null, null, null);
            }
        }

        // 2) 确定该玩家应当来自哪个皮肤站
        com.google.gson.JsonElement apiEl = info != null && info.has("from")
                ? lookupApiEl(info.get("from").getAsString()) : null;
        String apiId = (apiEl != null) ? apiEl.getAsJsonObject().get("id").getAsString() : null;

        Map<String, String> push = cfg.pushHandles();
        if (push.containsKey(username)) {
            apiEl = lookupApiEl(push.get(username));
            apiId = (apiEl != null) ? apiEl.getAsJsonObject().get("id").getAsString() : null;
        } else if (info != null && info.has("lastLogin")) {
            long last = info.get("lastLogin").getAsLong();
            // long 不可能是 NaN（JSON 数字缺失时 getAsLong 返回 0），无需 isNaN 判断。
            if ((now - last) < cfg.loginCooldownMs()) {
                return reject(detail, "LOGIN_TOO_FAST", getMsg("LOGIN_TOO_FAST", null), null, null, null);
            }
        }

        // 3) 未知玩家：按 method.handles 顺序去各皮肤站探测
        if (apiId == null) {
            return probeHandles(method, methodName, cache, username, serverId, ip, detail);
        }

        // 4) 已知玩家：校验其来源皮肤站是否被本 method 允许
        com.google.gson.JsonArray handles = method.has("handles") ? method.getAsJsonArray("handles") : null;
        boolean allowed = false;
        if (handles != null) {
            for (JsonElement h : handles) {
                if (h.getAsString().equals(apiId)) {
                    allowed = true;
                    break;
                }
            }
        }
        if (!allowed) {
            return reject(detail, "UNSUPPORTED_SKIN_SITE", getMsg("UNSUPPORTED_SKIN_SITE", null),
                    null, null, null);
        }

        // 5) 向该皮肤站发起 hasJoined 验证
        String siteRoot = (apiEl != null && apiEl.getAsJsonObject().has("root"))
                ? apiEl.getAsJsonObject().get("root").getAsString() : null;
        String url;
        if ("original".equals(apiId)) {
            url = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
                    + "?username=" + enc(username) + "&serverId=" + enc(serverId)
                    + (ip == null ? "" : "&ip=" + enc(ip));
        } else if (siteRoot != null) {
            url = siteRoot + "/sessionserver/session/minecraft/hasJoined"
                    + "?username=" + enc(username) + "&serverId=" + enc(serverId)
                    + (ip == null ? "" : "&ip=" + enc(ip));
        } else {
            return reject(detail, "UNSUPPORTED_SKIN_SITE", getMsg("UNSUPPORTED_SKIN_SITE", null),
                    null, null, null);
        }

        FetchResult fr;
        try {
            fr = fetch(url);
        } catch (IOException e) {
            return reject(detail, "FETCH_ERROR", getMsg("FETCH_ERROR", null), null, null, null);
        }
        if (fr.status == 204) {
            return reject(detail, "VERIFY_FAILED", getMsg("VERIFY_FAILED",
                    varMap("name", apiName(apiId))), null, null, null);
        }
        if (fr.status == 200) {
            return saveAndReturn(cache, methodName, username, fr.body, detail);
        }
        // 其它状态码按未找到处理
        return reject(detail, "VERIFY_FAILED", getMsg("VERIFY_FAILED",
                varMap("name", apiName(apiId))), null, null, null);
    }

    /** 首次登录：按 handles 顺序探测各皮肤站，第一个成功的即登记。 */
    private LoginApiClient.ApiResult probeHandles(JsonObject method, String methodName, PlayerCache cache,
                                                   String username, String serverId, String ip, boolean detail) {
        com.google.gson.JsonArray handles = method.has("handles") ? method.getAsJsonArray("handles") : null;
        if (handles == null || handles.size() == 0) {
            return reject(detail, "NOT_FOUND", getMsg("NOT_FOUND", null), null, null, null);
        }
        for (JsonElement h : handles) {
            String id = h.getAsString();
            com.google.gson.JsonElement apiEl = lookupApiEl(id);
            if (apiEl == null) {
                continue;
            }
            JsonObject api = apiEl.getAsJsonObject();
            String url;
            if ("original".equals(id)) {
                url = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
                        + "?username=" + enc(username) + "&serverId=" + enc(serverId)
                        + (ip == null ? "" : "&ip=" + enc(ip));
            } else if (api.has("root")) {
                url = api.get("root").getAsString() + "/sessionserver/session/minecraft/hasJoined"
                        + "?username=" + enc(username) + "&serverId=" + enc(serverId)
                        + (ip == null ? "" : "&ip=" + enc(ip));
            } else {
                continue;
            }
            FetchResult fr;
            try {
                fr = fetch(url);
            } catch (IOException e) {
                continue; // 尝试下一个
            }
            if (fr.status == 200) {
                return saveAndReturn(cache, methodName, username, fr.body, detail);
            }
            // 204 / 其它：尝试下一个皮肤站
        }
        return reject(detail, "NOT_FOUND", getMsg("NOT_FOUND", null), null, null, null);
    }

    /** 验证成功：把玩家档案登记进缓存并返回 200。 */
    private LoginApiClient.ApiResult saveAndReturn(PlayerCache cache, String methodName,
                                                    String username, String body, boolean detail) {
        JsonObject profile;
        try {
            profile = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return reject(detail, "FETCH_ERROR", getMsg("FETCH_ERROR", null), null, null, null);
        }
        String name = profile.has("name") ? profile.get("name").getAsString() : username;
        String id = profile.has("id") ? profile.get("id").getAsString() : null;
        if (id == null) {
            return reject(detail, "FETCH_ERROR", getMsg("FETCH_ERROR", null), null, null, null);
        }
        JsonObject existing = cache.lookup(name);
        if (existing == null) {
            PlayerCache.AddResult ar = cache.add(name, id, currentApiIdOf(methodName, body));
            if (!ar.success) {
                if (detail && ar.error != null) {
                    return reject(detail, ar.error, errorMsgFor(ar),
                            ar.error.equals("DUPLICATE_NAME") ? ar.existingFrom : null,
                            ar.error.equals("DUPLICATE_UUID") ? ar.existingName : null,
                            ar.error.equals("DUPLICATE_NAME") ? cache.findAvailableName(name) : null);
                }
                return new LoginApiClient.ApiResult(204, "");
            }
        } else {
            cache.newLogin(name, System.currentTimeMillis(), null);
        }
        return new LoginApiClient.ApiResult(200, body);
    }

    // 登记时使用「探测到的来源皮肤站」：从 method.handles 里第一个能解出档案的。
    // 简化：用 method 的第一个 handle 作为 from（与 Node 单路探测一致性最高）。
    private String currentApiIdOf(String methodName, String body) {
        return cfg.methods().get(0).getAsJsonObject().has("handles")
                && cfg.methods().get(0).getAsJsonObject().getAsJsonArray("handles").size() > 0
                ? cfg.methods().get(0).getAsJsonObject().getAsJsonArray("handles").get(0).getAsString()
                : cfg.defaultApi();
    }

    // ------------------------------------------------------------------ 错误文案

    private LoginApiClient.ApiResult reject(boolean detail, String cause, String message,
                                             String existingFrom, String existingName, String availableId) {
        if (!detail) {
            return new LoginApiClient.ApiResult(204, "");
        }
        JsonObject err = new JsonObject();
        err.addProperty("error", "ForbiddenOperationException");
        err.addProperty("errorMessage", message);
        err.addProperty("cause", cause);
        if (availableId != null) {
            err.addProperty("availableId", availableId);
        }
        return new LoginApiClient.ApiResult(403, err.toString());
    }

    private String errorMsgFor(PlayerCache.AddResult ar) {
        if ("DUPLICATE_NAME".equals(ar.error)) {
            return getMsg("DUPLICATE_NAME", varMap("from", ar.existingFrom));
        }
        if ("DUPLICATE_UUID".equals(ar.error)) {
            return getMsg("DUPLICATE_UUID", varMap2("name", ar.existingName, "from", ar.existingFrom));
        }
        return ar.error;
    }

    private String getMsg(String key, Map<String, String> vars) {
        JsonObject em = cfg.errorMessages();
        String tpl = (em != null && em.has(key)) ? em.get(key).getAsString() : DEFAULT_MESSAGES.get(key);
        if (tpl == null) {
            tpl = key;
        }
        if (vars != null) {
            for (Map.Entry<String, String> en : vars.entrySet()) {
                tpl = tpl.replace("{" + en.getKey() + "}", en.getValue() != null ? en.getValue() : "");
            }
        }
        return tpl;
    }

    private static final Map<String, String> DEFAULT_MESSAGES = new LinkedHashMap<String, String>();
    static {
        DEFAULT_MESSAGES.put("DUPLICATE_NAME", "该玩家名已被来自 \"{from}\" 的账号占用，不允许其他皮肤站的同名玩家登录");
        DEFAULT_MESSAGES.put("DUPLICATE_UUID", "该账号的 UUID 与已有玩家 \"{name}\"（来自 \"{from}\"）冲突");
        DEFAULT_MESSAGES.put("BANNED_FOREVER", "您已被永久封禁");
        DEFAULT_MESSAGES.put("BANNED", "您已被封禁");
        DEFAULT_MESSAGES.put("NOT_FOUND", "玩家未在任何已配置的皮肤站找到");
        DEFAULT_MESSAGES.put("UNSUPPORTED_SKIN_SITE", "该玩家注册的皮肤站不在此服务器支持列表中");
        DEFAULT_MESSAGES.put("FETCH_ERROR", "连接验证服务器失败");
        DEFAULT_MESSAGES.put("VERIFY_FAILED", "验证失败，你应当通过 {name} 进入");
        DEFAULT_MESSAGES.put("LOGIN_TOO_FAST", "你的登录过快，请稍后再试");
        DEFAULT_MESSAGES.put("BAN_UNTIL", "解封时间: ");
    }

    // ------------------------------------------------------------------ 工具

    private com.google.gson.JsonElement lookupApiEl(String id) {
        for (JsonElement e : cfg.apis()) {
            if (e.isJsonObject() && e.getAsJsonObject().get("id").getAsString().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private String apiName(String id) {
        com.google.gson.JsonElement e = lookupApiEl(id);
        return (e != null && e.getAsJsonObject().has("name")) ? e.getAsJsonObject().get("name").getAsString() : id;
    }

    private String banReason(JsonObject info) {
        return (info != null && info.has("banReason")) ? info.get("banReason").getAsString() : null;
    }

    private static String formatTime(long ms) {
        java.util.Date d = new java.util.Date(ms);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(d);
    }

    private static Map<String, String> varMap(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private static Map<String, String> varMap2(String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不可用", e);
        }
    }

    /** HTTP GET 取响应状态 + 正文。 */
    private FetchResult fetch(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MCMultiLoginCompat-Internal");
            int status = conn.getResponseCode();
            InputStream in = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            return new FetchResult(status, readAll(in));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // 忽略
            }
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class FetchResult {
        final int status;
        final String body;

        FetchResult(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}
