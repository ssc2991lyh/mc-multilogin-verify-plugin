package net.wifil.mcmultilogin.verify;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * OneBot 11 HTTP 客户端，用于往 QQ 群发消息（出入服播报、欢迎语等）。
 *
 * <p>对应 mcverify(Python) 通过 AstrBot / OneBot 发群消息的能力。合并进插件后，
 * Java 侧也能直接发群消息，做到「门禁 + 播报」全部自包含，不再依赖 Python 端代发。</p>
 *
 * <p>Java 8 兼容：用 {@link HttpURLConnection}，不引入任何 9+ API。</p>
 */
public final class OneBotSender {

    private final String httpUrl;
    private final String token;
    private final Logger logger;
    private final int timeoutMillis;

    /**
     * @param httpUrl  OneBot HTTP 地址，例如 {@code http://127.0.0.1:3000}
     * @param token    OneBot access_token（可为空，表示不鉴权）
     * @param logger   日志
     * @param timeoutSeconds 超时（秒）
     */
    public OneBotSender(String httpUrl, String token, Logger logger, int timeoutSeconds) {
        String base = (httpUrl == null) ? "" : httpUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.httpUrl = base;
        this.token = (token == null) ? "" : token;
        this.logger = logger;
        this.timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
    }

    public boolean available() {
        return !httpUrl.isEmpty();
    }

    /**
     * 往指定群发送一条文本消息。
     *
     * @param groupId 群号（字符串，避免大整数精度问题）
     * @param message 消息文本
     * @return 是否发送成功
     */
    public boolean sendGroupMessage(String groupId, String message) {
        if (!available() || groupId == null || groupId.isEmpty() || message == null || message.isEmpty()) {
            return false;
        }
        JsonObject body = new JsonObject();
        body.addProperty("group_id", groupId);
        body.addProperty("message", message);
        try {
            post(httpUrl + "/send_group_msg", body);
            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[OneBot] 发送群消息失败（群 " + groupId + "）：" + e.getMessage());
            }
            return false;
        }
    }

    private void post(String url, JsonObject body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "MCMultiLoginCompat");
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int status = conn.getResponseCode();
            // 即使状态码非 2xx 也读一下 body，便于排错
            InputStream in = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = readAll(in);
            if (status < 200 || status >= 300) {
                if (logger != null) {
                    logger.warning("[OneBot] 群消息返回非预期状态码 " + status + "：" + resp);
                }
            }
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
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
