package net.wifil.mcmultilogin.verify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.logging.Logger;

/**
 * mcverify（QQ 绑定验证）的共享状态存储 —— 对应 mcverify(Python) 端的
 * {@code data/plugin_data/astrbot_plugin_mc_verify/verify.json}。
 *
 * <p><b>关键设计</b>：Java 插件与 mcverify(Python) 共用<b>同一份</b> {@code verify.json}。
 * 分工是：</p>
 * <ul>
 *   <li><b>Java 插件（本类 + {@link VerifyGate}）</b>：玩家进服时生成验证码、写盘、
 *       未验证则踢出；也就是「门禁」的权威方。</li>
 *   <li><b>mcverify(Python)</b>：作为 QQ 群「验证 XXXX」绑定码的接收端，
 *       匹配成功后把对应记录 {@code status} 置为 {@code verified} 并登记 QQ。</li>
 * </ul>
 *
 * <p>文件结构（与 mcverify 完全一致，按 uuid 建索引）：</p>
 * <pre>
 * {
 *   "&lt;uuid&gt;": {
 *     "uuid": "...", "name": "...", "bedrock": false,
 *     "qq": "", "code": "ABC123", "status": "pending" | "verified",
 *     "code_created_at": 1234567890, "verified_at": 0,
 *     "frozen": false, "notified_at": 0
 *   }
 * }
 * </pre>
 *
 * <p>验证码字符集刻意与 mcverify 相同（去掉易混淆的 0/O/1/I/L），
 * 长度 6，用 {@link SecureRandom} 生成，保证两端产出的码空间一致、可互认。</p>
 *
 * <p>线程安全：读写都包在 {@code synchronized(storeLock)} 内，因为登录验证与进服事件
 * 可能并发，且与 mcverify(Python) 跨进程读写同一文件，本类只保证<b>进程内</b>互斥，
 * 跨进程一致性靠「短事务 + 整文件覆盖写」降低竞态窗口（mcverify 端同样整文件写）。</p>
 */
public final class VerifyState {

    /** 验证码字符集：去掉易混淆的 0/O/1/I/L（与 mcverify 保持一致）。 */
    public static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 验证码长度。 */
    public static final int CODE_LENGTH = 6;

    /** 状态值。 */
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_VERIFIED = "verified";

    private static final SecureRandom RAND = new SecureRandom();

    private final File file;
    private final Logger logger;
    private final Object storeLock = new Object();
    private JsonObject store;

    public VerifyState(File dataFolder, String fileName, Logger logger) {
        this.file = new File(dataFolder, fileName);
        this.logger = logger;
        this.store = load();
    }

