package net.wifil.mcmultilogin.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 玩家缓存层（忠实移植原 Node 版 {@code playercache.js} 的 {@code class_PlayerCache}）。
 *
 * <p>每个登录方式（method）拥有独立的缓存目录，目录内每个玩家一个
 * {@code <玩家名>.json}，外加一个 {@code a.ud.json} 作为「UUID -> 玩家名」索引。
 * 玩家记录字段：{@code name / uuid / from / ban / banTime / banReason / lastLogin / ip / old_names}。</p>
 *
 * <p>线程安全：登录验证可能并发发生，关键读写方法加 {@code synchronized}。</p>
 */
public final class PlayerCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File path;
    private final Logger logger;
    /** UUID -> 玩家名的索引（持久化到 a.ud.json）。 */
    private final Map<String, String> uuidCache = new LinkedHashMap<String, String>();

    public PlayerCache(File path, Logger logger) {
        this.path = path;
        this.logger = logger;
        if (!path.exists()) {
            path.mkdirs();
        }
        File idx = new File(path, "a.ud.json");
        if (idx.exists()) {
            try {
                JsonObject o = JsonParser.parseString(
                        new String(Files.readAllBytes(idx.toPath()), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> en : o.entrySet()) {
                    uuidCache.put(en.getKey(), en.getValue().getAsString());
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[PlayerCache] 加载 UUID 索引失败：" + e.getMessage());
                }
            }
        }
        rebuildUUIDCacheFromFiles(false);
    }

    // ------------------------------------------------------------------ 工具

    /** 玩家名合法性校验（与 Node 版 checkName 一致）。 */
    public static boolean checkName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.indexOf('.') < 0
                && name.indexOf('?') < 0
                && name.indexOf('\'') < 0
                && name.indexOf('"') < 0
                && name.indexOf('*') < 0
                && name.indexOf(':') < 0
                && name.indexOf('\\') < 0
                && name.indexOf('/') < 0
                && name.indexOf('>') < 0
                && name.indexOf('<') < 0;
    }

    private static String normalizeUUID(String uuid) {
        if (uuid == null) {
            return null;
        }
        return uuid.toLowerCase().replace("-", "");
    }

    private static String[] getUUIDKeys(String uuid) {
        String raw = uuid == null ? null : uuid.toLowerCase();
        String normalized = normalizeUUID(uuid);
        if (raw == null || normalized == null) {
            return new String[0];
        }
        if (raw.equals(normalized)) {
            return new String[] { normalized };
        }
        return new String[] { raw, normalized };
    }

    // ------------------------------------------------------------------ UUID 索引

    private synchronized boolean persistUUIDCache() {
        try {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, String> en : uuidCache.entrySet()) {
                o.addProperty(en.getKey(), en.getValue());
            }
            Files.write(new File(path, "a.ud.json").toPath(),
                    GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[PlayerCache] 持久化 UUID 索引失败：" + e.getMessage());
            }
            return false;
        }
    }

    private void rebuildUUIDCacheFromFiles(boolean overwriteConflict) {
        File[] files = path.listFiles();
        if (files == null) {
            return;
        }
        boolean changed = false;
        for (File f : files) {
            String fname = f.getName();
            if (!fname.endsWith(".json") || fname.equals("a.ud.json")) {
                continue;
            }
            String playerName = fname.substring(0, fname.length() - 5);
            if (!checkName(playerName)) {
                continue;
            }
            try {
                JsonObject data = JsonParser.parseString(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (data == null || !data.has("uuid")) {
                    continue;
                }
                String uuid = data.get("uuid").getAsString();
                for (String key : getUUIDKeys(uuid)) {
                    String cached = uuidCache.get(key);
                    if (cached == null) {
                        uuidCache.put(key, playerName);
                        changed = true;
                    } else if (!cached.equals(playerName) && overwriteConflict) {
                        uuidCache.put(key, playerName);
                        changed = true;
                    }
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("[PlayerCache] 重建 UUID 索引跳过 " + fname + "：" + e.getMessage());
                }
            }
        }
        if (changed) {
            persistUUIDCache();
        }
    }

    private synchronized String lookupUuid(String uuid) {
        String[] keys = getUUIDKeys(uuid);
        for (String key : keys) {
            String mapped = uuidCache.get(key);
            if (mapped != null) {
                return mapped;
            }
        }
        String target = normalizeUUID(uuid);
        if (target == null) {
            return null;
        }
        File[] files = path.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            String fname = f.getName();
            if (!fname.endsWith(".json") || fname.equals("a.ud.json")) {
                continue;
            }
            String playerName = fname.substring(0, fname.length() - 5);
            try {
                JsonObject data = JsonParser.parseString(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (data != null && data.has("uuid")
                        && target.equals(normalizeUUID(data.get("uuid").getAsString()))) {
                    cacheUUID(playerName, data.get("uuid").getAsString());
                    return playerName;
                }
            } catch (Exception e) {
                // 忽略损坏文件
            }
        }
        return null;
    }

    private synchronized boolean cacheUUID(String player, String uuid) {
        boolean ok = false;
        for (String key : getUUIDKeys(uuid)) {
            uuidCache.put(key, player);
            ok = true;
        }
        return ok && persistUUIDCache();
    }

    // ------------------------------------------------------------------ 玩家记录

    public synchronized JsonObject lookup(String player) {
        if (!checkName(player)) {
            return null;
        }
        File f = new File(path, player + ".json");
        if (!f.exists()) {
            return null;
        }
        try {
            return JsonParser.parseString(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[PlayerCache] 读取玩家 " + player + " 缓存失败：" + e.getMessage());
            }
            return null;
        }
    }

    /** add 的结果：success=true 表示写入成功；否则携带 error / existingFrom / existingName。 */
    public static final class AddResult {
        public final boolean success;
        public final String error;
        public final String existingFrom;
        public final String existingName;

        private AddResult(boolean success, String error, String existingFrom, String existingName) {
            this.success = success;
            this.error = error;
            this.existingFrom = existingFrom;
            this.existingName = existingName;
        }

        public static AddResult ok() {
            return new AddResult(true, null, null, null);
        }

        public static AddResult duplicateName(String existingFrom) {
            return new AddResult(false, "DUPLICATE_NAME", existingFrom, null);
        }

        public static AddResult duplicateUuid(String existingName, String existingFrom) {
            return new AddResult(false, "DUPLICATE_UUID", existingFrom, existingName);
        }
    }

    public synchronized AddResult add(String player, String uuid, String from) {
        String t = lookupUuid(uuid);
        if (t == null) {
            return addRaw(player, uuid, from);
        }
        if (t.equals(player)) {
            return AddResult.ok();
        }
        JsonObject info = lookup(t);
        String existingFrom = (info != null && info.has("from")) ? info.get("from").getAsString() : null;
        if (from.equals(existingFrom)) {
            return playerChangename(t, player) ? AddResult.ok() : AddResult.ok();
        }
        if (logger != null) {
            logger.info("[PlayerCache] <" + player + ">(From <" + from
                    + ">) 被拒绝：UUID 与 <" + t + ">(From <" + existingFrom + ">) 冲突");
        }
        return AddResult.duplicateUuid(t, existingFrom);
    }

    private synchronized AddResult addRaw(String player, String uuid, String from) {
        if (!checkName(player)) {
            return AddResult.duplicateName(null);
        }
        File f = new File(path, player + ".json");
        if (f.exists()) {
            try {
                JsonObject existing = JsonParser.parseString(
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                return AddResult.duplicateName(existing.has("from")
                        ? existing.get("from").getAsString() : null);
            } catch (Exception e) {
                return AddResult.duplicateName(null);
            }
        }
        cacheUUID(player, uuid);
        JsonObject info = new JsonObject();
        info.addProperty("name", player);
        info.addProperty("uuid", uuid);
        info.addProperty("from", from);
        writePlayer(player, info);
        return AddResult.ok();
    }

    public synchronized boolean newBan(String player, long time, String reason) {
        JsonObject data = lookup(player);
        if (data == null) {
            return false;
        }
        if (time == 0) {
            data.addProperty("ban", true);
            data.addProperty("banStart", System.currentTimeMillis());
            data.addProperty("banTime", 0);
        } else if (time == -1) {
            data.addProperty("ban", false);
            data.addProperty("banTime", 0);
            data.remove("banReason");
        } else {
            data.addProperty("ban", true);
            data.addProperty("banStart", System.currentTimeMillis());
            data.addProperty("banTime", System.currentTimeMillis() + time);
        }
        if (reason != null && !reason.trim().isEmpty()) {
            data.addProperty("banReason", reason.trim());
        } else {
            data.remove("banReason");
        }
        writePlayer(player, data);
        return true;
    }

    public synchronized boolean newLogin(String player, long time, String ip) {
        JsonObject data = lookup(player);
        if (data == null) {
            return false;
        }
        data.addProperty("lastLogin", time);
        if (ip != null) {
            data.addProperty("ip", ip);
        }
        writePlayer(player, data);
        return true;
    }

    /** 找可用改名（原名_2 ... 原名_9999），用于 DUPLICATE_NAME 时建议替代名。 */
    public synchronized String findAvailableName(String player) {
        for (int i = 2; i <= 9999; i++) {
            String candidate = player + "_" + i;
            if (!new File(path, candidate + ".json").exists()) {
                return candidate;
            }
        }
        return null;
    }

    private synchronized boolean playerChangename(String original, String newName) {
        if (!checkName(original)) {
            return false;
        }
        File f = new File(path, original + ".json");
        if (!f.exists()) {
            return false;
        }
        try {
            JsonObject data = JsonParser.parseString(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            com.google.gson.JsonElement old = data.get("old_names");
            if (old == null || !old.isJsonArray()) {
                data.add("old_names", new com.google.gson.JsonArray());
            }
            data.getAsJsonArray("old_names").add(original);
            data.addProperty("name", newName);
            String uid = data.has("uuid") ? data.get("uuid").getAsString() : null;
            if (uid != null) {
                cacheUUID(newName, uid);
            }
            new File(path, original + ".json").delete();
            writePlayer(newName, data);
            return true;
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[PlayerCache] 改名失败：" + e.getMessage());
            }
            return false;
        }
    }

    private void writePlayer(String player, JsonObject data) {
        try {
            Files.write(new File(path, player + ".json").toPath(),
                    GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[PlayerCache] 写玩家 " + player + " 失败：" + e.getMessage());
            }
        }
    }

    public synchronized boolean delete(String player) {
        if (!checkName(player)) {
            return false;
        }
        File f = new File(path, player + ".json");
        if (!f.exists()) {
            return false;
        }
        JsonObject data = lookup(player);
        if (data != null && data.has("uuid")) {
            for (String key : getUUIDKeys(data.get("uuid").getAsString())) {
                uuidCache.remove(key);
            }
            persistUUIDCache();
        }
        return f.delete();
    }

    public synchronized boolean modify(String player, JsonObject newData) {
        if (!checkName(player)) {
            return false;
        }
        if (!new File(path, player + ".json").exists()) {
            return false;
        }
        writePlayer(player, newData);
        if (newData.has("uuid") && newData.has("name")) {
            cacheUUID(newData.get("name").getAsString(), newData.get("uuid").getAsString());
        }
        return true;
    }

    /** 列出全部玩家（name/uuid/from），供管理接口使用。 */
    public synchronized java.util.List<JsonObject> listPlayers() {
        java.util.List<JsonObject> out = new java.util.ArrayList<JsonObject>();
        File[] files = path.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String fname = f.getName();
            if (!fname.endsWith(".json") || fname.equals("a.ud.json")) {
                continue;
            }
            JsonObject data = lookup(fname.substring(0, fname.length() - 5));
            if (data != null) {
                out.add(data);
            }
        }
        return out;
    }

    /** 列出当前生效中的封禁玩家。 */
    public synchronized java.util.List<JsonObject> listBannedPlayers() {
        java.util.List<JsonObject> out = new java.util.ArrayList<JsonObject>();
        long now = System.currentTimeMillis();
        File[] files = path.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String fname = f.getName();
            if (!fname.endsWith(".json") || fname.equals("a.ud.json")) {
                continue;
            }
            JsonObject data = lookup(fname.substring(0, fname.length() - 5));
            if (data == null || !data.has("ban") || !data.get("ban").getAsBoolean()) {
                continue;
            }
            long banTime = data.has("banTime") ? data.get("banTime").getAsLong() : 0;
            if (banTime != 0 && banTime <= now) {
                continue;
            }
            out.add(data);
        }
        return out;
    }
}