    private JsonObject load() {
        if (!file.exists()) {
            return new JsonObject();
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) {
                return new JsonObject();
            }
            JsonElement el = JsonParser.parseString(text);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[VerifyState] 读取 verify.json 失败，使用空状态：" + e.getMessage());
            }
            return new JsonObject();
        }
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            Files.write(file.toPath(), gson.toJson(store).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[VerifyState] 写 verify.json 失败：" + e.getMessage());
            }
        }
    }

    /** 取某 uuid 的记录（不存在返回 null）。 */
    public JsonObject get(String uuid) {
        if (uuid == null) {
            return null;
        }
        synchronized (storeLock) {
            JsonElement e = store.get(uuid);
            return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
        }
    }

    /** 该玩家是否已通过验证。 */
    public boolean isVerified(String uuid) {
        JsonObject rec = get(uuid);
        return rec != null && STATUS_VERIFIED.equals(rec.get("status").getAsString());
    }

    /**
     * 取/生成某玩家的待验证记录。
     *
     * <p>规则：无记录 → 新建待验证记录并生成码；已有待验证记录且未过期 → 复用旧码；
     * 已有待验证记录但已过期 → 重发新码；已验证记录 → 原样返回（不覆盖）。</p>
     *
     * @param uuid        玩家 uuid（离线模式也用固定 uuid）
     * @param name        玩家名
     * @param bedrock     是否基岩版（名带 . 前缀）
     * @param ttlSeconds  验证码有效期（秒）
     * @return 当前记录（含 code 字段），线程安全
     */
    public JsonObject ensurePending(String uuid, String name, boolean bedrock, int ttlSeconds) {
        long now = System.currentTimeMillis() / 1000L;
        synchronized (storeLock) {
            JsonObject rec = get(uuid);
            if (rec != null && STATUS_VERIFIED.equals(optStr(rec, "status", ""))) {
                return rec; // 已验证，不动
            }
            if (rec == null) {
                rec = new JsonObject();
                rec.addProperty("uuid", uuid);
                rec.addProperty("name", name);
                rec.addProperty("bedrock", bedrock);
                rec.addProperty("qq", "");
                rec.addProperty("status", STATUS_PENDING);
                rec.addProperty("verified_at", 0L);
                rec.addProperty("frozen", false);
                rec.addProperty("notified_at", 0L);
                store.add(uuid, rec);
            }
            // 名字可能变化（改名），同步更新
            rec.addProperty("name", name);
            rec.addProperty("bedrock", bedrock);

            boolean expired = now - optLong(rec, "code_created_at", 0L) > ttlSeconds;
            boolean noCode = optStr(rec, "code", "").isEmpty();
            if (noCode || expired) {
                String code = genCode();
                rec.addProperty("code", code);
                rec.addProperty("code_created_at", now);
                rec.addProperty("notified_at", 0L);
                rec.addProperty("status", STATUS_PENDING);
            }
            save();
            return rec;
        }
    }

    /**
     * 标记为已验证（由 mcverify(Python) 在 QQ 群收到「验证 XXXX」后调用，
     * 跨进程写盘；本方法供 Java 侧在本地也需要更新时使用）。
     *
     * @param uuid 玩家 uuid
     * @param qq   绑定 QQ（可为空）
     * @return 是否成功更新
     */
    public boolean markVerified(String uuid, String qq) {
        synchronized (storeLock) {
            JsonObject rec = get(uuid);
            if (rec == null) {
                return false;
            }
            rec.addProperty("status", STATUS_VERIFIED);
            rec.addProperty("verified_at", System.currentTimeMillis() / 1000L);
            if (qq != null) {
                rec.addProperty("qq", qq);
            }
            rec.addProperty("frozen", false);
            save();
            return true;
        }
    }

    /**
     * 按验证码标记对应玩家为已验证（跨机部署的核心入口）。
     *
     * <p>v3 重构后 verify.json 只存在于 MC 服本地：QQ 群里的「验证 XXXX」由 bot 端转发到
     * MC 服，MC 服调用本方法按码查到对应待验证记录并置为已验证。返回被验证的玩家名
     * （用于回群提示「玩家 XXX 已绑定」）；码不存在 / 已过期 / 已验证则返回 null。</p>
     *
     * @param code 验证码（大小写不敏感，内部统一大写比较）
     * @param qq   绑定 QQ（可为空；来自接码端）
     * @return 被验证的玩家名，未匹配返回 null
     */
    public String markVerifiedByCode(String code, String qq) {
        if (code == null) {
            return null;
        }
        String codeUp = code.trim().toUpperCase();
        if (codeUp.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000L;
        synchronized (storeLock) {
            for (java.util.Map.Entry<String, JsonElement> e : store.entrySet()) {
                if (!(e.getValue() != null && e.getValue().isJsonObject())) {
                    continue;
                }
                JsonObject rec = e.getValue().getAsJsonObject();
                if (!STATUS_PENDING.equals(optStr(rec, "status", ""))) {
                    continue;
                }
                String recCode = optStr(rec, "code", "").toUpperCase();
                if (!recCode.equals(codeUp)) {
                    continue;
                }
                // 过期检查（与 ensurePending 的 TTL 口径一致）
                long created = optLong(rec, "code_created_at", 0L);
                int ttl = (storeTtl > 0) ? storeTtl : 600;
                if (created > 0 && (now - created) > ttl) {
                    continue;
                }
                rec.addProperty("status", STATUS_VERIFIED);
                rec.addProperty("verified_at", now);
                if (qq != null) {
                    rec.addProperty("qq", qq);
                }
                rec.addProperty("frozen", false);
                save();
                return optStr(rec, "name", e.getKey());
            }
        }
        return null;
    }

    /** 设置 TTL（秒），供 {@link #markVerifiedByCode} 做过期判断；由插件初始化时注入。 */
    private int storeTtl = 600;

    public void setTtlSeconds(int ttl) {
        this.storeTtl = (ttl > 0) ? ttl : 600;
    }

    /** 生成验证码（大写，长度 {@link #CODE_LENGTH}）。 */
    public static String genCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RAND.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String optStr(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : def;
    }

    private static long optLong(JsonObject o, String k, long def) {
        JsonElement e = o.get(k);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsLong();
        }
        return def;
    }
}
